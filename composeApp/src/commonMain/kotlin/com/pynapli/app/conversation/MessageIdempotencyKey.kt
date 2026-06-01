package com.pynapli.app.conversation

import kotlin.random.Random

/**
 * Generates a client message idempotency key (UUIDv4 string). A stable key reused across retries of
 * the same logical send lets the server collapse a duplicate send (whose response was lost) into one
 * message. Mirrors the swipe action-id generator so the client has one consistent id scheme.
 */
internal fun newMessageIdempotencyKey(random: Random = Random.Default): String {
    val bytes = random.nextBytes(16)
    bytes[6] = ((bytes[6].toInt() and 0x0f) or 0x40).toByte()
    bytes[8] = ((bytes[8].toInt() and 0x3f) or 0x80).toByte()
    val hex =
        bytes.joinToString(separator = "") { byte ->
            (byte.toInt() and 0xff).toString(16).padStart(2, '0')
        }
    return buildString(36) {
        append(hex.substring(0, 8))
        append('-')
        append(hex.substring(8, 12))
        append('-')
        append(hex.substring(12, 16))
        append('-')
        append(hex.substring(16, 20))
        append('-')
        append(hex.substring(20, 32))
    }
}
