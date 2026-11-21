package com.anilili.ui

import android.view.View
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.anilili.data.remote.HanimeBridge
import com.anilili.diagnostics.CrashReporter
import com.anilili.diagnostics.DiagnosticsLog
import com.anilili.diagnostics.WebViewProcessController

/**
 * Hidden resolver WebView for hanime. Not a player surface; it exists so the site's own WASM can
 * produce its rotating credentials and negotiate a stream URL.
 *
 * The caller composes this only while adult content is switched on, so a viewer who never enables
 * it does not carry a third resident WebView — which matters most on the TV sticks where memory
 * pressure already costs us the hardware decoder.
 */
@Composable
fun HanimeResolverWebView() {
    // A one-pixel window onto a full-sized page.
    //
    // The WebView itself has to be big: the rebuilt site only mounts its player when that part of
    // the page is on screen, and at the 1dp this used to be it never was — measured, the page
    // reached `readyState=complete` with 150KB of body and *zero* video elements, so the WASM
    // handshake the resolver exists to observe never ran at all.
    //
    // The clip is what keeps it hidden, and it is deliberately a clip rather than a zero alpha or
    // an off-screen offset. Both of those were tried and neither works here: at a real size the
    // page now builds real video elements and paints frames of hanime, and on the release build
    // that came out as a thumbnail of the resolver drawn over the player. Clipping the parent is
    // the one form of hiding that Chromium's own painting cannot escape.
    Box(Modifier.size(1.dp).clipToBounds()) {
        ResolverWebView()
    }
}

@Composable
private fun ResolverWebView() {
    AndroidView(
        factory = { ctx ->
            try {
                DiagnosticsLog.event("HanimeResolverWebView factory create WebView start")
                WebView(ctx).also {
                    // Timers are paused process-wide, never per view; a pause left behind by the
                    // embed player would stall this resolver until it timed out.
                    it.resumeTimers()
                    it.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
                    it.isFocusable = false
                    it.isClickable = false
                    // Software layer keeps this hidden helper off Chromium's SurfaceControl
                    // overlay path — see PipeWebView for details.
                    it.setLayerType(View.LAYER_TYPE_SOFTWARE, null)
                    HanimeBridge.attach(it)
                    WebViewProcessController.register(it, "hanime-resolver")
                    DiagnosticsLog.event("HanimeResolverWebView factory create WebView complete")
                }
            } catch (e: Throwable) {
                CrashReporter.logNonFatal("System WebView unavailable; hanime resolver disabled", e)
                View(ctx)
            }
        },
        onRelease = { view ->
            val web = view as? WebView ?: return@AndroidView
            DiagnosticsLog.event("HanimeResolverWebView release")
            HanimeBridge.detach(web)
            web.stopLoading()
            web.webChromeClient = null
            web.webViewClient = WebViewClient()
            web.loadUrl("about:blank")
            WebViewProcessController.release(web, "hanime-resolver")
            web.destroy()
        },
        // `requiredSize` so the page is laid out at a real viewport regardless of the one-pixel
        // box it is clipped to — an ordinary `size` would be overruled by the parent's constraint
        // and put us straight back to a three-pixel page.
        modifier = Modifier.requiredSize(VIEWPORT_WIDTH.dp, VIEWPORT_HEIGHT.dp),
    )
}

/**
 * A viewport big enough for hanime to treat this as a real browser.
 *
 * Only one pixel of it is ever painted, so the numbers have to be plausible rather than to match
 * the device.
 */
private const val VIEWPORT_WIDTH = 412
private const val VIEWPORT_HEIGHT = 892
