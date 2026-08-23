package com.audic.music.utils

import com.audic.music.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import timber.log.Timber
import java.util.concurrent.ConcurrentHashMap

object ShortLinkManager {
    private const val DOMAIN = "short.playpixelpro.com"
    private const val TAG = "ShortLinkManager"

    private val cache = ConcurrentHashMap<String, String>()
    private val httpClient = OkHttpClient()

    suspend fun shorten(originalUrl: String): String {
        cache[originalUrl]?.let { return it }

        val apiKey = BuildConfig.YOURLS_API_KEY
        if (apiKey.isBlank()) {
            Timber.tag(TAG).w("YOURLS API key not configured, returning original URL")
            return originalUrl
        }

        return withContext(Dispatchers.IO) {
            try {
                val form = FormBody.Builder()
                    .add("api_key", apiKey)
                    .add("action", "shorturl")
                    .add("format", "json")
                    .add("url", originalUrl)
                    .build()

                val request = Request.Builder()
                    .url("https://$DOMAIN/yourls-api.php")
                    .post(form)
                    .build()

                val response = httpClient.newCall(request).execute()
                val code = response.code
                val body = response.body?.string() ?: ""

                if (!response.isSuccessful || body.isBlank()) {
                    Timber.tag(TAG).w("YOURLS returned HTTP %d with empty/invalid body for: %s", code, originalUrl)

                    // Retry with GET request (YOURLS also supports GET)
                    try {
                        val getUrl = "https://$DOMAIN/yourls-api.php?action=shorturl&format=json&api_key=$apiKey&url=$originalUrl"
                        val getResponse = httpClient.newCall(Request.Builder().url(getUrl).get().build()).execute()
                        val getBody = getResponse.body?.string() ?: ""
                        if (getResponse.isSuccessful && getBody.isNotBlank()) {
                            val getJson = JSONObject(getBody)
                            val shortUrl = getJson.optString("shorturl", originalUrl)
                            if (shortUrl != originalUrl) {
                                cache[originalUrl] = shortUrl
                                Timber.tag(TAG).d("Shortened via GET: %s -> %s", originalUrl, shortUrl)
                                return@withContext shortUrl
                            }
                        }
                        Timber.tag(TAG).w("YOURLS GET fallback also failed: HTTP %d", getResponse.code)
                    } catch (e: Exception) {
                        Timber.tag(TAG).e(e, "YOURLS GET fallback failed")
                    }

                    return@withContext originalUrl
                }

                val json = JSONObject(body)
                val shortUrl = json.optString("shorturl", originalUrl)

                if (shortUrl != originalUrl) {
                    cache[originalUrl] = shortUrl
                    Timber.tag(TAG).d("Shortened: %s -> %s", originalUrl, shortUrl)
                } else {
                    Timber.tag(TAG).w("YOURLS returned no short URL for: %s", originalUrl)
                }

                shortUrl
            } catch (e: Exception) {
                Timber.tag(TAG).e(e, "YOURLS shorten failed")
                originalUrl
            }
        }
    }

    fun clearCache() {
        cache.clear()
    }
}
