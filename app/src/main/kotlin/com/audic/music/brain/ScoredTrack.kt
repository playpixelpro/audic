package com.audic.music.brain

import com.audic.music.models.MediaMetadata

/**
 * Represents a track scored by the Audic Brain engine.
 */
data class ScoredTrack(
    val track: MediaMetadata,
    val source: TrackSource,
    val baseScore: Int = 0,
    val flowScore: Int = 0,
    val scoreReasons: List<String> = emptyList(),
    val fetchedAt: Long = System.currentTimeMillis()
) : Comparable<ScoredTrack> {
    
    val totalScore: Int
        get() = baseScore + flowScore

    override fun compareTo(other: ScoredTrack): Int {
        return other.totalScore.compareTo(this.totalScore)
    }
}

/**
 * Where a track recommendation came from.
 */
enum class TrackSource {
    VAULT,              // From the user's local library
    ANCHOR,             // Related to the currently playing song
    MOMENTUM,           // Related to the previously playing song
    DISCOVERY           // Random discovery / fallback
}