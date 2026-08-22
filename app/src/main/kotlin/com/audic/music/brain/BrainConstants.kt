package com.audic.music.brain

/**
 * Audic Brain — Scoring weights and constants
 *
 * Adapted from FlowAlgorithmV2 (reference/legacy/FlowAlgorithmV2.kt)
 * for a music-focused on-device recommendation engine.
 */
object BrainConstants {

    // =====================
    // SCORING WEIGHTS
    // =====================

    // Source trust scores (where the candidate came from)
    const val SOURCE_VAULT = 60        // From the user's library/top songs
    const val SOURCE_ANCHOR = 50        // Related to the currently playing song (YouTube.next)
    const val SOURCE_MOMENTUM = 40      // Related to the previously playing song
    const val SOURCE_DISCOVERY = 20     // Random discovery / fallback

    // Interest matching max
    const val INTEREST_MAX_SCORE = 100
    const val ARTIST_AFFINITY_MULTIPLIER = 3.0
    const val ALBUM_AFFINITY_MULTIPLIER = 1.5
    const val GENRE_AFFINITY_MULTIPLIER = 2.0

    // Freshness
    const val FRESHNESS_MAX_SCORE = 30
    private const val FRESHNESS_DAYS_THRESHOLD_1 = 1
    private const val FRESHNESS_DAYS_THRESHOLD_7 = 7
    private const val FRESHNESS_DAYS_THRESHOLD_30 = 30

    // Diversity
    const val ARTIST_REPEAT_PENALTY = -25
    const val SOURCE_REPEAT_PENALTY = -10
    const val MAX_SAME_ARTIST_IN_TOP = 2

    // Recency activity boost
    const val RECENCY_24H_BOOST = 20
    const val RECENCY_3D_BOOST = 10

    // Time constants
    const val HOURS_24_MS = 24 * 60 * 60 * 1000L
    const val DAYS_3_MS = 3 * 24 * 60 * 60 * 1000L
    const val DAYS_7_MS = 7 * 24 * 60 * 60 * 1000L
    const val DAYS_30_MS = 30 * 24 * 60 * 60 * 1000L

    // Skip detection threshold
    const val SKIP_THRESHOLD_MS = 15_000L  // 15 seconds

    // Queue injection
    const val INJECTION_COUNT = 3
    const val INJECTION_BUFFER_MS = 1500L  // Wait 1.5s after track start

    // Background refresh
    const val REFRESH_INTERVAL_HOURS = 4L
}