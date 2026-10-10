package com.audic.music.utils.playback

import com.audic.music.utils.rejectionKey
import com.music.innertube.models.YouTubeClient
import com.music.innertube.models.YouTubeClient.Companion.ANDROID_CREATOR
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

/**
 * Data-driven playback client catalog, adapted from InnerTubeX's `PlaybackClientCatalog`
 * (a *basis*, not a copy: only the fields needed by audic's ExoPlayer-based player are kept).
 *
 * It replaces the previous hard-coded `STREAM_FALLBACK_CLIENTS` array and the ad-hoc
 * login/age/SABR checks scattered through `YTPlayerUtils.resolvePlaybackData`, so client
 * ordering and eligibility live in one place.
 */

/** How a PoToken must be bound for a given request kind. */
enum class PoTokenBinding { NONE, VIDEO_ID, VISITOR_DATA }

enum class PoTokenRequirement { NOT_REQUIRED, OPTIONAL, REQUIRED }

/**
 * Per-client PoToken requirement/binding. Mirrors InnerTubeX where "current Web enforcement is
 * GVS-only": the stream URL (`pot=`) requires a VIDEO_ID-bound token, while the /player request
 * does not strictly require one for the web music client.
 */
data class PoTokenRule(
    val requirement: PoTokenRequirement = PoTokenRequirement.NOT_REQUIRED,
    val binding: PoTokenBinding = PoTokenBinding.NONE,
) {
    val isUsed: Boolean get() = requirement != PoTokenRequirement.NOT_REQUIRED
}

/** Playback transport the client can serve. audic's ExoPlayer path can only consume DIRECT urls. */
enum class PlaybackTransport { DIRECT, SABR }

enum class CapabilitySupport { SUPPORTED, LIMITED, UNSUPPORTED }

/** What content kinds a client can play. Used to filter the ladder for restricted media. */
data class ClientContentCapabilities(
    val ageRestricted: CapabilitySupport = CapabilitySupport.SUPPORTED,
    val kids: CapabilitySupport = CapabilitySupport.SUPPORTED,
    /** Privately-owned / uploaded tracks (musicVideoType = MUSIC_VIDEO_TYPE_PRIVATELY_OWNED_TRACK). */
    val uploads: CapabilitySupport = CapabilitySupport.SUPPORTED,
)

enum class AuthenticationPolicy { UNSUPPORTED, OPTIONAL, REQUIRED }

data class PlaybackClientManifest(
    val id: String,
    val client: YouTubeClient,
    val displayName: String,
    /** Higher = tried earlier. */
    val priority: Int,
    val transports: Set<PlaybackTransport> = setOf(PlaybackTransport.DIRECT),
    val content: ClientContentCapabilities = ClientContentCapabilities(),
    val authentication: AuthenticationPolicy = AuthenticationPolicy.UNSUPPORTED,
    val playerPoToken: PoTokenRule = PoTokenRule(),
    val gvsPoToken: PoTokenRule = PoTokenRule(),
    /**
     * The CDN serves this client's media only when the request carries a bounded `Range`
     * (open-ended reads return 403/416). Adapted from InnerTubeX's `requiresBoundedMediaRange`.
     */
    val requireBoundedRange: Boolean = false,
    /** Max bytes per media read once [requireBoundedRange] applies (0 = unbounded/no chunking). */
    val rangeChunkSizeBytes: Long = 0L,
)

/** Resolved range policy for a client's stream URL. */
data class PlaybackRangeRule(
    val requireBoundedRange: Boolean,
    val rangeChunkSizeBytes: Long,
)

/** Content-specific hints that influence which clients are eligible. */
data class PlaybackContentHints(
    val isAgeRestricted: Boolean = false,
    val isUploaded: Boolean = false,
    val isKids: Boolean = false,
    val isLoggedIn: Boolean = false,
)

object PlaybackClientCatalog {
    private const val CHUNK_512K = 512L * 1024L
    private const val CHUNK_1M = 1024L * 1024L

    private val webGvsRequired = PoTokenRule(PoTokenRequirement.REQUIRED, PoTokenBinding.VIDEO_ID)
    private val webPlayerOptional = PoTokenRule(PoTokenRequirement.OPTIONAL, PoTokenBinding.VISITOR_DATA)
    private val noToken = PoTokenRule()

    val manifests: List<PlaybackClientManifest> = listOf(
        // Web music is the primary client: returns direct/signatureCipher URLs and needs a
        // video-bound GVS token (see PoTokenRule doc above).
        manifest(
            id = "WEB_REMIX", client = WEB_REMIX, displayName = "YouTube Music web", priority = 100,
            content = ClientContentCapabilities(
                ageRestricted = CapabilitySupport.LIMITED,
                // Uploaded / privately-owned tracks previously skipped the web client entirely.
                uploads = CapabilitySupport.UNSUPPORTED,
            ),
            authentication = AuthenticationPolicy.OPTIONAL,
            playerPoToken = webPlayerOptional, gvsPoToken = webGvsRequired,
        ),
        manifest(
            id = "VISIONOS", client = VISIONOS, displayName = "visionOS", priority = 90,
            content = ClientContentCapabilities(
                ageRestricted = CapabilitySupport.UNSUPPORTED,
                kids = CapabilitySupport.UNSUPPORTED,
                uploads = CapabilitySupport.UNSUPPORTED,
            ),
        ),
        // Embedded TV player bypasses age-restriction for guests and serves direct URLs, but the
        // CDN requires bounded/chunked ranges for it.
        manifest(
            id = "TVHTML5_SIMPLY", client = TVHTML5_SIMPLY_EMBEDDED_PLAYER, displayName = "TV embedded", priority = 85,
            content = ClientContentCapabilities(ageRestricted = CapabilitySupport.SUPPORTED),
            requireBoundedRange = true, rangeChunkSizeBytes = CHUNK_512K,
        ),
        manifest(
            id = "ANDROID_VR", client = ANDROID_VR, displayName = "Android VR", priority = 80,
            content = ClientContentCapabilities(
                ageRestricted = CapabilitySupport.UNSUPPORTED,
                kids = CapabilitySupport.UNSUPPORTED,
            ),
            requireBoundedRange = true, rangeChunkSizeBytes = CHUNK_512K,
        ),
        manifest(
            id = "ANDROID_VR_1_43_32", client = ANDROID_VR_1_43_32, displayName = "Android VR 1.43", priority = 78,
            content = ClientContentCapabilities(
                ageRestricted = CapabilitySupport.UNSUPPORTED,
                kids = CapabilitySupport.UNSUPPORTED,
            ),
            requireBoundedRange = true, rangeChunkSizeBytes = CHUNK_512K,
        ),
        manifest(
            id = "ANDROID_VR_NO_AUTH", client = ANDROID_VR_NO_AUTH, displayName = "Android VR no-auth", priority = 76,
            content = ClientContentCapabilities(
                ageRestricted = CapabilitySupport.UNSUPPORTED,
                kids = CapabilitySupport.UNSUPPORTED,
            ),
            requireBoundedRange = true, rangeChunkSizeBytes = CHUNK_512K,
        ),
        manifest(
            id = "ANDROID_CREATOR", client = ANDROID_CREATOR, displayName = "Android creator", priority = 70,
            content = ClientContentCapabilities(ageRestricted = CapabilitySupport.LIMITED),
            authentication = AuthenticationPolicy.OPTIONAL,
        ),
        manifest(
            id = "WEB", client = WEB, displayName = "YouTube web", priority = 60,
            content = ClientContentCapabilities(
                ageRestricted = CapabilitySupport.LIMITED,
                uploads = CapabilitySupport.UNSUPPORTED,
            ),
            authentication = AuthenticationPolicy.OPTIONAL,
            playerPoToken = webPlayerOptional, gvsPoToken = webGvsRequired,
        ),
        manifest(
            id = "ANDROID_TESTSUITE", client = ANDROID_TESTSUITE, displayName = "Android testsuite", priority = 50,
            content = ClientContentCapabilities(ageRestricted = CapabilitySupport.UNSUPPORTED),
        ),
        manifest(
            id = "IOS", client = IOS, displayName = "iOS", priority = 45,
            content = ClientContentCapabilities(ageRestricted = CapabilitySupport.LIMITED),
            requireBoundedRange = true, rangeChunkSizeBytes = CHUNK_1M,
        ),
        manifest(
            id = "IPADOS", client = IPADOS, displayName = "iPadOS", priority = 44,
            content = ClientContentCapabilities(ageRestricted = CapabilitySupport.LIMITED),
            requireBoundedRange = true, rangeChunkSizeBytes = CHUNK_1M,
        ),
        manifest(
            id = "ANDROID", client = MOBILE, displayName = "Android", priority = 43,
            content = ClientContentCapabilities(ageRestricted = CapabilitySupport.LIMITED),
            authentication = AuthenticationPolicy.OPTIONAL,
        ),
        // Login-gated TV clients last: skipped entirely for guests by the selector.
        manifest(
            id = "WEB_CREATOR", client = WEB_CREATOR, displayName = "YouTube Studio web", priority = 30,
            content = ClientContentCapabilities(ageRestricted = CapabilitySupport.SUPPORTED),
            authentication = AuthenticationPolicy.REQUIRED,
            playerPoToken = webPlayerOptional, gvsPoToken = webGvsRequired,
        ),
        manifest(
            id = "TVHTML5", client = TVHTML5, displayName = "TV HTML5", priority = 20,
            content = ClientContentCapabilities(ageRestricted = CapabilitySupport.SUPPORTED),
            authentication = AuthenticationPolicy.REQUIRED,
            playerPoToken = webPlayerOptional, gvsPoToken = webGvsRequired,
        ),
    )

    private val byKey: Map<String, PlaybackClientManifest> =
        manifests.associateBy { it.client.rejectionKey() }

    fun manifestFor(client: YouTubeClient): PlaybackClientManifest? = byKey[client.rejectionKey()]

    fun gvsPoTokenRule(client: YouTubeClient): PoTokenRule = manifestFor(client)?.gvsPoToken ?: noToken

    fun playerPoTokenRule(client: YouTubeClient): PoTokenRule = manifestFor(client)?.playerPoToken ?: noToken

    /** Range/bounded-read policy for a client's stream URL. */
    fun rangeRule(client: YouTubeClient): PlaybackRangeRule {
        val manifest = manifestFor(client) ?: return PlaybackRangeRule(false, 0L)
        return PlaybackRangeRule(
            requireBoundedRange = manifest.requireBoundedRange,
            rangeChunkSizeBytes = if (manifest.requireBoundedRange) manifest.rangeChunkSizeBytes else 0L,
        )
    }

    private fun manifest(
        id: String,
        client: YouTubeClient,
        displayName: String,
        priority: Int,
        transports: Set<PlaybackTransport> = setOf(PlaybackTransport.DIRECT),
        content: ClientContentCapabilities = ClientContentCapabilities(),
        authentication: AuthenticationPolicy = AuthenticationPolicy.UNSUPPORTED,
        playerPoToken: PoTokenRule = noToken,
        gvsPoToken: PoTokenRule = noToken,
        requireBoundedRange: Boolean = false,
        rangeChunkSizeBytes: Long = 0L,
    ) = PlaybackClientManifest(
        id = id,
        client = client,
        displayName = displayName,
        priority = priority,
        transports = transports,
        content = content,
        authentication = authentication,
        playerPoToken = playerPoToken,
        gvsPoToken = gvsPoToken,
        requireBoundedRange = requireBoundedRange,
        rangeChunkSizeBytes = rangeChunkSizeBytes,
    )
}

/**
 * Selects the ordered list of clients to attempt, adapted from InnerTubeX's
 * `ContentAwareFallbackStrategy`. Eligible clients are filtered by content capabilities,
 * authentication and health (rejections), then ordered by priority.
 */
object PlaybackClientSelector {
    fun select(
        hints: PlaybackContentHints,
        isRejected: (YouTubeClient) -> Boolean = { false },
        catalog: List<PlaybackClientManifest> = PlaybackClientCatalog.manifests,
    ): List<YouTubeClient> =
        catalog
            .asSequence()
            .filter { it.transports.contains(PlaybackTransport.DIRECT) }
            .filter { it.isEligible(hints) }
            .filterNot { isRejected(it.client) }
            .sortedByDescending { it.priority }
            .map { it.client }
            .toList()

    private fun PlaybackClientManifest.isEligible(hints: PlaybackContentHints): Boolean {
        if (hints.isLoggedIn.not() && authentication == AuthenticationPolicy.REQUIRED) return false
        if (hints.isAgeRestricted && content.ageRestricted == CapabilitySupport.UNSUPPORTED) return false
        if (hints.isUploaded && content.uploads == CapabilitySupport.UNSUPPORTED) return false
        if (hints.isKids && content.kids == CapabilitySupport.UNSUPPORTED) return false
        return true
    }
}
