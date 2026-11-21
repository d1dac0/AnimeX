package com.anilili.data.reminder

import com.anilili.diagnostics.DiagnosticsLog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * Runs a `goAsync()` receiver's work off Main and always finishes the broadcast.
 *
 * [finish] is `PendingResult::finish`. It sits in a `finally`: a receiver that never finishes its
 * broadcast is a background ANR or a process kill, and building or posting a notification can throw
 * (a bad poster bitmap, a revoked channel). A failure is logged rather than rethrown, because an
 * uncaught exception on this scope would take the whole process down for a missed alert.
 */
internal fun launchReceiverWork(
    label: String,
    finish: () -> Unit,
    scope: CoroutineScope = CoroutineScope(Dispatchers.IO),
    block: suspend () -> Unit,
): Job = scope.launch {
    try {
        block()
    } catch (error: CancellationException) {
        throw error
    } catch (error: Exception) {
        DiagnosticsLog.throwable("$label failed", error)
    } finally {
        finish()
    }
}
