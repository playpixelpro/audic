package com.audic.music.utils

import com.audic.music.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import timber.log.Timber
import java.util.concurrent.TimeUnit

object ShortLinkManager {
    private const val TAG = "ShortLinkManager"
    private val client = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.SECONDS)
        .build()

    private const val API_ENDPOINT = "https://audic.playpixelpro.com/yourls-api.php"
    private val API_KEY = BuildConfig.YOURLS_API_KEY

    /**
     * Shortens a long URL via the private YOURLS instance.
     * Attempts POST first; if POST returns an empty body, falls back to GET.
     *
     * @param longUrl  The URL to shorten.
     * @param title    Optional title for the shortened link (stored in YOURLS).
     * @return The shortened URL on success, or [longUrl] as a safe fallback.
     */
    suspend fun shorten(longUrl: String, title: String? = null): String = withContext(Dispatchers.IO) {
        if (longUrl.isBlank()) return@withContext longUrl

        if (API_KEY.isBlank()) {
            Timber.tag(TAG).w("YOURLS API key not configured (BuildConfig.YOURLS_API_KEY empty), sharing original URL")
            return@withContext longUrl
        }

        Timber.tag(TAG).d("Shortening: $longUrl")

        // --- GET attempt (the audic.playpixelpro.com YOURLS instance handles GET reliably) ---
        val getResult = tryGet(longUrl, title)
        if (getResult != null) return@withContext getResult

        // --- POST fallback ---
        Timber.tag(TAG).d("GET attempt failed, trying POST fallback…")
        val postResult = tryPost(longUrl, title)
        if (postResult != null) return@withContext postResult

        // Graceful fallback to original URL so sharing never breaks
        Timber.tag(TAG).w("All shortening attempts exhausted, sharing original URL")
        longUrl
    }

    /**
     * Attempt to shorten via POST. Returns the shortened URL or null.
     */
    private fun tryPost(longUrl: String, title: String?): String? {
        try {
            val form = FormBody.Builder()
                .add("api_key", API_KEY)
                .add("action", "shorturl")
                .add("format", "json")
                .add("url", longUrl)

            if (!title.isNullOrBlank()) {
                form.add("title", title)
            }

            val request = Request.Builder()
                .url(API_ENDPOINT)
                .post(form.build())
                .build()

            return executeAndParse(request)
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "POST shortening failed with exception")
            return null
        }
    }

    /**
     * Attempt to shorten via GET (query-parameters). Returns the shortened URL or null.
     */
    private fun tryGet(longUrl: String, title: String?): String? {
        try {
            val urlBuilder = API_ENDPOINT.toHttpUrlOrNull()?.newBuilder()
                ?: return null

            urlBuilder.addQueryParameter("api_key", API_KEY)
            urlBuilder.addQueryParameter("action", "shorturl")
            urlBuilder.addQueryParameter("format", "json")
            urlBuilder.addQueryParameter("url", longUrl)
            if (!title.isNullOrBlank()) {
                urlBuilder.addQueryParameter("title", title)
            }

            val request = Request.Builder()
                .url(urlBuilder.build())
                .get()
                .build()

            return executeAndParse(request)
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "GET shortening failed with exception")
            return null
        }
    }

    /**
     * Execute a request, parse the YOURLS JSON response, and return the short URL or null.
     */
    private fun executeAndParse(request: Request): String? {
        client.newCall(request).execute().use { response ->
            val bodyString = response.body.string().orEmpty()
            if (response.isSuccessful && bodyString.isNotEmpty()) {
                val json = JSONObject(bodyString)
                val shortUrl = json.optString("shorturl", "")
                if (shortUrl.isNotEmpty()) {
                    Timber.tag(TAG).d("Shortened OK -> $shortUrl")
                    return shortUrl
                } else {
                    Timber.tag(TAG).w("Empty shorturl in response: HTTP ${response.code}, body='$bodyString'")
                }
            } else {
                Timber.tag(TAG).w("Request failed: HTTP ${response.code}, body='$bodyString'")
            }
        }
        return null
    }
}
