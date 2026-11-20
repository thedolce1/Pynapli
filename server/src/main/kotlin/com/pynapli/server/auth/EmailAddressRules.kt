package com.pynapli.server.auth

/**
 * The strict address check for NEW sign-ups (and their resends): exactly one mailbox, nothing a mail
 * provider could read as a second recipient, a display name or a header. Applied to the normalised
 * (trimmed, lower-cased) address, the same form `users.email_hash` is computed from.
 *
 * Sign-in and password reset keep the old, loose check on purpose: an existing account whose stored
 * address would fail this must still be able to get in.
 */
internal object EmailAddressRules {
    private const val MAX_ADDRESS_LENGTH = 254
    private const val MAX_LOCAL_LENGTH = 64
    private const val MAX_LABEL_LENGTH = 63

    // RFC 5322 "atext" without the backtick (and without anything that needs quoting). The apostrophe
    // stays: o'brien@example.com is a common, valid mailbox. Local part only; domains never allow it.
    private const val LOCAL_SPECIALS = "!#$%&'*+/=?^_{|}~-"

    fun isStrict(address: String): Boolean {
        if (address.isEmpty() || address.length > MAX_ADDRESS_LENGTH) return false
        if (address.count { it == '@' } != 1) return false
        val local = address.substringBefore('@')
        val domain = address.substringAfter('@')
        return isLocalPart(local) && isDomain(domain)
    }

    /**
     * The address with any `+tag` removed from the local part. Used only to key the per-address
     * email cap, so `a+1@x.com`, `a+2@x.com`... share one budget. Never used for `email_hash`.
     */
    fun withoutPlusTag(address: String): String {
        val local = address.substringBefore('@')
        val domain = address.substringAfter('@', missingDelimiterValue = "")
        val base = local.substringBefore('+').ifEmpty { local }
        return if (domain.isEmpty()) base else "$base@$domain"
    }

    private fun isLocalPart(local: String): Boolean {
        if (local.isEmpty() || local.length > MAX_LOCAL_LENGTH) return false
        if (local.startsWith('.') || local.endsWith('.') || local.contains("..")) return false
        return local.all { it in 'a'..'z' || it in '0'..'9' || it == '.' || it in LOCAL_SPECIALS }
    }

    private fun isDomain(domain: String): Boolean {
        val labels = domain.split('.')
        if (labels.size < 2) return false
        if (labels.any { !isLabel(it) }) return false
        // A top-level domain is letters (or an IDN "xn--" label), never all digits.
        val tld = labels.last()
        return tld.length >= 2 && (tld.all { it in 'a'..'z' } || tld.startsWith("xn--"))
    }

    private fun isLabel(label: String): Boolean =
        label.isNotEmpty() &&
            label.length <= MAX_LABEL_LENGTH &&
            !label.startsWith('-') &&
            !label.endsWith('-') &&
            label.all { it in 'a'..'z' || it in '0'..'9' || it == '-' }
}
