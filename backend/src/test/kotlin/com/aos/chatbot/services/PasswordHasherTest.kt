package com.aos.chatbot.services

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PasswordHasherTest {

    @Test
    fun `hash produces a string with bcrypt cost-12 prefix`() {
        val hash = PasswordHasher.hash("correct horse battery staple")

        assertTrue(hash.isNotEmpty())
        assertTrue(hash.startsWith("\$2a\$12\$"), "expected \$2a\$12\$ prefix, got: $hash")
    }

    @Test
    fun `hash and verify round-trip succeeds for a non-trivial password`() {
        val password = "S3cr3t!-äöü-correct horse battery staple"
        val hash = PasswordHasher.hash(password)

        assertTrue(PasswordHasher.verify(password, hash))
    }

    @Test
    fun `verify returns false for the wrong password`() {
        val hash = PasswordHasher.hash("right-password")

        assertFalse(PasswordHasher.verify("wrong-password", hash))
    }

    @Test
    fun `verify returns false on a malformed hash string instead of throwing`() {
        assertFalse(PasswordHasher.verify("anything", "not-a-bcrypt-hash"))
        assertFalse(PasswordHasher.verify("anything", ""))
        assertFalse(PasswordHasher.verify("anything", "\$2a\$12\$tooshort"))
    }

    @Test
    fun `hash throws IllegalArgumentException on blank password`() {
        assertThrows<IllegalArgumentException> { PasswordHasher.hash("") }
        assertThrows<IllegalArgumentException> { PasswordHasher.hash("   ") }
    }
}
