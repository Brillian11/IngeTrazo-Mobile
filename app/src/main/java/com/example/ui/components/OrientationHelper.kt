package com.example.ui.components

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ActivityInfo
import android.os.Build
import android.view.Surface
import android.view.WindowManager

tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

fun Context.readCurrentDisplayRotation(): Int {
    return try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            display?.rotation ?: Surface.ROTATION_0
        } else {
            @Suppress("DEPRECATION")
            getSystemService(WindowManager::class.java)?.defaultDisplay?.rotation ?: Surface.ROTATION_0
        }
    } catch (_: Throwable) {
        Surface.ROTATION_0
    }
}

fun togglePreviewLandscapeOrientation(activity: Activity?, currentlyLandscape: Boolean) {
    activity ?: return
    if (!currentlyLandscape) {
        activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
    } else {
        // If user forced landscape from portrait, return to sensor-driven unspecified;
        // if physically rotated in landscape already, flip explicitly to sensor portrait.
        activity.requestedOrientation =
            if (activity.requestedOrientation == ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE) {
                ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            } else {
                ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
            }
    }
}

fun lockSensorLandscapeForVr(activity: Activity?): Int? {
    activity ?: return null
    val previous = activity.requestedOrientation
    activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
    return previous
}

fun restoreOrientationAfterVr(activity: Activity?, previousOrientation: Int?) {
    activity ?: return
    activity.requestedOrientation = previousOrientation ?: ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
}
