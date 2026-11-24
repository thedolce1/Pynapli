package com.pynapli.shared.auth

/**
 * The rule for a new password (sign-up, reset, change), shared by the client and the server so the
 * two can never disagree. The server is authoritative: it runs [problemWith] before it looks up any
 * address, so the answer (and its copy) is the same for every address, member or not
 * (ONBOARDING_AUTH_SPEC, "Membership Privacy").
 *
 * Sign-in deliberately does NOT apply the denylist: an existing member whose password is on it must
 * still get in (and can change it from the profile hub).
 *
 * Normalisation: the rule judges the password after Unicode NFKC normalisation (so full-width or
 * compatibility look-alikes such as "ｐａｓｓｗｏｒｄ１２３" count as "password123"), with surrounding
 * whitespace trimmed, and measures length in code points. Common Kotlin has no NFKC, so the caller
 * passes the normaliser: the server passes `java.text.Normalizer` NFKC (authoritative); the client
 * passes none, so its pre-check can miss a look-alike the server then refuses with the same copy.
 * Normalisation is for judging only; the password itself is hashed exactly as typed.
 */
object PasswordRules {
    /** Lowered from 12 (onboarding + first grid plan, Section 1): less friction, plus the denylist below. */
    const val MIN_LENGTH = 10

    const val TOO_SHORT_CODE = "PASSWORD_TOO_SHORT"
    const val TOO_COMMON_CODE = "PASSWORD_TOO_COMMON"

    const val TOO_SHORT_MESSAGE = "Password must be at least $MIN_LENGTH characters."
    const val TOO_COMMON_MESSAGE = "That password is too easy to guess. Choose a different one."

    /**
     * The error code for a new password that can't be used, or null when it can. Blank (after
     * trimming) and too short both answer [TOO_SHORT_CODE].
     */
    fun problemWith(
        password: String,
        normalize: (String) -> String = { it },
    ): String? {
        val judged = normalize(password).trim()
        return when {
            judged.isEmpty() || judged.codePointLength() < MIN_LENGTH -> TOO_SHORT_CODE
            judged.lowercase() in commonPasswords -> TOO_COMMON_CODE
            else -> null
        }
    }

    /** The member-facing copy for [problemWith]'s code (null when the password can be used). */
    fun messageFor(
        password: String,
        normalize: (String) -> String = { it },
    ): String? =
        when (problemWith(password, normalize)) {
            TOO_SHORT_CODE -> TOO_SHORT_MESSAGE
            TOO_COMMON_CODE -> TOO_COMMON_MESSAGE
            else -> null
        }

    /** Case-insensitive, after trimming surrounding whitespace (which a person can't see). */
    fun isCommon(
        password: String,
        normalize: (String) -> String = { it },
    ): Boolean = normalize(password).trim().lowercase() in commonPasswords

    // A surrogate pair is one character to a person: count it once.
    private fun String.codePointLength(): Int = count { !it.isLowSurrogate() }

    /**
     * Common breach-corpus words that people pad to a length rule with a year, a run of digits or a
     * "!" (the dominant shape of 10+ character passwords in public breach lists). Combined with
     * [commonSuffixes] below; only combinations of [MIN_LENGTH] or more are kept.
     */
    private val commonWords =
        listOf(
            "password", "passw0rd", "p@ssword", "p@ssw0rd", "qwerty", "qwertyuiop", "iloveyou",
            "sunshine", "princess", "football", "baseball", "welcome", "dragon", "monkey", "master",
            "shadow", "letmein", "superman", "batman", "trustno1", "michael", "jessica", "charlie",
            "jordan", "hunter", "freedom", "whatever", "starwars", "pokemon", "computer", "internet",
            "samsung", "liverpool", "chelsea", "arsenal", "soccer", "summer", "flower", "secret",
            "admin", "hello", "lovely", "changeme", "abcdef", "abc123", "pynapli", "swinger",
            "lifestyle",
        )

    private val commonSuffixes =
        listOf(
            "", "1", "12", "123", "1234", "12345", "!", "1!", "123!", "12345!", "2024", "2025",
        )

    /**
     * About four hundred of the most common passwords in public breach corpora that are at least
     * [MIN_LENGTH] long (shorter ones are already refused by length): the keyboard, number and
     * phrase list below plus [commonWords] x [commonSuffixes]. Lowercase; compared lowercased.
     */
    internal val commonPasswords: Set<String> by lazy {
        (
            explicitCommonPasswords +
                commonWords.flatMap { word -> commonSuffixes.map { suffix -> word + suffix } }
        ).filter { it.length >= MIN_LENGTH }
            .toSet()
    }

    private val explicitCommonPasswords: Set<String> =
        setOf(
            "1234567890",
            "12345678910",
            "123456789a",
            "123456789q",
            "1234567890a",
            "a123456789",
            "a1234567890",
            "q123456789",
            "0123456789",
            "0987654321",
            "9876543210",
            "1111111111",
            "0000000000",
            "2222222222",
            "5555555555",
            "7777777777",
            "8888888888",
            "9999999999",
            "1212121212",
            "1122334455",
            "1029384756",
            "1357924680",
            "123123123123",
            "123321123321",
            "147258369147",
            "123456123456",
            "qwertyuiop",
            "qwertyuiop1",
            "qwertyuiop123",
            "qwerty1234",
            "qwerty12345",
            "qwerty123456",
            "qwertyqwerty",
            "asdfghjkl1",
            "asdfghjkl123",
            "asdfasdfasdf",
            "zxcvbnm123",
            "zxcvbnmasdfghjkl",
            "1q2w3e4r5t",
            "1q2w3e4r5t6y",
            "q1w2e3r4t5",
            "q1w2e3r4t5y6",
            "1qaz2wsx3edc",
            "1qazxsw23edc",
            "zaq12wsxcde3",
            "qazwsxedcrfv",
            "abcdefghij",
            "abcdefghijk",
            "abcd123456",
            "abcde12345",
            "abc1234567",
            "abc123456789",
            "aaaaaaaaaa",
            "password10",
            "password11",
            "password12",
            "password123",
            "password1234",
            "password12345",
            "password123!",
            "password!123",
            "passw0rd123",
            "p@ssword123",
            "p@ssw0rd123",
            "mypassword",
            "mypassword1",
            "mypassword123",
            "passwordpassword",
            "changeme123",
            "letmein123",
            "welcome123",
            "welcome1234",
            "iloveyou12",
            "iloveyou123",
            "iloveyou1234",
            "sunshine123",
            "princess123",
            "football123",
            "baseball123",
            "basketball",
            "basketball1",
            "starwars123",
            "superman123",
            "pokemon1234",
            "dragon1234",
            "monkey12345",
            "shadow1234",
            "master1234",
            "michael123",
            "jennifer123",
            "jordan2323",
            "trustno1234",
            "chocolate1",
            "chocolate123",
            "butterfly1",
            "liverpool1",
            "liverpool123",
            "manchester",
            "newyork123",
            "computer123",
            "internet123",
            "whatever123",
            "administrator",
            "admin12345",
            "admin123456",
            "pynapli123",
            "pynapli1234",
            "pynaplipassword",
            "swinger123",
            "swingers123",
            "lifestyle1",
            "lifestyle123",
        )
}
