package com.audic.music.utils

import com.audic.music.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

object ShortLinkManager {
    private val client = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.SECONDS)
        .build()

    private const val API_ENDPOINT = "https://short.playpixelpro.com/yourls-api.php"
    private val API_KEY = BuildConfig.YOURLS_API_KEY

    /**
     * Shortens longUrl on every Share button click.
     * Returns the shortened URL on success, or longUrl as safe fallback.
     */
    suspend fun shorten(longUrl: String): String = withContext(Dispatchers.IO) {
        if (longUrl.isBlank()) return@withContext longUrl
        try {
            // POST form-data request
            val form = FormBody.Builder()
                .add("api_key", API_KEY)
                .add("action", "shorturl")
                .add("format", "json")
                .add("url", longUrl)
                .build()

            val request = Request.Builder()
                .url(API_ENDPOINT)
                .post(form)
                .build()

            client.newCall(request).execute().use { response ->
                val bodyString = response.body.string().orEmpty()
                if (response.isSuccessful && bodyString.isNotEmpty()) {
                    val json = JSONObject(bodyString)
                    val shortUrl = json.optString("shorturl", "")
                    if (shortUrl.isNotEmpty()) {
                        return@withContext shortUrl
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        // Graceful fallback to original URL so sharing never breaks
        longUrl
    }
}
