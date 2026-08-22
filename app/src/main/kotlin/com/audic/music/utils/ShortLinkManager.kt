package com.audic.music.utils

import com.github.shortiosdk.ShortIOParameters
import com.github.shortiosdk.ShortIOResult
import com.github.shortiosdk.ShortioSdk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.util.concurrent.ConcurrentHashMap

object ShortLinkManager {
    private const val DOMAIN = "share.playpixelpro.com"
    private const val API_KEY = "sk_ua8d8fqXZTWqinWx"
    private const val TAG = "ShortLinkManager"

    private val cache = ConcurrentHashMap<String, String>()

    suspend fun shorten(originalUrl: String): String {
        cache[originalUrl]?.let { return it }

        return withContext(Dispatchers.IO) {
            try {
                val params = ShortIOParameters(
                    domain = DOMAIN,
                    originalURL = originalUrl,
                )
                when (val result = ShortioSdk.shortenUrl(API_KEY, params)) {
                    is ShortIOResult.Success -> {
                        val shortUrl = result.data.shortURL ?: originalUrl
                        cache[originalUrl] = shortUrl
                        Timber.tag(TAG).d("Shortened: %s -> %s", originalUrl, shortUrl)
                        shortUrl
                    }
                    is ShortIOResult.Error -> {
                        Timber.tag(TAG).w("Short.io error: %s (domain=%s, keyPrefix=%s)", 
                            result.data.message, DOMAIN, API_KEY.take(8))
                        originalUrl
                    }
                }
            } catch (e: Exception) {
                Timber.tag(TAG).e(e, "Short.io failed")
                originalUrl
            }
        }
    }

    fun clearCache() {
        cache.clear()
    }
}
