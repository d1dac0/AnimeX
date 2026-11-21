package com.anilili.ui.profile

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.os.Build
import android.view.KeyEvent
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.anilili.data.remote.looksLikePipeChallengeTitle
import com.anilili.diagnostics.CrashReporter
import com.anilili.diagnostics.DiagnosticsLog
import com.anilili.diagnostics.WebViewProcessController
import com.anilili.ui.adaptive.LocalAppDeviceProfile
import com.anilili.ui.adaptive.focusFill
import com.anilili.ui.adaptive.rememberFocusFill
import com.anilili.ui.components.AppChromeTakeoverEffect
import com.anilili.ui.sendTab
import com.anilili.ui.tabDirectionForRemoteKey
import kotlin.coroutines.resume
import kotlinx.coroutines.android.awaitFrame
import kotlinx.coroutines.isActive
import kotlinx.coroutines.suspendCancellableCoroutine
import org.json.JSONObject

/**
 * Fullscreen OAuth login shared by AniList and MyAnimeList. Loads [authorizeUrl]; when the
 * service redirects to its registered localhost URL, [extractResult] pulls the token (AniList
 * implicit grant) or authorization code (MAL) out of it before Android networking tries to
 * open localhost, and the result is handed to [onResult].
 */

/**
 * Android TV's system IME is unreliable for WebView inputs: it can appear while D-pad events keep
 * moving focus around the page, then dismiss as soon as the HTML input blurs. This script reports
 * editable focus to a native Compose editor. The editor owns D-pad/IME focus and mirrors its value
 * back into the original first-party OAuth field. It is injected after every full navigation.
 */
private const val TV_EDITOR_JS = """
(function() {
  if (window.__miruroTvEditorInstalled) return;
  window.__miruroTvEditorInstalled = true;
  window.__miruroTvEditorSequence = 0;

  var focusStyle = document.createElement('style');
  focusStyle.id = 'miruro-tv-focus-style';
  focusStyle.textContent =
    '[data-miruro-tv-focused="true"]{' +
    'outline:4px solid #8f7cff !important;' +
    'outline-offset:4px !important;' +
    'box-shadow:0 0 0 6px rgba(143,124,255,.28) !important;' +
    '}';
  (document.head || document.documentElement).appendChild(focusStyle);

  function editable(el) {
    if (!el) return false;
    if (el.isContentEditable) return true;
    var tag = (el.tagName || '').toLowerCase();
    if (tag === 'textarea') return true;
    if (tag !== 'input') return false;
    var t = (el.type || 'text').toLowerCase();
    return ['hidden','button','checkbox','radio','submit','reset','file','image','range','color'].indexOf(t) < 0;
  }
  function visible(el) {
    if (!el) return false;
    var rect = el.getBoundingClientRect();
    var style = window.getComputedStyle(el);
    return rect.width > 0 && rect.height > 0 &&
      style.display !== 'none' && style.visibility !== 'hidden' &&
      Number(style.opacity || 1) > 0;
  }
  function editables() {
    return Array.prototype.filter.call(
      document.querySelectorAll('input,textarea,[contenteditable="true"]'),
      function(el) { return editable(el) && visible(el) && !el.disabled && !el.readOnly; }
    );
  }
  // Cloudflare puts the Turnstile check in a *closed* shadow root, so its iframe is unreachable
  // from here: querySelectorAll never pierces it and el.shadowRoot is null. The walker therefore
  // stepped straight over the widget — verified on the TV emulator, where focus went password ->
  // Login with no stop in between and the checkbox could never be ticked. What the light DOM does
  // expose is the hidden input the form reads the token from; its parent is the widget box. Make
  // that box a stop of its own so the remote can at least arrive at the check, from where Tab
  // crosses into it.
  function markChallenges() {
    document.querySelectorAll('input[id^="cf-chl-widget"][id$="_response"]').forEach(function(input) {
      var host = input.parentElement;
      if (!host || host.dataset.miruroTvChallenge) return;
      host.dataset.miruroTvChallenge = 'true';
      if (host.tabIndex < 0) host.setAttribute('tabindex', '0');
    });
  }
  function inChallenge(el) {
    return !!(el && el.closest && el.closest('[data-miruro-tv-challenge]'));
  }
  function controls() {
    markChallenges();
    return Array.prototype.filter.call(
      document.querySelectorAll(
        'input,textarea,[contenteditable="true"],button,select,a[href],[role="button"],' +
        '[tabindex],[onclick],form .submit,iframe'
      ),
      function(el) {
        return visible(el) && !el.disabled && el.getAttribute('tabindex') !== '-1';
      }
    );
  }
  function markFocused(el) {
    var previous = window.__miruroTvControl;
    if (previous && previous !== el) previous.removeAttribute('data-miruro-tv-focused');
    window.__miruroTvControl = el;
    el.setAttribute('data-miruro-tv-focused', 'true');
  }
  function reportEditable(el) {
    if (!el.dataset.miruroTvEditorId) {
      el.dataset.miruroTvEditorId = String(++window.__miruroTvEditorSequence);
    }
    var fields = editables();
    var index = fields.indexOf(el);
    var type = (el.type || (el.isContentEditable ? 'text' : el.tagName) || 'text').toLowerCase();
    var label = el.getAttribute('aria-label') || el.placeholder || el.name ||
      (type === 'password' ? 'Password' : 'Text field');
    MiruroTvEditor.onEditableFocused(
      el.dataset.miruroTvEditorId,
      label,
      type,
      el.value || el.textContent || '',
      index >= 0 && index < fields.length - 1
    );
  }
  function focusControl(el) {
    if (!el) return false;
    markFocused(el);
    if (el.tabIndex < 0 && !editable(el)) el.setAttribute('tabindex', '0');
    try { el.focus({ preventScroll: false }); } catch (_) { el.focus(); }
    try { el.scrollIntoView({ block: 'center', inline: 'nearest' }); } catch (_) {}
    return true;
  }
  // Arriving at a text field must NOT open the native editor — only asking for it may.
  //
  // This listener used to call reportEditable() on every focusin, which made every text field a
  // one-way trap: the walker could enter one but the modal editor opened the instant it did, and
  // dismissing that editor moved focus on to the form's submit. On MyAnimeList's OAuth page the
  // four social-login buttons ("Sign in with Google" among them) sit *above* the username box, so
  // a viewer trying to reach them pressed Up, landed on a field, got the keyboard, backed out onto
  // the Login button, pressed Up again — and cycled there forever, unable to sign in with Google
  // at all. Passing over a field is now free; Select is what opens it.
  window.__miruroTvOpenOnFocus = false;
  document.addEventListener('focusin', function(e) {
    var el = e.target;
    // Claimed before anything can bail out. A request lasts for exactly one focus change, so a
    // request that lands somewhere unexpected — an offscreen input, a control the walker skips —
    // must still be spent there rather than left armed to fire on the next field walked over.
    var requested = window.__miruroTvOpenOnFocus;
    window.__miruroTvOpenOnFocus = false;
    if (!el || !visible(el)) return;
    markFocused(el);
    if (editable(el) && requested) reportEditable(el);
  }, true);

  window.__miruroTvFocusKind = function() {
    var el = document.activeElement;
    if (!el) return 'page';
    // Focus inside a closed shadow root reports as its host, so the challenge is recognised by the
    // host rather than by finding an IFRAME — there is never one to find.
    if (el.tagName === 'IFRAME' || inChallenge(el)) return 'frame';
    return 'page';
  };
  window.__miruroTvMoveFocus = function(delta) {
    var items = controls();
    if (!items.length) return false;
    var current = window.__miruroTvControl;
    var index = items.indexOf(current);
    if (index < 0) {
      if (delta >= 0) {
        var firstEditable = items.filter(editable)[0];
        return focusControl(firstEditable || items[0]);
      }
      return focusControl(items[items.length - 1]);
    }
    var next = Math.max(0, Math.min(items.length - 1, index + (delta >= 0 ? 1 : -1)));
    return focusControl(items[next]);
  };
  window.__miruroTvFocusInitial = function() {
    var current = window.__miruroTvControl;
    if (current && document.documentElement.contains(current)) return true;
    return window.__miruroTvMoveFocus(1);
  };
  window.__miruroTvActivate = function() {
    var el = window.__miruroTvControl || document.activeElement;
    if (!el || el === document.body || el === document.documentElement) {
      return window.__miruroTvMoveFocus(1);
    }
    if (editable(el)) {
      reportEditable(el);
      return true;
    }
    el.click();
    return true;
  };
  window.__miruroTvFocusAfter = function(el) {
    var form = el.form || (el.closest ? el.closest('form') : null);
    if (form) {
      var submit = Array.prototype.filter.call(
        form.querySelectorAll(
          'button[type="submit"],input[type="submit"],button:not([type]),.submit,[role="button"],[onclick]'
        ),
        visible
      )[0];
      if (submit) return focusControl(submit);
    }
    var items = controls();
    var index = items.indexOf(el);
    if (index >= 0 && index < items.length - 1) {
      return focusControl(items[index + 1]);
    }
    el.blur();
    return false;
  };
})();
"""

private const val TV_FOCUS_FIRST_JS =
    "window.__miruroTvFocusInitial && window.__miruroTvFocusInitial();"
/**
 * Where focus currently sits: `frame` once it is inside something the injected walker cannot see
 * into. Cloudflare's Turnstile check on AniList's login page is the case that matters — it sits in
 * a cross-origin iframe inside a *closed* shadow root, so nothing in it can be enumerated or
 * clicked from here, and focus landing there reports as the widget's host element rather than as
 * the frame. While focus is in there the arrows drive Chromium's own traversal instead.
 */
private const val TV_FOCUS_KIND_JS =
    "window.__miruroTvFocusKind ? window.__miruroTvFocusKind() : 'page';"
private const val TV_FOCUS_NEXT_JS =
    "window.__miruroTvMoveFocus && window.__miruroTvMoveFocus(1);"
private const val TV_FOCUS_PREVIOUS_JS =
    "window.__miruroTvMoveFocus && window.__miruroTvMoveFocus(-1);"
private const val TV_ACTIVATE_CONTROL_JS =
    "window.__miruroTvActivate && window.__miruroTvActivate();"

private val TV_WEB_SELECT_KEYS = setOf(
    KeyEvent.KEYCODE_DPAD_CENTER,
    KeyEvent.KEYCODE_ENTER,
    KeyEvent.KEYCODE_NUMPAD_ENTER,
    KeyEvent.KEYCODE_BUTTON_A,
    KeyEvent.KEYCODE_BUTTON_SELECT,
)

private class TvEditorBridge(
    private val webView: WebView,
    private val onFocused: (TvWebField) -> Unit,
) {
    @JavascriptInterface
    fun onEditableFocused(
        id: String,
        label: String,
        type: String,
        value: String,
        hasNext: Boolean,
    ) {
        webView.post {
            if (!webView.isAttachedToWindow) return@post
            DiagnosticsLog.event("TV OAuth editor opened type=$type hasNext=$hasNext")
            onFocused(
                TvWebField(
                    id = id,
                    label = label,
                    type = type,
                    value = value,
                    hasNext = hasNext,
                ),
            )
        }
    }
}

internal fun tvWebFieldValueScript(
    fieldId: String,
    value: String,
    dispatchChange: Boolean = false,
    advance: Boolean = false,
    blur: Boolean = false,
): String {
    val idJson = JSONObject.quote(fieldId)
    val valueJson = JSONObject.quote(value)
    return """
        (function() {
          var el = document.querySelector('[data-miruro-tv-editor-id="' + $idJson + '"]');
          if (!el) return false;
          var value = $valueJson;
          if (el.isContentEditable) {
            el.textContent = value;
          } else {
            var proto = el.tagName.toLowerCase() === 'textarea'
              ? window.HTMLTextAreaElement.prototype : window.HTMLInputElement.prototype;
            var setter = Object.getOwnPropertyDescriptor(proto, 'value').set;
            setter.call(el, value);
          }
          el.dispatchEvent(new Event('input', { bubbles: true }));
          ${if (dispatchChange) "el.dispatchEvent(new Event('change', { bubbles: true }));" else ""}
          if (${advance.toString()}) {
            var fields = Array.prototype.filter.call(
              document.querySelectorAll('input,textarea,[contenteditable="true"]'),
              function(candidate) {
                var tag = (candidate.tagName || '').toLowerCase();
                var type = (candidate.type || 'text').toLowerCase();
                return !candidate.disabled && !candidate.readOnly &&
                  (candidate.isContentEditable || tag === 'textarea' ||
                    (tag === 'input' && ['hidden','button','checkbox','radio','submit','reset','file','image','range','color'].indexOf(type) < 0));
              }
            );
            var next = fields[fields.indexOf(el) + 1];
            // "Next" is an explicit request to keep typing, so this is one of the two paths
            // allowed to reopen the editor from a focus change.
            if (next) { window.__miruroTvOpenOnFocus = true; next.focus(); return true; }
          }
          if (${blur.toString()}) {
            if (window.__miruroTvFocusAfter) {
              window.__miruroTvFocusAfter(el);
              // Some TV IMEs replay their confirming key after the native editor closes. Restore
              // the intended form action once that replay has finished so focus cannot escape to
              // an unrelated page control (AniList's floating forum menu is a common example).
              window.setTimeout(function() {
                if (el.isConnected && window.__miruroTvFocusAfter) {
                  window.__miruroTvFocusAfter(el);
                }
              }, 180);
            } else {
              el.blur();
            }
          }
          return true;
        })();
    """.trimIndent()
}
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun LoginWebView(
    authorizeUrl: String,
    isRedirect: (String) -> Boolean,
    extractResult: (String) -> String?,
    onResult: (String) -> Unit,
    onCancel: () -> Unit,
) {
    val device = LocalAppDeviceProfile.current
    // The phone's tab capsule frosts the screen behind it by drawing it a second time, and this
    // WebView flickers blank under that for as long as the page is open.
    AppChromeTakeoverEffect()
    var webView by remember(authorizeUrl) { mutableStateOf<WebView?>(null) }
    var tvEditor by remember(authorizeUrl) { mutableStateOf<TvWebField?>(null) }
    var pageLoading by remember(authorizeUrl) { mutableStateOf(true) }
    var loadProblem by remember(authorizeUrl) { mutableStateOf<LoginLoadProblem?>(null) }
    // Reset per page: a fresh navigation starts with focus back in the top document.
    var tvFocusInFrame by remember(authorizeUrl) { mutableStateOf(false) }
    var factoryAttempt by remember(authorizeUrl) { mutableIntStateOf(0) }
    var challengeStuck by remember(authorizeUrl) { mutableStateOf(false) }
    // Views whose renderer is gone: their release skips the page calls and only tears them down.
    val deadRendererViews = remember {
        java.util.Collections.newSetFromMap(java.util.WeakHashMap<WebView, Boolean>())
    }

    // Closing the editor keeps what was typed and leaves focus on the field it was editing —
    // never advancing to the form's submit, which is what Back used to do. Both routes into this
    // (the dialog's own dismiss, and the fallback below for when a host BackHandler wins instead)
    // share one implementation so they cannot drift apart again.
    val dismissTvEditor: () -> Unit = {
        val field = tvEditor
        val activeWebView = webView
        tvEditor = null
        if (field != null) {
            activeWebView?.evaluateJavascript(
                tvWebFieldValueScript(
                    fieldId = field.id,
                    value = field.value,
                    dispatchChange = true,
                ),
            ) {
                activeWebView.restoreTvFocus()
            }
        }
    }

    BackHandler {
        when {
            tvEditor != null -> dismissTvEditor()
            challengeStuck -> challengeStuck = false
            webView?.canGoBack() == true -> webView?.goBack()
            else -> onCancel()
        }
    }

    LaunchedEffect(authorizeUrl, pageLoading, factoryAttempt) {
        if (!pageLoading) return@LaunchedEffect
        kotlinx.coroutines.delay(LOGIN_LOAD_TIMEOUT_MS)
        if (pageLoading) {
            val activeWebView = webView?.takeIf { it.isAttachedToWindow }
            DiagnosticsLog.event(
                category = "auth",
                name = "oauth.load_timeout",
                attributes = mapOf(
                    "host" to (activeWebView?.url ?: authorizeUrl).oauthHost(),
                    "hasWebView" to (activeWebView != null),
                    "attached" to (activeWebView?.isAttachedToWindow == true),
                    "progress" to (activeWebView?.progress ?: 0),
                    "title" to (activeWebView?.title ?: "none"),
                ),
            )
            pageLoading = false
            loadProblem = if (activeWebView == null) {
                LoginLoadProblem.WEBVIEW_UNAVAILABLE
            } else {
                LoginLoadProblem.TIMEOUT
            }
        }
    }

    // The watchdog above only covers a page that never paints. A Cloudflare challenge *does*
    // paint — the load finishes, the spinner runs — and on some TVs it then sits "verifying"
    // forever: a viewer signed in fine on the phone and could not get past it on the television
    // at all. The pipe bridge met the same wedge (PipeBridge's stale-clearance purge) and its
    // cure is reused here: once a challenge has held for [LOGIN_CHALLENGE_SETTLE_MS], drop the
    // Cloudflare cookies — a clearance earned by an older WebView fingerprint is refused on
    // sight and can never be re-answered — and reload once. If it still holds after a second
    // settle window, a banner offers a manual retry rather than an endless spinner.
    LaunchedEffect(authorizeUrl, factoryAttempt) {
        var stuckSince = 0L
        var stuckKind = "none"
        var purged = false
        while (true) {
            kotlinx.coroutines.delay(LOGIN_CHALLENGE_POLL_MS)
            if (pageLoading || loadProblem != null) continue
            val activeWebView = webView?.takeIf { it.isAttachedToWindow }
            if (activeWebView == null) {
                stuckSince = 0L
                continue
            }
            val kind = awaitLoginChallengeProbe(activeWebView)?.let { snapshot ->
                classifyLoginChallenge(
                    title = snapshot.optString("title"),
                    turnstileTokenPresent = snapshot.optBoolean("tokenPresent"),
                    turnstileTokenChars = snapshot.optInt("tokenChars"),
                    challengeScaffold = snapshot.optBoolean("scaffold"),
                )
            } ?: LoginChallengeKind.NONE
            val stuck = kind == LoginChallengeKind.INTERSTITIAL || kind == LoginChallengeKind.AWAITING_TOKEN
            if (!stuck) {
                if (stuckSince != 0L) {
                    DiagnosticsLog.event(
                        category = "auth",
                        name = "oauth.challenge_cleared",
                        attributes = mapOf(
                            "kind" to stuckKind,
                            "heldMs" to (android.os.SystemClock.elapsedRealtime() - stuckSince),
                        ),
                    )
                }
                stuckSince = 0L
                challengeStuck = false
                continue
            }
            if (stuckSince == 0L) {
                stuckSince = android.os.SystemClock.elapsedRealtime()
                stuckKind = kind.name.lowercase()
                DiagnosticsLog.event(
                    category = "auth",
                    name = "oauth.challenge_detected",
                    attributes = mapOf(
                        "host" to (activeWebView.url ?: authorizeUrl).oauthHost(),
                        "kind" to stuckKind,
                    ),
                )
                continue
            }
            val heldMs = android.os.SystemClock.elapsedRealtime() - stuckSince
            // A viewer may be mid-typing or mid-tick on the challenge itself; never yank the
            // page out from under them. Time keeps counting, action waits for them to leave.
            if (tvEditor != null || tvFocusInFrame) continue
            if (!purged && heldMs >= LOGIN_CHALLENGE_SETTLE_MS) {
                purged = true
                DiagnosticsLog.event(
                    category = "auth",
                    name = "oauth.challenge_purge_reloaded",
                    attributes = mapOf("kind" to stuckKind, "heldMs" to heldMs),
                )
                purgeStaleCloudflareCookies(activeWebView.url)
                stuckSince = 0L
                challengeStuck = false
                activeWebView.reload()
            } else if (purged && heldMs >= LOGIN_CHALLENGE_SETTLE_MS && !challengeStuck) {
                DiagnosticsLog.event(
                    category = "auth",
                    name = "oauth.challenge_stuck",
                    attributes = mapOf("kind" to stuckKind, "heldMs" to heldMs),
                )
                challengeStuck = true
            }
        }
    }

    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        // The app draws edge to edge, so nothing makes room for the keyboard unless a screen asks.
        // This one did not: on a phone the login page kept the whole screen with the keyboard
        // over its lower half, so MyAnimeList's password field, its reCAPTCHA and the Login button
        // sat underneath it and the page could not scroll to them — measured through DevTools as a
        // 384x832 viewport that never shrank while the keyboard was up. Its header also ran under
        // the status bar. A television enters text through its own dialog and keeps the full screen.
        Box(
            Modifier
                .fillMaxSize()
                .then(if (device.isTv) Modifier else Modifier.windowInsetsPadding(WindowInsets.safeDrawing)),
        ) {
            // Not composed while a dead renderer's retry state is up: a fresh view would load the
            // page straight away and hide the state the viewer is meant to see. Retry clears it.
            if (loadProblem != LoginLoadProblem.RENDERER_GONE) key(authorizeUrl, factoryAttempt) {
                AndroidView(
                    modifier = Modifier.fillMaxSize(),
                    factory = { ctx ->
                        try {
                            DiagnosticsLog.event(
                                category = "auth",
                                name = "oauth.webview_create",
                                attributes = mapOf(
                                    "host" to authorizeUrl.oauthHost(),
                                    "attempt" to (factoryAttempt + 1),
                                ),
                            )
                            DiagnosticsLog.webViewPackage("LoginWebView factory")
                            var tokenHandled = false
                            WebView(ctx).apply {
                            webView = this
                            // Timers are paused process-wide, never per view; a pause left behind
                            // by the embed player would stall the login page's scripts.
                            resumeTimers()
                            // AndroidView leaves a view at WRAP_CONTENT, and a WebView whose
                            // height is wrap-content lays the page out zero pixels tall: `100vh`
                            // and `height:100%` resolve to 0. Discord sizes its whole login on
                            // those, so on a TV the page was there — fields focusable, typing
                            // landing — but nothing painted, just the dark page background.
                            // AniList and MAL do not depend on them, which is why they never
                            // showed it.
                            layoutParams = ViewGroup.LayoutParams(
                                ViewGroup.LayoutParams.MATCH_PARENT,
                                ViewGroup.LayoutParams.MATCH_PARENT,
                            )
                            isFocusable = true
                            isFocusableInTouchMode = true
                            settings.javaScriptEnabled = true
                            settings.domStorageEnabled = true
                            settings.allowFileAccess = false
                            settings.allowContentAccess = false
                            // AniList's login page now runs a Cloudflare Turnstile check, and the
                            // widget is served from challenges.cloudflare.com — a third party to
                            // anilist.co. A WebView blocks third-party cookies by default, so the
                            // challenge could never complete: the sign-in form never appeared, the
                            // page stayed blank, and the load never finished. A device report shows
                            // six attempts, every one stalled at 70% progress with no page-finished
                            // event, while MyAnimeList (no such check) loaded normally in between.
                            //
                            // This does not answer the challenge for anyone; it only lets the
                            // widget run so the person signing in can.
                            val cookies = CookieManager.getInstance()
                            cookies.setAcceptCookie(true)
                            cookies.setAcceptThirdPartyCookies(this, true)
                            fun markPageUsable(view: WebView?, url: String?, signal: String) {
                                if (url == null || url == "about:blank") return
                                val wasLoading = pageLoading || loadProblem != null
                                pageLoading = false
                                loadProblem = null
                                if (wasLoading) {
                                    DiagnosticsLog.event(
                                        category = "auth",
                                        name = "oauth.page_usable",
                                        attributes = mapOf(
                                            "host" to url.oauthHost(),
                                            "signal" to signal,
                                            "progress" to (view?.progress ?: 0),
                                        ),
                                    )
                                }
                                if (device.isTv) {
                                    view?.evaluateJavascript(TV_EDITOR_JS) {
                                        view.postDelayed({
                                            if (view.isAttachedToWindow) {
                                                view.evaluateJavascript(TV_FOCUS_FIRST_JS, null)
                                            }
                                        }, 180)
                                    }
                                }
                            }
                            webChromeClient = object : WebChromeClient() {
                                override fun onProgressChanged(view: WebView?, newProgress: Int) {
                                    if (isLoginPageUsableProgress(newProgress)) {
                                        markPageUsable(view, view?.url, "progress")
                                    }
                                }
                            }
                            webViewClient = object : WebViewClient() {
                                private fun handleRedirect(view: WebView?, url: String?): Boolean {
                                    if (tokenHandled || url == null || !isRedirect(url)) return false
                                    val result = extractResult(url) ?: return false
                                    tokenHandled = true
                                    view?.stopLoading()
                                    onResult(result)
                                    return true
                                }

                                override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                                    tvEditor = null
                                    pageLoading = true
                                    loadProblem = null
                                    challengeStuck = false
                                    DiagnosticsLog.event(
                                        "LoginWebView page started host=${url.oauthHost()}",
                                    )
                                    handleRedirect(view, url)
                                }

                                @Suppress("DEPRECATION")
                                override fun shouldOverrideUrlLoading(view: WebView?, url: String?): Boolean {
                                    return handleRedirect(view, url)
                                }

                                override fun shouldOverrideUrlLoading(
                                    view: WebView?,
                                    request: WebResourceRequest?,
                                ): Boolean {
                                    return handleRedirect(view, request?.url?.toString())
                                }

                                override fun onPageFinished(view: WebView?, url: String?) {
                                    if (url == "about:blank") return
                                    markPageUsable(view, url, "finished")
                                    DiagnosticsLog.event(
                                        "LoginWebView page finished host=${url.oauthHost()} " +
                                            "title=${view?.title ?: "none"}",
                                    )
                                }

                                override fun onPageCommitVisible(view: WebView?, url: String?) {
                                    markPageUsable(view, url, "commit_visible")
                                }

                                override fun onReceivedError(
                                    view: WebView?,
                                    request: WebResourceRequest?,
                                    error: WebResourceError?,
                                ) {
                                    if (request?.isForMainFrame != true) return
                                    pageLoading = false
                                    loadProblem = LoginLoadProblem.NETWORK
                                    val details =
                                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                                            "code=${error?.errorCode ?: 0} " +
                                                "description=${error?.description ?: "none"}"
                                        } else {
                                            "details=unavailable"
                                        }
                                    DiagnosticsLog.event(
                                        "LoginWebView main frame error host=${request.url?.host ?: "none"} " +
                                            details,
                                    )
                                }

                                override fun onReceivedHttpError(
                                    view: WebView?,
                                    request: WebResourceRequest?,
                                    errorResponse: WebResourceResponse?,
                                ) {
                                    if (request?.isForMainFrame != true) return
                                    pageLoading = false
                                    loadProblem = LoginLoadProblem.HTTP
                                    DiagnosticsLog.event(
                                        "LoginWebView main frame HTTP error " +
                                            "host=${request.url?.host ?: "none"} " +
                                            "status=${errorResponse?.statusCode ?: 0}",
                                    )
                                }

                                // Every other WebView in the app answers this; the login page did
                                // not, and the default false tells the platform to take the app
                                // process down with the renderer — on API 26+ the renderer is
                                // shared, so one reclaimed on a starved Fire TV stick while the
                                // login page was open closed the app without a trace (the silent
                                // exit PaheBridge documents). Drop the dead view and show the
                                // retry state; Retry builds a fresh WebView.
                                @android.annotation.TargetApi(Build.VERSION_CODES.O)
                                override fun onRenderProcessGone(
                                    view: WebView?,
                                    detail: RenderProcessGoneDetail?,
                                ): Boolean {
                                    DiagnosticsLog.event(
                                        category = "auth",
                                        name = "oauth.renderer_gone",
                                        attributes = mapOf(
                                            "host" to authorizeUrl.oauthHost(),
                                            "didCrash" to detail?.didCrash(),
                                            "priority" to detail?.rendererPriorityAtExit(),
                                        ),
                                    )
                                    view?.let(deadRendererViews::add)
                                    if (view == null || webView === view) webView = null
                                    tvEditor = null
                                    tvFocusInFrame = false
                                    challengeStuck = false
                                    pageLoading = false
                                    loadProblem = LoginLoadProblem.RENDERER_GONE
                                    factoryAttempt++
                                    return true
                                }
                            }
                            if (device.isTv) {
                                addJavascriptInterface(
                                    TvEditorBridge(this) { field -> tvEditor = field },
                                    "MiruroTvEditor",
                                )
                                setOnKeyListener { view, keyCode, event ->
                                    if (event.action != KeyEvent.ACTION_DOWN) {
                                        return@setOnKeyListener false
                                    }
                                    val web = view as? WebView
                                    // Focus is inside a cross-origin frame — a Turnstile check, in
                                    // practice. Nothing in there can be reached by the injected
                                    // walker, so the remote has to drive Chromium's own traversal.
                                    //
                                    // Handing it the raw arrow key does not do that: Chromium takes
                                    // web content as one opaque focus block and arrows die there.
                                    // So the checkbox could never be ticked, and a viewer reported
                                    // exactly that — "captcha failed, no tick button" — because the
                                    // widget sat unanswered until it timed out. Tab is what
                                    // traverses a page, and it crosses into the frame; see
                                    // [sendTab], which already answers the pipe's own Cloudflare
                                    // challenge this way.
                                    if (tvFocusInFrame) {
                                        val shift = tabDirectionForRemoteKey(keyCode)
                                        if (shift != null) web?.sendTab(shift)
                                        // Read where it landed after the frame has moved focus:
                                        // leaving the frame hands the page back to the walker.
                                        web?.postDelayed({
                                            if (web.isAttachedToWindow) {
                                                web.evaluateJavascript(TV_FOCUS_KIND_JS) { kind ->
                                                    tvFocusInFrame = kind?.contains("frame") == true
                                                }
                                            }
                                        }, TV_FRAME_FOCUS_SETTLE_MS)
                                        // Centre/Enter falls through: Chromium already activates
                                        // the focused element with it.
                                        return@setOnKeyListener shift != null
                                    }
                                    val script = when {
                                        keyCode == KeyEvent.KEYCODE_DPAD_DOWN -> TV_FOCUS_NEXT_JS
                                        keyCode == KeyEvent.KEYCODE_DPAD_UP -> TV_FOCUS_PREVIOUS_JS
                                        keyCode in TV_WEB_SELECT_KEYS -> TV_ACTIVATE_CONTROL_JS
                                        else -> return@setOnKeyListener false
                                    }
                                    evaluateJavascript(script) {
                                        web?.evaluateJavascript(TV_FOCUS_KIND_JS) { kind ->
                                            val inFrame = kind?.contains("frame") == true
                                            if (inFrame != tvFocusInFrame) {
                                                DiagnosticsLog.event(
                                                    "LoginWebView TV focus ${if (inFrame) "entered" else "left"} a frame",
                                                )
                                            }
                                            tvFocusInFrame = inFrame
                                        }
                                    }
                                    true
                                }
                            }
                            // After a profile switch the jar still holds the last person's
                            // AniList / MAL website session, which would sign this one straight in
                            // as them.
                            if (com.anilili.data.profiles.Profiles.consumeLoginCookiesStale()) {
                                purgeAccountSiteCookies()
                            }
                            loadUrl(authorizeUrl)
                            WebViewProcessController.register(this, "account-login")
                            DiagnosticsLog.event(
                                "LoginWebView load requested host=${authorizeUrl.oauthHost()}",
                            )
                                if (device.isTv) post { requestFocus() }
                            }
                        } catch (e: Throwable) {
                            pageLoading = false
                            loadProblem = LoginLoadProblem.WEBVIEW_UNAVAILABLE
                            DiagnosticsLog.event(
                                category = "auth",
                                name = "oauth.webview_unavailable",
                                attributes = mapOf(
                                    "host" to authorizeUrl.oauthHost(),
                                    "attempt" to (factoryAttempt + 1),
                                    "errorType" to e.javaClass.simpleName,
                                    "message" to (e.message ?: "none"),
                                ),
                            )
                            CrashReporter.logNonFatal("System WebView unavailable; login disabled", e)
                            android.view.View(ctx)
                        }
                    },
                    onRelease = { view ->
                        val web = view as? WebView ?: return@AndroidView
                        if (webView === web) webView = null
                        tvEditor = null
                        DiagnosticsLog.event(
                            "LoginWebView release host=${web.url.oauthHost()} size=${web.width}x${web.height}",
                        )
                        // Persist whatever the session left behind before the view goes away, so a
                        // challenge already passed is not asked for again on the next attempt.
                        runCatching { CookieManager.getInstance().flush() }
                        // A dead renderer has no page to stop, and a load would only ask the view
                        // to start another one.
                        if (!deadRendererViews.remove(web)) {
                            web.stopLoading()
                            web.loadUrl("about:blank")
                        }
                        web.clearHistory()
                        web.removeAllViews()
                        WebViewProcessController.release(web, "account-login")
                        web.destroy()
                    },
                )
            }
            if (pageLoading || loadProblem != null) {
                LoginLoadStatus(
                    problem = loadProblem,
                    onRetry = {
                        val activeWebView = webView?.takeIf { it.isAttachedToWindow }
                        DiagnosticsLog.event(
                            category = "auth",
                            name = "oauth.retry",
                            attributes = mapOf(
                                "host" to (activeWebView?.url ?: authorizeUrl).oauthHost(),
                                "recreateWebView" to (activeWebView == null),
                            ),
                        )
                        pageLoading = true
                        loadProblem = null
                        if (activeWebView == null) {
                            factoryAttempt++
                            return@LoginLoadStatus
                        }
                        activeWebView.stopLoading()
                        activeWebView.loadUrl("about:blank")
                        activeWebView.postDelayed({
                            if (activeWebView.isAttachedToWindow) {
                                activeWebView.loadUrl(authorizeUrl)
                            }
                        }, LOGIN_RETRY_DELAY_MS)
                    },
                )
            }
            if (device.isTv) {
                tvEditor?.let { field ->
                    TvWebEditorDialog(
                        field = field,
                        onValueChange = { value ->
                            tvEditor = field.copy(value = value)
                            webView?.evaluateJavascript(
                                tvWebFieldValueScript(field.id, value),
                                null,
                            )
                        },
                        onNext = {
                            tvEditor = null
                            webView?.evaluateJavascript(
                                tvWebFieldValueScript(
                                    fieldId = field.id,
                                    value = field.value,
                                    dispatchChange = true,
                                    advance = true,
                                ),
                                null,
                            )
                        },
                        onDone = {
                            val activeWebView = webView
                            tvEditor = null
                            activeWebView?.evaluateJavascript(
                                tvWebFieldValueScript(
                                    fieldId = field.id,
                                    value = field.value,
                                    dispatchChange = true,
                                    blur = true,
                                ),
                            ) {
                                activeWebView.restoreTvFocus()
                            }
                        },
                        onCancel = dismissTvEditor,
                    )
                }
            }
            if (challengeStuck && !pageLoading && loadProblem == null) {
                LoginChallengeStuckBanner(
                    onRetry = {
                        challengeStuck = false
                        val activeWebView = webView?.takeIf { it.isAttachedToWindow }
                        DiagnosticsLog.event(
                            category = "auth",
                            name = "oauth.challenge_manual_retry",
                            attributes = mapOf("host" to (activeWebView?.url ?: authorizeUrl).oauthHost()),
                        )
                        activeWebView?.reload()
                    },
                    onKeepWaiting = { challengeStuck = false },
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth(0.92f)
                        .padding(bottom = 16.dp),
                )
            }
            val controlFocus1 = rememberFocusFill()
            IconButton(
                colors = controlFocus1.iconButtonColors(IconButtonDefaults.iconButtonColors()),
                onClick = onCancel,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(device.pagePadding)
                    .focusFill(controlFocus1, RoundedCornerShape(24.dp)),
            ) {
                Icon(Icons.Default.Close, contentDescription = "Cancel")
            }
        }
    }
}

/**
 * The escape hatch for a Cloudflare check that neither cleared itself nor responded to the
 * automatic purge-and-reload. Sits over the bottom of the page so the challenge stays visible
 * and answerable by hand; Retry asks for a fresh one, Keep waiting steps aside.
 */
@Composable
private fun LoginChallengeStuckBanner(
    onRetry: () -> Unit,
    onKeepWaiting: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val retryFocus = remember { FocusRequester() }
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 8.dp,
    ) {
        Column(Modifier.padding(20.dp)) {
            Text(
                text = "The security check is stuck",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Black,
            )
            Text(
                text = "It hasn't finished on its own — this can happen on TVs and restricted " +
                    "networks. Retry asks for a fresh check; you can also keep waiting or cancel " +
                    "and try again later.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp),
            )
            Row(
                modifier = Modifier.padding(top = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                val controlFocus2 = rememberFocusFill()
                Button(
                    colors = controlFocus2.buttonColors(ButtonDefaults.buttonColors()),
                    onClick = onRetry,
                    modifier = Modifier
                        .focusRequester(retryFocus)
                        .focusFill(controlFocus2, RoundedCornerShape(50)),
                ) {
                    Text("Retry")
                }
                val controlFocus3 = rememberFocusFill()
                OutlinedButton(
                    colors = controlFocus3.buttonColors(ButtonDefaults.outlinedButtonColors()),
                    onClick = onKeepWaiting,
                    modifier = Modifier.focusFill(controlFocus3, RoundedCornerShape(50)),
                ) {
                    Text("Keep waiting")
                }
            }
        }
    }
    // A TV remote has to reach Retry before the WebView retakes focus.
    LaunchedEffect(Unit) {
        awaitFrame()
        runCatching { retryFocus.requestFocus() }
    }
}

@Composable
private fun LoginLoadStatus(
    problem: LoginLoadProblem?,
    onRetry: () -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background,
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(48.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            if (problem == null) {
                CircularProgressIndicator()
                Text(
                    text = "Opening secure login…",
                    modifier = Modifier.padding(top = 20.dp),
                    style = MaterialTheme.typography.titleMedium,
                )
            } else {
                // Retry is the only control on this screen, and the WebView that had focus is gone
                // by the time it appears — so on a TV the D-pad had nowhere to go and the screen
                // was a dead end. It takes focus itself.
                val retryFocus = remember { FocusRequester() }
                Text(
                    text = problem.title,
                    style = MaterialTheme.typography.titleLarge,
                    textAlign = TextAlign.Center,
                )
                Text(
                    text = problem.guidance,
                    modifier = Modifier.padding(top = 8.dp, bottom = 20.dp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
                val controlFocus4 = rememberFocusFill()
                Button(
                    colors = controlFocus4.buttonColors(ButtonDefaults.buttonColors()),
                    onClick = onRetry,
                    modifier = Modifier
                        .focusRequester(retryFocus)
                        .focusFill(controlFocus4, RoundedCornerShape(20.dp)),
                ) {
                    Text("Retry")
                }
                LaunchedEffect(problem) {
                    awaitFrame()
                    runCatching { retryFocus.requestFocus() }
                }
            }
        }
    }
}

internal enum class LoginLoadProblem(
    val title: String,
    val guidance: String,
) {
    TIMEOUT(
        title = "The login page is taking too long to open.",
        guidance = "Check your connection, then try again.",
    ),
    NETWORK(
        title = "Couldn't open the login page.",
        guidance = "Check your connection, private DNS, or VPN, then try again.",
    ),
    HTTP(
        title = "The login service returned an error.",
        guidance = "The service may be temporarily unavailable. Try again shortly.",
    ),
    WEBVIEW_UNAVAILABLE(
        title = "Secure login isn't available on this device.",
        guidance = "Install or enable Android System WebView, then restart Anilili and try again.",
    ),

    /** The page's renderer crashed or was reclaimed for memory; a fresh WebView usually works. */
    RENDERER_GONE(
        title = "The login page stopped.",
        guidance = "The device ran short of memory or the page crashed. Try again.",
    ),
}

/**
 * Confirms a completed sign-in, because a successful one can be indistinguishable from a failure.
 *
 * AniList skips its consent page when the account is already signed in and has approved the app
 * before, so re-authorising redirects straight back and the login screen closes about as fast as it
 * opened. A viewer reported that as "it buffers and returns me to settings", while saying in the
 * same breath that their list was syncing — it had worked every time; nothing ever said so.
 */
internal fun confirmSignedIn(context: android.content.Context, service: String) {
    android.widget.Toast.makeText(context, "$service connected", android.widget.Toast.LENGTH_SHORT)
        .show()
}

/**
 * Hands D-pad focus back to the page after the native editor closes. Delayed because the dialog's
 * window is still coming down on the frame the editor is dismissed, and a WebView that asks for
 * focus while it is still behind that window does not get it.
 */
private fun WebView.restoreTvFocus() {
    postDelayed({ if (isAttachedToWindow) requestFocus() }, TV_EDITOR_FOCUS_RETURN_MS)
}

private fun String?.oauthHost(): String =
    this?.let { value -> runCatching { android.net.Uri.parse(value).host }.getOrNull() }
        ?: "none"

internal fun isLoginPageUsableProgress(progress: Int): Boolean =
    progress >= LOGIN_USABLE_PROGRESS

/**
 * Reads the live page for the two shapes a Cloudflare check takes during sign-in: the
 * full-page interstitial, or AniList's embedded Turnstile whose token field is still empty.
 * An object is returned (not a bare string) so `evaluateJavascript` hands back plain JSON
 * rather than a double-encoded one.
 */
private const val LOGIN_CHALLENGE_PROBE_JS = """
(function(){
  try {
    var token = document.querySelector(
      'input[name="cf-turnstile-response"],input[id^="cf-chl-widget"][id$="_response"]'
    );
    return {
      title: document.title || '',
      tokenPresent: !!token,
      tokenChars: token ? (token.value || '').length : 0,
      scaffold: !!document.querySelector(
        '#challenge-form,#challenge-stage,#challenge-running,#challenge-error-text,' +
        'iframe[src*="challenges.cloudflare.com"]'
      )
    };
  } catch (e) {
    return { title: '', tokenPresent: false, tokenChars: 0, scaffold: false };
  }
})()
"""

private suspend fun awaitLoginChallengeProbe(web: WebView): JSONObject? =
    suspendCancellableCoroutine { cont ->
        web.evaluateJavascript(LOGIN_CHALLENGE_PROBE_JS) { result ->
            if (cont.isActive) cont.resume(runCatching { JSONObject(result ?: "null") }.getOrNull())
        }
    }

internal enum class LoginChallengeKind { NONE, INTERSTITIAL, AWAITING_TOKEN, READY }

/**
 * Orders the signals: the interstitial is named by its title (the one reliable marker before
 * its widget renders), and a present token field outranks generic scaffolding because the
 * login page legitimately embeds a Cloudflare iframe of its own.
 */
internal fun classifyLoginChallenge(
    title: String?,
    turnstileTokenPresent: Boolean,
    turnstileTokenChars: Int,
    challengeScaffold: Boolean,
): LoginChallengeKind {
    if (looksLikePipeChallengeTitle(title)) return LoginChallengeKind.INTERSTITIAL
    return when {
        turnstileTokenPresent && turnstileTokenChars > 0 -> LoginChallengeKind.READY
        turnstileTokenPresent -> LoginChallengeKind.AWAITING_TOKEN
        challengeScaffold -> LoginChallengeKind.INTERSTITIAL
        else -> LoginChallengeKind.NONE
    }
}

/** Expires every cookie the AniList and MyAnimeList sites hold, signing their pages out. */
private fun purgeAccountSiteCookies() {
    val manager = CookieManager.getInstance()
    var purged = 0
    listOf("anilist.co", "myanimelist.net").forEach { host ->
        val cookieUrl = "https://$host/"
        val existing = runCatching { manager.getCookie(cookieUrl) }.getOrNull() ?: return@forEach
        existing.split(';').forEach { pair ->
            val name = pair.substringBefore('=').trim()
            if (name.isNotEmpty()) {
                cookieDeletionScopes(host).forEach { scope ->
                    runCatching { manager.setCookie(cookieUrl, "$name=; Path=/; Max-Age=0$scope") }
                }
                purged++
            }
        }
    }
    runCatching { manager.flush() }
    DiagnosticsLog.event("LoginWebView account-site cookies purged after profile switch count=$purged")
}

/**
 * Drops the cookies Cloudflare's edge uses to recognise a returning client. A clearance earned
 * by a previous WebView build is refused on sight — the pipe bridge proved this loop — so a
 * stuck challenge gets one fresh start with them gone. AniList's own session state is untouched.
 */
private fun purgeStaleCloudflareCookies(pageUrl: String?) {
    val host = pageUrl?.let { runCatching { android.net.Uri.parse(it).host }.getOrNull() } ?: return
    val manager = CookieManager.getInstance()
    val cookieUrl = "https://$host/"
    val existing = runCatching { manager.getCookie(cookieUrl) }.getOrNull() ?: return
    var purged = 0
    existing.split(';').forEach { pair ->
        val name = pair.substringBefore('=').trim()
        if (name.isNotEmpty() && isCloudflareSessionCookie(name)) {
            // A cookie is keyed by domain as well as name, so the expiry has to be written for
            // the scope it was stored under. Cloudflare sets clearance on the registrable domain
            // (`.anilist.co`), and a host-only expiry makes a *second*, different entry while the
            // original survives — the purge would report success and change nothing.
            cookieDeletionScopes(host).forEach { scope ->
                runCatching { manager.setCookie(cookieUrl, "$name=; Path=/; Max-Age=0$scope") }
            }
            purged++
        }
    }
    if (purged > 0) {
        runCatching { manager.flush() }
        DiagnosticsLog.event(
            category = "auth",
            name = "oauth.challenge_cookies_purged",
            attributes = mapOf("host" to host, "count" to purged),
        )
    }
}

/**
 * Every domain scope one of these cookies might have been stored under, as `; Domain=` suffixes.
 *
 * The host itself comes first with no suffix at all, because a host-only cookie is only matched
 * by a host-only expiry. Then each parent domain down to the registrable one, since Cloudflare
 * writes clearance against that rather than against the page's host. Public-suffix awareness is
 * deliberately not attempted: an expiry aimed at a scope that holds nothing is inert, so erring
 * wide costs nothing while erring narrow is what leaves the cookie in place.
 */
internal fun cookieDeletionScopes(host: String): List<String> {
    val clean = host.trim().trimStart('.').lowercase()
    if (clean.isEmpty() || clean.toIntOrNull() != null) return listOf("")
    val labels = clean.split('.').filter(String::isNotEmpty)
    if (labels.size < 2) return listOf("")
    val scopes = mutableListOf("")
    for (start in labels.indices) {
        val candidate = labels.drop(start)
        if (candidate.size < 2) break
        scopes += "; Domain=.${candidate.joinToString(".")}"
    }
    return scopes
}

internal fun isCloudflareSessionCookie(name: String): Boolean {
    val normalized = name.trim().lowercase()
    return normalized == "cf_clearance" || normalized == "__cf_bm" || normalized == "__cfruid" ||
        normalized.startsWith("cf_chl") || normalized.startsWith("cf_use_obsolete")
}

// A login page that runs a Cloudflare challenge never reports itself finished — it holds
// connections open for the check — and a TV takes longer to get anywhere. Twenty seconds at 85%
// declared a page broken that was merely busy: an emulator sat at 70% and showed "taking too
// long" twice in a row. The watchdog only fires now if nothing has painted at all.
private const val LOGIN_LOAD_TIMEOUT_MS = 45_000L
private const val LOGIN_RETRY_DELAY_MS = 180L
private const val LOGIN_USABLE_PROGRESS = 65
// How often the challenge supervisor samples the page, and how long a check may sit unsolved
// before the recovery acts. A healthy one resolves in a few seconds; the pipe bridge gives its
// own twelve, and login gets more because a television is slower to get anywhere at all.
private const val LOGIN_CHALLENGE_POLL_MS = 3_000L
private const val LOGIN_CHALLENGE_SETTLE_MS = 15_000L
// Chromium moves focus on the Tab it was just handed before this reads where focus sits, so the
// answer describes the new position rather than the old one.
private const val TV_FRAME_FOCUS_SETTLE_MS = 80L
private const val TV_EDITOR_FOCUS_RETURN_MS = 100L
