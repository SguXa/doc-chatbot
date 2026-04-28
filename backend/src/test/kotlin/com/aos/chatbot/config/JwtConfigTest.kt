package com.aos.chatbot.config

import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class JwtConfigTest {

    private val secret32 = "0123456789abcdef0123456789abcdef"

    @Test
    fun `sign then verify round-trip returns true on the same instance`() {
        val jwt = JwtConfig(secret = secret32)
        val token = jwt.sign()
        assertTrue(token.isNotEmpty())
        assertTrue(jwt.verify(token))
    }

    @Test
    fun `verify returns false on a token signed with a different secret`() {
        val signer = JwtConfig(secret = secret32)
        val verifier = JwtConfig(secret = "ffffffffffffffffffffffffffffffff")
        val token = signer.sign()
        assertFalse(verifier.verify(token))
    }

    @Test
    fun `verify returns false on a token with a different issuer`() {
        val signer = JwtConfig(secret = secret32, issuer = "other-issuer")
        val verifier = JwtConfig(secret = secret32, issuer = "aos-chatbot")
        val token = signer.sign()
        assertFalse(verifier.verify(token))
    }

    @Test
    fun `verify returns false on an expired token`() {
        val pastClock = Clock.fixed(Instant.parse("2020-01-01T00:00:00Z"), ZoneOffset.UTC)
        val signer = JwtConfig(secret = secret32, ttlSeconds = 60, clock = pastClock)
        val verifier = JwtConfig(secret = secret32)
        val token = signer.sign()
        assertFalse(verifier.verify(token))
    }

    @Test
    fun `verify returns false on garbage strings`() {
        val jwt = JwtConfig(secret = secret32)
        assertFalse(jwt.verify(""))
        assertFalse(jwt.verify("not-a-jwt"))
        assertFalse(jwt.verify("a.b.c"))
    }

    @Test
    fun `constructor throws on a 31-char secret`() {
        val short = "0123456789abcdef0123456789abcde"
        assertEquals(31, short.length)
        val ex = assertFailsWith<IllegalArgumentException> {
            JwtConfig(secret = short)
        }
        assertTrue(ex.message!!.contains(">= 32"))
    }

    @Test
    fun `constructor accepts a 32-char secret`() {
        assertEquals(32, secret32.length)
        val jwt = JwtConfig(secret = secret32)
        assertNotNull(jwt.sign())
    }

    @Test
    fun `verifier returns a configured JWTVerifier that accepts a valid token`() {
        val jwt = JwtConfig(secret = secret32)
        val token = jwt.sign()
        val v = jwt.verifier()
        val decoded = v.verify(token)
        assertEquals("aos-chatbot", decoded.issuer)
        assertEquals("admin", decoded.subject)
    }
}
