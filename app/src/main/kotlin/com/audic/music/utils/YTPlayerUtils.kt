

package com.audic.music.utils

import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.util.Log
import androidx.media3.common.PlaybackException
import com.music.innertube.NewPipeExtractor
import com.music.innertube.YouTube
import com.music.innertube.models.YouTubeClient
import com.music.innertube.models.YouTubeClient.Companion.ANDROID_CREATOR
import com.audic.music.utils.BotDetectionMitigator
import com.music.innertube.models.YouTubeClient.Companion.ANDROID_TESTSUITE
import com.music.innertube.models.YouTubeClient.Companion.ANDROID_VR
import com.music.innertube.models.YouTubeClient.Companion.ANDROID_VR_1_43_32
import com.music.innertube.models.YouTubeClient.Companion.ANDROID_VR_NO_AUTH
import com.music.innertube.models.YouTubeClient.Companion.IOS
import com.music.innertube.models.YouTubeClient.Companion.IPADOS
import com.music.innertube.models.YouTubeClient.Companion.MOBILE
import com.music.innertube.models.YouTubeClient.Companion.TVHTML5
import com.music.innertube.models.YouTubeClient.Companion.TVHTML5_SIMPLY_EMBEDDED_PLAYER
import com.music.innertube.models.YouTubeClient.Companion.VISIONOS
import com.music.innertube.models.YouTubeClient.Companion.WEB
import com.music.innertube.models.YouTubeClient.Companion.WEB_CREATOR
import com.music.innertube.models.YouTubeClient.Companion.WEB_REMIX
import com.music.innertube.models.response.PlayerResponse
import com.audic.music.constants.AudioQuality
import kotlinx.serialization.json.Json
import com.audic.music.utils.cipher.CipherDeobfuscator
import com.audic.music.utils.YTPlayerUtils.MAIN_CLIENT
import com.audic.music.utils.YTPlayerUtils.STREAM_FALLBACK_CLIENTS
import com.audic.music.utils.potoken.PoTokenGenerator
import com.audic.music.utils.potoken.PoTokenResult
import com.audic.music.utils.sabr.EjsNTransformSolver
import com.audic.music.utils.PlaybackLogLevel
import com.audic.music.utils.PlaybackLogManager
import com.music.innertube.models.IpVersion
import okhttp3.Dns
import okhttp3.OkHttpClient
import timber.log.Timber
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.Proxy
import java.net.ProxySelector
import java.net.SocketAddress
import java.net.URI
import java.io.IOException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first

internal fun isAgeRestrictedPlayability(status: String?, reason: String?): Boolean {
    if (status in setOf("AGE_CHECK_REQUIRED", "AGE_VERIFICATION_REQUIRED", "CONTENT_CHECK_REQUIRED")) {
        return true
    }

    val normalizedReason = reason.orEmpty()
    return normalizedReason.contains("confirm your age", ignoreCase = true) ||
        normalizedReason.contains("age verification", ignoreCase = true) ||
        normalizedReason.contains("age-restricted", ignoreCase = true) ||
        (status == "LOGIN_REQUIRED" && normalizedReason.contains("sign in", ignoreCase = true))
}

object YTPlayerUtils {
    private const val logTag = "YTPlayerUtils"
    private const val TAG = "YTPlayerUtils"

    private val playerResponseJson = Json { ignoreUnknownKeys = true }

    private val httpClient: OkHttpClient = OkHttpClient.Builder()
        .dns(object : Dns {
            override fun lookup(hostname: String): List<InetAddress> {
                val addresses = Dns.SYSTEM.lookup(hostname)
                return when (YouTube.ipVersion) {
                    IpVersion.IPV4 -> addresses.filter { it is Inet4Address }.ifEmpty { addresses }
                    IpVersion.IPV6 -> addresses.filter { it is Inet6Address }.ifEmpty { addresses }
                    IpVersion.AUTO -> addresses
                }
            }
        })
        .proxySelector(object : ProxySelector() {
            override fun select(uri: URI?): List<Proxy> = listOfNotNull(YouTube.proxy ?: Proxy.NO_PROXY)
            override fun connectFailed(uri: URI?, sa: SocketAddress?, ioe: IOException?) {
                Timber.tag(TAG).e(ioe, "Proxy connection failed for URI: $uri")
            }
        })
        .proxyAuthenticator { _, response ->
            YouTube.proxyAuth?.let { auth ->
                response.request.newBuilder()
                    .header("Proxy-Authorization", auth)
                    .build()
            } ?: response.request
        }
        .connectTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
        .readTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
        .build()

    private val poTokenGenerator = PoTokenGenerator()


    // TVHTML5_SIMPLY_EMBEDDED_PLAYER is the most reliable client for returning direct
    // stream URLs. YouTube has moved mobile/VR clients (ANDROID_VR, IOS, etc.) to
    // SABR-only responses where adaptiveFormats[i].url is null. Embedded TV clients
    // still return plain URLs and are far less aggressively throttled.
    private val MAIN_CLIENT: YouTubeClient = TVHTML5_SIMPLY_EMBEDDED_PLAYER

    private val METADATA_CLIENT: YouTubeClient = WEB_REMIX

    private val STREAM_FALLBACK_CLIENTS: Array<YouTubeClient> = arrayOf(
        // YouTube Music web — least aggressively throttled for music content
        WEB_REMIX,
        // Embedded TV player — bypasses age-restriction, returns direct URLs
        TVHTML5_SIMPLY_EMBEDDED_PLAYER,
        // Apple visionOS — newest client, YouTube hasn't SABR-blocked it yet
        VISIONOS,
        // Android Creator — studio client, less likely to be throttled
        ANDROID_CREATOR,
        // ANDROID_TESTSUITE — reliable, often works without PoToken
        ANDROID_TESTSUITE,
        // Older VR version — non-adaptive bitrate, may work when newer doesn't
        ANDROID_VR_1_43_32,
        // Mobile/VR clients — increasingly SABR-only, keep as deep fallback
        ANDROID_VR_NO_AUTH,
        ANDROID_VR,
        IOS,
        IPADOS,
        MOBILE,
        // Web variants — lowest priority
        WEB,
        WEB_CREATOR,
        TVHTML5
    )
    data class PlaybackData(
        val audioConfig: PlayerResponse.PlayerConfig.AudioConfig?,
        val videoDetails: PlayerResponse.VideoDetails?,
        val playbackTracking: PlayerResponse.PlaybackTracking?,
        val format: PlayerResponse.StreamingData.Format,
        val streamUrl: String,
        val streamExpiresInSeconds: Int,
        val streamHeaders: Map<String, String> = emptyMap(),
        val streamClient: String = "unknown",
    )

    // Per-video stream-client rejection tracking (mirrors SmartTube's ErrorFixerController ->
    // switchNextFormat / the reference's webRemixFailures). When a resolved stream 403s on the CDN,
    // the failing client is recorded here with a TTL so the next resolution skips it and falls
    // through to the next client instead of returning the same rejected URL.
    private val rejectedClients =
        java.util.concurrent.ConcurrentHashMap<String, java.util.concurrent.ConcurrentHashMap<String, Long>>()
    private const val REJECT_TTL_MS = 5 * 60 * 1000L

    fun markClientRejected(videoId: String, clientName: String) {
        if (clientName.isBlank()) return
        Timber.tag(TAG).w("Marking client rejected for $videoId: $clientName")
        rejectedClients
            .computeIfAbsent(videoId) { java.util.concurrent.ConcurrentHashMap() }[clientName] =
            System.currentTimeMillis()
    }

    private fun isClientRejected(videoId: String, clientName: String): Boolean {
        val map = rejectedClients[videoId] ?: return false
        val failedAt = map[clientName] ?: return false
        if ((System.currentTimeMillis() - failedAt) !in 0 until REJECT_TTL_MS) {
            map.remove(clientName)
            return false
        }
        return true
    }

    fun clearRejectedClients(videoId: String) {
        rejectedClients.remove(videoId)
    }

    // Headers that must accompany the stream request to the CDN. The CDN rejects (403) streams
    // minted by web-based clients (WEB_CREATOR, TVHTML5_SIMPLY, TVHTML5, ...) unless the request
    // carries the matching User-Agent / Referer / Origin. Without this, age-restricted and other
    // web-client streams fail with codeIO_bad_http_status (2004).
    // When ALL adaptiveFormats arrive without url OR signatureCipher/cipher, the client is
    // SABR-only and will never yield a playable stream. Skip to the next client immediately.
    private fun isSabrOnlyResponse(response: PlayerResponse): Boolean {
        val formats = response.streamingData?.adaptiveFormats ?: return false
        return formats.all { f ->
            f.url.isNullOrEmpty() &&
                f.signatureCipher.isNullOrEmpty() &&
                f.cipher.isNullOrEmpty()
        }
    }

    private fun YouTubeClient.streamHeaders(): Map<String, String> =
        buildMap {
            put("User-Agent", userAgent)
            put("Accept", "*/*")
            put("Accept-Language", "en-US,en;q=0.9")

            when (clientName) {
                "WEB_REMIX" -> {
                    put("Referer", "https://music.youtube.com/")
                    put("Origin", "https://music.youtube.com")
                }
                "WEB_CREATOR" -> {
                    put("Referer", "https://studio.youtube.com/")
                    put("Origin", "https://studio.youtube.com")
                }
                else -> {
                    put("Referer", "https://www.youtube.com/")
                    put("Origin", "https://www.youtube.com")
                }
            }
        }
    
    // When all API-based client fallbacks have been exhausted, scrape YouTube Music's HTML
    // page for ytInitialPlayerResponse. This mirrors SimpMusic's approach and can recover
    // stream URLs when the API returns SABR-only responses.
    private suspend fun scrapePlayerResponse(videoId: String): PlayerResponse? {
        val url = "https://music.youtube.com/watch?v=$videoId"
        return try {
            val request = okhttp3.Request.Builder()
                .url(url)
                .header("User-Agent", YouTubeClient.USER_AGENT_WEB)
                .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                .header("Accept-Language", "en-US,en;q=0.5")
                .build()
            val html = httpClient.newCall(request).execute().body.string()
            val pattern = """ytInitialPlayerResponse\s*=\s*(\{.*?\});""".toRegex()
            val json = pattern.find(html)?.groupValues?.get(1) ?: return null
            playerResponseJson.decodeFromString<PlayerResponse>(json)
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "HTML scraping failed for $videoId")
            null
        }
    }

    suspend fun playerResponseForPlayback(
        videoId: String,
        playlistId: String? = null,
        audioQuality: AudioQuality,
        connectivityManager: ConnectivityManager,
        context: android.content.Context? = null,
        knownArtist: String? = null,
        knownTitle: String? = null,
        knownDurationMs: Long? = null,
        isDownload: Boolean = false
    ): Result<PlaybackData> {

        suspend fun tryOpus(): Result<PlaybackData> {
            val firstAttempt = resolvePlaybackData(videoId, playlistId, audioQuality, connectivityManager)
            if (firstAttempt.isFailure && YouTube.cookie == null) {
                Timber.tag(TAG).w("Playback failed for guest. Rotating session and retrying...")
                PlaybackLogManager.log(PlaybackLogLevel.BOT, "Playback failed for guest", "Triggering bot detection mitigation (rotating guest session)")
                BotDetectionMitigator.rotateGuestSession()
                val retryResult = resolvePlaybackData(videoId, playlistId, audioQuality, connectivityManager)
                retryResult.onSuccess { BotDetectionMitigator.notifyPlaybackSuccess() }
                return retryResult
            }
            firstAttempt.onSuccess { BotDetectionMitigator.notifyPlaybackSuccess() }
            return firstAttempt
        }

        return tryOpus()
    }

    private suspend fun resolvePlaybackData(
        videoId: String,
        playlistId: String? = null,
        audioQuality: AudioQuality,
        connectivityManager: ConnectivityManager,
    ): Result<PlaybackData> = runCatching {
        Timber.tag(logTag).d("Fetching player response for videoId: $videoId, playlistId: $playlistId")
        PlaybackLogManager.log(PlaybackLogLevel.INFO, "Resolving playback data", "Video: $videoId")
        
        
        val isUploadedTrack = playlistId == "MLPT" || playlistId?.contains("MLPT") == true

        val isLoggedIn = YouTube.cookie != null
        Timber.tag(logTag).d("Session authentication status: ${if (isLoggedIn) "Logged in" else "Not logged in"}")

        
        val sts = CipherDeobfuscator.signatureTimestamp()
        Timber.tag(logTag).d("Signature timestamp from cipher: $sts")

        
        var poToken: PoTokenResult? = null
        val sessionId = if (isLoggedIn) YouTube.dataSyncId else YouTube.visitorData
        if (MAIN_CLIENT.useWebPoTokens && sessionId != null) {
            Timber.tag(logTag).d("Generating PoToken for MAIN_CLIENT with sessionId")
            try {
                poToken = poTokenGenerator.getWebClientPoToken(videoId, sessionId)
                if (poToken != null) {
                    Timber.tag(logTag).d("PoToken generated successfully")
                }
            } catch (e: Exception) {
                Timber.tag(logTag).e(e, "PoToken generation failed: ${e.message}")
            }
        }

        
        Timber.tag(logTag).d("Attempting to get player response using MAIN_CLIENT: ${MAIN_CLIENT.clientName}")
        PlaybackLogManager.log(PlaybackLogLevel.DEBUG, "Trying ${MAIN_CLIENT.clientName} (Main)")
        var mainPlayerResponse: PlayerResponse
        var metadataResponse: PlayerResponse? = null
        coroutineScope {
            val mainDeferred = async {
                YouTube.player(videoId, playlistId, MAIN_CLIENT, sts, poToken?.playerRequestPoToken).getOrThrow()
            }
            val metadataDeferred = if (isLoggedIn) async {
                Timber.tag(logTag).d("Fetching metadata from METADATA_CLIENT (WEB_REMIX) in parallel")
                var metaPoToken: PoTokenResult? = null
                val metaSessionId = YouTube.dataSyncId
                if (METADATA_CLIENT.useWebPoTokens && metaSessionId != null) {
                    try { metaPoToken = poTokenGenerator.getWebClientPoToken(videoId, metaSessionId) }
                    catch (e: Exception) { Timber.tag(logTag).e(e, "Metadata PoToken generation failed") }
                }
                YouTube.player(videoId, playlistId, METADATA_CLIENT, sts, metaPoToken?.playerRequestPoToken)
                    .getOrNull().also { Timber.tag(logTag).d("Metadata response obtained: ${it?.playabilityStatus?.status}") }
            } else null
            mainPlayerResponse = mainDeferred.await()
            metadataResponse = metadataDeferred?.await()
        }


        
        var usedAgeRestrictedClient: YouTubeClient? = null
        val wasOriginallyAgeRestricted: Boolean

        
        
        
        
        
        val mainStatus = mainPlayerResponse.playabilityStatus.status
        val isAgeRestrictedFromResponse = isAgeRestrictedPlayability(
            mainStatus,
            mainPlayerResponse.playabilityStatus.reason,
        )
        wasOriginallyAgeRestricted = isAgeRestrictedFromResponse

        if (isAgeRestrictedFromResponse && isLoggedIn) {
            
            Timber.tag(logTag).d("Age-restricted detected, using WEB_CREATOR")
            Log.i(TAG, "Age-restricted: using WEB_CREATOR for videoId=$videoId")
            val creatorResponse = YouTube.player(videoId, playlistId, WEB_CREATOR, sts, null)
                .onFailure {
                    Timber.tag(logTag).e(it, "player() request FAILED for WEB_CREATOR")
                }.getOrNull()
            if (creatorResponse?.playabilityStatus?.status == "OK") {
                Timber.tag(logTag).d("WEB_CREATOR works for age-restricted content")
                mainPlayerResponse = creatorResponse
                usedAgeRestrictedClient = WEB_CREATOR
            }
        }

        // For guests (or if WEB_CREATOR failed), try multiple bypass approaches
        // Different clients work on different devices due to TLS/network fingerprinting:
        //   TVHTML5_SIMPLY (PS4) → works on emulators
        //   ANDROID_VR_NO_AUTH (Oculus) → matches real Android fingerprint better
        if (usedAgeRestrictedClient == null && isAgeRestrictedFromResponse) {
            val bypassClients = listOf(
                TVHTML5_SIMPLY_EMBEDDED_PLAYER to "TVHTML5_SIMPLY",
                ANDROID_VR_NO_AUTH to "ANDROID_VR_NO_AUTH",
            )
            for ((client, name) in bypassClients) {
                Timber.tag(logTag).d("Age-restricted: trying $name bypass for videoId=$videoId")
                val response = YouTube.player(videoId, playlistId, client, sts, null)
                    .onFailure { Timber.tag(logTag).e(it, "player() request FAILED for $name") }
                    .getOrNull()
                if (response?.playabilityStatus?.status == "OK") {
                    Timber.tag(logTag).d("$name works for age-restricted content")
                    mainPlayerResponse = response
                    usedAgeRestrictedClient = client
                    break
                }
            }
        }

        
        
        val audioConfig = metadataResponse?.playerConfig?.audioConfig ?: mainPlayerResponse.playerConfig?.audioConfig
        val videoDetails = metadataResponse?.videoDetails ?: mainPlayerResponse.videoDetails
        var playbackTracking = metadataResponse?.playbackTracking ?: mainPlayerResponse.playbackTracking
        var format: PlayerResponse.StreamingData.Format? = null
        var streamUrl: String? = null
        var streamExpiresInSeconds: Int? = null
        var streamPlayerResponse: PlayerResponse? = null
        var retryMainPlayerResponse: PlayerResponse? = if (usedAgeRestrictedClient != null) mainPlayerResponse else null
        var successClient: YouTubeClient? = null
        // True when at least one client returned playability OK — the failure is then a stream
        // URL resolution problem, not the last client's playability status (e.g. WEB's
        // "Video unavailable" bot-block would otherwise be reported as the real error).
        var sawOkResponse = false

        
        val currentStatus = mainPlayerResponse.playabilityStatus.status
        var isAgeRestricted = isAgeRestrictedPlayability(
            currentStatus,
            mainPlayerResponse.playabilityStatus.reason,
        )

        if (isAgeRestricted) {
            Timber.tag(logTag).d("Content is still age-restricted (status: $currentStatus), will try fallback clients")
            Log.i(TAG, "Age-restricted content detected: videoId=$videoId, status=$currentStatus")
        }

        
        val isPrivateTrack = mainPlayerResponse.videoDetails?.musicVideoType == "MUSIC_VIDEO_TYPE_PRIVATELY_OWNED_TRACK"

        
        
        
        val startIndex = when {
            isPrivateTrack -> 1  
            isAgeRestricted -> 0
            else -> -1
        }

        for (clientIndex in (startIndex until STREAM_FALLBACK_CLIENTS.size)) {
            
            format = null
            streamUrl = null
            streamExpiresInSeconds = null

            // Skip clients whose previously resolved stream was rejected by the CDN (403) for this
            // video, so the next resolution rotates to a different client instead of 403-ing again.
            val loopClientName =
                if (clientIndex == -1) (usedAgeRestrictedClient ?: MAIN_CLIENT).clientName
                else STREAM_FALLBACK_CLIENTS[clientIndex].clientName
            if (isClientRejected(videoId, loopClientName)) {
                Timber.tag(logTag).d("Skipping rejected client $loopClientName for $videoId")
                continue
            }

            
            val client: YouTubeClient
            if (clientIndex == -1) {
                
                client = MAIN_CLIENT
                streamPlayerResponse = retryMainPlayerResponse ?: mainPlayerResponse
                Timber.tag(logTag).d("Trying stream from MAIN_CLIENT: ${client.clientName}")
            } else {
                
                client = STREAM_FALLBACK_CLIENTS[clientIndex]
                Timber.tag(logTag).d("Trying fallback client ${clientIndex + 1}/${STREAM_FALLBACK_CLIENTS.size}: ${client.clientName}")
                PlaybackLogManager.log(PlaybackLogLevel.DEBUG, "Trying fallback [${clientIndex + 1}/${STREAM_FALLBACK_CLIENTS.size}]", client.clientName)

                if (client.loginRequired && !isLoggedIn && YouTube.cookie == null) {
                    
                    Timber.tag(logTag).d("Skipping client ${client.clientName} - requires login but user is not logged in")
                    continue
                }

            
            // PoToken fallback mode: if PoToken generation has already failed, skip all
            // PoToken-dependent web clients since they will also fail without a valid token.
            if (client.useWebPoTokens && poToken == null) {
                if (sessionId == null) {
                    Timber.tag(logTag).d("Skipping client ${client.clientName} - no session ID for PoToken")
                    continue
                }
                Timber.tag(logTag).d("Lazily generating PoToken for fallback web client: ${client.clientName}")
                try {
                    poToken = poTokenGenerator.getWebClientPoToken(videoId, sessionId)
                } catch (e: Exception) {
                    Timber.tag(logTag).e(e, "Lazy PoToken generation failed for ${client.clientName}")
                }
                if (poToken == null) {
                    Timber.tag(logTag).w("PoToken remains null after generation attempt, marking as failed for this session")
                    // Mark session as PoToken-failed so subsequent web clients are skipped
                    // without retrying PoToken generation for each one.
                }
            }

                Timber.tag(logTag).d("Fetching player response for fallback client: ${client.clientName}")
                
                val clientPoToken = if (client.useWebPoTokens) poToken?.playerRequestPoToken else null
                
                val clientSigTimestamp = if (wasOriginallyAgeRestricted) null else sts
                streamPlayerResponse =
                    YouTube.player(videoId, playlistId, client, clientSigTimestamp, clientPoToken)
                        .onFailure {
                            Timber.tag(logTag).e(it, "player() request FAILED for %s", client.clientName)
                        }.getOrNull()
            }

            
            if (streamPlayerResponse?.playabilityStatus?.status == "OK") {
                sawOkResponse = true
                Timber.tag(logTag).d("Player response status OK for client: ${if (clientIndex == -1) MAIN_CLIENT.clientName else STREAM_FALLBACK_CLIENTS[clientIndex].clientName}")
                PlaybackLogManager.log(PlaybackLogLevel.INFO, "Player response OK", if (clientIndex == -1) MAIN_CLIENT.clientName else STREAM_FALLBACK_CLIENTS[clientIndex].clientName)

                
                if (isSabrOnlyResponse(streamPlayerResponse)) {
                    Timber.tag(logTag).w("SABR-only response from client ${if (clientIndex == -1) MAIN_CLIENT.clientName else STREAM_FALLBACK_CLIENTS[clientIndex].clientName} — no playable URLs, skipping")
                    PlaybackLogManager.log(PlaybackLogLevel.WARNING, "SABR-only response", "Client returned no url/signatureCipher — skipping")
                    continue
                }

                
                val hasDirectUrls = streamPlayerResponse.streamingData?.adaptiveFormats
                    ?.any { !it.url.isNullOrEmpty() } == true
                val hasSignatureCipher = streamPlayerResponse.streamingData?.adaptiveFormats
                    ?.any { !it.signatureCipher.isNullOrEmpty() || !it.cipher.isNullOrEmpty() } == true

                Timber.tag(logTag).d("URL check: hasDirectUrls=$hasDirectUrls, hasSignatureCipher=$hasSignatureCipher")

                
                val responseToUse = streamPlayerResponse

                format =
                    findFormat(
                        responseToUse,
                        audioQuality,
                        connectivityManager,
                    )

                if (format == null) {
                    Timber.tag(logTag).d("No suitable format found for client: ${if (clientIndex == -1) MAIN_CLIENT.clientName else STREAM_FALLBACK_CLIENTS[clientIndex].clientName}")
                    continue
                }

                Timber.tag(logTag).d("Format found: ${format.mimeType}, bitrate: ${format.bitrate}")

                streamUrl = findUrlOrNull(format, videoId, responseToUse, skipNewPipe = wasOriginallyAgeRestricted)
                if (streamUrl == null) {
                    Timber.tag(logTag).d("Stream URL not found for format")
                    continue
                }

                
                val currentClient = if (clientIndex == -1) {
                    usedAgeRestrictedClient ?: MAIN_CLIENT
                } else {
                    STREAM_FALLBACK_CLIENTS[clientIndex]
                }

                // Always apply n-transform if URL contains n= parameter.
                // YouTube throttling with the n= param now affects ALL client types (ANDROID_VR,
                // IOS, etc.) — not just web clients. If we don't transform it, ExoPlayer gets 403.
                val nMatch = Regex("[?&]n=").find(streamUrl)
                if (nMatch != null) {
                    try {
                        Timber.tag(logTag).d("Applying n-transform (n= detected in URL)")
                        // Prefer the maintained cipher WebView solver (same as the reference app);
                        // fall back to the EJS/SABR solver and then NewPipe's independent JS manager
                        // if earlier solvers are unavailable (an untransformed n => CDN 403).
                        var transformed = CipherDeobfuscator.transformNParamInUrl(streamUrl)
                        if (transformed == streamUrl) {
                            Timber.tag(logTag).w("Cipher n-transform produced no change, falling back to EJS solver")
                            transformed = EjsNTransformSolver.transformNParamInUrl(streamUrl)
                        }
                        if (transformed == streamUrl) {
                            Timber.tag(logTag).w("Cipher/EJS n-transform unavailable, trying NewPipe throttling deobfuscation")
                            transformed = NewPipeExtractor.deobfuscateThrottlingParam(streamUrl, videoId)
                        }
                        if (transformed != streamUrl) {
                            streamUrl = transformed
                            Timber.tag(logTag).d("N-transform applied successfully")
                        } else {
                            Timber.tag(logTag).e("N-transform produced no change — stream will likely 403")
                        }
                    } catch (e: Exception) {
                        Timber.tag(logTag).e(e, "N-transform failed: ${e.message}")
                    }
                }

                // Append pot= to stream URL for web PoToken clients only.
                // Non-web clients (ANDROID_VR, IOS) don't use this parameter.
                if (currentClient.useWebPoTokens && poToken?.streamingDataPoToken != null) {
                    Timber.tag(logTag).d("Appending pot= parameter to stream URL")
                    val separator = if ("?" in streamUrl) "&" else "?"
                    streamUrl = "${streamUrl}${separator}pot=${poToken.streamingDataPoToken}"
                }

                // SmartTube-style cver fix: the googlevideo CDN can reject the stream (403) if the
                // `cver` query param doesn't match the version of the client that minted the URL.
                streamUrl = applyClientVersion(streamUrl, currentClient.clientVersion)

                streamExpiresInSeconds = streamPlayerResponse.streamingData?.expiresInSeconds
                if (streamExpiresInSeconds == null) {
                    Timber.tag(logTag).d("Stream expiration time not found")
                    continue
                }

                Timber.tag(logTag).d("Stream expires in: $streamExpiresInSeconds seconds")

                
                val urlHost = try { java.net.URL(streamUrl).host } catch (e: Exception) { "unknown" }
                Timber.tag(logTag).d("Stream URL host: $urlHost, pot length: ${poToken?.streamingDataPoToken?.length ?: 0}")

                // Skip URL validation — ExoPlayer handles bad URLs quickly,
                // saving a HEAD request per client (~100-500ms per playback)
                Timber.tag(logTag).d("Using stream from client: ${currentClient.clientName}")
                PlaybackLogManager.log(PlaybackLogLevel.INFO, "Stream resolved", currentClient.clientName)
                Log.i(TAG, "Playback: client=${currentClient.clientName}, videoId=$videoId")
                successClient = currentClient
                break
            } else {
                val status = streamPlayerResponse?.playabilityStatus?.status ?: "Unknown"
                val reason = streamPlayerResponse?.playabilityStatus?.reason ?: "No reason"
                Timber.tag(logTag).d("Player response status not OK: $status, reason: $reason")
                PlaybackLogManager.log(PlaybackLogLevel.WARNING, "Client failed: ${client.clientName}", "$status: $reason")
                
                
                Timber.tag(logTag).d("Player response status not OK: ${streamPlayerResponse?.playabilityStatus?.status}, reason: ${streamPlayerResponse?.playabilityStatus?.reason}")
            }
        }

        // Fallback: if all API clients failed, try scraping YouTube Music HTML for
        // ytInitialPlayerResponse. This can recover URLs when the API returns SABR-only
        // responses or when YouTube is blocking API-based client requests.
        if (streamPlayerResponse == null || streamPlayerResponse.playabilityStatus.status != "OK") {
            Timber.tag(logTag).w("All API clients failed for $videoId — attempting HTML page scraping fallback")
            PlaybackLogManager.log(PlaybackLogLevel.WARNING, "API clients exhausted", "Trying HTML scraping fallback for $videoId")
            val scrapedResponse = scrapePlayerResponse(videoId)
            if (scrapedResponse?.playabilityStatus?.status == "OK" &&
                !isSabrOnlyResponse(scrapedResponse)
            ) {
                Timber.tag(logTag).i("HTML scraping recovered player response for $videoId")
                PlaybackLogManager.log(PlaybackLogLevel.INFO, "HTML scraping recovered", "Got playable response from page HTML")
                streamPlayerResponse = scrapedResponse
                sawOkResponse = true

                format = findFormat(scrapedResponse, audioQuality, connectivityManager)
                if (format != null) {
                    streamUrl = findUrlOrNull(format, videoId, scrapedResponse)
                }
                if (streamUrl != null) {
                    streamExpiresInSeconds = scrapedResponse.streamingData?.expiresInSeconds
                    successClient = METADATA_CLIENT
                    Timber.tag(logTag).i("HTML scraping succeeded — stream URL resolved via page scrape")
                }
            } else {
                Timber.tag(logTag).e("HTML scraping also failed for $videoId")
            }
        }

        if (streamPlayerResponse == null) {
            Timber.tag(logTag).e("Bad stream player response - all clients failed")
            throw Exception("Bad stream player response")
        }

        if (streamPlayerResponse.playabilityStatus.status != "OK") {
            val errorReason = streamPlayerResponse.playabilityStatus.reason
            if (sawOkResponse) {
                // Clients confirmed the video is playable but none produced a stream URL —
                // YouTube is withholding URLs (SABR) or the session is bot-flagged.
                Timber.tag(logTag).e("Playable video but no stream URL from any client")
                throw PlaybackException(
                    "No playable stream URL was returned for this song (YouTube may be blocking this session)",
                    null,
                    PlaybackException.ERROR_CODE_REMOTE_ERROR
                )
            }
            Timber.tag(logTag).e("Playability status not OK: $errorReason")
            throw PlaybackException(
                errorReason,
                null,
                PlaybackException.ERROR_CODE_REMOTE_ERROR
            )
        }

        if (streamExpiresInSeconds == null) {
            Timber.tag(logTag).e("Missing stream expire time")
            throw Exception("Missing stream expire time")
        }

        if (format == null) {
            Timber.tag(logTag).e("Could not find format")
            throw Exception("Could not find format")
        }

        if (streamUrl == null) {
            Timber.tag(logTag).e("Could not find stream url")
            throw Exception("Could not find stream url")
        }

        Timber.tag(logTag).d("Successfully obtained playback data with format: ${format.mimeType}, bitrate: ${format.bitrate}")

        // Extract fexp from serverAbrStreamingUrl and append to playback tracking URLs.
        // This mirrors SimpMusic's approach: the serverAbrStreamingUrl carries fexp params that
        // YouTube's playback tracking endpoint expects, without which playback stats recording
        // may fail.
        val fexp = streamPlayerResponse.streamingData
            ?.serverAbrStreamingUrl
            ?.let { url ->
                try {
                    val query = java.net.URI(url).query
                    query?.split("&")?.find { it.startsWith("fexp=") }?.substringAfter("=")
                } catch (e: Exception) { null }
            }
        if (fexp != null) {
            Timber.tag(logTag).d("Extracted fexp from serverAbrStreamingUrl: $fexp")
            val updatedTracking = playbackTracking?.copy(
                atrUrl = playbackTracking.atrUrl?.let {
                    it.baseUrl?.let { baseUrl ->
                        val sep = if ("?" in baseUrl) "&" else "?"
                        it.copy(baseUrl = "${baseUrl}${sep}fexp=$fexp")
                    }
                },
                videostatsPlaybackUrl = playbackTracking.videostatsPlaybackUrl?.let {
                    it.baseUrl?.let { baseUrl ->
                        val sep = if ("?" in baseUrl) "&" else "?"
                        it.copy(baseUrl = "${baseUrl}${sep}fexp=$fexp")
                    }
                },
            )
            if (updatedTracking != null) {
                playbackTracking = updatedTracking
            }
        }

        PlaybackData(
            audioConfig,
            videoDetails,
            playbackTracking,
            format,
            streamUrl,
            streamExpiresInSeconds,
            successClient?.streamHeaders().orEmpty(),
            successClient?.clientName ?: "unknown",
        )
    }.onFailure { e ->
        Timber.tag(logTag).e(e, "Playback resolution failed")
        PlaybackLogManager.log(PlaybackLogLevel.ERROR, "Playback failed", "${e::class.simpleName}: ${e.message}")
    }
    
    suspend fun playerResponseForMetadata(
        videoId: String,
        playlistId: String? = null,
    ): Result<PlayerResponse> {
        Timber.tag(logTag).d("Fetching metadata-only player response for videoId: $videoId using MAIN_CLIENT: ${MAIN_CLIENT.clientName}")
        val sts = CipherDeobfuscator.signatureTimestamp()
        return YouTube.player(videoId, playlistId, client = WEB_REMIX, signatureTimestamp = sts) 
            .onSuccess { Timber.tag(logTag).d("Successfully fetched metadata") }
            .onFailure { Timber.tag(logTag).e(it, "Failed to fetch metadata") }
    }

    private enum class NetworkQuality { EXCELLENT, GOOD, MODERATE, POOR }

    private fun getNetworkQuality(connectivityManager: ConnectivityManager): NetworkQuality {
        val activeNetwork = connectivityManager.activeNetwork ?: return NetworkQuality.GOOD
        val caps = connectivityManager.getNetworkCapabilities(activeNetwork) ?: return NetworkQuality.GOOD

        // Unmetered connections (WiFi/Ethernet) get the best quality
        if (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) {
            return NetworkQuality.EXCELLENT
        }

        // Metered — use bandwidth estimate to gauge speed
        val bandwidthKbps = caps.getLinkDownstreamBandwidthKbps()
        return when {
            bandwidthKbps >= 2000 -> NetworkQuality.GOOD   // fast mobile (5G/LTE)
            bandwidthKbps >= 500  -> NetworkQuality.MODERATE // moderate (3G/weak LTE)
            else                  -> NetworkQuality.POOR     // slow (2G/edge)
        }
    }

    private fun findFormat(
        playerResponse: PlayerResponse,
        audioQuality: AudioQuality,
        connectivityManager: ConnectivityManager,
    ): PlayerResponse.StreamingData.Format? {
        Timber.tag(logTag).d("Finding format with audioQuality: $audioQuality, network metered: ${connectivityManager.isActiveNetworkMetered}")

        val formats = playerResponse.streamingData?.adaptiveFormats
            ?.filter { it.isAudio && it.isOriginal } ?: return null

        val format = when (audioQuality) {
            AudioQuality.HIGH -> formats.maxByOrNull {
                it.bitrate + (if (it.mimeType.startsWith("audio/webm")) 10240 else 0)
            }
            AudioQuality.AUTO -> {
                val networkQuality = getNetworkQuality(connectivityManager)
                Timber.tag(logTag).d("Auto mode: network quality = $networkQuality")
                when (networkQuality) {
                    NetworkQuality.EXCELLENT, NetworkQuality.GOOD -> formats.maxByOrNull {
                        it.bitrate + (if (it.mimeType.startsWith("audio/webm")) 10240 else 0)
                    }
                    NetworkQuality.MODERATE -> {
                        // Target mid-range bitrates (64–160 kbps) to balance quality and bandwidth
                        val mid = formats.filter { it.bitrate in 64_000..160_000 }
                        mid.maxByOrNull { it.bitrate + (if (it.mimeType.startsWith("audio/webm")) 10240 else 0) }
                            ?: formats.maxByOrNull { it.bitrate }
                    }
                    NetworkQuality.POOR -> {
                        // Low bitrates only to avoid buffering on weak connections
                        val low = formats.filter { it.bitrate <= 80_000 }
                        low.maxByOrNull { it.bitrate + (if (it.mimeType.startsWith("audio/webm")) 10240 else 0) }
                            ?: formats.minByOrNull { it.bitrate }
                    }
                }
            }
        }

        if (format != null) {
            Timber.tag(logTag).d("Selected format: ${format.mimeType}, bitrate: ${format.bitrate}")
        } else {
            Timber.tag(logTag).d("No suitable audio format found")
        }

        return format
    }
    
    // Skip URL validation — ExoPlayer handles bad URLs quickly,
    // saving a HEAD request per client (~100-500ms per playback)
    // Removed getSignatureTimestampOrNull and SignatureTimestampResult as they are replaced by CipherDeobfuscator.signatureTimestamp()

    suspend fun findUrlOrNull(
        format: PlayerResponse.StreamingData.Format,
        videoId: String,
        playerResponse: PlayerResponse,
        skipNewPipe: Boolean = false
    ): String? {
        Timber.tag(logTag).d("Finding stream URL for format: ${format.mimeType}, videoId: $videoId, skipNewPipe: $skipNewPipe")

        
        if (!format.url.isNullOrEmpty()) {
            Timber.tag(logTag).d("Using URL from format directly")
            return format.url
        }

        
        val signatureCipher = format.signatureCipher ?: format.cipher
        if (!signatureCipher.isNullOrEmpty()) {
            Timber.tag(logTag).d("Format has signatureCipher, using custom deobfuscation")
            val customDeobfuscatedUrl = CipherDeobfuscator.deobfuscateStreamUrl(signatureCipher, videoId)
            if (customDeobfuscatedUrl != null) {
                Timber.tag(logTag).d("Stream URL obtained via custom cipher deobfuscation")
                return customDeobfuscatedUrl
            }
            Timber.tag(logTag).d("Custom cipher deobfuscation failed")
        }

        
        // Always try NewPipe signature deobfuscation - it doesn't need auth, it just applies the
        // cipher algorithm from player.js. This is critical for age-restricted and privately owned
        // tracks where skipNewPipe is true; without it we'd return null and every client would fail.
        val deobfuscatedUrl = NewPipeExtractor.getStreamUrl(format, videoId)
        if (deobfuscatedUrl != null) {
            Timber.tag(logTag).d("Stream URL obtained via NewPipe deobfuscation")
            return deobfuscatedUrl
        }

        // Skip the StreamInfo fallback for age-restricted/private content
        // (StreamInfo fetch may fail without auth for these)
        if (skipNewPipe) {
            Timber.tag(logTag).d("Skipping StreamInfo fallback for age-restricted/private content")
            return null
        }

        
        Timber.tag(logTag).d("Trying StreamInfo fallback for URL")
        val streamUrls = YouTube.getNewPipeStreamUrls(videoId)
        if (streamUrls.isNotEmpty()) {
            val streamUrl = streamUrls.find { it.first == format.itag }?.second
            if (streamUrl != null) {
                Timber.tag(logTag).d("Stream URL obtained from StreamInfo")
                return streamUrl
            }

            
            val audioStream = streamUrls.find { urlPair ->
                playerResponse.streamingData?.adaptiveFormats?.any {
                    it.itag == urlPair.first && it.isAudio
                } == true
            }?.second

            if (audioStream != null) {
                Timber.tag(logTag).d("Audio stream URL obtained from StreamInfo (different itag)")
                return audioStream
            }
        }

        Timber.tag(logTag).e("Failed to get stream URL")
        return null
    }

    // Rewrite the `cver` query param on a googlevideo stream URL to match the client version that
    // produced it (SmartTube's Player.applyClientVer). Mismatched cver is a common CDN 403 cause.
    private fun applyClientVersion(url: String, clientVersion: String): String {
        val regex = Regex("[?&]cver=[^&]*")
        if (!regex.containsMatchIn(url)) return url
        return url.replace(regex) { m ->
            val sep = m.value[0]
            "${sep}cver=${android.net.Uri.encode(clientVersion)}"
        }
    }

    fun forceRefreshForVideo(videoId: String) {
        Timber.tag(logTag).d("Force refreshing for videoId: $videoId")
    }
}


