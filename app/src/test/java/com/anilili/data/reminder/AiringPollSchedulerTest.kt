package com.anilili.data.reminder

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AiringPollSchedulerTest {

    @Test
    fun pollsOnlyForASignedInViewerWithReleaseNotificationsOn() {
        assertTrue(AiringPollScheduler.shouldPoll(releaseNotifications = true, loggedIn = true))
    }

    @Test
    fun doesNotPollForALoggedOutViewer() {
        assertFalse(AiringPollScheduler.shouldPoll(releaseNotifications = true, loggedIn = false))
    }

    @Test
    fun doesNotPollWithReleaseNotificationsOff() {
        assertFalse(AiringPollScheduler.shouldPoll(releaseNotifications = false, loggedIn = true))
        assertFalse(AiringPollScheduler.shouldPoll(releaseNotifications = false, loggedIn = false))
    }
}
