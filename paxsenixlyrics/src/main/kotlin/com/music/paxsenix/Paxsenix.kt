package com.music.paxsenix

import android.content.Context
import com.music.paxsenix.models.AppleMusicSearchResponse
import com.music.paxsenix.models.LyricsResponse
import com.music.paxsenix.models.SearchResponse
import com.music.paxsenix.models.SearchResult
import com.audic.music.betterlyrics.TTMLParser
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.defaultRequest
import io.ktor.client.plugins.ClientRequestException
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.statement.bodyAsText
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import timber.log.Timber
import java.util.Locale
import kotlin.math.abs

object Paxsenix {
    @Volatile
    private var client: HttpClient? = null
    private var appVersion: String = "Unknown"
    private val appleTokenManager = AppleTokenManager()

    fun init(context: Context) {
        if (client != null) return

        synchronized(this) {
            if (client != null) return

            appVersion = try {
                context.packageManager.getPackageInfo(context.packageName, 0).versionName
                    ?: "Unknown"
            } catch (e: Exception) {
                Timber.e(e, "Failed to get app version")
                "Unknown"
            }

            Timber.d("Initializing Paxsenix with version: $appVersion")

            client = HttpClient(CIO) {
                install(HttpTimeout) {
                    requestTimeoutMillis = 15_000
                    connectTimeoutMillis = 10_000
                }
                install(ContentNegotiation) {
                    json(
                        Json {
                            isLenient = true
                            ignoreUnknownKeys = true
                        },
                    )
                }

                defaultRequest {
                    url("https://lyrics.paxsenix.org")
                    header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.3")
                }

                expectSuccess = true
            }

            Timber.d("Paxsenix HTTP client initialized")
        }
    }

    private val httpClient: HttpClient
        get() = client ?: throw IllegalStateException("Paxsenix.init() must be called before using Paxsenix")

    private val titleCleanupPatterns = listOf(
        Regex("""\s*\(.*?(official|video|audio|lyrics|lyric|visualizer|hd|hq|4k|remaster|remix|live|acoustic|version|edit|extended|radio|clean|explicit).*?\)""", RegexOption.IGNORE_CASE),
        Regex("""\s*\[.*?(official|video|audio|lyrics|lyric|visualizer|hd|hq|4k|remaster|remix|live|acoustic|version|edit|extended|radio|clean|explicit).*?\]""", RegexOption.IGNORE_CASE),
        Regex("""\s*【.*?】"""),
        Regex("""\s*\|.*$"""),
        Regex("""\s*-\s*(official|video|audio|lyrics|lyric|visualizer).*$""", RegexOption.IGNORE_CASE),
        Regex("""\s*\(feat\..*?\)""", RegexOption.IGNORE_CASE),
        Regex("""\s*\(ft\..*?\)""", RegexOption.IGNORE_CASE),
        Regex("""\s*feat\..*$""", RegexOption.IGNORE_CASE),
        Regex("""\s*ft\..*$""", RegexOption.IGNORE_CASE),
        Regex("""\s*\([^)]*\d{4}[^)]*\)""", RegexOption.IGNORE_CASE),
    )

    private val artistSeparators = listOf(" & ", " and ", ", ", " x ", " X ", " feat. ", " feat ", " ft. ", " ft ", " featuring ", " with ")

    private fun cleanTitle(title: String): String {
        var cleaned = title.trim()
        for (pattern in titleCleanupPatterns) {
            cleaned = cleaned.replace(pattern, "")
        }
        return cleaned.trim()
    }

    private fun cleanArtist(artist: String): String {
        var cleaned = artist.trim()
        for (separator in artistSeparators) {
            if (cleaned.contains(separator, ignoreCase = true)) {
                cleaned = cleaned.split(separator, ignoreCase = true, limit = 2)[0]
                break
            }
        }
        return cleaned.trim()
    }

    private suspend fun search(query: String): List<SearchResult> = runCatching {
        Timber.d("Searching for: $query")

        // Use Apple Music catalog API to bypass Cloudflare on lyrics.paxsenix.org/apple-music/search
        val token = appleTokenManager.getToken()
        val appleResponse = httpClient.get("https://amp-api.music.apple.com/v1/catalog/us/search") {
            header("Authorization", "Bearer $token")
            header("Origin", "https://music.apple.com")
            header("Referer", "https://music.apple.com/")
            header("Accept-Language", "en-US,en;q=0.9")
            parameter("term", query)
            parameter("types", "songs")
            parameter("limit", "25")
        }.body<AppleMusicSearchResponse>()

        val results = mutableListOf<SearchResult>()
        val songIds = appleResponse.results.songs?.data?.map { it.id } ?: emptyList()
        if (songIds.isEmpty()) return@runCatching emptyList()

        val resources = appleResponse.resources

        songIds.forEach { id ->
            val detail = resources?.songs?.get(id)
            if (detail != null) {
                results.add(
                    SearchResult(
                        id = id,
                        trackName = detail.attributes.name,
                        artistName = detail.attributes.artistName,
                        albumName = detail.attributes.albumName,
                        duration = detail.attributes.durationInMillis?.div(1000)?.toInt(),
                        artwork = detail.attributes.artwork?.url
                    )
                )
            }
        }

        Timber.d("Search results count: ${results.size}")
        results
    }.onFailure { e ->
        if (e is ClientRequestException && e.response.status.value == 401) {
            Timber.d("Token expired, clearing and retrying")
            appleTokenManager.clearToken()
        } else {
            Timber.e(e, "Apple Music search error: ${e.message}")
        }
    }.getOrDefault(emptyList())

    suspend fun getLyrics(
        title: String,
        artist: String,
        duration: Int,
        album: String? = null,
    ): Result<String> = runCatching {
        val cleanedTitle = cleanTitle(title)
        val cleanedArtist = cleanArtist(artist)

        Timber.d("getLyrics: title='$title', artist='$artist', duration=$duration")

        val searchQueries = buildList {
            add("$cleanedTitle $cleanedArtist")
            add(cleanedTitle)
            if (!album.isNullOrBlank()) {
                add("$cleanedTitle $cleanedArtist $album")
            }
        }

        var allResults: List<Pair<SearchResult, Double>> = emptyList()

        for (query in searchQueries) {
            if (allResults.isEmpty()) {
                val searchResults = search(query)
                if (searchResults.isNotEmpty()) {
                    allResults = scoreAndFilterResults(searchResults, title, artist, duration)
                }
            }
        }

        if (allResults.isEmpty()) {
            Timber.w("No tracks found for any query")
            throw IllegalStateException("No tracks found on Paxsenix")
        }

        // Fetch top candidates in parallel; return the first word-timed result,
        // or the best plain/line-synced result if none have word timings.
        val candidates = allResults.take(5)
        val scope = CoroutineScope(Dispatchers.IO)
        val jobs = candidates.map { (result, score) ->
            scope.async {
                Timber.d("Fetching (parallel): ${result.displayName} (ID: ${result.id}, score: $score)")
                result to runCatching { fetchLyricsForTrackWithType(result.id) }.getOrDefault("" to false)
            }
        }

        var plainFallback: String? = null
        try {
            val remaining = jobs.toMutableList()
            while (remaining.isNotEmpty()) {
                val (_, lrcPair) = select {
                    remaining.forEach { deferred -> deferred.onAwait { it } }
                }
                remaining.removeAll { it.isCompleted }
                val (lrc, hasWordTimings) = lrcPair
                if (lrc.isNotEmpty()) {
                    if (hasWordTimings) {
                        return Result.success(lrc)
                    } else if (plainFallback == null) {
                        plainFallback = lrc
                    }
                }
            }
        } finally {
            scope.coroutineContext.cancelChildren()
        }

        plainFallback?.let {
            Timber.d("Using Paxsenix lyrics without word-level sync")
            return Result.success(it)
        }
        Timber.w("No lyrics content from Paxsenix for matched tracks")
        return Result.failure(IllegalStateException("No lyrics available from Paxsenix"))
    }

    private fun scoreAndFilterResults(
        results: List<SearchResult>,
        title: String,
        artist: String,
        duration: Int
    ): List<Pair<SearchResult, Double>> {
        val durationMs = duration * 1000
        val cleanupRegex = Regex("""\s*\(.*?\)|\s*\[.*?\]""")

        val cleanedTitle = title.replace(cleanupRegex, "").lowercase().trim()
        val cleanedArtist = cleanArtist(artist).lowercase()

        val targetIsMixed = title.contains("mixed", ignoreCase = true)
        val targetIsRemix = title.contains("remix", ignoreCase = true)

        return results.map { result ->
            var score = 0.0

            result.duration?.let { d ->
                val diff = abs(d - durationMs)
                when {
                    diff <= 2000 -> score += 100
                    diff <= 5000 -> score += 50
                    diff <= 10000 -> score += 10
                    else -> score -= 50
                }
            }

            val resultTitleCleaned = result.displayName.replace(cleanupRegex, "").lowercase().trim()
            when {
                resultTitleCleaned == cleanedTitle -> score += 80
                resultTitleCleaned.contains(cleanedTitle) || cleanedTitle.contains(resultTitleCleaned) -> score += 40
            }

            val resultIsMixed = result.displayName.contains("mixed", ignoreCase = true)
            val resultIsRemix = result.displayName.contains("remix", ignoreCase = true)
            if (resultIsMixed && !targetIsMixed) score -= 60
            if (resultIsRemix && !targetIsRemix) score -= 40

            val resultArtistLower = result.displayArtist.lowercase()
            when {
                resultArtistLower.contains(cleanedArtist) -> score += 50
                else -> {
                    val artistWords = cleanedArtist.split(Regex("\\s+")).filter { it.length > 2 }
                    if (artistWords.any { resultArtistLower.contains(it) }) score += 25
                }
            }

            result to score
        }.sortedByDescending { it.second }.filter { it.second > 0 }.take(10)
    }

    private suspend fun fetchLyricsForTrackWithType(id: String): Pair<String, Boolean> {
        val result = fetchLyricsForTrack(id)
        if (result.isSuccess) {
            val lrc = result.getOrNull()!!
            val hasWordTimings = lrc.contains("<") && lrc.contains(">")
            return lrc to hasWordTimings
        }
        return "" to false
    }

    private suspend fun fetchLyricsForTrack(id: String): Result<String> = runCatching {
        Timber.d("Fetching lyrics for track ID: $id")

        val response = httpClient.get("/apple-music/lyrics") {
            parameter("id", id)
        }.body<LyricsResponse>()

        val lyricsType = response.type
        Timber.d("Lyrics response: type=$lyricsType")

        if (!response.ttmlContent.isNullOrBlank()) {
            val lrc = convertTTMLToAppFormat(response.ttmlContent)
            if (lrc.isNotEmpty()) {
                Timber.d("Generated LRC from ttmlContent using TTMLParser")
                return@runCatching lrc
            }
        }

        if (!response.elrcMultiPerson.isNullOrBlank()) {
            Timber.d("Using elrcMultiPerson as fallback")
            return@runCatching response.elrcMultiPerson
        }
        if (!response.elrc.isNullOrBlank()) {
            Timber.d("Using elrc as fallback")
            return@runCatching response.elrc
        }
        if (!response.plain.isNullOrBlank()) {
            Timber.d("Using plain lyrics field")
            return@runCatching response.plain
        }

        if (response.content.isEmpty()) {
            throw IllegalStateException("No lyrics found")
        }

        val hasWordLevel = lyricsType == "Syllable"
        Timber.d("Using content array as source, hasWordLevel=$hasWordLevel")

        if (!hasWordLevel) {
            val plain = response.content
                .map { line -> line.text.joinToString(" ") { it.text } }
                .filter { it.isNotBlank() }
                .joinToString("\n")
            return@runCatching plain
        }

        val lrc = buildString {
            response.content.forEach { line ->
                val timeMs = line.timestamp
                val minutes = timeMs / 1000 / 60
                val seconds = (timeMs / 1000) % 60
                val centiseconds = (timeMs % 1000) / 10

                val agent = when {
                    line.background -> "{bg}"
                    line.oppositeTurn -> "{agent:v2}"
                    else -> "{agent:v1}"
                }

                val lineText = line.text.joinToString(" ") { it.text }

                if (lineText.isNotBlank()) {
                    appendLine(String.format(Locale.US, "[%02d:%02d.%02d]%s%s", minutes, seconds, centiseconds, agent, lineText))
                    if (line.text.isNotEmpty()) {
                        val wordsData = line.text.joinToString("|") { word ->
                            "${word.text}:${word.timestamp.toDouble() / 1000}:${word.endtime.toDouble() / 1000}"
                        }
                        if (wordsData.isNotEmpty()) appendLine("<$wordsData>")
                    }
                }
            }
        }

        return@runCatching lrc
    }

    suspend fun getAllLyrics(
        title: String,
        artist: String,
        duration: Int,
        album: String? = null,
        callback: (String) -> Unit,
    ) {
        val cleanedTitle = cleanTitle(title)
        val cleanedArtist = cleanArtist(artist)

        val searchQueries = listOf("$cleanedTitle $cleanedArtist", cleanedTitle)
        var plainFallback: String? = null
        var scoredResults: List<Pair<SearchResult, Double>> = emptyList()

        for (query in searchQueries) {
            val results = search(query)
            if (results.isEmpty()) continue
            val filtered = scoreAndFilterResults(results, title, artist, duration)
            if (filtered.isNotEmpty()) {
                scoredResults = filtered
                break
            }
        }

        // Fetch top 3 candidates in parallel; deliver word-timed result first.
        val candidates = scoredResults.take(3)
        val scope = CoroutineScope(Dispatchers.IO)
        val jobs = candidates.map { (result, _) ->
            scope.async {
                Timber.d("Fetching (parallel/all): ${result.displayName}")
                runCatching { fetchLyricsForTrackWithType(result.id) }.getOrDefault("" to false)
            }
        }
        try {
            val remaining = jobs.toMutableList()
            while (remaining.isNotEmpty()) {
                val (lrc, hasWordTimings) = select {
                    remaining.forEach { deferred -> deferred.onAwait { it } }
                }
                remaining.removeAll { it.isCompleted }
                if (lrc.isNotEmpty()) {
                    if (hasWordTimings) {
                        callback(lrc)
                        return
                    } else if (plainFallback == null) {
                        plainFallback = lrc
                    }
                }
            }
        } finally {
            scope.coroutineContext.cancelChildren()
        }

        plainFallback?.let {
            Timber.d("Offering plain/non-synced lyrics as fallback")
            callback(it)
        }
    }

    private fun convertTTMLToAppFormat(ttml: String): String {
        return try {
            val parsedLines = TTMLParser.parseTTML(ttml)
            TTMLParser.toLRC(parsedLines)
        } catch (e: Exception) {
            Timber.e(e, "TTML conversion failed: ${e.message}")
            ""
        }
    }

    /**
     * Manages Apple Music API tokens by scraping them from beta.music.apple.com's JS bundle.
     * Tokens are cached until they expire (401 response).
     */
    private class AppleTokenManager {
        private var cachedToken: String? = null
        private val mutex = Mutex()

        suspend fun getToken(): String = mutex.withLock {
            cachedToken?.let { return it }

            try {
                val mainPageResponse = httpClient.get("https://beta.music.apple.com")
                val mainPageBody = mainPageResponse.bodyAsText()

                val indexJsRegex = Regex("""/assets/index~[^/]+\.js""")
                val indexJsMatch = indexJsRegex.find(mainPageBody)
                    ?: throw Exception("Could not find index JS URL")

                val indexJsUri = indexJsMatch.value

                val indexJsResponse = httpClient.get("https://beta.music.apple.com$indexJsUri")
                val indexJsBody = indexJsResponse.bodyAsText()

                val tokenRegex = Regex("""eyJ[A-Za-z0-9\-_=]+\.[A-Za-z0-9\-_=]+\.[A-Za-z0-9\-_=]+""")
                val tokenMatch = tokenRegex.find(indexJsBody)
                    ?: throw Exception("Could not find token")

                val token = tokenMatch.value
                cachedToken = token
                Timber.d("Fetched new Apple Music token")
                return token
            } catch (e: Exception) {
                Timber.e(e, "Error fetching Apple Music token")
                throw Exception("Error fetching Apple Music token: ${e.message}", e)
            }
        }

        fun clearToken() {
            cachedToken = null
            Timber.d("Cleared cached Apple Music token")
        }
    }
}
