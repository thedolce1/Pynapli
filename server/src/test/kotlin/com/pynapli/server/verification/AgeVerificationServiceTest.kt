package com.pynapli.server.verification

import com.pynapli.shared.network.dto.AgeVerificationCallbackRequestDto
import com.pynapli.shared.network.dto.AdminAgeVerificationReviewRequestDto
import com.pynapli.shared.network.dto.StartAgeVerificationRequestDto
import kotlinx.coroutines.runBlocking
import org.junit.Test
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull

class AgeVerificationServiceTest {
    private val now = Instant.parse("2026-05-12T12:00:00Z")
    private val clock = Clock.fixed(now, ZoneOffset.UTC)

    @Test
    fun startRequiresExplicitAdultConfirmation() = runBlocking {
        val userId = UUID.randomUUID()
        val service = AgeVerificationService(
            InMemoryAgeVerificationRepository(profile(userId, birthdate = LocalDate.parse("1990-01-01"))),
            clock
        )

        val error = assertFailsWith<VerificationException> {
            service.start(userId, StartAgeVerificationRequestDto(confirmedAdult = false))
        }

        assertEquals("ADULT_CONFIRMATION_REQUIRED", error.code)
    }

    @Test
    fun startRequiresBirthdateOnProfile() = runBlocking {
        val userId = UUID.randomUUID()
        val service = AgeVerificationService(
            InMemoryAgeVerificationRepository(profile(userId, birthdate = null)),
            clock
        )

        val error = assertFailsWith<VerificationException> {
            service.start(userId, StartAgeVerificationRequestDto(confirmedAdult = true))
        }

        assertEquals("BIRTHDATE_REQUIRED", error.code)
    }

    @Test
    fun startRejectsUnderageProfile() = runBlocking {
        val userId = UUID.randomUUID()
        val service = AgeVerificationService(
            InMemoryAgeVerificationRepository(profile(userId, birthdate = LocalDate.parse("2010-01-01"))),
            clock
        )

        val error = assertFailsWith<VerificationException> {
            service.start(userId, StartAgeVerificationRequestDto(confirmedAdult = true))
        }

        assertEquals("AGE_RESTRICTED", error.code)
    }

    @Test
    fun startCreatesOnePendingManualReviewForAdultProfile() = runBlocking {
        val userId = UUID.randomUUID()
        val repository = InMemoryAgeVerificationRepository(profile(userId, birthdate = LocalDate.parse("1990-01-01")))
        val service = AgeVerificationService(repository, clock)

        val first = service.start(userId, StartAgeVerificationRequestDto(confirmedAdult = true))
        val second = service.start(userId, StartAgeVerificationRequestDto(confirmedAdult = true))

        assertEquals("pending", first.status)
        assertEquals("pending", second.status)
        assertEquals(1, repository.records.size)
        assertEquals("manual_review", repository.records.single().method)
    }

    @Test
    fun approvedReviewReturnsVerifiedStatusAndMarksProfileVerified() = runBlocking {
        val userId = UUID.randomUUID()
        val repository = InMemoryAgeVerificationRepository(profile(userId, birthdate = LocalDate.parse("1990-01-01")))
        val service = AgeVerificationService(repository, clock)
        service.start(userId, StartAgeVerificationRequestDto(confirmedAdult = true))
        val verificationId = repository.records.single().id.toString()

        val status = service.review(
            verificationId,
            AdminAgeVerificationReviewRequestDto(status = "approved", reason = "manual age check"),
            actorId = "admin-1"
        )

        assertEquals("verified", status.status)
        assertEquals(now.toString(), status.verifiedAt)
        assertEquals(now, assertNotNull(repository.profileState.ageVerifiedAt))
    }

    @Test
    fun rejectedReviewDoesNotMarkProfileVerified() = runBlocking {
        val userId = UUID.randomUUID()
        val repository = InMemoryAgeVerificationRepository(profile(userId, birthdate = LocalDate.parse("1990-01-01")))
        val service = AgeVerificationService(repository, clock)
        service.start(userId, StartAgeVerificationRequestDto(confirmedAdult = true))
        val verificationId = repository.records.single().id.toString()

        val status = service.review(
            verificationId,
            AdminAgeVerificationReviewRequestDto(status = "rejected", reason = "could not verify"),
            actorId = "admin-1"
        )

        assertEquals("rejected", status.status)
        assertEquals(null, status.verifiedAt)
        assertEquals(null, repository.profileState.ageVerifiedAt)
    }

    @Test
    fun verifiedProviderCallbackCreatesRecordAndMarksProfileVerified() = runBlocking {
        val userId = UUID.randomUUID()
        val repository = InMemoryAgeVerificationRepository(profile(userId, birthdate = LocalDate.parse("1990-01-01")))
        val service = AgeVerificationService(repository, clock)

        val status = service.callback(
            AgeVerificationCallbackRequestDto(
                userId = userId.toString(),
                provider = "persona",
                providerReference = "provider-ref-1",
                status = "verified"
            ),
            actorId = "provider-worker",
            fallbackReason = "provider callback"
        )

        assertEquals("verified", status.status)
        assertEquals(now.toString(), status.verifiedAt)
        assertEquals(now, repository.profileState.ageVerifiedAt)
        assertEquals("provider_callback", repository.records.single().method)
        assertEquals("provider-ref-1", repository.records.single().providerReference)
    }

    private fun profile(userId: UUID, birthdate: LocalDate?): AgeVerificationProfileState =
        AgeVerificationProfileState(
            userId = userId,
            birthdate = birthdate,
            ageVerifiedAt = null
        )
}

private class InMemoryAgeVerificationRepository(
    initialProfileState: AgeVerificationProfileState
) : AgeVerificationRepository {
    var profileState = initialProfileState
        private set
    val records = mutableListOf<AgeVerificationRecord>()

    override suspend fun getProfileState(userId: UUID): AgeVerificationProfileState? =
        profileState.takeIf { it.userId == userId }

    override suspend fun latestForUser(userId: UUID): AgeVerificationRecord? =
        records.filter { it.userId == userId }.maxByOrNull { it.requestedAt }

    override suspend fun findById(verificationId: UUID): AgeVerificationRecord? =
        records.firstOrNull { it.id == verificationId }

    override suspend fun findByProviderReference(provider: String, providerReference: String): AgeVerificationRecord? =
        records.firstOrNull { it.provider == provider && it.providerReference == providerReference }

    override suspend fun createPending(
        verificationId: UUID,
        userId: UUID,
        market: String,
        method: String,
        provider: String,
        providerReference: String?,
        requestedAt: Instant
    ): AgeVerificationRecord {
        val record = AgeVerificationRecord(
            id = verificationId,
            userId = userId,
            market = market,
            method = method,
            provider = provider,
            providerReference = providerReference,
            status = "pending",
            requestedAt = requestedAt,
            verifiedAt = null,
            expiresAt = null,
            reviewedAt = null,
            reviewerId = null,
            reviewReason = null
        )
        records += record
        return record
    }

    override suspend fun recordCallback(
        verificationId: UUID,
        userId: UUID,
        market: String,
        method: String,
        provider: String,
        providerReference: String?,
        status: String,
        verifiedAt: Instant?,
        expiresAt: Instant?,
        reviewerId: String,
        reason: String?,
        reviewedAt: Instant
    ): AgeVerificationRecord {
        val record = AgeVerificationRecord(
            id = verificationId,
            userId = userId,
            market = market,
            method = method,
            provider = provider,
            providerReference = providerReference,
            status = status,
            requestedAt = reviewedAt,
            verifiedAt = verifiedAt,
            expiresAt = expiresAt,
            reviewedAt = reviewedAt,
            reviewerId = reviewerId,
            reviewReason = reason
        )
        records.removeAll { it.id == verificationId }
        records += record
        if (status == "verified" && verifiedAt != null) {
            profileState = profileState.copy(ageVerifiedAt = verifiedAt)
        }
        return record
    }

    override suspend fun review(
        verificationId: UUID,
        status: String,
        verifiedAt: Instant?,
        expiresAt: Instant?,
        reviewerId: String,
        reason: String?,
        reviewedAt: Instant
    ): AgeVerificationRecord? {
        val index = records.indexOfFirst { it.id == verificationId }
        if (index == -1) return null
        val record = records[index].copy(
            status = status,
            verifiedAt = verifiedAt,
            expiresAt = expiresAt,
            reviewedAt = reviewedAt,
            reviewerId = reviewerId,
            reviewReason = reason
        )
        records[index] = record
        if (status == "verified" && verifiedAt != null) {
            profileState = profileState.copy(ageVerifiedAt = verifiedAt)
        }
        return record
    }
}
