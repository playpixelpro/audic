package com.audic.music.utils

import android.content.Context
import com.google.firebase.FirebaseApp
import com.google.firebase.analytics.FirebaseAnalytics
import com.google.firebase.crashlytics.FirebaseCrashlytics
import timber.log.Timber

/**
 * GMS-only helper that initializes Firebase and reports exceptions to Crashlytics.
 * This file lives in `src/gms/` so it ONLY compiles for GMS builds (never FOSS).
 * Shared code in `src/main/` calls this via reflection + BuildConfig.CAST_AVAILABLE check.
 */
object FirebaseReporter {

    private var initialized = false

    @JvmStatic
    fun initialize(context: Context) {
        try {
            FirebaseApp.initializeApp(context)
            FirebaseCrashlytics.getInstance().setCrashlyticsCollectionEnabled(true)
            FirebaseAnalytics.getInstance(context)
            initialized = true
            Timber.d("FirebaseReporter initialized successfully")
        } catch (e: Exception) {
            Timber.e(e, "Failed to initialize Firebase")
        }
    }

    @JvmStatic
    fun recordException(throwable: Throwable) {
        if (!initialized) return
        try {
            FirebaseCrashlytics.getInstance().recordException(throwable)
            FirebaseCrashlytics.getInstance().sendUnsentReports()
            Timber.d(throwable, "Reported exception to Crashlytics")
        } catch (e: Exception) {
            Timber.e(e, "Failed to record exception in Crashlytics")
        }
    }
}