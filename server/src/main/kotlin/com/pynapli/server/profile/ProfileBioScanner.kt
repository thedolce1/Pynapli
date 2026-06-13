package com.pynapli.server.profile

/**
 * Moderation seam for free-text bio writes (founder default 2026-06-10: seam
 * stubbed for MVP). A real scanner (keyword policy, LLM classifier, vendor API)
 * plugs in behind this interface via ServerDependencies without touching the
 * write path; rejection maps to BIO_REJECTED (400) before anything is stored.
 */
fun interface ProfileBioScanner {
    suspend fun scan(bio: String): Decision

    enum class Decision {
        Accept,
        Reject,
    }
}

/** MVP default: every bio passes; moderation still applies via user reports. */
object AcceptAllProfileBioScanner : ProfileBioScanner {
    override suspend fun scan(bio: String): ProfileBioScanner.Decision = ProfileBioScanner.Decision.Accept
}
