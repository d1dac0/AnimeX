package com.anilili.ui.components

import com.anilili.ui.theme.appCanvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.isUnspecified
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlin.math.max
import androidx.compose.material3.MaterialTheme
import coil.compose.AsyncImage
import coil.compose.rememberAsyncImagePainter
import coil.request.ImageRequest
import com.anilili.data.model.Media

/**
 * A real landscape image is safe to crop across a television hero — and only a banner is one.
 *
 * A trailer thumbnail used to stand in when no banner existed, but YouTube's hqdefault is a
 * 480x360 frame: stretched across a hero it is magnified about fourfold and loses three quarters
 * of its height, which is why a featured title without a banner showed an unrecognisable close-up.
 * Falling through to null instead hands the title to the cover treatment below, which blurs a copy
 * for the background and keeps the poster whole.
 */
internal fun Media.wideHeroArtwork(): String? = bannerImage

/**
 * Where the clear part of a slide begins, as a fraction of its width. The title, the synopsis and
 * the left fade over them take everything before it, so a picture's subject belongs after it.
 */
private const val CLEAR_AREA_START = .35f

/** The narrowest a picture is drawn, as a fraction of the slide: below this it is cropped instead. */
private const val MIN_PICTURE_WIDTH = .50f

/** How far into the picture, as a fraction of the slide's width, its left edge takes to fade in. */
private const val PICTURE_FADE_WIDTH = .10f

/** Where a hero picture is drawn on a slide, in the slide's pixels, and where its left edge fades. */
internal data class HeroPictureGeometry(
    val scale: Float,
    val left: Float,
    val top: Float,
    val fadeStart: Float,
    val fadeEnd: Float,
)

/**
 * Sized to the slide's height, so a picture is cropped as little as the slide allows, and set
 * against the right edge.
 *
 * Cropped edge to edge, a 16:9 backdrop lost about half its height to a slide three times as wide
 * as it is tall and put its middle under the text. At the slide's height it is about half the
 * slide wide and shown whole, ending where the text does. A banner is far wider than 16:9: at the
 * slide's height it is wider than the slide, so it covers it, with its middle on the middle of the
 * clear area and never short of the right edge. A picture squarer than [MIN_PICTURE_WIDTH] allows
 * is drawn that wide instead and loses its bottom, as before.
 */
internal fun heroPictureGeometry(src: Size, slide: Size): HeroPictureGeometry {
    val scale = max(slide.height / src.height, slide.width * MIN_PICTURE_WIDTH / src.width)
    val width = src.width * scale
    val clearStart = slide.width * CLEAR_AREA_START
    val centred = slide.width * (1f + CLEAR_AREA_START) / 2f - width / 2f
    // Never short of the right edge; never starting inside the clear area when it is wide enough
    // to cover it, and set against the right edge when it is not.
    val left = centred.coerceIn(slide.width - width, max(slide.width - width, clearStart))
    val fadeStart = max(left, clearStart)
    return HeroPictureGeometry(scale, left, 0f, fadeStart, fadeStart + slide.width * PICTURE_FADE_WIDTH)
}

/**
 * A landscape picture drawn by [heroPictureGeometry], its left edge faded out of the picture's own
 * pixels (offscreen, DstIn) rather than painted over in black, so it meets whatever the slide sits
 * on — the canvas or the viewer's own background picture — without a seam.
 *
 * The offscreen layer that lets the mask reach only the picture is the most expensive rectangle
 * on the page (#48), so [lowCostTvEffects] skips it and paints the fade in the canvas colour over
 * the picture instead: no layer, no mask, and over the plain canvas the melt looks the same. Over
 * a viewer's own background picture it paints the canvas colour across it — the simplification
 * #48 accepts there.
 */
@Composable
private fun HeroPicture(url: String, lowCostTvEffects: Boolean, modifier: Modifier) {
    // Fit, so the request asks for a bitmap no larger than the slide. The size the painter is
    // drawn at below is what decides how it looks.
    val painter = rememberAsyncImagePainter(model = url, contentScale = ContentScale.Fit)
    val canvas = MaterialTheme.colorScheme.background
    Spacer(
        modifier
            .then(
                if (lowCostTvEffects) {
                    Modifier
                } else {
                    Modifier.graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
                },
            )
            .drawBehind {
                val src = painter.intrinsicSize
                if (src.isUnspecified || src.width <= 0f || src.height <= 0f) {
                    // Drawing once at the slide's size is what starts the request: the painter
                    // takes its target size from its first draw.
                    with(painter) { draw(size) }
                    return@drawBehind
                }
                val geometry = heroPictureGeometry(src, size)
                translate(geometry.left, geometry.top) {
                    with(painter) { draw(Size(src.width * geometry.scale, src.height * geometry.scale)) }
                }
                if (lowCostTvEffects) {
                    // (#48) The mask's own fade, inverted and painted in the canvas colour: the
                    // picture emerges as the cover thins, and the layer the mask needed is never
                    // drawn.
                    drawRect(
                        Brush.horizontalGradient(
                            0f to canvas,
                            1f to Color.Transparent,
                            startX = geometry.fadeStart,
                            endX = geometry.fadeEnd,
                        ),
                    )
                } else {
                    drawRect(
                        Brush.horizontalGradient(
                            0f to Color.Transparent,
                            1f to Color.Black,
                            startX = geometry.fadeStart,
                            endX = geometry.fadeEnd,
                        ),
                        blendMode = BlendMode.DstIn,
                    )
                }
            },
    )
}

/**
 * TV hero art that does not turn a portrait-only cover into a narrow, face-cropping backdrop.
 *
 * Landscape artwork is drawn at the slide's height against its right edge, clear of the text (see
 * [heroPictureGeometry]), and fades in at its left edge. When there is only a cover,
 * a deliberately small copy becomes the soft ambient background while the complete poster remains
 * visible at the right. The low-resolution request also avoids decoding a second full-size bitmap
 * just to blur it.
 *
 * [lowCostTvEffects] drops the parts the cheap boxes cannot carry (#48): the offscreen layer and
 * mask behind the landscape fade, and the 34dp blur behind the ambient cover. Home passes the
 * profile's answer; the default keeps every other caller rendering as it does today.
 */
@Composable
fun TvHeroArtwork(
    media: Media,
    modifier: Modifier = Modifier,
    posterEndPadding: Int = 56,
    posterTopPadding: Int = 24,
    /** A real landscape picture from elsewhere (TheTVDB fanart, TMDB) where AniList has no banner. */
    wideArtwork: String? = media.wideHeroArtwork(),
    lowCostTvEffects: Boolean = false,
) {
    val cover = media.coverImage.best

    Box(modifier.clipToBounds().appCanvas()) {
        if (wideArtwork != null) {
            HeroPicture(wideArtwork, lowCostTvEffects, Modifier.fillMaxSize())
        } else if (cover != null) {
            val context = LocalContext.current
            val ambientRequest = remember(cover) {
                ImageRequest.Builder(context)
                    .data(cover)
                    .size(320, 480)
                    .crossfade(false)
                    .build()
            }
            AsyncImage(
                model = ambientRequest,
                contentDescription = null,
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        scaleX = 1.16f
                        scaleY = 1.16f
                    }
                    // (#48) The ambient blur is a redraw pass the low-cost boxes cannot spare, and
                    // their copy is already soft from the stretch across the slide.
                    .then(if (lowCostTvEffects) Modifier else Modifier.blur(34.dp)),
                contentScale = ContentScale.Crop,
            )
            Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.30f)))
            AsyncImage(
                model = cover,
                contentDescription = null,
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .padding(end = posterEndPadding.dp, top = posterTopPadding.dp, bottom = 24.dp)
                    .fillMaxHeight()
                    .aspectRatio(2f / 3f)
                    .clip(RoundedCornerShape(14.dp)),
                contentScale = ContentScale.Fit,
            )
        }
    }
}
