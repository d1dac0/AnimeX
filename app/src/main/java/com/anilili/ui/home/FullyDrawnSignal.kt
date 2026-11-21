package com.anilili.ui.home

import android.app.Activity
import android.app.Application
import android.os.Bundle
import com.anilili.diagnostics.DiagnosticsLog
import com.anilili.ui.FirstContent
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Reports the launch to the framework as fully drawn the moment the first Home load settles.
 *
 * Nothing in the app called [Activity.reportFullyDrawn], so macrobenchmark's `StartupTimingMetric`
 * could only report `timeToInitialDisplayMs` — the intro's black frame, not anything a viewer would
 * call started. The honest moment for the report is the one [FirstContent] already marks: the first
 * Home load that finished, success *or* failure, because "the page I opened has drawn" is what a
 * viewer is waiting on, and a permanently unreachable network must not hold the report forever
 * (that would also cost the launch a `Fully drawn` timeout in the system's own logs).
 *
 * The report needs an Activity, and a ViewModel never holds one. [HomeViewModel] reaches the one it
 * belongs to through the Application's lifecycle callbacks instead — the same route
 * [com.anilili.ui.random.RandomAnimeViewModel] already takes to hold an Application — so the wiring
 * needs nothing from `MainActivity`: install in the ViewModel's `init`, report at the mark site.
 * The framework keeps only the first report per process, so activity recreation and Home refreshes
 * are ignored here rather than re-reported.
 */
object FullyDrawnSignal : Application.ActivityLifecycleCallbacks {
    private val installed = AtomicBoolean(false)
    private val reported = AtomicBoolean(false)

    @Volatile
    private var resumed: Activity? = null

    /**
     * Registers the callbacks on the Application. Safe to call from every [HomeViewModel] instance
     * — an activity recreation builds another one — because only the first registration lands.
     */
    fun install(application: Application) {
        if (!installed.compareAndSet(false, true)) return
        application.registerActivityLifecycleCallbacks(this)
    }

    /**
     * The first Home load settled. Called beside [FirstContent.mark], which is what makes the
     * settle official for the resolver delay; the report reads the same mark, so the two can never
     * disagree about when "fully drawn" happened.
     */
    fun onHomeSettled() {
        tryReport()
    }

    override fun onActivityResumed(activity: Activity) {
        resumed = activity
        // A settle can beat the first resume on a fast restore, so the resume is a second chance.
        tryReport()
    }

    override fun onActivityDestroyed(activity: Activity) {
        // The only Activity this app has is the one being destroyed; letting the reference go keeps
        // a stale instance from outliving its window.
        if (resumed === activity) resumed = null
    }

    /**
     * The decision, pure so the JVM suite can pin it: exactly one report per process, and only
     * once both halves are in — an Activity has resumed and the first Home load has settled.
     */
    internal fun shouldReport(activityResumed: Boolean, homeSettled: Boolean, alreadyReported: Boolean): Boolean =
        activityResumed && homeSettled && !alreadyReported

    private fun tryReport() {
        if (!shouldReport(
                activityResumed = resumed != null,
                homeSettled = FirstContent.settled.value,
                alreadyReported = reported.get(),
            )
        ) {
            return
        }
        if (!reported.compareAndSet(false, true)) return
        // Re-read under the flag: a destroy that raced the report must not call into a dead window.
        resumed?.reportFullyDrawn()
        DiagnosticsLog.event("Fully drawn reported at the first Home settle (#85)")
    }

    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
    override fun onActivityStarted(activity: Activity) = Unit
    override fun onActivityPaused(activity: Activity) = Unit
    override fun onActivityStopped(activity: Activity) = Unit
    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
}
