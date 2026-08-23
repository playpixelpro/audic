package com.audic.music.utils.potoken

import android.webkit.CookieManager
import com.audic.music.utils.cipher.CipherDeobfuscator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.util.concurrent.atomic.AtomicReference

class PoTokenGenerator {
    private val TAG = "PoTokenGenerator"

    private val webViewSupported by lazy { runCatching { CookieManager.getInstance() }.isSuccess }
    private var webViewBadImpl = false 
    private var poTokenFullyFailed = false

    private val cachedPoTokenResult = AtomicReference<CachedResult?>()

    private data class CachedResult(
        val result: PoTokenResult,
        val sessionId: String,
        val cachedAtMs: Long,
    )

    private val webPoTokenGenLock = Mutex()
    private var webPoTokenSessionId: String? = null
    private var webPoTokenStreamingPot: String? = null
    private var webPoTokenGenerator: PoTokenWebView? = null

    fun getWebClientPoToken(videoId: String, sessionId: String): PoTokenResult? {
        Timber.tag(TAG).d("getWebClientPoToken called: videoId=$videoId, sessionId=$sessionId")
        Timber.tag(TAG).d("WebView state: supported=$webViewSupported, badImpl=$webViewBadImpl, fullyFailed=$poTokenFullyFailed")

        // If PoToken has permanently failed (e.g. WebView crash), return null immediately
        // so callers skip PoToken-dependent clients without hanging or retrying.
        if (!webViewSupported || webViewBadImpl || poTokenFullyFailed) {
            Timber.tag(TAG).d("WebView not available: supported=$webViewSupported, badImpl=$webViewBadImpl, fullyFailed=$poTokenFullyFailed")
            return null
        }

        // Return cached PoToken for the same session within a reasonable window
        val cached = cachedPoTokenResult.get()
        if (cached != null && cached.sessionId == sessionId &&
            System.currentTimeMillis() - cached.cachedAtMs < 30_000
        ) {
            Timber.tag(TAG).d("Returning cached PoToken for session ${sessionId.take(20)}...")
            return cached.result
        }

        return try {
            Timber.tag(TAG).d("Calling runBlocking to generate poToken...")
            val result = runBlocking { getWebClientPoToken(videoId, sessionId, forceRecreate = false) }
            cachedPoTokenResult.set(CachedResult(result, sessionId, System.currentTimeMillis()))
            result
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "poToken generation exception: ${e.javaClass.simpleName}: ${e.message}")
            when (e) {
                is BadWebViewException -> {
                    Timber.tag(TAG).e(e, "Could not obtain poToken because WebView is broken")
                    webViewBadImpl = true
                    poTokenFullyFailed = true
                    null
                }
                is PoTokenException -> {
                    Timber.tag(TAG).e(e, "PoToken generation timed out or failed")
                    // Mark as fully failed so subsequent calls skip PoToken-dependent clients
                    poTokenFullyFailed = true
                    null
                }
                else -> throw e 
            }
        }
    }

    
    private suspend fun getWebClientPoToken(videoId: String, sessionId: String, forceRecreate: Boolean): PoTokenResult {
        Timber.tag(TAG).d("Web poToken requested: videoId=$videoId, sessionId=$sessionId")

        val (poTokenGenerator, streamingPot, hasBeenRecreated) =
            webPoTokenGenLock.withLock {
                val shouldRecreate =
                    forceRecreate || webPoTokenGenerator == null || webPoTokenGenerator!!.isExpired ||
                        webPoTokenGenerator!!.isDead ||
                        webPoTokenSessionId != sessionId

                if (shouldRecreate) {
                    Timber.tag(TAG).d("Creating new PoTokenWebView (forceRecreate=$forceRecreate)")

                    withContext(Dispatchers.Main) {
                        webPoTokenGenerator?.close()
                    }

                    
                    val newGenerator = PoTokenWebView.getNewPoTokenGenerator(CipherDeobfuscator.appContext)

                    
                    val newStreamingPot = try {
                        newGenerator.generatePoToken(sessionId)
                    } catch (e: Exception) {
                        withContext(Dispatchers.Main) { newGenerator.close() }
                        throw e
                    }
                    
                    webPoTokenSessionId = sessionId
                    webPoTokenGenerator = newGenerator
                    webPoTokenStreamingPot = newStreamingPot
                    Timber.tag(TAG).d("Streaming poToken generated for sessionId=${webPoTokenSessionId?.take(20)}...")
                }

                Triple(webPoTokenGenerator!!, webPoTokenStreamingPot!!, shouldRecreate)
            }

        val playerPot = try {
            poTokenGenerator.generatePoToken(videoId)
        } catch (throwable: Throwable) {
            if (hasBeenRecreated) {
                
                
                throw throwable
            } else {
                
                
                
                Timber.tag(TAG).e(throwable, "Failed to obtain poToken, retrying")
                return getWebClientPoToken(videoId = videoId, sessionId = sessionId, forceRecreate = true)
            }
        }

        Timber.tag(TAG).d("poToken generated successfully: player=${playerPot.take(20)}..., streaming=${streamingPot.take(20)}...")

        return PoTokenResult(playerPot, streamingPot)
    }
}
