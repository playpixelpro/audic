package com.audic.music.utils.lastfm

import com.audic.music.utils.lastfm.ScrobblerClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ScrobblerClientTest {

    /**
     * The spec's own worked example (last.fm/api/authspec §8, auth.getSession):
     *   api_key=xxxxxxxxxx, token=yyyyyy, secret=ilovecher
     *   -> md5("api_keyxxxxxxxxxxmethodauth.getSessiontokenyyyyyyilovecher")
     *      = b87d61da3cda91a8b6746c4aef55d6f8
     */
    private val specParams = mapOf(
        "api_key" to "xxxxxxxxxx",
        "method" to "auth.getSession",
        "token" to "yyyyyy",
    )

    private val client = ScrobblerClient(
        baseUrl = "https://example.invalid/2.0/",
        authUrlBase = "https://example.invalid/api/auth/",
        apiKey = "APIKEY",
        secret = "SECRET",
    )

    @Test
    fun `api signature matches the documented spec vector`() {
        assertEquals("b87d61da3cda91a8b6746c4aef55d6f8", ScrobblerClient.apiSig(specParams, "ilovecher"))
    }

    @Test
    fun `api signature is stable across param insertion order`() {
        val shuffled = LinkedHashMap<String, String>().apply {
            put("method", specParams.getValue("method"))
            put("token", specParams.getValue("token"))
            put("api_key", specParams.getValue("api_key"))
        }
        assertEquals(
            ScrobblerClient.apiSig(specParams, "ilovecher"),
            ScrobblerClient.apiSig(shuffled, "ilovecher"),
        )
    }

    @Test
    fun `auth state requires both credentials and a session`() {
        val loggedOut = ScrobblerClient("u", "a", "key", "secret")
        assertTrue(loggedOut.isInitialized())
        assertEquals(false, loggedOut.isAuthenticated())
        loggedOut.sessionKey = "session"
        assertTrue(loggedOut.isAuthenticated())
    }
}
