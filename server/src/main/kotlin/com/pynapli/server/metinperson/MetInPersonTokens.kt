package com.pynapli.server.metinperson

import java.util.UUID
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Keyed pseudonyms for "Met in person" (TRUST_AND_SAFETY_SPEC "Met in person"): HMAC-SHA256 under the
 * server key K3 (`PYNAPLI_MET_IN_PERSON_TOKEN_SECRET`), hex-encoded, one domain prefix per kind, so no
 * token of one kind can equal a token of another and a token of one person differs by role.
 *
 * - [pair]: DIRECTED, voucher then subject; the unique key of a confirmation.
 * - [subject]: who a row is about. [partner]: the subject's partner on a couple confirmation.
 * - [link]: the couple's canonical link row on a couple confirmation.
 * - [checkValue]: proves the configured key is the one the rows were written with.
 * - [auditVoucher]: names a voucher in the admin audit log for a key holder only.
 *
 * Without the key none of these can be reversed; with it, a token is matched by recomputing it over a
 * candidate set (conversation partners), never decrypted.
 */
class MetInPersonTokens(
    secret: String,
) {
    private val key = SecretKeySpec(secret.toByteArray(Charsets.UTF_8), ALGORITHM)

    fun pair(
        voucherUserId: UUID,
        subjectUserId: UUID,
    ): String = hmac("pair:$voucherUserId>$subjectUserId")

    fun subject(userId: UUID): String = hmac("subject:$userId")

    fun partner(userId: UUID): String = hmac("partner:$userId")

    fun link(linkId: UUID): String = hmac("link:$linkId")

    fun auditVoucher(userId: UUID): String = hmac("audit-voucher:$userId")

    fun checkValue(): String = hmac("key-check:met-in-person")

    private fun hmac(input: String): String {
        // Mac is not thread-safe; one per call is cheap next to the database round trip.
        val mac = Mac.getInstance(ALGORITHM)
        mac.init(key)
        val digest = mac.doFinal(input.toByteArray(Charsets.UTF_8))
        val out = StringBuilder(digest.size * 2)
        digest.forEach { byte ->
            val value = byte.toInt() and 0xff
            out.append(HEX[value ushr 4])
            out.append(HEX[value and 0x0f])
        }
        return out.toString()
    }

    companion object {
        const val KEY_VERSION: Short = 1
        private const val ALGORITHM = "HmacSHA256"
        private const val HEX = "0123456789abcdef"

        /** The fixed local development key (ServerConfig's default), for local runs and tests. */
        const val LOCAL_DEV_KEY = "pynapli-local-dev-met-in-person-token-secret"

        fun localDevelopment(): MetInPersonTokens = MetInPersonTokens(LOCAL_DEV_KEY)
    }
}
