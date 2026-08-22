package com.audic.music.brain

import com.audic.music.db.entities.Song
import com.audic.music.models.MediaMetadata
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.LocalDateTime

/**
 * Interest profile built from the user's local library.
 *
 * Tracks artist and album affinities based on play counts,
 * liked status, and recency of listening.
 */
data class BrainInterestProfile(
    val artistAffinities: Map<String, ArtistAffinity> = emptyMap(),
    val albumAffinities: Map<String, AlbumAffinity> = emptyMap(),
    val topGenres: List<String> = emptyList(),
    val totalInteractions: Long = 0,
    val lastUpdated: LocalDateTime = LocalDateTime.now()
)

data class ArtistAffinity(
    val artistId: String,
    val artistName: String,
    val score: Float,          // 0.0 - 1.0
    val playCount: Int,
    val liked: Boolean = false,
    val lastPlayedAt: LocalDateTime? = null
)

data class AlbumAffinity(
    val albumId: String,
    val albumName: String,
    val artistName: String,
    val score: Float,          // 0.0 - 1.0
    val playCount: Int,
    val liked: Boolean = false
)

/**
 * Builds a [BrainInterestProfile] from the user's local library data.
 */
object BrainInterestProfileBuilder {

    /**
     * Build a profile from the user's top songs and liked songs.
     *
     * @param topSongs Most-played songs from the database
     * @param likedSongs Liked/starred songs from the database
     */
    suspend fun build(
        topSongs: List<Song>,
        likedSongs: List<Song>
    ): BrainInterestProfile = withContext(Dispatchers.Default) {
        val artistAffinities = mutableMapOf<String, ArtistAffinity>()
        val albumAffinities = mutableMapOf<String, AlbumAffinity>()
        val artistCounts = mutableMapOf<String, Int>()
        val albumCounts = mutableMapOf<String, Int>()

        // 1. Process top songs (weighted by play count)
        topSongs.forEach { song ->
            song.artists.forEach { artist ->
                val current = artistCounts.getOrDefault(artist.id, 0)
                artistCounts[artist.id] = current + 1
            }

            song.album?.let { album ->
                val current = albumCounts.getOrDefault(album.id, 0)
                albumCounts[album.id] = current + 1
            }
        }

        // 2. Process liked songs (bonus for liked)
        likedSongs.forEach { song ->
            song.artists.forEach { artist ->
                val current = artistCounts.getOrDefault(artist.id, 0)
                artistCounts[artist.id] = current + 2  // Double weight for liked
            }
            song.album?.let { album ->
                val current = albumCounts.getOrDefault(album.id, 0)
                albumCounts[album.id] = current + 2
            }
        }

        // 3. Build affinities
        val maxArtistCount = artistCounts.values.maxOrNull()?.toFloat() ?: 1f
        val maxAlbumCount = albumCounts.values.maxOrNull()?.toFloat() ?: 1f

        val combinedSongs = (topSongs + likedSongs).distinctBy { it.song.id }

        combinedSongs.forEach { song ->
            song.artists.forEach { artist ->
                val count = artistCounts[artist.id] ?: 1
                val score = (count / maxArtistCount).coerceIn(0f, 1f)
                artistAffinities[artist.id] = ArtistAffinity(
                    artistId = artist.id,
                    artistName = artist.name,
                    score = score,
                    playCount = count,
                    liked = song.song.liked,
                    lastPlayedAt = song.song.inLibrary
                )
            }
            song.album?.let { album ->
                val count = albumCounts[album.id] ?: 1
                val score = (count / maxAlbumCount).coerceIn(0f, 1f)
                albumAffinities[album.id] = AlbumAffinity(
                    albumId = album.id,
                    albumName = album.title,
                    artistName = song.artists.firstOrNull()?.name ?: "",
                    score = score,
                    playCount = count,
                    liked = song.song.liked
                )
            }
        }

        val totalPlayTime = topSongs.sumOf { it.song.totalPlayTime }

        BrainInterestProfile(
            artistAffinities = artistAffinities,
            albumAffinities = albumAffinities,
            totalInteractions = totalPlayTime,
            lastUpdated = LocalDateTime.now()
        )
    }

    /**
     * Calculate the affinity score between a candidate track and the user's profile.
     *
     * @return A score from 0 to 100
     */
    fun calculateMatchingScore(
        track: MediaMetadata,
        profile: BrainInterestProfile
    ): Int {
        if (profile.totalInteractions == 0L) return 0

        var score = 0.0

        // Artist matching
        track.artists.forEach { artist ->
            profile.artistAffinities[artist.id]?.let { affinity ->
                score += affinity.score * BrainConstants.ARTIST_AFFINITY_MULTIPLIER
            }
        }

        // Album matching
        track.album?.let { album ->
            profile.albumAffinities[album.id]?.let { affinity ->
                score += affinity.score * BrainConstants.ALBUM_AFFINITY_MULTIPLIER
            }
        }

        return (score / 6.0 * 100).toInt().coerceIn(0, BrainConstants.INTEREST_MAX_SCORE)
    }
}