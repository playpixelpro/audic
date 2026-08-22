package com.audic.music.db.entities

import androidx.compose.runtime.Immutable
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Records a listening session for the Audic Brain.
 * A session starts when a track begins playing and ends when it transitions,
 * is paused, or the track is skipped.
 */
@Immutable
@Entity(tableName = "brain_listening_session")
data class BrainListeningSession(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val trackId: String,
    val trackTitle: String,
    val artistNames: String,         // comma-separated
    val startedAt: Long,             // epoch millis
    val endedAt: Long? = null,       // epoch millis, null if still playing
    val playDurationMs: Long = 0,    // actual listened duration
    val trackDurationMs: Int = -1,   // total track duration
    val wasSkipped: Boolean = false, // true if skipped before threshold
    val wasCompleted: Boolean = false, // true if listened to completion
    val source: String = "user"      // "user", "brain", "radio"
)