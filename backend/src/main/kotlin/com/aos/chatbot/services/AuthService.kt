package com.aos.chatbot.services

import com.aos.chatbot.config.AuthConfig
import com.aos.chatbot.config.JwtConfig

/**
 * Owns the in-memory password hash and the login decision. JWT issuance is
 * delegated to [JwtConfig].
 *
 * The `username` field on `LoginRequest` (see ARCHITECTURE.md §7.4) is
 * intentionally not a parameter of [login]: the wire format keeps it for
 * forward-compatibility with future multi-user support, but the server in
 * Phase 4 only validates `password` (single administrator — see ADR 0007).
 */
class AuthService(
    authConfig: AuthConfig,
    private val jwtConfig: JwtConfig,
    private val hasher: PasswordHasher = PasswordHasher
) {
    init {
        require(authConfig.adminPassword.isNotBlank()) { "ADMIN_PASSWORD must be set" }
    }

    private val passwordHash: String = hasher.hash(authConfig.adminPassword)

    fun login(password: String): String? {
        return if (hasher.verify(password, passwordHash)) jwtConfig.sign() else null
    }
}
