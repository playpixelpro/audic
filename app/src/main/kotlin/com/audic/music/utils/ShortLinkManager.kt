package com.audic.music.utils

import com.github.shortiosdk.ShortIOParameters
import com.github.shortiosdk.ShortIOResult
import com.github.shortiosdk.ShortioSdk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber

object ShortLinkManager {
    private const val DOMAIN = "share.playpixelpro.com"
    private const val API_KEY = "sk_FMoamrV6cDmUgO5R"
    private const val TAG = "ShortLinkManager"

    private val cache = mutableMapOf<String, String>()

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
                        Timber.tag(TAG).w("Short.io error: %s", result.data.message)
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
