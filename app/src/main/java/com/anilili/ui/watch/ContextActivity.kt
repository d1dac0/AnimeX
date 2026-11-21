package com.anilili.ui.watch

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.lifecycle.LifecycleOwner

/**
 * The [Activity] behind a Compose [Context], unwrapping whatever `ContextWrapper` layers the theme
 * and window decor put in the way. Player screens need it for orientation and window-insets work,
 * which the composition context alone cannot reach.
 */
internal tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

/**
 * The [LifecycleOwner] behind a Compose [Context] — the activity, through the same wrapper layers
 * [findActivity] unwraps. The players follow the activity's lifecycle for what must answer
 * regardless of composition: the embed pausing its timers, the native surface reviving a player
 * the screensaver parked.
 */
internal tailrec fun Context.findLifecycleOwner(): LifecycleOwner? = when (this) {
    is LifecycleOwner -> this
    is ContextWrapper -> baseContext.findLifecycleOwner()
    else -> null
}
