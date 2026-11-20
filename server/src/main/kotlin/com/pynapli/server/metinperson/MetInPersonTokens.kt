package com.pynapli.server.metinperson

import java.util.UUID
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Keyed pseudonyms for "Met in person" (TRUST_AND_SAFETY_SPEC "Met in person"): HMAC-SHA256 under the
 * server secret K3 (`PYNAPLI_MET_IN_PERSON_TOKEN_SECRET`), hex-encoded. Each kind has its own prefix so
 * a token of one kind can never equal a token of another.
 *
 * - [voucher]: the same for every confirmation one person gives. Used for the 30-day cap, an admin
 *   "void everything this person gave", deletion and export.
 * - [pair]: DIRECTED, voucher then subject. A confirming B and B confirming A are different pairs.
 *   Unique in storage, which is what makes a pair confirmable once.
 * - [conversation]: the conversation the confirmation was given from. Stored instead of the id,
 *   because a clear conversation id names the voucher through `direct_conversations`.
 *
 * Without K3 none of these can be reversed; with K3 a token is matched by recomputing it for a
 * candidate user (everyone who shares a conversation with the subject), never decrypted.
 */
class MetInPersonTokens(
    secret: String,
) {
    private val key = SecretKeySpec(secret.toByteArray(Charsets.UTF_8), ALGORITHM)

    fun voucher(voucherUserId: UUID): String = hmac("voucher:$voucherUserId")

    fun pair(
        voucherUserId: UUID,
        subjectUserId: UUID,
    ): String = hmac("pair:$voucherUserId>$subjectUserId")

    fun conversation(conversationId: UUID): String = hmac("conversation:$conversationId")

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

    private companion object {
        const val ALGORITHM = "HmacSHA256"
        const val HEX = "0123456789abcdef"
    }
}
