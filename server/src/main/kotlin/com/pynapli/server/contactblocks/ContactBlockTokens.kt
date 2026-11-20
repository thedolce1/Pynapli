package com.pynapli.server.contactblocks

import java.util.Base64
import java.util.UUID
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * The keyed tokens behind "Hide from people you know" (TRUST_AND_SAFETY_SPEC, "Contact blocks").
 *
 * - [emailToken]: HMAC-SHA256(K1, normalised address), stored for each added address. Never the plain
 *   SHA-256 that `users.email_hash` holds, so the table can't be joined to `email_hash` without K1.
 * - [memberToken]: HMAC-SHA256(K2, "member:" + emailToken), stored as `users.contact_token` for each
 *   member whose address is verified, so rows and members can't be joined without K2 either.
 * - [targetToken]: HMAC-SHA256(K2, "target:" + member id), written when a row is bound to the member
 *   who owns the address. A viewer's own token is computed per request to find who hides from them.
 * - [exemptToken]: HMAC-SHA256(K2, "exempt:" + member id), written instead when the pair had already
 *   interacted. It matches no viewer lookup, and without K2 it looks exactly like a bound row.
 *
 * Both keys come from configuration (ServerConfig.contactBlockEmailKey / contactBlockTargetKey);
 * production-like environments refuse to start without explicit, distinct values.
 */
class ContactBlockTokens(
    emailKey: String,
    targetKey: String,
) {
    private val emailKeySpec = SecretKeySpec(emailKey.toByteArray(Charsets.UTF_8), ALGORITHM)
    private val targetKeySpec = SecretKeySpec(targetKey.toByteArray(Charsets.UTF_8), ALGORITHM)

    init {
        require(emailKey.isNotEmpty() && targetKey.isNotEmpty()) { "Contact block keys must not be empty" }
    }

    /** [normalisedEmail] must already be normalised exactly as `users.email_hash` is (normalizeEmail). */
    fun emailToken(normalisedEmail: String): String = hmac(emailKeySpec, normalisedEmail)

    /**
     * What `users.contact_token` holds for a verified member: HMAC-SHA256(K2, "member:" + [emailToken]).
     * Deliberately not the row's [emailToken] itself, so a copy of both tables can't be joined without
     * K2 (the resolver computes this per pending row and looks members up by it).
     */
    fun memberToken(emailToken: String): String = hmac(targetKeySpec, "member:$emailToken")

    fun targetToken(userId: UUID): String = hmac(targetKeySpec, "target:$userId")

    fun exemptToken(userId: UUID): String = hmac(targetKeySpec, "exempt:$userId")

    private fun hmac(
        key: SecretKeySpec,
        value: String,
    ): String {
        // Mac instances aren't thread-safe; one per call is cheap next to the query it feeds.
        val mac = Mac.getInstance(ALGORITHM)
        mac.init(key)
        return encoder.encodeToString(mac.doFinal(value.toByteArray(Charsets.UTF_8)))
    }

    companion object {
        private const val ALGORITHM = "HmacSHA256"
        private val encoder = Base64.getUrlEncoder().withoutPadding()

        /** Local development and tests only (ServerConfig refuses these values outside local/test). */
        const val LOCAL_DEV_EMAIL_KEY = "pynapli-local-dev-contact-block-email-key"
        const val LOCAL_DEV_TARGET_KEY = "pynapli-local-dev-contact-block-target-key"

        fun localDevelopment(): ContactBlockTokens = ContactBlockTokens(LOCAL_DEV_EMAIL_KEY, LOCAL_DEV_TARGET_KEY)
    }
}
