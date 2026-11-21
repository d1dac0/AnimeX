package com.anilili.ui

import android.annotation.SuppressLint
import android.graphics.Color
import android.os.Build
import android.view.KeyEvent
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.anilili.data.remote.CloudflareChallengeCoordinator
import com.anilili.data.remote.CloudflareClearance
import com.anilili.data.remote.CloudflareClearanceStore
import com.anilili.diagnostics.DiagnosticsLog
import com.anilili.diagnostics.WebViewProcessController
import com.anilili.ui.adaptive.focusFill
import com.anilili.ui.adaptive.rememberFocusFill
import java.util.concurrent.atomic.AtomicBoolean

/**
 * App-level host for the one-time Cloudflare check.
 *
 * Sits beside [AllAnimeCaptchaHost] for the same reason: a provider running on a background
 * dispatcher needs a browser to appear without the networking layer knowing anything about which
 * screen the viewer happens to be on.
 */
@Composable
fun CloudflareChallengeHost() {
    val challenge by CloudflareChallengeCoordinator.challenge.collectAsState()
    challenge?.let { current ->
        CloudflareChallengeDialog(
            challenge = current,
            onSolved = { CloudflareChallengeCoordinator.submit(current.id, it) },
            onCancel = { CloudflareChallengeCoordinator.cancel(current.id) },
        )
    }
}

@Composable
private fun CloudflareChallengeDialog(
    challenge: CloudflareChallengeCoordinator.Challenge,
    onSolved: (CloudflareClearance) -> Unit,
    onCancel: () -> Unit,
) {
    Dialog(
        onDismissRequest = onCancel,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(
                // Back has to be claimed in preview or a focused WebView eats it — see
                // AllAnimeCaptchaDialog for the same problem and the same fix.
                modifier = Modifier.fillMaxSize().padding(20.dp).onPreviewKeyEvent { event ->
                    val isBack = event.key == Key.Back || event.key == Key.Escape
                    if (isBack && event.type == KeyEventType.KeyUp) {
                        onCancel()
                        true
                    } else {
                        isBack
                    }
                },
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                // Deliberately terse. A Cloudflare interstitial centres its checkbox in the
                // viewport, so every line of explanation here pushes that checkbox further down —
                // on a phone, a four-line preamble moved it off-screen entirely and the check
                // became impossible to complete.
                Text(
                    "${challenge.host} security check",
                    style = MaterialTheme.typography.titleMedium,
                )
                Box(
                    modifier = Modifier.fillMaxWidth().weight(1f).heightIn(min = 260.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    CloudflareWebView(challenge.host, challenge.url, onSolved, onCancel)
                }
                val extraFocus1 = rememberFocusFill()
                Button(
                    colors = extraFocus1.buttonColors(ButtonDefaults.buttonColors()),onClick = onCancel, modifier = Modifier.align(Alignment.End).focusFill(extraFocus1, RoundedCornerShape(24.dp))) {
                    Text("Skip this server")
                }
            }
        }
    }
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun CloudflareWebView(
    host: String,
    url: String,
    onSolved: (CloudflareClearance) -> Unit,
    onCancel: () -> Unit,
) {
    val currentOnCancel by rememberUpdatedState(onCancel)
    val currentOnSolved by rememberUpdatedState(onSolved)
    AndroidView(
        modifier = Modifier.fillMaxSize().focusProperties { canFocus = true },
        factory = { context ->
            WebView(context).apply {
                // Timers are paused process-wide, never per view; a pause left behind by the
                // embed player would freeze the challenge script.
                resumeTimers()
                setOnKeyListener { _, keyCode, event ->
                    if (keyCode == KeyEvent.KEYCODE_BACK && event.action == KeyEvent.ACTION_UP) {
                        currentOnCancel()
                        true
                    } else {
                        false
                    }
                }
                setBackgroundColor(Color.rgb(245, 245, 245))
                isFocusable = true
                isFocusableInTouchMode = true
                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT,
                )
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                settings.allowFileAccess = false
                settings.allowContentAccess = false
                settings.setSupportMultipleWindows(false)
                // Deliberately NOT overridden. Cloudflare issues clearance against the agent that
                // solved the challenge, and CloudflareInterceptor replays requests under the
                // WebView's default agent — so spoofing one here would earn a cookie the app can
                // never present. This is the same reason CloudStream passes `userAgent = null`.
                settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
                CookieManager.getInstance().setAcceptCookie(true)
                CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)

                val completed = AtomicBoolean(false)
                fun harvest(from: String) {
                    if (completed.get()) return
                    val clearance = CloudflareClearanceStore.harvest(url, host) ?: return
                    if (completed.compareAndSet(false, true)) {
                        DiagnosticsLog.event(
                            category = "cloudflare",
                            name = "cloudflare.challenge.cleared",
                            attributes = mapOf("host" to host, "at" to from),
                        )
                        currentOnSolved(clearance)
                    }
                }

                webChromeClient = object : WebChromeClient() {
                    // A managed challenge clears itself without a navigation: the page stays put and
                    // the cookie simply appears. Progress is the only signal that fires for it, so
                    // the cookie jar is checked on the way up as well as at onPageFinished.
                    override fun onProgressChanged(view: WebView?, newProgress: Int) {
                        super.onProgressChanged(view, newProgress)
                        if (newProgress >= PROGRESS_HARVEST_AT) harvest("progress")
                    }
                }
                webViewClient = object : WebViewClient() {
                    override fun onPageFinished(view: WebView?, finishedUrl: String?) {
                        super.onPageFinished(view, finishedUrl)
                        view?.requestFocus()
                        harvest("pageFinished")
                        // Not immediately: Cloudflare injects its widget with script that runs
                        // after the load event, so probing the DOM the instant the page finishes
                        // finds no challenge on a page that is about to show one, and would cancel
                        // the very challenge the viewer is meant to solve.
                        view?.postDelayed({
                            if (!completed.get()) view.giveUpUnlessChallenged(host, currentOnCancel)
                        }, CHALLENGE_SETTLE_MS)
                    }

                    // Without this the platform takes the whole app down when the shared Chromium
                    // renderer dies, which a constrained TV does routinely.
                    @android.annotation.TargetApi(Build.VERSION_CODES.O)
                    override fun onRenderProcessGone(
                        view: WebView?,
                        detail: RenderProcessGoneDetail?,
                    ): Boolean {
                        DiagnosticsLog.event(
                            "Cloudflare challenge render process gone didCrash=${detail?.didCrash()}",
                        )
                        currentOnCancel()
                        return true
                    }
                }
                WebViewProcessController.register(this, "cloudflare-challenge")
                loadUrl(url)
            }
        },
        onRelease = { view ->
            val web = view as WebView
            DiagnosticsLog.event("Cloudflare challenge WebView release")
            web.stopLoading()
            web.webChromeClient = null
            web.webViewClient = WebViewClient()
            web.settings.javaScriptEnabled = false
            web.clearHistory()
            WebViewProcessController.release(web, "cloudflare-challenge")
            web.destroy()
        },
    )
}

/**
 * Closes the dialog when the loaded page is the real site rather than a challenge.
 *
 * Some Cloudflare zones admit a WebView on its TLS fingerprint alone and set no cookie at all —
 * measured against miruro.to, which served its full homepage with an empty cookie jar. There is
 * nothing to solve and nothing to harvest there, so leaving the dialog up strands the viewer
 * looking at somebody's website with no checkbox anywhere on it, which is exactly what happened.
 *
 * The DOM is the only reliable way to tell that apart from a Turnstile that is genuinely waiting
 * for a tap: an unsolved challenge always still has its widget in the page.
 */
private fun WebView.giveUpUnlessChallenged(host: String, onCancel: () -> Unit) {
    val probe = CHALLENGE_SELECTORS.joinToString(",") { "'$it'" }
    evaluateJavascript(
        "(function(){try{return [$probe].some(function(s){return !!document.querySelector(s)})}" +
            "catch(e){return true}})()",
    ) { result ->
        if (result == "true") return@evaluateJavascript
        DiagnosticsLog.event(
            category = "cloudflare",
            name = "cloudflare.challenge.nothing-to-solve",
            attributes = mapOf("host" to host),
        )
        onCancel()
    }
}

private val CHALLENGE_SELECTORS = listOf(
    "#challenge-form",
    "#challenge-running",
    "#challenge-stage",
    "#cf-challenge-running",
    ".cf-turnstile",
    "iframe[src*='challenges.cloudflare.com']",
)

private const val PROGRESS_HARVEST_AT = 90
private const val CHALLENGE_SETTLE_MS = 2_500L
