package com.anilili.ui.theme

import android.app.ActivityManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import androidx.compose.foundation.background
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.toRect
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.core.graphics.drawable.toBitmap
import coil.imageLoader
import coil.request.CachePolicy
import coil.request.ImageRequest
import coil.request.SuccessResult
import coil.size.Precision
import coil.size.Scale
import com.anilili.data.settings.AppBackdropSource
import com.anilili.data.settings.SettingsStore
import com.anilili.diagnostics.DiagnosticsLog
import com.anilili.ui.adaptive.rememberIsTelevision
import com.anilili.ui.adaptive.shouldUseLowCostTvEffects
import com.anilili.ui.dialogs.boxBlur
import com.anilili.ui.rethrowIfCancellation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.android.awaitFrame
import kotlinx.coroutines.withContext
import androidx.compose.runtime.LaunchedEffect
import kotlin.math.roundToInt

/**
 * The viewer's background picture, already cropped to the window, blurred and darkened.
 *
 * Everything the effect costs is paid once, off the main thread, when the picture or the window
 * changes: what the screens draw is one scaled bitmap, never a live blur. That is what lets a
 * television box from 2016 carry it — no RenderEffect below Android 12, and none needed.
 */
@Stable
class AppBackdrop internal constructor(val image: ImageBitmap)

/** The background picture in effect, or null for the plain canvas colour. Provided at [MiruroTheme]. */
val LocalAppBackdrop = staticCompositionLocalOf<AppBackdrop?> { null }

/** Whether a background picture is being drawn instead of the canvas colour. */
@Composable
@ReadOnlyComposable
fun hasAppBackdrop(): Boolean = LocalAppBackdrop.current != null

/**
 * The canvas colour for a slot that only takes a colour — a Scaffold's container, a top bar — or
 * transparent while a background picture is drawn, so the picture shows through it. Pair it with
 * [appCanvas] on the same element wherever it has to hide something drawn beneath it.
 */
@Composable
@ReadOnlyComposable
fun canvasColor(): Color = if (hasAppBackdrop()) Color.Transparent else MaterialTheme.colorScheme.background

/**
 * Loads and prepares the background picture the Appearance settings name, and provides it below.
 *
 * Keyed on the window's size too: a phone turned sideways needs the picture cropped the other way.
 */
@Composable
internal fun ProvideAppBackdrop(content: @Composable () -> Unit) {
    val source by SettingsStore.appBackdropSource.collectAsState()
    val animeUrl by SettingsStore.appBackdropAnimeUrl.collectAsState()
    val imageUrl by SettingsStore.appBackdropImage.collectAsState()
    val dim by SettingsStore.appBackdropDim.collectAsState()
    val blur by SettingsStore.appBackdropBlur.collectAsState()
    val url = when (source) {
        AppBackdropSource.NONE -> null
        AppBackdropSource.ANIME -> animeUrl
        AppBackdropSource.IMAGE -> imageUrl
    }
    val context = LocalContext.current
    val view = LocalView.current
    val configuration = LocalConfiguration.current
    // (#48) The device profile is provided below the theme, and this runs inside the theme, so the
    // low-cost answer is reached the way the theme reaches its form factor: from the device's own
    // signals, through the same predicate the profile uses.
    val activityManager = remember(context.applicationContext) {
        context.applicationContext.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
    }
    val lowCostTvEffects = shouldUseLowCostTvEffects(
        rememberIsTelevision(),
        activityManager?.isLowRamDevice == true,
        activityManager?.memoryClass,
    )
    var backdrop by remember { mutableStateOf<AppBackdrop?>(null) }
    LaunchedEffect(url, dim, blur, configuration.screenWidthDp, configuration.screenHeightDp, lowCostTvEffects) {
        if (url == null) {
            backdrop = null
            return@LaunchedEffect
        }
        // After a rotation the configuration arrives before the window is laid out again; one
        // frame later the root view has the size the picture has to fill.
        awaitFrame()
        var size = IntSize(view.rootView.width, view.rootView.height)
        while (size.width <= 0 || size.height <= 0) {
            awaitFrame()
            size = IntSize(view.rootView.width, view.rootView.height)
        }
        backdrop = renderAppBackdrop(context, url, size, dim.alpha, blur, lowCostTvEffects)?.let(::AppBackdrop)
    }
    CompositionLocalProvider(LocalAppBackdrop provides backdrop, content = content)
}

/**
 * Paints the app's canvas behind this element: the canvas colour, or — while a background picture
 * is set — the part of that picture lying behind this element on screen.
 *
 * The slice is aligned to the window, not to the element, so a header, a page and a sheet that each
 * paint it line up into one picture. That is what keeps every opaque fill doing its old job: a
 * pinned header still hides the rows scrolling under it, and still looks like the picture behind.
 */
fun Modifier.appCanvas(): Modifier = composed {
    val backdrop = LocalAppBackdrop.current
    val colour = MaterialTheme.colorScheme.background
    if (backdrop == null) {
        background(colour)
    } else {
        val view = LocalView.current
        var position by remember { mutableStateOf(Offset.Zero) }
        onGloballyPositioned { position = it.positionInWindow() }
            .drawBehind {
                clipRect {
                    drawRect(colour)
                    drawBackdropSlice(backdrop, position, IntSize(view.rootView.width, view.rootView.height))
                }
            }
    }
}

/**
 * A fade into the app's canvas: [brush] is given the canvas colour and returns the gradient that
 * used to be painted over artwork so it melts into the page. [alpha] is read while drawing, so an
 * animated fade costs no recomposition.
 *
 * With a background picture set, the same gradient is used as a mask over the picture's slice
 * instead — every stop's alpha kept — so the artwork melts into the picture rather than into a band
 * of black. Stops that were plain black scrims become that much of the (already darkened) picture,
 * which darkens the artwork the same way.
 *
 * [lowCostTvEffects] skips that mask: its `saveLayer` is the most expensive thing a fade can do
 * (#48), so a cheap box paints the gradient straight in the canvas colour, exactly as it is painted
 * when no background picture is set, and the picture behind the fade stays plain. Callers that do
 * not pass the flag keep the masked fade.
 */
fun Modifier.appCanvasFade(
    alpha: () -> Float = { 1f },
    lowCostTvEffects: Boolean = false,
    brush: (canvas: Color) -> Brush,
): Modifier = composed {
    val backdrop = LocalAppBackdrop.current
    val colour = MaterialTheme.colorScheme.background
    if (backdrop == null || lowCostTvEffects) {
        val painted = remember(colour) { brush(colour) }
        drawBehind { drawRect(painted, alpha = alpha().coerceIn(0f, 1f)) }
    } else {
        val view = LocalView.current
        val mask = remember { brush(Color.Black) }
        var position by remember { mutableStateOf(Offset.Zero) }
        onGloballyPositioned { position = it.positionInWindow() }
            .drawBehind {
                val a = alpha().coerceIn(0f, 1f)
                if (a <= 0f) return@drawBehind
                clipRect {
                    drawIntoCanvas { canvas ->
                        canvas.saveLayer(size.toRect(), androidx.compose.ui.graphics.Paint().apply { this.alpha = a })
                        drawBackdropSlice(backdrop, position, IntSize(view.rootView.width, view.rootView.height))
                        drawRect(mask, blendMode = BlendMode.DstIn)
                        canvas.restore()
                    }
                }
            }
    }
}

/**
 * Paints the canvas over [topLeft]/[rectSize] of this draw scope, for a backing that reaches past
 * its own bounds. [origin] is where this scope's (0, 0) sits in the window, which the caller has to
 * know — the scope itself cannot say.
 */
fun DrawScope.drawAppCanvas(
    backdrop: AppBackdrop?,
    colour: Color,
    origin: Offset,
    window: IntSize,
    topLeft: Offset = Offset.Zero,
    rectSize: androidx.compose.ui.geometry.Size = size,
) {
    drawRect(colour, topLeft = topLeft, size = rectSize)
    if (backdrop == null) return
    clipRect(topLeft.x, topLeft.y, topLeft.x + rectSize.width, topLeft.y + rectSize.height) {
        drawBackdropSlice(backdrop, origin, window)
    }
}

private fun DrawScope.drawBackdropSlice(backdrop: AppBackdrop, position: Offset, window: IntSize) {
    if (window.width <= 0 || window.height <= 0) return
    drawImage(
        image = backdrop.image,
        dstOffset = IntOffset(-position.x.roundToInt(), -position.y.roundToInt()),
        dstSize = window,
        filterQuality = FilterQuality.Low,
    )
}

/**
 * Decodes [url] and bakes it for a [window]-sized screen: cropped to the window's shape around its
 * centre, blurred if asked, darkened by [dim]. Null when the picture cannot be loaded, which leaves
 * the plain canvas — a background is never worth an error on screen.
 *
 * Stored small. Blurred, it is drawn from half the window's resolution — a third where the bitmap
 * has to be smaller (see [backdropRenderScale]) — where the blur hides the scaling entirely; sharp,
 * from at most [SHARP_LONG_SIDE] pixels on the long side. Both fit in a few megabytes.
 */
internal suspend fun renderAppBackdrop(
    context: Context,
    url: String,
    window: IntSize,
    dim: Float,
    blur: Boolean,
    lowCostTvEffects: Boolean = false,
): ImageBitmap? {
    val lowRam = (context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager)?.isLowRamDevice == true
    val longSide = maxOf(window.width, window.height).toFloat()
    val scale = backdropRenderScale(lowRam, lowCostTvEffects, blur, longSide)
    val width = (window.width * scale).roundToInt().coerceAtLeast(1)
    val height = (window.height * scale).roundToInt().coerceAtLeast(1)
    return try {
        val request = ImageRequest.Builder(context)
            .data(url)
            .size(width, height)
            .scale(Scale.FILL)
            .precision(Precision.INEXACT)
            .allowHardware(false)
            // Only the baked copy is kept; the decode would just sit in the memory cache twice over.
            .memoryCachePolicy(CachePolicy.DISABLED)
            .build()
        val decoded = (context.imageLoader.execute(request) as? SuccessResult)?.drawable?.toBitmap()
            ?: run {
                DiagnosticsLog.event("App background could not be loaded: ${url.take(120)}")
                return null
            }
        withContext(Dispatchers.Default) {
            val baked = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(baked)
            val crop = centreCrop(decoded.width, decoded.height, width, height)
            canvas.drawBitmap(
                decoded,
                Rect(crop.left, crop.top, crop.right, crop.bottom),
                Rect(0, 0, width, height),
                Paint(Paint.FILTER_BITMAP_FLAG),
            )
            if (blur) boxBlur(baked, radius = (minOf(width, height) / 45).coerceIn(4, 16), passes = 3)
            canvas.drawColor(android.graphics.Color.argb((dim * 255).roundToInt(), 0, 0, 0))
            DiagnosticsLog.event(
                "App background baked ${decoded.width}x${decoded.height} -> ${width}x$height " +
                    "blur=$blur dim=$dim lowRam=$lowRam lowCost=$lowCostTvEffects",
            )
            baked.asImageBitmap()
        }
    } catch (e: Exception) {
        e.rethrowIfCancellation()
        DiagnosticsLog.throwable("App background failed", e)
        null
    }
}

/** The largest centred part of a [sourceW]×[sourceH] picture with the shape of [targetW]×[targetH]. */
internal fun centreCrop(sourceW: Int, sourceH: Int, targetW: Int, targetH: Int): IntRect {
    val targetRatio = targetW.toFloat() / targetH
    return if (sourceW.toFloat() / sourceH > targetRatio) {
        val w = (sourceH * targetRatio).roundToInt().coerceIn(1, sourceW)
        val x = (sourceW - w) / 2
        IntRect(x, 0, x + w, sourceH)
    } else {
        val h = (sourceW / targetRatio).roundToInt().coerceIn(1, sourceH)
        val y = (sourceH - h) / 2
        IntRect(0, y, sourceW, y + h)
    }
}

/**
 * How large a baked background is drawn from, as a fraction of the window.
 *
 * Blurred, the blur hides the scaling, so the picture is baked from half the window — a third
 * where the bitmap has to be smaller: a low-RAM device, and, because the memory class alone misses
 * cheap television boxes with a roomy heap (#48), anything the profile calls [lowCostTvEffects].
 * Sharp, the long side is capped the same way, and never scaled up.
 */
internal fun backdropRenderScale(
    lowRam: Boolean,
    lowCostTvEffects: Boolean,
    blur: Boolean,
    longSide: Float,
): Float = when {
    blur -> if (lowRam || lowCostTvEffects) 1f / 3 else 0.5f
    else -> minOf(1f, (if (lowRam || lowCostTvEffects) SHARP_LOW_RAM_LONG_SIDE else SHARP_LONG_SIDE) / longSide)
}

/** A sharp picture's long side, in pixels: enough for a phone or a 1080p television at arm's length. */
private const val SHARP_LONG_SIDE = 1600f

/** A sharp picture's long side on a low-RAM device, or one the profile calls low-cost. */
private const val SHARP_LOW_RAM_LONG_SIDE = 960f
