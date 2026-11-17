package com.pynapli.server.profile

/**
 * Moderation seam for free-text bio writes. Rejection maps to BIO_REJECTED (400) before anything
 * is stored. A vendor or classifier scanner can replace [ContactAndSolicitationBioScanner] behind
 * this interface without touching the write path.
 */
fun interface ProfileBioScanner {
    suspend fun scan(bio: String): Decision

    enum class Decision {
        Accept,
        Reject,
    }
}

/** Accepts everything; kept for tests that exercise other profile rules. */
object AcceptAllProfileBioScanner : ProfileBioScanner {
    override suspend fun scan(bio: String): ProfileBioScanner.Decision = ProfileBioScanner.Decision.Accept
}

/**
 * Bios are public (MODERATION_AND_AGE_VERIFICATION_SPEC "Content Zones": app-store safe, moderated
 * before publishing). This keeps out the two things that turn a profile into an ad or a scam:
 *
 * - **Taking people off Pynapli:** links and domains, phone numbers, email addresses, and handles
 *   for other messaging or social apps (the same platforms first-message moderation holds).
 * - **Selling:** payment apps and price-for-time wording ("$200/hr", "my rates").
 *
 * Deliberately narrow so ordinary lifestyle bios pass: words about what people are into are left to
 * reports and review, not keyword matching. Light obfuscation ("dot com", spaced digits) is caught.
 */
object ContactAndSolicitationBioScanner : ProfileBioScanner {
    override suspend fun scan(bio: String): ProfileBioScanner.Decision = if (rejects(bio)) ProfileBioScanner.Decision.Reject else ProfileBioScanner.Decision.Accept

    internal fun rejects(bio: String): Boolean {
        val text = bio.lowercase()
        return LINK.containsMatchIn(text) ||
            OBFUSCATED_DOMAIN.containsMatchIn(text) ||
            EMAIL.containsMatchIn(text) ||
            PHONE.containsMatchIn(text) ||
            OFF_APP_HANDLE.containsMatchIn(text) ||
            PAYMENT.containsMatchIn(text) ||
            PRICED_TIME.containsMatchIn(text)
    }

    private val LINK =
        Regex("""(https?://|www\.|\b[a-z0-9][a-z0-9-]*\.(com|net|org|io|co|app|me|site|xyz|club|info|ly|gg|link|page|to)\b)""")
    private val OBFUSCATED_DOMAIN = Regex("""\b[a-z0-9-]{2,}\s*(\(|\[)?\s*dot\s*(\)|\])?\s*(com|net|org|me|co)\b""")
    private val EMAIL = Regex("""[a-z0-9._%+-]+\s*(@|\(at\)|\[at\])\s*[a-z0-9-]+\s*(\.|\bdot\b)\s*[a-z]{2,}""")

    // Seven or more digits, allowing the spaces, dots, dashes and brackets people put in numbers.
    private val PHONE = Regex("""(?:\+?\d[\s().-]{0,2}){7,}""")
    private val OFF_APP_HANDLE =
        Regex(
            """\b(telegram|whatsapp|whats app|snapchat|snap ?chat|kik|wickr|signal app|instagram|insta|onlyfans|only fans|fansly|""" +
                """\bsnap\s*[:@]|\big\s*[:@]|\btg\s*[:@])""",
        )
    private val PAYMENT = Regex("""\b(cash ?app|venmo|paypal|zelle|crypto|bitcoin|btc|apple pay|gift ?cards?|sugar (daddy|baby|mommy))\b|\$[a-z][a-z0-9_]{2,}""")
    private val PRICED_TIME =
        Regex("""(\$|€|£)\s?\d+\s*(/|per\s*)\s*(hr|hour|h|night|session)\b|\bmy rates\b""")
}
