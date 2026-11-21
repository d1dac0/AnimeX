package com.anilili.data.reminder

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ReceiverWorkTest {

    @Test
    fun finishesTheBroadcastAfterTheWorkCompletes() = runBlocking {
        var finished = 0
        var ran = false
        launchReceiverWork("test", { finished++ }, CoroutineScope(Dispatchers.Default)) {
            ran = true
        }.join()
        assertTrue(ran)
        assertEquals(1, finished)
    }

    @Test
    fun finishesTheBroadcastWhenTheWorkThrows() = runBlocking {
        var finished = 0
        val job = launchReceiverWork("test", { finished++ }, CoroutineScope(Dispatchers.Default)) {
            error("notification could not be built")
        }
        job.join()
        assertEquals(1, finished)
        // The failure is logged, not rethrown into the scope, so the process survives it.
        assertTrue(job.isCompleted && !job.isCancelled)
    }
}
