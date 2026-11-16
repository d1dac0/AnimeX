package com.anilili.ui.watch

import androidx.media3.common.VideoSize
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The words the quality notice puts on screen, and when it decides a report is worth saying.
 *
 * Both are pure so the two mistakes that matter can be caught without a device: calling a 4K master
 * by some other number, and re-announcing a height the viewer is already looking at.
 */
class PlayerQualityNoticeTest {
    @Test
    fun uhdReadsAsFourK() {
        assertEquals("4K", qualityLabel(VideoSize(3840, 2160)))
    }

    /** A 4K master trimmed to widescreen is still 4K, even though its height is not 2160. */
    @Test
    fun aTrimmedUhdMasterStillReadsAsFourK() {
        assertEquals("4K", qualityLabel(VideoSize(3840, 1600)))
    }

    @Test
    fun theCommonRungsReadAsTheirHeight() {
        assertEquals("1440p", qualityLabel(VideoSize(2560, 1440)))
        assertEquals("1080p", qualityLabel(VideoSize(1920, 1080)))
        assertEquals("720p", qualityLabel(VideoSize(1280, 720)))
        assertEquals("480p", qualityLabel(VideoSize(854, 480)))
        assertEquals("360p", qualityLabel(VideoSize(640, 360)))
    }

    /**
     * An anamorphic stream reports its coded height, which is what the ladder names: 1440x1080 at
     * 4:3 pixels is 1080p, not 1440p or 1080-something.
     */
    @Test
    fun anAnamorphicSizeIsNamedByItsCodedHeight() {
        assertEquals("1080p", qualityLabel(VideoSize(1440, 1080, 4f / 3f)))
    }

    @Test
    fun anUnknownSizeHasNoLabel() {
        assertNull(qualityLabel(VideoSize.UNKNOWN))
        assertNull(qualityLabel(VideoSize(1920, 0)))
        assertNull(qualityLabel(VideoSize(0, 1080)))
        assertNull(qualityLabel(0))
    }

    /** The settings sheet orders rungs by height alone, and no rung is 4K without saying so. */
    @Test
    fun aRungWithNoWidthStillNamesFourK() {
        assertEquals("4K", qualityLabel(2160))
        assertEquals("1080p", qualityLabel(1080))
    }

    @Test
    fun aNewHeightIsWorthAnnouncing() {
        assertEquals("720p", qualityNoticeLabel("720p", "1080p"))
        assertEquals("1080p", qualityNoticeLabel("1080p", null))
    }

    @Test
    fun theSameHeightIsNotRepeated() {
        assertNull(qualityNoticeLabel("1080p", "1080p"))
        assertNull(qualityNoticeLabel("4K", "4K"))
    }

    @Test
    fun emptyOrAbsentReportsSayNothing() {
        assertNull(qualityNoticeLabel(null, null))
        assertNull(qualityNoticeLabel(null, "1080p"))
        assertNull(qualityNoticeLabel("", "1080p"))
    }
}
