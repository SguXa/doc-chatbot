package com.aos.chatbot.config

import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import com.auth0.jwt.exceptions.JWTVerificationException
import com.auth0.jwt.interfaces.JWTVerifier
import java.time.Clock
import java.util.Date

/**
 * HS256 JWT signer/verifier with fixed issuer and 24 h TTL by default.
 *
 * Only signature, issuer, and `exp` are verified. The token deliberately omits
 * `aud`, `role`, and `nbf` — every claim that does not change behavior in
 * Phase 4 is left out to keep the surface tight (single-admin, no role
 * hierarchy; see ADR 0007).
 *
 * No clock leeway is configured (`acceptLeeway` is left at the `java-jwt`
 * default of 0 seconds). Deployments with skewed clocks must run NTP — future
 * operators should not expect skew tolerance from this verifier.
 */
class JwtConfig(
    private val secret: String,
    private val issuer: String = "aos-chatbot",
    val ttlSeconds: Long = 86_400,
    private val clock: Clock = Clock.systemUTC()
) {
    init {
        require(secret.length >= 32) { "JWT secret must be >= 32 chars" }
    }

    private val algorithm: Algorithm = Algorithm.HMAC256(secret)

    fun sign(): String {
        val now = clock.instant()
        return JWT.create()
            .withIssuer(issuer)
            .withSubject("admin")
            .withIssuedAt(Date.from(now))
            .withExpiresAt(Date.from(now.plusSeconds(ttlSeconds)))
            .sign(algorithm)
    }

    fun verify(token: String): Boolean {
        return try {
            verifier().verify(token)
            true
        } catch (e: JWTVerificationException) {
            false
        } catch (e: IllegalArgumentException) {
            false
        }
    }

    fun verifier(): JWTVerifier =
        JWT.require(algorithm)
            .withIssuer(issuer)
            .build()
}
