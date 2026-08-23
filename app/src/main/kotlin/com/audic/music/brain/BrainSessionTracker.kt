package com.audic.music.brain

import android.util.Log
import com.audic.music.db.entities.BrainListeningSession
import com.audic.music.models.MediaMetadata

/**
 * Tracks listening sessions for the Audic Brain.
 *
 * Monitors playback state to detect:
 * - Track completions (listened through)
 * - Track skips (listened < 15s before next)
 * - Accumulated listening duration
 */
class BrainSessionTracker(
    private val brainDao: BrainDao
) {
    private val tag = "BrainSessionTracker"

    private var currentSession: BrainListeningSession? = null
    private var sessionStartTime: Long = 0L
    private var accumulatedDurationMs: Long = 0L
    private var isTracking: Boolean = false

    /**
     * Start tracking a new listening session for the given track.
     */
    suspend fun startSession(track: MediaMetadata) {
        // Finalize any previous session first
        finalizeSession(trackDurationMs = track.duration)

        sessionStartTime = System.currentTimeMillis()
        accumulatedDurationMs = 0L
        isTracking = true

        val session = BrainListeningSession(
            trackId = track.id,
            trackTitle = track.title,
            artistNames = track.artists.joinToString(", ") { it.name },
            startedAt = sessionStartTime,
            trackDurationMs = track.duration,
            source = when (track.source) {
                com.audic.music.models.QueueItemSource.AUDIC_BRAIN -> "brain"
                com.audic.music.models.QueueItemSource.USER -> "user"
            }
        )

        currentSession = session
        Log.d(tag, "Started session for: ${track.title}")
    }

    /**
     * Update the accumulated play duration.
     * Call this periodically while the track is playing.
     */
    fun updatePlayDuration(currentPositionMs: Long) {
        if (!isTracking) return
        accumulatedDurationMs = currentPositionMs
    }

    /**
     * Finalize the current session when a track ends or is skipped.
     */
    suspend fun finalizeSession(
        trackDurationMs: Int = -1,
        wasExplicitlySkipped: Boolean = false
    ) {
        val session = currentSession ?: return
        val now = System.currentTimeMillis()

        val wasSkipped = wasExplicitlySkipped ||
            (accumulatedDurationMs < BrainConstants.SKIP_THRESHOLD_MS && accumulatedDurationMs > 0)

        val wasCompleted = trackDurationMs > 0 &&
            accumulatedDurationMs >= trackDurationMs * 0.9  // listened to 90%+

        val finalizedSession = session.copy(
            endedAt = now,
            playDurationMs = accumulatedDurationMs,
            trackDurationMs = trackDurationMs,
            wasSkipped = wasSkipped,
            wasCompleted = wasCompleted
        )

        brainDao.insertSession(finalizedSession)

        if (wasSkipped) {
            Log.d(tag, "Session ended early (skip): ${session.trackTitle} - ${accumulatedDurationMs}ms")
        } else if (wasCompleted) {
            Log.d(tag, "Session completed: ${session.trackTitle} - ${accumulatedDurationMs}ms")
        } else {
            Log.d(tag, "Session ended: ${session.trackTitle} - ${accumulatedDurationMs}ms")
        }

        currentSession = null
        accumulatedDurationMs = 0L
        isTracking = false
    }

    /**
     * Cancel tracking without saving the session.
     */
    fun cancelTracking() {
        currentSession = null
        accumulatedDurationMs = 0L
        isTracking = false
    }

    val isCurrentlyTracking: Boolean get() = isTracking
    val currentTrackId: String? get() = currentSession?.trackId
}