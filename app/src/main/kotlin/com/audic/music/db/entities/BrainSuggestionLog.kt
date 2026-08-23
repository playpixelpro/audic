package com.audic.music.db.entities

import androidx.compose.runtime.Immutable
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Records a recommendation/suggestion made by the Audic Brain.
 * Used for analytics and "Why this song?" transparency.
 */
@Immutable
@Entity(tableName = "brain_suggestion_log")
data class BrainSuggestionLog(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val trackId: String,
    val trackTitle: String,
    val artistNames: String,
    val suggestedAt: Long,            // epoch millis
    val source: String,               // "anchor", "momentum", "vault", "discovery"
    val flowScore: Int,               // final score
    val scoreReasons: String,         // pipe-separated list of reasons
    val artistAffinityScore: Float,
    val wasAccepted: Boolean = false,  // user listened for > 30s
    val wasSkipped: Boolean = false    // user skipped
)