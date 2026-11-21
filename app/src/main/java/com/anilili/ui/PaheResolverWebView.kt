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
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.anilili.data.remote.PaheBridge
import com.anilili.diagnostics.CrashReporter
import com.anilili.diagnostics.DiagnosticsLog
import com.anilili.diagnostics.WebViewProcessController

/**
 * Hidden resolver WebView for AnimePahe.
 *
 * Unlike the flixcloud resolver this never plays anything — it exists only because AnimePahe's
 * Cloudflare zone binds its clearance to the engine that earned it, so the requests have to come
 * from a WebView rather than from OkHttp carrying its cookie. See [PaheBridge].
 */
@Composable
fun PaheResolverWebView() {
    // Keep the helper out of the UI without giving the challenge a 1dp layout viewport.
    // Use the real window dimensions, as the pipe hosts do, so screen and viewport agree.
    val configuration = LocalConfiguration.current
    Box(Modifier.size(1.dp).clipToBounds()) {
        AndroidView(
            factory = { ctx ->
                try {
                    DiagnosticsLog.webViewPackage("PaheResolverWebView factory")
                    WebView(ctx).also {
                        // Timers are paused process-wide, never per view; a pause left behind by
                        // the embed player would stall the clearance into its cooldown.
                        it.resumeTimers()
                        it.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
                        it.isFocusable = false
                        it.isClickable = false
                        // A full-window software layer repaints a bitmap on every challenge frame.
                        it.setLayerType(View.LAYER_TYPE_HARDWARE, null)
                        PaheBridge.attach(it)
                        WebViewProcessController.register(it, "animepahe-resolver")
                    }
                } catch (e: Throwable) {
                    CrashReporter.logNonFatal("System WebView unavailable; AnimePahe disabled", e)
                    View(ctx)
                }
            },
            onRelease = { view ->
                val web = view as? WebView ?: return@AndroidView
                DiagnosticsLog.event("PaheResolverWebView release url=${web.url ?: "none"}")
                PaheBridge.detach(web)
                web.stopLoading()
                web.webChromeClient = null
                web.webViewClient = WebViewClient()
                web.loadUrl("about:blank")
                WebViewProcessController.release(web, "animepahe-resolver")
                web.destroy()
            },
            modifier = Modifier.requiredSize(configuration.screenWidthDp.dp, configuration.screenHeightDp.dp),
        )
    }
}
