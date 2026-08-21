package com.music.audic.utils.lastfm

import com.music.audic.models.lastfm.Authentication
import com.music.audic.models.lastfm.LastFmError
import com.music.audic.models.lastfm.TokenResponse
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.defaultRequest
import io.ktor.client.request.*
import io.ktor.client.request.forms.FormDataContent
import io.ktor.client.statement.bodyAsText
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.json.Json
import java.security.MessageDigest

/** Raised when the service answers with an `{"error": N, "message": ...}` object. */
class LastFmException(val code: Int, override val message: String) : Exception(message) {
    override fun toString(): String = "LastFmException(code=$code, message=$message)"
}

/**
 * AudioScrobbler 2.0 client. Last.fm and Libre.fm (GNU FM) implement the same protocol —
 * same methods, same md5 signing, same session model — so they differ only by endpoint and
 * credentials. See [LastFM] and [LibreFM].
 */
open class ScrobblerClient(
    private val baseUrl: String,
    private val authUrlBase: String,
    apiKey: String,
    secret: String,
) {
    /** Session key from a successful login. Null means "not logged in". */
    var sessionKey: String? = null

    private var apiKey: String = apiKey
    private var secret: String = secret

    private val json = Json {
        isLenient = true
        ignoreUnknownKeys = true
    }

    private val client by lazy {
        HttpClient(OkHttp) {
            install(ContentNegotiation) {
                json(json)
            }
            defaultRequest { url(baseUrl) }
            expectSuccess = false
        }
    }

    private fun Map<String, String>.apiSig(secret: String) = ScrobblerClient.apiSig(this, secret)

    private fun HttpRequestBuilder.scrobblerParams(
        method: String,
        sessionKey: String? = null,
        extra: Map<String, String> = emptyMap(),
        format: String = "json",
    ) {
        contentType(ContentType.Application.FormUrlEncoded)
        userAgent(USER_AGENT)
        val paramsForSig = mutableMapOf(
            "method" to method,
            "api_key" to apiKey,
        ).apply {
            sessionKey?.let { put("sk", it) }
            putAll(extra)
        }
        // `format` is deliberately outside the signature — the spec excludes it.
        val apiSig = paramsForSig.apiSig(secret)
        setBody(FormDataContent(Parameters.build {
            paramsForSig.forEach { (k, v) -> append(k, v) }
            append("api_sig", apiSig)
            append("format", format)
        }))
    }

    // Desktop-style OAuth token flow (kept for backward compatibility; unused by the UI).
    suspend fun getToken() = runCatching {
        client.post { scrobblerParams(method = "auth.getToken") }.body<TokenResponse>()
    }

    suspend fun getSession(token: String) = runCatching {
        client.post {
            scrobblerParams(method = "auth.getSession", extra = mapOf("token" to token))
        }.body<Authentication>()
    }

    fun getAuthUrl(token: String): String = "$authUrlBase?api_key=$apiKey&token=$token"

    /** Username/password login. Returns the session whose `key` becomes [sessionKey]. */
    suspend fun getMobileSession(username: String, password: String) = runCatching {
        val response = client.post {
            scrobblerParams(
                method = "auth.getMobileSession",
                extra = mapOf("username" to username, "password" to password),
            )
        }

        val responseText = response.bodyAsText()
        if (responseText.contains("\"error\"")) {
            val error = json.decodeFromString<LastFmError>(responseText)
            throw LastFmException(error.error, error.message)
        }
        json.decodeFromString<Authentication>(responseText)
    }

    suspend fun updateNowPlaying(
        artist: String, track: String,
        album: String? = null, trackNumber: Int? = null, duration: Int? = null,
    ) = runCatching {
        client.post {
            scrobblerParams(
                method = "track.updateNowPlaying",
                sessionKey = requireSession(),
                extra = buildMap {
                    put("artist", artist)
                    put("track", track)
                    album?.let { put("album", it) }
                    trackNumber?.let { put("trackNumber", it.toString()) }
                    duration?.let { put("duration", it.toString()) }
                },
            )
        }
    }

    suspend fun scrobble(
        artist: String, track: String, timestamp: Long,
        album: String? = null, trackNumber: Int? = null, duration: Int? = null,
    ) = runCatching {
        client.post {
            scrobblerParams(
                method = "track.scrobble",
                sessionKey = requireSession(),
                extra = buildMap {
                    put("artist[0]", artist)
                    put("track[0]", track)
                    put("timestamp[0]", timestamp.toString())
                    album?.let { put("album[0]", it) }
                    trackNumber?.let { put("trackNumber[0]", it.toString()) }
                    duration?.let { put("duration[0]", it.toString()) }
                },
            )
        }
    }

    suspend fun setLoveStatus(
        artist: String, track: String, love: Boolean,
    ) = runCatching {
        client.post {
            scrobblerParams(
                method = if (love) "track.love" else "track.unlove",
                sessionKey = requireSession(),
                extra = buildMap {
                    put("artist", artist)
                    put("track", track)
                },
            )
        }
    }

    /** Override the compiled-in credentials (e.g. a user-supplied key). */
    fun initialize(apiKey: String, secret: String) {
        this.apiKey = apiKey
        this.secret = secret
    }

    /** True when credentials were compiled in — not whether the user is logged in. */
    fun isInitialized(): Boolean = apiKey.isNotEmpty() && secret.isNotEmpty()

    /** Credentials present AND a session exists, i.e. calls can actually succeed. */
    fun isAuthenticated(): Boolean = isInitialized() && !sessionKey.isNullOrEmpty()

    private fun requireSession(): String =
        sessionKey?.takeIf { it.isNotEmpty() } ?: throw LastFmException(9, "Not logged in")

    companion object {
        const val USER_AGENT = "Audic Music (https://github.com/dindoquitor/audic)"

        /** AudioScrobbler 2.0 api_sig: md5 over sorted `key+value` pairs, then the secret. */
        fun apiSig(params: Map<String, String>, secret: String): String {
            val toHash = params.toSortedMap().entries.joinToString("") { it.key + it.value } + secret
            val digest = MessageDigest.getInstance("MD5").digest(toHash.toByteArray())
            return digest.joinToString("") { "%02x".format(it) }
        }
    }
}
