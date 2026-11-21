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
import com.anilili.data.auth.SimklAuthManager
import com.anilili.diagnostics.DiagnosticsLog
import com.anilili.ui.rethrowIfCancellation
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * What the Simkl tracker is told, and the one place that decides whether it is told anything.
 *
 * Simkl runs beside the list service rather than instead of it: it hears about a watched episode,
 * a save or a status move whether the viewer is on AniList, MyAnimeList or neither. Every call is a
 * no-op while Simkl is not connected, and for catalogue-native titles (hanime, One Pace), whose
 * negative app-owned ids name nothing on Simkl.
 */
object SimklTracker {
    private lateinit var appContext: Context

    fun init(context: Context) {
        appContext = context.applicationContext
    }

    fun tracks(anilistId: Int): Boolean =
        ::appContext.isInitialized && SimklAuthManager.isLoggedIn && !isCatalogueNativeMediaId(anilistId)

    /** Episodes 1 through [progress] watched. */
    fun progress(anilistId: Int, progress: Int) {
        if (progress > 0 && tracks(anilistId)) SimklSyncWorker.enqueue(appContext, anilistId, SimklSyncWorker.OP_PROGRESS, progress.toString())
    }

    /** A status chosen in the app, in AniList's vocabulary — COMPLETED marks every episode. */
    fun status(anilistId: Int, aniListStatus: String) {
        if (tracks(anilistId)) SimklSyncWorker.enqueue(appContext, anilistId, SimklSyncWorker.OP_STATUS, aniListStatus)
    }

    /** Saved to, or taken off, My List. */
    fun saved(anilistId: Int, saved: Boolean) {
        if (tracks(anilistId)) SimklSyncWorker.enqueue(appContext, anilistId, SimklSyncWorker.OP_SAVED, saved.toString())
    }

    /** The title was removed from the viewer's list outright. */
    fun removed(anilistId: Int) {
        if (tracks(anilistId)) SimklSyncWorker.enqueue(appContext, anilistId, SimklSyncWorker.OP_REMOVE, "")
    }
}

/**
 * Persisted delivery to Simkl, built the way [ListProgressSyncWorker] is and for the same reasons:
 * the player's scope dies the moment the viewer leaves it, which is about when an episode counts as
 * watched, and nothing would ever resend a lost update. WorkManager keeps each one across
 * navigation, process death and lost connectivity.
 *
 * Each title has one serial chain, so a save, a status move and an episode reach Simkl in the order
 * they happened. Different titles may run side by side; [SimklClient] spaces their writes.
 *
 * A Simkl sign-out while work is queued drops it rather than holding it for a later account.
 */
class SimklSyncWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val anilistId = inputData.getInt(KEY_ANILIST_ID, 0)
        val op = inputData.getString(KEY_OP).orEmpty()
        val value = inputData.getString(KEY_VALUE).orEmpty()
        if (anilistId <= 0 || op.isEmpty()) return Result.failure()
        if (!SimklAuthManager.isLoggedIn) {
            log("abandoned", anilistId, op, value, "signed_out")
            return Result.success()
        }
        return try {
            val outcome = deliver(anilistId, op, value)
            log(outcome, anilistId, op, value)
            Result.success()
        } catch (error: CancellationException) {
            throw error
        } catch (error: SimklSignedOutException) {
            log("abandoned", anilistId, op, value, "signed_out")
            Result.success()
        } catch (error: Exception) {
            val retryable = (error as? SimklHttpException)?.retryable ?: true
            DiagnosticsLog.throwable(
                "Simkl $op attempt ${runAttemptCount + 1}/$MAX_ATTEMPTS failed id=$anilistId",
                error,
            )
            if (retryable && runAttemptCount + 1 < MAX_ATTEMPTS) {
                Result.retry()
            } else {
                // Consumed, not failed: a failed link would cancel everything queued behind it for
                // this title, and a later episode carries the earlier ones anyway.
                log("exhausted", anilistId, op, value)
                Result.success()
            }
        }
    }

    /** Sends one update and names what happened, for the diagnostics line. */
    private suspend fun deliver(anilistId: Int, op: String, value: String): String {
        val malId = runCatching { AppGraph.repository.malIdFor(anilistId) }
            .onFailure { it.rethrowIfCancellation() }
            .getOrNull()
        val ids = SimklPayloads.ids(anilistId, malId)
        val matched = when (op) {
            OP_PROGRESS -> SimklClient.addToHistory(SimklPayloads.history(ids, value.toInt()))
            OP_STATUS -> if (value == "COMPLETED") {
                SimklClient.addToHistory(SimklPayloads.completed(ids))
            } else {
                val to = simklListStatus(value) ?: return "skipped"
                SimklClient.addToList(SimklPayloads.addToList(ids, to))
            }
            OP_REMOVE -> SimklClient.removeFromHistory(SimklPayloads.remove(ids))
            OP_SAVED -> {
                val state = SimklClient.watchState(SimklPayloads.watchedQuery(ids))
                if (!state.found) {
                    false
                } else {
                    when (simklSavedAction(value.toBoolean(), state.list, state.watchedBefore)) {
                        SimklSavedAction.ADD_PLANNED -> SimklClient.addToList(SimklPayloads.addToList(ids, "plantowatch"))
                        SimklSavedAction.REMOVE -> SimklClient.removeFromHistory(SimklPayloads.remove(ids))
                        SimklSavedAction.NONE -> return "kept list=${state.list ?: "none"}"
                    }
                }
            }
            else -> return "unknown"
        }
        return if (matched) "sent" else "not_found mal=${malId ?: "none"}"
    }

    private fun log(outcome: String, anilistId: Int, op: String, value: String, reason: String? = null) {
        DiagnosticsLog.event(
            category = "simkl",
            name = "simkl.sync",
            attributes = buildMap {
                put("op", op)
                put("id", anilistId)
                if (value.isNotEmpty()) put("value", value)
                put("outcome", outcome)
                reason?.let { put("reason", it) }
            },
        )
    }

    companion object {
        internal const val OP_PROGRESS = "progress"
        internal const val OP_STATUS = "status"
        internal const val OP_SAVED = "saved"
        internal const val OP_REMOVE = "remove"

        private const val KEY_ANILIST_ID = "anilist_id"
        private const val KEY_OP = "op"
        private const val KEY_VALUE = "value"
        private const val MAX_ATTEMPTS = 6
        private const val TAG = "simkl-sync"

        internal fun enqueue(context: Context, anilistId: Int, op: String, value: String) {
            val request = OneTimeWorkRequestBuilder<SimklSyncWorker>()
                .setInputData(workDataOf(KEY_ANILIST_ID to anilistId, KEY_OP to op, KEY_VALUE to value))
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .addTag(TAG)
                .build()
            WorkManager.getInstance(context.applicationContext).enqueueUniqueWork(
                "simkl:$anilistId",
                ExistingWorkPolicy.APPEND_OR_REPLACE,
                request,
            )
            DiagnosticsLog.event(
                category = "simkl",
                name = "simkl.queued",
                attributes = mapOf("op" to op, "id" to anilistId, "value" to value),
            )
        }

        /**
         * Drops every queued update; used when the person signed in is about to change.
         *
         * The wait runs on IO: `result.get()` is unbounded while WorkManager's serial executor is
         * busy (pruneWork, an export), and the Settings disconnect row calls this from Main.
         */
        suspend fun cancelAll(context: Context) {
            withContext(Dispatchers.IO) {
                WorkManager.getInstance(context.applicationContext).cancelAllWorkByTag(TAG).result.get()
            }
        }
    }
}
