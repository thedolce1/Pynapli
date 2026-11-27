package com.pynapli.shared.network.dto

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Provider credentials never show up in toString (logs, crash reports), and the wire format is unchanged. */
class SocialAuthDtosTest {
    @Test
    fun signInRequestRedactsTheTokenTheNonceTheCodeAndTheBirthdate() {
        val request =
            SocialSignInRequestDto(
                idToken = "eyJ.secret-id-token.sig",
                nonce = "secret-nonce",
                authorizationCode = "secret-apple-code",
                birthdate = "1990-01-01",
                confirmedAdult = true,
                acceptedTerms = true,
                acceptedPrivacy = true,
                platform = "android",
            )
        val text = request.toString()
        listOf("secret-id-token", "secret-nonce", "secret-apple-code", "1990-01-01").forEach { secret ->
            assertFalse(text.contains(secret), "toString must not contain $secret")
        }
        assertTrue(text.contains("platform=android"))

        val json = Json.encodeToString(SocialSignInRequestDto.serializer(), request)
        assertTrue(json.contains("\"idToken\":\"eyJ.secret-id-token.sig\""), "serialization is unchanged")
        assertEquals(request, Json.decodeFromString(SocialSignInRequestDto.serializer(), json))
    }

    @Test
    fun linkAndAppealRequestsRedactTheirCredentials() {
        val link = LinkSocialIdentityRequestDto("secret-id-token", "secret-nonce", "secret-apple-code")
        val appeal = SocialAccountAppealRequestDto("secret-id-token", "secret-nonce", "my private reason")
        listOf(link.toString(), appeal.toString()).forEach { text ->
            listOf("secret-id-token", "secret-nonce", "secret-apple-code", "my private reason").forEach { secret ->
                assertFalse(text.contains(secret), "toString must not contain $secret")
            }
        }
        assertEquals(link, Json.decodeFromString(LinkSocialIdentityRequestDto.serializer(), Json.encodeToString(LinkSocialIdentityRequestDto.serializer(), link)))
        assertEquals(appeal, Json.decodeFromString(SocialAccountAppealRequestDto.serializer(), Json.encodeToString(SocialAccountAppealRequestDto.serializer(), appeal)))
        assertEquals("LinkSocialIdentityRequestDto(idToken=<redacted>, nonce=<redacted>, authorizationCode=null)", LinkSocialIdentityRequestDto("t", "n").toString())
    }
}
