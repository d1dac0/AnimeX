package com.anilili.ui.watch

import androidx.media3.common.Player
import org.junit.Assert.assertEquals
import org.junit.Test

class PlayerGestureControlsTest {
    @Test
    fun playbackTogglePausesEvenWhilePlaybackIsBuffering() {
        assertEquals(false, playerToggleWillPlay(playWhenReady = true))
        assertEquals(true, playerToggleWillPlay(playWhenReady = false))
    }

    /**
     * A SHIELD viewer with auto-play off reached the end of an episode and found play/pause dead:
     * ExoPlayer leaves `playWhenReady` true at the end, so the toggle paused a finished player.
     */
    @Test
    fun playbackToggleStartsAgainAtTheEndOfAnEpisode() {
        assertEquals(true, playerToggleWillPlay(playWhenReady = true, playbackState = Player.STATE_ENDED))
        assertEquals(true, playerToggleWillPlay(playWhenReady = false, playbackState = Player.STATE_ENDED))
    }

    /** The same report: a finished episode was covered by the loading overlay as if it were loading. */
    @Test
    fun aFinishedEpisodeStillShowsAPicture() {
        assertEquals(true, playerShowsPicture(Player.STATE_READY))
        assertEquals(true, playerShowsPicture(Player.STATE_ENDED))
        assertEquals(false, playerShowsPicture(Player.STATE_BUFFERING))
        assertEquals(false, playerShowsPicture(Player.STATE_IDLE))
    }

    @Test
    fun horizontalSlideSeeksRelativeToDragDistance() {
        assertEquals(
            630_000L,
            playerSlideSeekTarget(
                startPositionMs = 600_000L,
                durationMs = 1_440_000L,
                horizontalDragPx = 250f,
                widthPx = 1_000f,
            ),
        )
        assertEquals(
            570_000L,
            playerSlideSeekTarget(
                startPositionMs = 600_000L,
                durationMs = 1_440_000L,
                horizontalDragPx = -250f,
                widthPx = 1_000f,
            ),
        )
    }

    @Test
    fun horizontalSlideClampsToVideoBounds() {
        assertEquals(0L, playerSlideSeekTarget(10_000L, 60_000L, -1_000f, 1_000f))
        assertEquals(60_000L, playerSlideSeekTarget(50_000L, 60_000L, 1_000f, 1_000f))
    }

    @Test
    fun horizontalSlideWithoutDurationKeepsCurrentPosition() {
        assertEquals(15_000L, playerSlideSeekTarget(15_000L, 0L, 500f, 1_000f))
    }

    @Test
    fun verticalDragSplitsBrightnessAndVolumeDownTheMiddle() {
        assertEquals(GestureZone.Brightness, playerGestureZone(0f, 1_000f, dragGestures = true))
        assertEquals(GestureZone.Brightness, playerGestureZone(499f, 1_000f, dragGestures = true))
        assertEquals(GestureZone.Volume, playerGestureZone(500f, 1_000f, dragGestures = true))
        assertEquals(GestureZone.Volume, playerGestureZone(1_000f, 1_000f, dragGestures = true))
    }

    @Test
    fun levelIndicatorAppearsOppositeTheDraggingThumb() {
        assertEquals(GestureIndicatorSide.Right, gestureIndicatorSide(GestureZone.Brightness))
        assertEquals(GestureIndicatorSide.Left, gestureIndicatorSide(GestureZone.Volume))
    }

    @Test
    fun midPictureDragsStillPickASideRatherThanGoingInert() {
        // The old edge strips reached ~28% in from each side and left the middle dead; every
        // horizontal position now belongs to one control or the other.
        assertEquals(GestureZone.Brightness, playerGestureZone(400f, 1_000f, dragGestures = true))
        assertEquals(GestureZone.Volume, playerGestureZone(600f, 1_000f, dragGestures = true))
    }

    @Test
    fun dragsFromTheShadeStripLeaveBrightnessAndVolumeAlone() {
        // Reaching for the notification shade must not dim the screen or mute the episode.
        assertEquals(true, startsInShadePullStrip(0f, 96f))
        assertEquals(true, startsInShadePullStrip(95f, 96f))
        assertEquals(false, startsInShadePullStrip(96f, 96f))
        assertEquals(false, startsInShadePullStrip(400f, 96f))
        assertEquals(false, startsInShadePullStrip(Float.NaN, 96f))
    }

    @Test
    fun disabledGesturesAndDegenerateSurfacesClaimNothing() {
        assertEquals(null, playerGestureZone(100f, 1_000f, dragGestures = false))
        assertEquals(null, playerGestureZone(100f, 0f, dragGestures = true))
        assertEquals(null, playerGestureZone(Float.NaN, 1_000f, dragGestures = true))
    }

    @Test
    fun seekDragIsSwitchedSeparatelyFromBrightnessAndVolume() {
        assertEquals(
            false,
            canSlideSeek(slideToSeek = false, hasSeekHandler = true, durationMs = 1_440_000L),
        )
        // The vertical drags are the other setting's business and keep claiming their side either
        // way; which side is which is [verticalDragSplitsBrightnessAndVolumeDownTheMiddle]'s to say.
        assertEquals(true, playerGestureZone(100f, 1_000f, dragGestures = true) != null)
    }

    @Test
    fun holdSpeedHasItsOwnSwitchAndAcceptsSlowOrFastTargets() {
        assertEquals(true, canStartHoldSpeed(enabled = true, hasHandler = true, speed = 0.5f))
        assertEquals(true, canStartHoldSpeed(enabled = true, hasHandler = true, speed = 4f))
        assertEquals(false, canStartHoldSpeed(enabled = false, hasHandler = true, speed = 2f))
        assertEquals(false, canStartHoldSpeed(enabled = true, hasHandler = false, speed = 2f))
    }

    @Test
    fun nothingToScrubMeansNoScrub() {
        assertEquals(
            false,
            canSlideSeek(slideToSeek = true, hasSeekHandler = false, durationMs = 1_440_000L),
        )
        assertEquals(
            false,
            canSlideSeek(slideToSeek = true, hasSeekHandler = true, durationMs = 0L),
        )
        assertEquals(
            true,
            canSlideSeek(slideToSeek = true, hasSeekHandler = true, durationMs = 1_440_000L),
        )
    }
}

/**
 * Two TV users independently reported the control row appearing but ignoring the remote, with OK
 * firing "previous episode" — the row's leading button. Both noted it behaved only while the
 * "Next Episode" action was up, which is the tell: that action already exists before the row
 * opens, so its focus reclaim never re-fires. The "+Ns" button appears *with* the row, so its
 * reclaim did fire, one frame after the row had taken focus.
 */
class SkipActionFocusTest {
    @Test
    fun openingTheControlRowDoesNotPullFocusBackToThePlayer() {
        assertEquals(false, reclaimsPlayerFocusOnSkipAction(controlsVisible = true))
    }

    @Test
    fun timedActionOverBarePictureStillReclaimsTheRemote() {
        assertEquals(true, reclaimsPlayerFocusOnSkipAction(controlsVisible = false))
    }

    /** Up three times and then OK was the only way to answer a skip; now OK alone does. */
    @Test
    fun everyTimedSkipTakesTheRemote() {
        listOf("Skip Recap", "Skip Intro", "Skip Outro", "Skipping Intro", "Skipping Outro").forEach {
            assertEquals(it, true, tvSkipActionTakesRemote(it))
        }
    }

    /** Offered whenever the row is up: taking the remote would make every OK an 85-second jump. */
    @Test
    fun fixedSkipAndNoActionLeaveTheRemoteAlone() {
        assertEquals(false, tvSkipActionTakesRemote("+85s"))
        assertEquals(false, tvSkipActionTakesRemote(null))
    }

    /** Down opens the row on "+85s"; OK and Up still open it on play/pause. */
    @Test
    fun onlyDownOpensTheRowOnTheFixedSkip() {
        assertEquals(true, tvRowOpensOnFixedSkip(openedByDown = true, fixedSkipShown = true, skipActionFocused = false))
        assertEquals(false, tvRowOpensOnFixedSkip(openedByDown = false, fixedSkipShown = true, skipActionFocused = false))
        // Turned off in settings, or too near the end for a jump to fit.
        assertEquals(false, tvRowOpensOnFixedSkip(openedByDown = true, fixedSkipShown = false, skipActionFocused = false))
        // Down pressed from the skip itself asks for the row.
        assertEquals(false, tvRowOpensOnFixedSkip(openedByDown = true, fixedSkipShown = true, skipActionFocused = true))
    }
}
