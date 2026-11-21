package com.anilili.data.remote

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.anilili.data.AppGraph
import com.anilili.data.auth.AccountService
import com.anilili.data.library.LibraryStore
import com.anilili.diagnostics.DiagnosticsLog
import com.anilili.ui.rethrowIfCancellation
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

/**
 * Persisted progress delivery for whichever list service is signed in.
 *
 * Playback only has to enqueue the target episode. WorkManager retains it across navigation,
 * process death, and temporary loss of connectivity. Updates for one title form a serial chain;
 * both services refuse a progress regression, which makes out-of-order or repeated playback
 * harmless.
 *
 * MAL was delivered this way from the start. AniList was not: it posted straight from
 * viewModelScope, so leaving the player at the 80% mark cancelled the mutation mid-flight, and a
 * timeout or a 429 — a quota this app is already known to run close to — dropped the episode with
 * nothing but a diagnostics line. Nothing retried it, and nothing ever would: the account's count
 * only moves on the *next* episode, so a single lost push left that episode unrecorded on every
 * other device. AniList is the more popular login here and had the weaker path.
 *
 * The service is read when the work runs rather than when it is queued, so an update outlives the
 * screen that asked for it and still goes to the account the viewer is actually signed into.
 */
class ListProgressSyncWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val anilistId = inputData.getInt(KEY_ANILIST_ID, 0)
        val progress = inputData.getInt(KEY_PROGRESS, 0)
        val storedTotal = inputData.getInt(KEY_TOTAL_EPISODES, UNKNOWN_TOTAL)
        val totalEpisodes = storedTotal.takeIf { it > 0 }
        if (anilistId <= 0 || progress <= 0) {
            DiagnosticsLog.event("List progress work rejected invalid id=$anilistId progress=$progress")
            return Result.failure()
        }
        val service = AccountService.active
        if (service == null) {
            DiagnosticsLog.event(
                "List progress work abandoned id=$anilistId episode=$progress: signed out",
            )
            return Result.success()
        }

        return try {
            when (service) {
                AccountService.MAL -> syncMal(anilistId, progress, totalEpisodes)
                AccountService.ANILIST -> syncAniList(anilistId, progress, resolvedTotal(anilistId, totalEpisodes))
            }
            Result.success()
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            DiagnosticsLog.throwable(
                "${service.label} progress sync attempt ${runAttemptCount + 1}/${attemptLimit(error)} failed " +
                    "id=$anilistId episode=$progress",
                error,
            )
            if (AccountService.active != null && runAttemptCount + 1 < attemptLimit(error)) {
                Result.retry()
            } else {
                // Treat the exhausted item as consumed so a later episode appended to the unique
                // chain is still allowed to run. Its larger progress can repair the remote state.
                DiagnosticsLog.event(
                    "${service.label} progress sync exhausted id=$anilistId episode=$progress",
                )
                Result.success()
            }
        }
    }

    /**
     * The series length, looked up when the caller had none to give.
     *
     * It is what decides whether this episode finishes the series, and a title queued from the
     * offline player has no episode list behind it — without this, watching a finale from downloads
     * left the account on CURRENT forever. The lookup is served from the metadata cache in the
     * normal case; a failure is not worth losing the episode over, so it falls back to unknown and
     * the progress still goes out.
     */
    private suspend fun resolvedTotal(anilistId: Int, totalEpisodes: Int?): Int? {
        if (totalEpisodes != null) return totalEpisodes
        return runCatching { AppGraph.repository.animeInfo(anilistId)?.episodes }
            .onFailure { it.rethrowIfCancellation() }
            .getOrNull()
            ?.takeIf { it > 0 }
    }

    private suspend fun syncMal(anilistId: Int, progress: Int, totalEpisodes: Int?) {
        when (val sync = AppGraph.repository.saveMalProgress(anilistId, progress, totalEpisodes)) {
            is MalProgressSyncResult.Updated -> {
                sync.status?.let { LibraryStore.updateRemoteStatus(anilistId, it) }
                if (sync.status == "COMPLETED") LibraryStore.finishedOnAccount(anilistId, sync.targetProgress)
                DiagnosticsLog.event(
                    "MAL progress sync confirmed id=$anilistId malId=${sync.malId} " +
                        "target=$progress previous=${sync.previousProgress ?: "none"} " +
                        "confirmed=${sync.confirmedProgress} status=${sync.confirmedStatus ?: "unchanged"}",
                )
            }
            is MalProgressSyncResult.Skipped -> DiagnosticsLog.event(
                "MAL progress sync skipped id=$anilistId malId=${sync.malId ?: "none"} " +
                    "target=$progress remote=${sync.remoteProgress ?: "none"} " +
                    "reason=${sync.reason.name.lowercase()}",
            )
        }
    }

    private suspend fun syncAniList(anilistId: Int, progress: Int, totalEpisodes: Int?) {
        val update = AppGraph.repository.saveAniListProgress(anilistId, progress, totalEpisodes)
        update?.status?.let { LibraryStore.updateRemoteStatus(anilistId, it) }
        // A status only comes back when this push changed it, so COMPLETED means it finished the
        // series just now — and the row has to go with it, or nothing ever takes it off.
        if (update?.status == "COMPLETED") LibraryStore.finishedOnAccount(anilistId, update.progress)
        DiagnosticsLog.event(
            "AniList progress sync ${if (update == null) "skipped" else "confirmed"} " +
                "id=$anilistId episode=$progress",
        )
    }

    companion object {
        private const val KEY_ANILIST_ID = "anilist_id"
        private const val KEY_PROGRESS = "progress"
        private const val KEY_TOTAL_EPISODES = "total_episodes"
        private const val UNKNOWN_TOTAL = -1
        private const val MAX_ATTEMPTS = 6

        /**
         * How many runs a push gets before it is given up, by what made this one fail.
         *
         * The network constraint only says a network is up, not that AniList or MAL can be
         * reached through it, and the people most likely to watch from downloads are the ones
         * whose connection is the reason they downloaded. Six attempts with a 30-second
         * exponential backoff is about fifteen minutes: a hotel Wi-Fi behind a login page, or a
         * connection that drops every few minutes, used all six and lost the episode — and the
         * last episode of a session has no later push to repair it. A failure that never reached
         * the service, or one it refused for pace, says nothing about the push itself, so those
         * keep trying for about two days (backoff is capped at five hours). Anything the service
         * actually answered keeps the old limit, because a push that fails on its merits blocks
         * the title's later episodes queued behind it.
         */
        internal fun attemptLimit(error: Throwable): Int =
            if (isTransientDeliveryFailure(error)) MAX_TRANSIENT_ATTEMPTS else MAX_ATTEMPTS

        internal fun isTransientDeliveryFailure(error: Throwable): Boolean =
            generateSequence(error) { it.cause }.take(8).any { cause ->
                cause is java.net.SocketException ||
                    cause is java.net.UnknownHostException ||
                    cause is java.io.InterruptedIOException ||
                    cause is java.io.EOFException ||
                    cause is javax.net.ssl.SSLException ||
                    (cause as? com.anilili.errors.AnililiException)?.error ==
                    com.anilili.errors.AnililiError.ACCOUNT_RATE_LIMITED
            }

        private const val MAX_TRANSIENT_ATTEMPTS = 20

        private fun workName(anilistId: Int) = "list-progress:$anilistId"

        fun enqueue(context: Context, anilistId: Int, progress: Int, totalEpisodes: Int?) {
            val request = OneTimeWorkRequestBuilder<ListProgressSyncWorker>()
                .setInputData(
                    workDataOf(
                        KEY_ANILIST_ID to anilistId,
                        KEY_PROGRESS to progress,
                        KEY_TOTAL_EPISODES to (totalEpisodes ?: UNKNOWN_TOTAL),
                    ),
                )
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build(),
                )
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .addTag(TAG)
                .build()
            WorkManager.getInstance(context.applicationContext).enqueueUniqueWork(
                workName(anilistId),
                ExistingWorkPolicy.APPEND_OR_REPLACE,
                request,
            )
            DiagnosticsLog.event(
                "List progress queued id=$anilistId episode=$progress total=${totalEpisodes ?: "unknown"}",
            )
        }

        /**
         * Drops every queued push; used when the person signed in is about to change.
         *
         * Suspends and waits on IO: `result.get()` is unbounded while WorkManager's serial executor
         * is busy, so it must never be reachable from Main.
         */
        suspend fun cancelAll(context: Context) {
            withContext(Dispatchers.IO) {
                WorkManager.getInstance(context.applicationContext).cancelAllWorkByTag(TAG).result.get()
            }
            DiagnosticsLog.event("List progress work cancelled for a profile switch")
        }

        private const val TAG = "list-progress"
    }
}
