package com.audic.music.playback

import com.audic.music.utils.isAgeRestrictedPlayability
import org.junit.Assert.assertEquals
import org.junit.Test

class DownloadUtilTest {
    @Test
    fun `stream expiry is relative to the current time`() {
        assertEquals(1_012_000L, streamExpiryTimestamp(1_000_000L, 12))
    }

    @Test
    fun `age verification reason is recognized even with a generic playability status`() {
        assertEquals(true, isAgeRestrictedPlayability("LOGIN_REQUIRED", "Sign in to confirm your age"))
        assertEquals(true, isAgeRestrictedPlayability("UNPLAYABLE", "Sign in to confirm your age"))
    }

    @Test
    fun `forbidden and gone responses are treated as expired streams`() {
        assertEquals(true, isExpiredStreamResponseCode(403))
        assertEquals(true, isExpiredStreamResponseCode(410))
        assertEquals(false, isExpiredStreamResponseCode(500))
    }
}