package com.aos.chatbot.services

import at.favre.lib.crypto.bcrypt.BCrypt

/**
 * Thin wrapper over `at.favre.lib:bcrypt` for password hashing.
 *
 * Cost=12 takes roughly 100 ms on dev hardware — that is the budget per login.
 * No need to expose cost configuration in Phase 4 (single-admin, low login frequency).
 */
object PasswordHasher {

    fun hash(password: String, cost: Int = 12): String {
        require(password.isNotBlank()) { "password must not be blank" }
        return BCrypt.withDefaults().hashToString(cost, password.toCharArray())
    }

    fun verify(password: String, hash: String): Boolean {
        return try {
            BCrypt.verifyer().verify(password.toCharArray(), hash).verified
        } catch (e: IllegalArgumentException) {
            false
        }
    }
}
