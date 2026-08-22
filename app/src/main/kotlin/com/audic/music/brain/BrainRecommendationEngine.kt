package com.audic.music.brain

import android.util.Log
import com.audic.music.db.entities.Song
import com.audic.music.models.MediaMetadata
import com.audic.music.models.toMediaMetadata
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Core recommendation engine for the Audic Brain.
 *
 * Gathers candidate tracks from three sources (Anchor, Momentum, Vault),
 * scores them using multi-signal ranking, and returns the top picks.
 *
 * Adapted from FlowAlgorithmV2 (reference/legacy/FlowAlgorithmV2.kt)
 */
class BrainRecommendationEngine {

    private val tag = "BrainEngine"

    suspend fun scoreAndRank(
        anchorCandidates: List<ScoredTrack> = emptyList(),
        momentumCandidates: List<ScoredTrack> = emptyList(),
        topSongs: List<Song> = emptyList(),
        profile: BrainInterestProfile,
        recentlyPlayedTrackIds: Set<String> = emptySet(),
        alreadyQueuedIds: Set<String> = emptySet(),
        currentTime: Long = System.currentTimeMillis()
    ): List<ScoredTrack> = withContext(Dispatchers.Default) {

        val candidates = mutableListOf<ScoredTrack>()
        val seenIds = mutableSetOf<String>()

        // 1. Gather candidates from all sources
        candidates.addAll(anchorCandidates)
        candidates.addAll(momentumCandidates)

        // Add vault candidates (top songs from library)
        topSongs.forEach { song ->
            if (song.song.id !in alreadyQueuedIds && song.song.id !in seenIds) {
                seenIds.add(song.song.id)
                candidates.add(
                    ScoredTrack(
                        track = song.toMediaMetadata(),
                        source = TrackSource.VAULT,
                        baseScore = BrainConstants.SOURCE_VAULT
                    )
                )
            }
        }

        Log.d(tag, "Gathered ${candidates.size} unique candidates")
        Log.d(tag, "Profile: ${profile.artistAffinities.size} artists, ${profile.albumAffinities.size} albums")

        if (candidates.isEmpty()) return@withContext emptyList()

        // 2. Score each candidate
        val scored = scoreCandidates(candidates, profile, recentlyPlayedTrackIds, currentTime)

        // 3. Apply diversity penalties
        val diversified = applyDiversityPenalties(scored)

        // 4. Sort by final score descending
        diversified.sortedByDescending { it.flowScore }
    }

    fun lightShuffle(videos: List<ScoredTrack>, bucketSize: Int = 3): List<ScoredTrack> {
        if (videos.size <= bucketSize) return videos.shuffled()
        val result = mutableListOf<ScoredTrack>()
        videos.chunked(bucketSize).forEach { bucket ->
            result.addAll(bucket.shuffled())
        }
        return result
    }

    /**
     * Internal scoring logic - scores candidates against user profile.
     */
    private suspend fun scoreCandidates(
        candidates: List<ScoredTrack>,
        profile: BrainInterestProfile,
        recentlyPlayedTrackIds: Set<String>,
        currentTime: Long
    ): List<ScoredTrack> = withContext(Dispatchers.Default) {
        candidates.map { candidate ->
            val reasons = mutableListOf<String>()
            var score = candidate.baseScore

            // Interest matching score
            val matchScore = BrainInterestProfileBuilder.calculateMatchingScore(
                candidate.track, profile
            )
            score += matchScore
            when {
                matchScore > 50 -> reasons.add("strong artist match")
                matchScore > 20 -> reasons.add("artist match")
            }

            // Freshness
            val freshnessScore = calculateFreshnessScore(candidate, currentTime)
            score += freshnessScore
            if (freshnessScore > 0) reasons.add("fresh track")

            // Recency boost
            if (candidate.track.id in recentlyPlayedTrackIds) {
                score += BrainConstants.RECENCY_24H_BOOST
                reasons.add("recently played")
            }

            ScoredTrack(
                track = candidate.track,
                source = candidate.source,
                baseScore = candidate.baseScore,
                flowScore = score,
                scoreReasons = reasons,
                fetchedAt = currentTime
            )
        }
    }

    /**
     * Apply diversity penalties to prevent too many tracks from the same artist.
     */
    private fun applyDiversityPenalties(scored: List<ScoredTrack>): List<ScoredTrack> {
        val artistCounts = mutableMapOf<String, Int>()
        return scored.sortedByDescending { it.flowScore }.map { scoredTrack ->
            var finalScore = scoredTrack.flowScore
            scoredTrack.track.artists.forEach { artist ->
                artist.id?.let { artistId ->
                    val count = artistCounts.getOrDefault(artistId, 0) + 1
                    artistCounts[artistId] = count
                    if (count > 1) {
                        finalScore += BrainConstants.ARTIST_REPEAT_PENALTY
                    }
                }
            }
            scoredTrack.copy(
                flowScore = finalScore,
                scoreReasons = scoredTrack.scoreReasons +
                    if (finalScore < scoredTrack.flowScore) listOf("diversity adjustment") else emptyList()
            )
        }
    }

    /**
     * Calculate freshness score based on how recently the track was added.
     */
    private fun calculateFreshnessScore(candidate: ScoredTrack, currentTime: Long): Int {
        if (candidate.source != TrackSource.VAULT) return 0
        return BrainConstants.FRESHNESS_MAX_SCORE / 2
    }
}