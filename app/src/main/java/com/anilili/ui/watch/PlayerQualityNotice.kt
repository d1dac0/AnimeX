package com.anilili.ui.watch

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.displayCutoutPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.media3.common.VideoSize
import com.anilili.ui.adaptive.LocalAppDeviceProfile

/*
 * The player's answer to "what quality is this actually running at?".
 *
 * The Quality section of the settings sheet lists the ladder a stream offers, not the rendition the
 * decoder settled on, and it is only on screen while the sheet is open. Meanwhile an adaptive
 * stream is free to drop from 1080p to 480p mid-episode on a weak connection, and until now it did
 * so in silence: the one number that says what is playing, Media3's `videoSize`, was received and
 * logged but only ever used for the picture's aspect ratio.
 *
 * So this is a readout, not a control: a chip that names the decoded height for a few seconds when
 * playback starts and again whenever that height changes. It is deliberately passive — no focus, no
 * pointer handling — because it reports over a picture that must not stop or move for it.
 */

/**
 * How long a reported height has to hold before the notice accepts it.
 *
 * An HLS stream ramps to its operating rendition over the first seconds — 480p, then 720p, then
 * whatever the connection supports — and each step is a real change of `videoSize`. Without a
 * settle the viewer would get three notices stacked on top of each other and learn nothing from any
 * of them; with it, a change has to last this long before it counts, and the ramp announces only
 * where it landed.
 */
internal const val QUALITY_NOTICE_SETTLE_MS = 1_000L

/** How long the chip stays up. Long enough to read at ten feet, short enough to get out of the way. */
internal const val QUALITY_NOTICE_VISIBLE_MS = 3_000L

/**
 * The quality a decoder height represents, in the words a quality ladder uses.
 *
 * Height is the unit every provider ladder is built on, so it is what the chip says: `1080p`,
 * `720p`, `480p`. The exception is the top rung, which nobody calls "2160p" — and a 4K master is
 * often trimmed to a widescreen height (a 3840x1600 letterboxed film), so [widthPx] is consulted
 * before falling back to the height. [heightPx] of zero or less is media3's "no picture yet", which
 * has no label to show.
 *
 * [widthPx] defaults to 0 rather than to a "very wide" value: the settings sheet orders rungs by
 * height alone, and a default that tripped the 4K branch would label every rung "4K".
 */
internal fun qualityLabel(heightPx: Int, widthPx: Int = 0): String? = when {
    heightPx <= 0 -> null
    heightPx >= UHD_HEIGHT_PX || widthPx >= UHD_WIDTH_PX -> "4K"
    else -> "${heightPx}p"
}

/**
 * [qualityLabel] for the size media3 reports on [VideoSize].
 *
 * Both dimensions have to be known. Media3 reports one of them as zero while a stream is still
 * being resolved, and a height on its own would name a picture the decoder has not produced yet.
 */
internal fun qualityLabel(videoSize: VideoSize): String? = when {
    videoSize.width <= 0 || videoSize.height <= 0 -> null
    else -> qualityLabel(videoSize.height, videoSize.width)
}

/**
 * What, if anything, a freshly reported label asks the notice to say.
 *
 * Null is "nothing to show": no report yet, or the same height the viewer has already been told
 * about. Everything else is the label to put up.
 */
internal fun qualityNoticeLabel(reportedLabel: String?, announcedLabel: String?): String? =
    reportedLabel?.takeIf { it.isNotBlank() && it != announcedLabel }

/**
 * The chip itself: the player's usual black-and-hairline treatment, sized to be read during a
 * glance rather than aimed at.
 *
 * Not focusable and without a gesture handler, so a D-pad press or a tap passes through it exactly
 * as if it were not there. It is a polite live region as well, because a viewer running TalkBack
 * hears a quality drop the same way a sighted one sees it.
 */
@Composable
internal fun PlayerQualityNotice(text: String, modifier: Modifier = Modifier) {
    val isTv = LocalAppDeviceProfile.current.isTv
    val shape = RoundedCornerShape(if (isTv) 12.dp else 10.dp)
    Box(
        modifier = modifier
            .clip(shape)
            .background(Color.Black.copy(alpha = 0.62f))
            .border(1.dp, Color.White.copy(alpha = 0.18f), shape)
            .padding(horizontal = if (isTv) 18.dp else 14.dp, vertical = if (isTv) 9.dp else 7.dp)
            .clearAndSetSemantics {
                contentDescription = "Video quality $text"
                liveRegion = LiveRegionMode.Polite
            },
    ) {
        Text(
            text = text,
            style = if (isTv) {
                MaterialTheme.typography.titleMedium
            } else {
                MaterialTheme.typography.titleSmall
            },
            fontWeight = FontWeight.SemiBold,
            color = Color.White.copy(alpha = 0.92f),
        )
    }
}

/**
 * Where the chip sits: the left margin, vertically centred.
 *
 * That band is the only one no other player overlay uses. The top belongs to the clock and the
 * seek readout, the bottom to the bar and the captions, both corners to the skip action and the
 * up-next card, and the phone's volume and brightness indicators only visit the same band while a
 * finger is dragging. A television also gets the overscan inset the rest of its overlays use.
 */
internal fun Modifier.playerQualityNoticeAnchor(isTv: Boolean): Modifier = if (isTv) {
    padding(start = TV_SAFE_AREA_INSET)
} else {
    displayCutoutPadding().padding(start = 24.dp)
}

/* 2160 lines is UHD; a few pixels under it is still a 4K master. */
private const val UHD_HEIGHT_PX = 2_000

/* 3840 is the 4K UHD width; 2560-wide 1440p masters must not match. */
private const val UHD_WIDTH_PX = 3_600
