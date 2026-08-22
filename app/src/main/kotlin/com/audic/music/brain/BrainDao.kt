package com.audic.music.brain

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.audic.music.db.entities.BrainListeningSession
import com.audic.music.db.entities.BrainSuggestionLog
import kotlinx.coroutines.flow.Flow

/**
 * DAO for Audic Brain tracking tables.
 */
@Dao
interface BrainDao {

    // ── Listening Sessions ──

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSession(session: BrainListeningSession)

    @Query("SELECT * FROM brain_listening_session ORDER BY startedAt DESC LIMIT :limit")
    suspend fun getRecentSessions(limit: Int = 50): List<BrainListeningSession>

    @Query("SELECT * FROM brain_listening_session WHERE trackId = :trackId ORDER BY startedAt DESC")
    suspend fun getSessionsForTrack(trackId: String): List<BrainListeningSession>

    @Query("SELECT COUNT(*) FROM brain_listening_session WHERE wasSkipped = 1 AND startedAt > :sinceEpoch")
    suspend fun getSkipCountSince(sinceEpoch: Long): Int

    @Query("SELECT COUNT(*) FROM brain_listening_session WHERE wasCompleted = 1 AND startedAt > :sinceEpoch")
    suspend fun getCompletionCountSince(sinceEpoch: Long): Int

    // ── Suggestion Log ──

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSuggestion(suggestion: BrainSuggestionLog)

    @Query("SELECT * FROM brain_suggestion_log ORDER BY suggestedAt DESC LIMIT :limit")
    suspend fun getRecentSuggestions(limit: Int = 50): List<BrainSuggestionLog>

    @Query("SELECT * FROM brain_suggestion_log WHERE trackId = :trackId ORDER BY suggestedAt DESC")
    suspend fun getSuggestionsForTrack(trackId: String): List<BrainSuggestionLog>

    @Query("SELECT * FROM brain_suggestion_log WHERE suggestedAt > :sinceEpoch ORDER BY flowScore DESC")
    suspend fun getTopSuggestionsSince(sinceEpoch: Long): List<BrainSuggestionLog>

    // ── Stats ──

    @Query("SELECT COUNT(*) FROM brain_suggestion_log")
    suspend fun getTotalSuggestions(): Int

    @Query("SELECT COUNT(*) FROM brain_suggestion_log WHERE wasAccepted = 1")
    suspend fun getAcceptedSuggestions(): Int

    @Query("SELECT COUNT(*) FROM brain_suggestion_log WHERE suggestedAt > :sinceEpoch")
    suspend fun getSuggestionsSince(sinceEpoch: Long): Int
}