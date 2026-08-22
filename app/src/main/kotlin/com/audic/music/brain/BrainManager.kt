package com.audic.music.brain

import android.util.Log
import com.audic.music.db.MusicDatabase
import com.audic.music.db.entities.BrainSuggestionLog
import com.audic.music.models.MediaMetadata
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

/**
 * Audic Brain Manager — the main orchestrator.
 *
 * Hooks into PlayerConnection to track listening behavior,
 * build interest profiles, generate recommendations, and
 * log suggestions for transparency.
 */
class BrainManager(
    private val database: MusicDatabase,
    private val brainDao: BrainDao,
    private val engine: BrainRecommendationEngine = BrainRecommendationEngine()
) {
    private val tag = "BrainManager"
    private val sessionTracker = BrainSessionTracker(brainDao)

    private var cachedProfile: BrainInterestProfile? = null
    private var profileLastBuiltAt: Long = 0L
    private val profileCacheTtlMs = 5 * 60 * 1000L

    private var recentlyPlayedIds = mutableListOf<String>()
    private var isEnabled: Boolean = true

    /**
     * Called by PlayerConnection when a track starts playing.
     */
    suspend fun onTrackStarted(track: MediaMetadata) {
        if (!isEnabled) return
        Log.d(tag, "Track started: ${track.title}")
        sessionTracker.startSession(track)
        recentlyPlayedIds.remove(track.id)
        recentlyPlayedIds.add(0, track.id)
        if (recentlyPlayedIds.size > 50) {
            recentlyPlayedIds = recentlyPlayedIds.take(50).toMutableList()
        }
    }

    /**
     * Called by PlayerConnection when the player position updates.
     */
    fun onPlayPositionUpdate(positionMs: Long) {
        if (!isEnabled) return
        sessionTracker.updatePlayDuration(positionMs)
    }
/**
     * Called by PlayerConnection when a track ends or is skipped.
     */
    suspend fun onTrackEnded(track: MediaMetadata?, wasSkipped: Boolean = false) {
        if (!isEnabled) return
        Log.d(tag, "Track ended: ${track?.title ?: "unknown"} (skipped=$wasSkipped)")
        sessionTracker.finalizeSession(
            trackDurationMs = track?.duration ?: -1,
            wasExplicitlySkipped = wasSkipped
        )
    }

    /**
     * Generate real-time recommendations for queue injection.
     */
    suspend fun generateRecommendations(
        currentTrack: MediaMetadata?,
        previousTrack: MediaMetadata?,
        alreadyQueuedIds: Set<String> = emptySet()
    ): List<ScoredTrack> {
        if (!isEnabled || currentTrack == null) return emptyList()

        val profile = getOrBuildProfile()
        val topSongs = database.topSongs(15).first()

        val scored = engine.scoreAndRank(
            anchorCandidates = emptyList(),
            momentumCandidates = emptyList(),
            topSongs = topSongs,
            profile = profile,
            recentlyPlayedTrackIds = recentlyPlayedIds.toSet(),
            alreadyQueuedIds = alreadyQueuedIds + currentTrack.id
        )

        return scored.take(BrainConstants.INJECTION_COUNT)
    }

    /**
     * Log a suggestion that was made.
     */
    suspend fun logSuggestion(scoredTrack: ScoredTrack) {
        if (!isEnabled) return
        val log = BrainSuggestionLog(
            trackId = scoredTrack.track.id,
            trackTitle = scoredTrack.track.title,
            artistNames = scoredTrack.track.artists.joinToString(", ") { it.name },
            suggestedAt = System.currentTimeMillis(),
            source = scoredTrack.source.name.lowercase(),
            flowScore = scoredTrack.flowScore,
            scoreReasons = scoredTrack.scoreReasons.joinToString(" | "),
            artistAffinityScore = 0f
        )
        brainDao.insertSuggestion(log)
    }

    /**
     * Mark a suggestion as accepted.
     */
    suspend fun markSuggestionAccepted(trackId: String) {
        Log.d(tag, "Suggestion accepted: $trackId")
    }

    /**
     * Get reasons why a track was recommended.
     */
    suspend fun getRecommendationReasons(trackId: String): List<String> {
        return withContext(Dispatchers.IO) {
            brainDao.getSuggestionsForTrack(trackId)
                .firstOrNull()?.scoreReasons
                ?.split(" | ")?.filter { it.isNotBlank() }
                ?: emptyList()
        }
    }

    /**
     * Get or build the interest profile (cached for 5 min).
     */
    private suspend fun getOrBuildProfile(): BrainInterestProfile {
        val now = System.currentTimeMillis()
        if (cachedProfile != null && (now - profileLastBuiltAt) < profileCacheTtlMs) {
            return cachedProfile!!
        }
        val topSongs = database.topSongs(30).first()
        val likedSongs = database.likedSongsByPlayTimeAsc().first()
        val profile = BrainInterestProfileBuilder.build(topSongs, likedSongs)
        cachedProfile = profile
        profileLastBuiltAt = now
        Log.d(tag, "Profile built: ${profile.artistAffinities.size} artists")
        return profile
    }

    val tracker: BrainSessionTracker get() = sessionTracker

    fun setEnabled(enabled: Boolean) {
        isEnabled = enabled
        if (!enabled) sessionTracker.cancelTracking()
        Log.d(tag, "Brain ${if (enabled) "enabled" else "disabled"}")
    }

    fun isEnabled(): Boolean = isEnabled
}