package com.anilili.playback

import android.os.Handler
import android.os.Looper
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.ProcessLifecycleOwner
import com.anilili.diagnostics.DiagnosticsLog
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Whether any playback session is open, whichever player holds it, and how long since none was.
 *
 * `isPlaying` cannot answer that: Media3 reports it false while paused, buffering and preparing,
 * and nothing feeds it during embed (WebView) playback or while another app plays a hand-off. The
 * export deferral read it anyway, so every pause, rebuffer and episode start re-armed a transmux
 * that then ran on through resumed playback — the pairing a device report caught dying with
 * OutOfMemoryError on both threads at once.
 *
 * Three sources, any one of which makes a session active:
 * - the native player, from prepare until stop or release ([setNative]);
 * - an embed player, while it is mounted ([embedMounted] / [embedUnmounted], counted so two
 *   overlapping compositions cannot clear each other);
 * - an external hand-off, from launch until the viewer is back in this app ([setExternal]).
 */
internal class PlaybackSessionTracker(private val clock: () -> Long) {
    private var native = false
    private var embeds = 0
    private var external = false

    /** When the last session ended; null while one is open or none has been in this process. */
    private var inactiveSinceMs: Long? = null

    private val _active = MutableStateFlow(false)
    val active: StateFlow<Boolean> = _active.asStateFlow()

    @Synchronized
    fun setNative(active: Boolean) {
        native = active
        publish()
    }

    @Synchronized
    fun embedMounted() {
        embeds++
        publish()
    }

    @Synchronized
    fun embedUnmounted() {
        embeds = (embeds - 1).coerceAtLeast(0)
        publish()
    }

    @Synchronized
    fun setExternal(active: Boolean) {
        external = active
        publish()
    }

    /**
     * How much longer background work should wait before it may treat playback as over: null while
     * a session is open, 0 once [quietMs] have passed since the last one closed (or none ever was).
     *
     * The wait is what keeps a pause-and-resume, a rebuffer's stop-and-prepare or the step to the
     * next episode from reading as "done watching".
     */
    @Synchronized
    fun quietRemainingMs(quietMs: Long): Long? {
        if (_active.value) return null
        val since = inactiveSinceMs ?: return 0L
        return (quietMs - (clock() - since)).coerceAtLeast(0L)
    }

    private fun publish() {
        val now = native || embeds > 0 || external
        if (now == _active.value) return
        inactiveSinceMs = if (now) null else clock()
        _active.value = now
    }
}

/**
 * Ends an external hand-off when the viewer comes back to this app.
 *
 * Most players never report back, and the ones that do report through an activity result that
 * arrives as this app returns to the foreground anyway, so the process coming back to the
 * foreground after having left it is the signal. A process that is killed while the other player
 * runs starts with no hand-off at all, which is the same as before.
 */
internal object ExternalHandOffLifetime {
    private var observing = false
    @Volatile private var leftSinceHandOff = false

    fun started() {
        leftSinceHandOff = false
        if (Looper.myLooper() == Looper.getMainLooper()) {
            observe()
        } else {
            Handler(Looper.getMainLooper()).post(::observe)
        }
    }

    private fun observe() {
        if (observing) return
        observing = true
        runCatching {
            // Adding an observer to a started lifecycle replays ON_START at once; that replay
            // comes before any ON_STOP, so it cannot end the hand-off that is just beginning.
            ProcessLifecycleOwner.get().lifecycle.addObserver(
                LifecycleEventObserver { _, event ->
                    when (event) {
                        Lifecycle.Event.ON_STOP -> leftSinceHandOff = true
                        Lifecycle.Event.ON_START -> if (leftSinceHandOff) {
                            leftSinceHandOff = false
                            PlaybackStatus.externalHandOffEnded()
                        }
                        else -> Unit
                    }
                },
            )
        }.onFailure {
            observing = false
            DiagnosticsLog.throwable("External hand-off lifetime observer failed", it)
        }
    }
}
