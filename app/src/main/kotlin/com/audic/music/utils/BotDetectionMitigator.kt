

package com.audic.music.utils

import androidx.datastore.preferences.core.edit
import com.music.innertube.YouTube
import com.audic.music.constants.VisitorDataKey
import com.audic.music.utils.cipher.CipherDeobfuscator
import com.audic.music.utils.PlaybackLogManager
import com.audic.music.utils.PlaybackLogLevel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.util.concurrent.atomic.AtomicInteger


object BotDetectionMitigator {
    private const val TAG = "BotDetectionMitigator"

    private val failureCount = AtomicInteger(0)

    // Single-flight guard: overlapping rotations used to clobber each other and leave the
    // session in a half-rotated state. Only one rotation runs at a time.
    private val rotationMutex = Mutex()

    
    
    private val GEO_ERROR_SIGNATURES = listOf(
        "not available in your country",
        "not available in your region",
        "not available in this country",
        "not available in this region",
        "geo-restricted",
        "GEO_RESTRICTED",
        "NOT_AVAILABLE_IN_THIS_COUNTRY",
        "only available in certain countries",
        "country restriction",
        "region restriction",
    )

    
    private val BOT_ERROR_SIGNATURES = listOf(
        "Sign in to confirm",
        "confirm you're not a bot",
        "automated queries",
        "Error 2000",
        "403",
        "This content isn't available on this device",
    )

    
    fun notifyPlaybackFailure(isLoggedIn: Boolean, errorMessage: String? = null): Boolean {
        if (isLoggedIn) return false
        if (isGeoError(errorMessage)) return false

        failureCount.incrementAndGet()
        return true
    }

    
    fun notifyPlaybackSuccess() {
        failureCount.set(0)
    }

    
    suspend fun rotateGuestSession() {
        // Single-flight: if a rotation is already running, let it finish instead of starting a
        // second one that would race the first and null/stale the session.
        if (!rotationMutex.tryLock()) {
            Timber.tag(TAG).d("Guest session rotation already in progress, skipping duplicate")
            return
        }
        try {
            PlaybackLogManager.log(
                PlaybackLogLevel.BOT,
                "Rotating guest session",
                "Bypassing bot detection by refreshing visitorData (locale preserved)"
            )

            withContext(Dispatchers.IO) {
                val currentLocale = YouTube.locale

                // Fetch the new visitorData FIRST and swap it in only on success. Never null the
                // current session first: concurrent resolves would observe visitorData == null
                // and silently skip PoToken generation (see YTPlayerUtils.resolvePlaybackData).
                YouTube.refreshVisitorData().onSuccess { newData ->
                    Timber.tag(TAG).i("New visitorData obtained successfully for region ${currentLocale.gl}.")

                    CipherDeobfuscator.appContext.dataStore.edit { settings ->
                        settings[VisitorDataKey] = newData
                    }
                }.onFailure { e ->
                    Timber.tag(TAG).e(e, "Failed to refresh visitorData during rotation")

                    YouTube.locale = currentLocale
                }
            }

            failureCount.set(0)
        } finally {
            rotationMutex.unlock()
        }
    }

    
    fun isGeoError(message: String?): Boolean {
        if (message == null) return false
        val lower = message.lowercase()
        return GEO_ERROR_SIGNATURES.any { lower.contains(it.lowercase()) }
    }

    
    fun isBotDetectionError(message: String?): Boolean {
        if (message == null) return false
        val lower = message.lowercase()
        return BOT_ERROR_SIGNATURES.any { lower.contains(it.lowercase()) }
    }

    fun reset() {
        failureCount.set(0)
    }
}
