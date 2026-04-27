package com.aos.chatbot.services

import com.aos.chatbot.config.AuthConfig
import com.aos.chatbot.config.JwtConfig
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AuthServiceTest {

    private val secret32 = "0123456789abcdef0123456789abcdef"
    private val correctPassword = "S3cr3t!-correct horse battery staple"

    private fun newJwtConfig() = JwtConfig(secret = secret32)

    @Test
    fun `login with correct password returns a non-null token that verifies`() {
        val jwt = newJwtConfig()
        val service = AuthService(
            authConfig = AuthConfig(jwtSecret = secret32, adminPassword = correctPassword),
            jwtConfig = jwt
        )

        val token = service.login(correctPassword)

        assertNotNull(token)
        assertTrue(token.isNotEmpty())
        assertTrue(jwt.verify(token))
    }

    @Test
    fun `login with wrong password returns null`() {
        val service = AuthService(
            authConfig = AuthConfig(jwtSecret = secret32, adminPassword = correctPassword),
            jwtConfig = newJwtConfig()
        )

        assertNull(service.login("not-the-password"))
    }

    @Test
    fun `constructor throws when adminPassword is empty`() {
        assertFailsWith<IllegalArgumentException> {
            AuthService(
                authConfig = AuthConfig(jwtSecret = secret32, adminPassword = ""),
                jwtConfig = newJwtConfig()
            )
        }
    }

    @Test
    fun `constructor throws when adminPassword is blank`() {
        assertFailsWith<IllegalArgumentException> {
            AuthService(
                authConfig = AuthConfig(jwtSecret = secret32, adminPassword = "   "),
                jwtConfig = newJwtConfig()
            )
        }
    }

    @Test
    fun `login with blank password returns null without throwing`() {
        val service = AuthService(
            authConfig = AuthConfig(jwtSecret = secret32, adminPassword = correctPassword),
            jwtConfig = newJwtConfig()
        )

        assertNull(service.login(""))
        assertNull(service.login("   "))
    }
}
