package com.anilili.data.backup

import android.Manifest
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.text.format.DateUtils
import androidx.core.content.ContextCompat
import com.anilili.BuildConfig
import com.anilili.data.auth.AccountAdoption
import com.anilili.data.auth.AuthManager
import com.anilili.data.auth.MalAuthManager
import com.anilili.data.auth.OpenSubtitlesAuthManager
import com.anilili.data.library.HistoryEntry
import com.anilili.data.library.LibraryStore
import com.anilili.data.library.SearchHistoryStore
import com.anilili.data.settings.SettingsStore
import com.anilili.diagnostics.DiagnosticsLog
import java.io.File
import kotlinx.serialization.json.Json

/**
 * The device half of a backup: reading the stores into a [BackupDocument] and writing one back.
 *
 * Kept apart from [AppBackup] so the file format has no Android in it and can be tested off-device;
 * this is the part that knows which stores exist.
 */
object BackupManager {
    /** Where a backup goes on a device with no document picker — see [fallbackDirectory]. */
    private const val FALLBACK_DIRECTORY = "Backups"

    /** The folder under public Downloads a picker-less backup is copied into. */
    private const val DOWNLOADS_FOLDER = "Anilili"

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    /**
     * Snapshot everything a backup carries, right now.
     *
     * With a [passphrase], whatever logins [collectCredentials] can carry are sealed into the file
     * under it; without one the file holds no credentials at all.
     */
    suspend fun create(passphrase: String? = null, malHandoff: MalHandoff? = null): BackupDocument {
        val document = BackupDocument(
            createdAt = System.currentTimeMillis(),
            appVersion = BuildConfig.VERSION_NAME,
            settings = SettingsStore.backupSettings(),
            watchlist = LibraryStore.watchlist.value,
            // Rows seeded from a signed-in account are the account's, and the next refresh on the
            // new device recreates them from it. Carrying copies would only race that refresh with
            // a staler picture of the same list.
            history = LibraryStore.history.value.filterNot(HistoryEntry::fromRemote),
            searchHistory = SearchHistoryStore.history.value,
            customLists = com.anilili.data.library.CustomListStore.lists.value,
            watched = com.anilili.data.library.LocalWatchedStore.entries.value,
        )
        if (passphrase.isNullOrEmpty()) return document
        val credentials = collectCredentials(malHandoff)
        if (credentials.isEmpty) return document
        val sealed = BackupCrypto.seal(
            passphrase = passphrase,
            plaintext = json.encodeToString(BackupCredentials.serializer(), credentials),
            associatedData = document.credentialAssociatedData(),
        )
        return document.copy(credentials = sealed.copy(services = credentials.services()))
    }

    /**
     * The logins that can travel.
     *
     * AniList and OpenSubtitles are read straight out of their stores. MyAnimeList cannot be read
     * out of anything — it has to be *minted*, by an authorization the viewer runs before the
     * backup is written — so it arrives here as [malHandoff] rather than being fetched.
     */
    private fun collectCredentials(malHandoff: MalHandoff?): BackupCredentials {
        val openSubtitles = OpenSubtitlesAuthManager.exportLogin()
        return BackupCredentials(
            aniListToken = AuthManager.current(),
            openSubtitlesUsername = openSubtitles?.first,
            openSubtitlesPassword = openSubtitles?.second,
            malCode = malHandoff?.code,
            malVerifier = malHandoff?.verifier,
            malIssuedAt = malHandoff?.let { System.currentTimeMillis() } ?: 0L,
        )
    }

    /** The PKCE material for a hand-off, generated fresh for every backup that carries one. */
    fun newMalHandoffVerifier(): String = MalAuthManager.newPkceVerifier()

    fun newMalHandoffState(): String = MalAuthManager.newOAuthState()

    /** A passphrase to offer, so nobody has to invent one at the moment they are asked for it. */
    fun suggestPassphrase(): String = BackupCrypto.generatePassphrase()

    /** The shortest passphrase the seal will accept, for the field that collects one. */
    const val MIN_PASSPHRASE_LENGTH = BackupCrypto.MIN_PASSPHRASE_LENGTH

    /**
     * Apply [document] to this device.
     *
     * Settings are replaced and the two lists are merged — a preference has one value and the
     * backup's is the one being asked for, while a title saved on either device is still a title
     * the viewer saved. [LibraryStore.importWatchlist] pushes the merged result to whichever list
     * service is signed in, so a restore also puts the saves onto the account.
     */
    suspend fun restore(document: BackupDocument, passphrase: String? = null): RestoreSummary {
        // Unsealed before anything at all is written. A wrong passphrase has to fail the whole
        // restore, not leave the settings already replaced and the logins missing — the viewer's
        // next move is to type it again, and that must not be a second half-restore.
        val credentials = unsealCredentials(document, passphrase)
        val settings = SettingsStore.restoreSettings(document.settings)
        // Logins before the library, so the merge below pushes the restored saves to the account
        // this backup signs the device in to rather than to the one it is replacing.
        val logins = applyCredentials(credentials)
        val summary = RestoreSummary(
            settings = settings,
            watchlist = LibraryStore.importWatchlist(document.watchlist),
            history = LibraryStore.restoreHistory(document.history),
            searchHistory = SearchHistoryStore.restore(document.searchHistory),
            logins = logins.count,
            customLists = com.anilili.data.library.CustomListStore.importLists(document.customLists),
            watched = com.anilili.data.library.LocalWatchedStore.importEntries(document.watched),
            warnings = logins.warnings,
        )
        DiagnosticsLog.event(
            category = "backup",
            name = "restore.applied",
            attributes = mapOf(
                "settings" to summary.settings,
                "watchlist" to summary.watchlist,
                "history" to summary.history,
                "searchHistory" to summary.searchHistory,
                "logins" to summary.logins,
                "customLists" to summary.customLists,
                "watched" to summary.watched,
                "fromVersion" to document.appVersion.ifBlank { "unknown" },
            ),
        )
        return summary
    }

    /**
     * Open the sealed section, or throw the reason it would not open.
     *
     * A file with no credentials restores without a passphrase, which is the common case; one that
     * has them and was given none is left sealed rather than treated as an error, because the rest
     * of the backup is still worth applying.
     */
    private fun unsealCredentials(document: BackupDocument, passphrase: String?): BackupCredentials? {
        val sealed = document.credentials ?: return null
        if (passphrase.isNullOrEmpty()) return null
        val plaintext = BackupCrypto.open(passphrase, sealed, document.credentialAssociatedData())
        return runCatching { json.decodeFromString(BackupCredentials.serializer(), plaintext) }
            .getOrElse { error("The logins in that backup are damaged.") }
    }

    /** What taking on the unsealed logins actually achieved. */
    private class AppliedCredentials(val count: Int, val warnings: List<String>)

    /**
     * Take on the unsealed logins.
     *
     * Nothing in here throws. A list service that refuses its credential is a disappointment, not a
     * reason to abandon a restore that has already replaced the settings — so the failure comes
     * back as a warning and the rest of the backup still lands.
     */
    private suspend fun applyCredentials(credentials: BackupCredentials?): AppliedCredentials {
        if (credentials == null) return AppliedCredentials(0, emptyList())
        var applied = 0
        val warnings = mutableListOf<String>()
        // AniList and MyAnimeList are one-at-a-time, and adopting either drops the other, so a file
        // should never carry both. If one somehow does, the MyAnimeList hand-off wins: it was
        // deliberate enough to require a sign-in when the backup was written, and it expires.
        if (credentials.hasMalHandoff) {
            runCatching {
                AccountAdoption.adoptMalCode(credentials.malCode!!, credentials.malVerifier!!)
            }.onSuccess {
                applied++
            }.onFailure { error ->
                DiagnosticsLog.throwable("MAL hand-off from a backup was refused", error)
                // The age is the useful part of the explanation: MAL never says whether a code was
                // spent or simply expired, and "made four days ago" answers it better than either.
                val age = credentials.malIssuedAt.takeIf { it > 0L }
                    ?.let { " The sign-in in it was made ${DateUtils.getRelativeTimeSpanString(it)}." }
                    .orEmpty()
                warnings += "MyAnimeList refused the sign-in in that backup.$age It works once " +
                    "and expires quickly — pair this device from your phone, or sign in here."
            }
        } else if (credentials.hasAniList) {
            // The same path a paired phone's token takes on a television, so a restored login and
            // a paired one leave the device in exactly the same state.
            AccountAdoption.adoptAniList(credentials.aniListToken!!)
            applied++
        }
        if (credentials.hasOpenSubtitles) {
            OpenSubtitlesAuthManager.adoptLogin(
                credentials.openSubtitlesUsername!!,
                credentials.openSubtitlesPassword!!,
            )
            applied++
        }
        return AppliedCredentials(applied, warnings)
    }

    /**
     * Whether this device has anything that can answer a document picker.
     *
     * Android TV builds routinely ship without DocumentsUI — Fire TV among them — and Fire OS does
     * not let the app find that out by asking: it catches the missing activity in the framework and
     * shows its own "no app for this" message, so `launch()` returns normally and the caller's
     * catch-and-fall-back below never runs. Asking the package manager first is the only way to
     * know before opening a picker that cannot open.
     *
     * [openable] mirrors the category the launch contract itself adds: `OpenDocument` asks for
     * openable content, `CreateDocument` does not.
     */
    fun canPickDocuments(
        context: Context,
        action: String,
        mimeType: String,
        openable: Boolean = false,
    ): Boolean {
        val intent = Intent(action).setType(mimeType)
        if (openable) intent.addCategory(Intent.CATEGORY_OPENABLE)
        return context.packageManager.resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY) != null
    }

    /**
     * Write [document] into public `Downloads/Anilili`, returning where it went or null if this
     * device would not take it.
     *
     * The app's own folder ([fallbackDirectory]) is not good enough for the device this exists for:
     * on Android 11 and later `Android/data` is closed to file managers and to `adb pull` for a
     * normal app, so a backup written there on a television cannot be taken off it — which is the
     * same as not having been written. Downloads is visible to a file manager, readable over USB,
     * and survives an uninstall.
     *
     * The same route [com.anilili.diagnostics.DiagnosticsExport] uses for reports, for the same
     * reason; kept separate rather than shared because that one is about a zip of logs and carries
     * its own permission dance.
     */
    fun saveToDownloads(context: Context, document: BackupDocument): String? = runCatching {
        val name = AppBackup.fileName()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            insertViaMediaStore(context, document, name)
        } else {
            writeDownloadsDirectly(context, document, name)
        }
    }.getOrElse { error ->
        DiagnosticsLog.throwable("Backup copy to Downloads failed", error)
        null
    }

    /** Q and up: MediaStore owns Downloads, and `IS_PENDING` hides the row until the bytes land. */
    private fun insertViaMediaStore(context: Context, document: BackupDocument, name: String): String? {
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, AppBackup.MIME_TYPE)
            put(MediaStore.MediaColumns.RELATIVE_PATH, "${Environment.DIRECTORY_DOWNLOADS}/$DOWNLOADS_FOLDER")
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: return null
        val written = runCatching {
            resolver.openOutputStream(uri)?.use { stream ->
                stream.write(AppBackup.encode(document).toByteArray(Charsets.UTF_8))
            } != null
        }.getOrDefault(false)
        if (!written) {
            resolver.delete(uri, null, null)
            return null
        }
        resolver.update(
            uri,
            ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) },
            null,
            null,
        )
        return "Downloads/$DOWNLOADS_FOLDER/$name"
    }

    /** Below Q there is no MediaStore row for an arbitrary file, so Downloads needs the grant. */
    private fun writeDownloadsDirectly(context: Context, document: BackupDocument, name: String): String? {
        val granted = Build.VERSION.SDK_INT < Build.VERSION_CODES.M ||
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.WRITE_EXTERNAL_STORAGE,
            ) == PackageManager.PERMISSION_GRANTED
        if (!granted) return null
        val directory = File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
            DOWNLOADS_FOLDER,
        )
        if (!directory.exists() && !directory.mkdirs()) return null
        val file = uniqueFile(directory, name)
        file.writeText(AppBackup.encode(document))
        return "Downloads/$DOWNLOADS_FOLDER/${file.name}"
    }

    /**
     * Where backups go when the system has no document picker to ask.
     *
     * Android TV builds routinely ship without DocumentsUI, so `CREATE_DOCUMENT` has nothing to
     * open and the viewer would be told to pick a folder that cannot be picked. This folder needs no
     * picker and no permission, and is the last resort: [saveToDownloads] is tried first because
     * this one cannot be reached from a television on Android 11 and later.
     */
    fun fallbackDirectory(context: Context): File =
        File(context.getExternalFilesDir(null) ?: context.filesDir, FALLBACK_DIRECTORY)

    /** Write [document] into [fallbackDirectory], returning the file it landed in. */
    fun writeFallback(context: Context, document: BackupDocument): File {
        val directory = fallbackDirectory(context).apply { mkdirs() }
        val file = uniqueFile(directory, AppBackup.fileName())
        file.writeText(AppBackup.encode(document))
        return file
    }

    /** The most recent backup sitting in [fallbackDirectory], for a restore with no picker. */
    fun latestFallback(context: Context): File? = fallbackDirectory(context)
        .listFiles { file -> file.isFile && file.name.endsWith(".json") }
        ?.maxByOrNull(File::lastModified)

    /** `Anilili-backup-20260823.json`, then `-2`, `-3`… so today's second backup keeps the first. */
    private fun uniqueFile(directory: File, name: String): File {
        val file = File(directory, name)
        if (!file.exists()) return file
        val stem = name.substringBeforeLast(".json")
        return generateSequence(2) { it + 1 }
            .map { File(directory, "$stem-$it.json") }
            .first { !it.exists() }
    }
}
