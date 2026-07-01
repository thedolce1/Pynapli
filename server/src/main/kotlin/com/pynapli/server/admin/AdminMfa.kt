package com.pynapli.server.admin

import com.pynapli.server.config.ServerConfig
import com.pynapli.server.moderation.ModerationException
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.header
import java.security.MessageDigest
import java.time.Instant
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

const val ADMIN_MFA_HEADER = "X-Admin-MFA"

/**
 * Dependency-free RFC 6238 TOTP (HMAC-SHA1, 30s step, 6 digits) — the step-up second factor for
 * sensitive admin actions. Secrets are base32 (RFC 4648, the Google Authenticator format).
 */
object AdminTotp {
    private const val STEP_SECONDS = 30L
    private const val DIGITS = 6
    private const val BASE32_ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567"

    fun base32Decode(secret: String): ByteArray {
        val clean = secret.trim().uppercase().replace("=", "")
        val out = ArrayList<Byte>(clean.length * 5 / 8 + 1)
        var buffer = 0
        var bitsLeft = 0
        for (ch in clean) {
            val value = BASE32_ALPHABET.indexOf(ch)
            if (value < 0) continue
            buffer = (buffer shl 5) or value
            bitsLeft += 5
            if (bitsLeft >= 8) {
                bitsLeft -= 8
                out.add(((buffer shr bitsLeft) and 0xff).toByte())
            }
        }
        return out.toByteArray()
    }

    fun generate(
        secretBase32: String,
        timeSeconds: Long,
    ): String {
        val counter = timeSeconds / STEP_SECONDS
        val message = ByteArray(8)
        var value = counter
        for (i in 7 downTo 0) {
            message[i] = (value and 0xffL).toByte()
            value = value shr 8
        }
        val mac = Mac.getInstance("HmacSHA1")
        mac.init(SecretKeySpec(base32Decode(secretBase32), "HmacSHA1"))
        val hash = mac.doFinal(message)
        val offset = hash[hash.size - 1].toInt() and 0x0f
        val binary =
            ((hash[offset].toInt() and 0x7f) shl 24) or
                ((hash[offset + 1].toInt() and 0xff) shl 16) or
                ((hash[offset + 2].toInt() and 0xff) shl 8) or
                (hash[offset + 3].toInt() and 0xff)
        return (binary % 1_000_000).toString().padStart(DIGITS, '0')
    }

    /** Verifies [code] within a +/-[window]-step clock-skew tolerance, in constant time per candidate. */
    fun verify(
        secretBase32: String,
        code: String,
        timeSeconds: Long,
        window: Int = 1,
    ): Boolean {
        val normalized = code.trim()
        if (normalized.length != DIGITS || normalized.any { !it.isDigit() }) return false
        for (w in -window..window) {
            val candidate = generate(secretBase32, timeSeconds + w * STEP_SECONDS)
            if (MessageDigest.isEqual(candidate.toByteArray(), normalized.toByteArray())) return true
        }
        return false
    }
}

/**
 * Pure step-up decision: satisfied when MFA is not enforced, or when [code] is a valid current TOTP
 * for the actor's configured secret. Kept separate from the call extension so the policy is
 * unit-testable without a running pipeline.
 */
fun adminMfaSatisfied(
    config: ServerConfig,
    actorId: String,
    code: String?,
    nowSeconds: Long = Instant.now().epochSecond,
): Boolean {
    if (!config.adminMfaEnforced) return true
    val secret = config.adminMfaSecrets[actorId] ?: return false
    val presented = code?.trim()?.takeIf { it.isNotEmpty() } ?: return false
    return AdminTotp.verify(secret, presented, nowSeconds)
}

/**
 * Requires a valid step-up MFA code for a sensitive admin action (a no-op unless enforced): reads the
 * `X-Admin-MFA` header and throws `ADMIN_MFA_REQUIRED` (-> 403) when [adminMfaSatisfied] is false. The
 * real second factor on top of the shared token + self-declared actor id.
 */
fun ApplicationCall.requireAdminMfa(
    config: ServerConfig,
    actorId: String,
    nowSeconds: Long = Instant.now().epochSecond,
) {
    if (!adminMfaSatisfied(config, actorId, request.header(ADMIN_MFA_HEADER), nowSeconds)) {
        throw ModerationException("ADMIN_MFA_REQUIRED")
    }
}
