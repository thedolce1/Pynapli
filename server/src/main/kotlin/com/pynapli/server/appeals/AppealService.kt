package com.pynapli.server.appeals

import com.pynapli.server.moderation.AdminRiskService
import com.pynapli.shared.network.dto.AdminResolveAppealRequestDto
import com.pynapli.shared.network.dto.AppealDto
import com.pynapli.shared.network.dto.AppealListResponseDto
import com.pynapli.shared.network.dto.SubmitAppealRequestDto
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.Base64
import java.util.UUID

class AppealService(
    private val repository: AppealRepository,
    private val eligibilityRepository: AppealEligibilityRepository,
    private val adminRiskService: AdminRiskService,
    private val notAppealablePolicy: NotAppealablePolicy = NotAppealablePolicy.AllAppealable,
    private val appealWindow: Duration = DEFAULT_APPEAL_WINDOW,
    private val clock: Clock = Clock.systemUTC(),
) {
    /** Submit an appeal on behalf of the authenticated user. */
    suspend fun submitAppeal(
        userId: UUID,
        request: SubmitAppealRequestDto,
    ): AppealDto {
        val subjectType = request.subjectType.trim().lowercase()
        if (subjectType !in SUBJECT_TYPES) throw AppealException("INVALID_APPEAL_SUBJECT")
        val subjectRef = request.subjectRef?.trim()?.takeIf { it.isNotEmpty() }
        val reason = request.reason.normalizedAppealReasonOrThrow()

        if (subjectType == SUBJECT_ACCOUNT) {
            if (subjectRef != null) throw AppealException("INVALID_APPEAL_SUBJECT")
            enforceAccountEligibility(userId)
        } else {
            // Content/message/media appeals must name the moderated subject.
            if (subjectRef == null) throw AppealException("INVALID_APPEAL_SUBJECT")
        }

        // Severe/legally-prohibited actions (e.g. confirmed CSAM) are not appealable.
        if (notAppealablePolicy.isNotAppealable(userId, subjectType, subjectRef)) {
            throw AppealException("APPEAL_NOT_ALLOWED")
        }

        val created =
            repository.createAppeal(
                userId = userId,
                subjectType = subjectType,
                subjectRef = subjectRef,
                reason = reason,
            ) ?: throw AppealException("APPEAL_ALREADY_EXISTS")
        return created.toDto()
    }

    suspend fun listMyAppeals(userId: UUID): AppealListResponseDto = AppealListResponseDto(items = repository.listAppealsForUser(userId).map { it.toDto() })

    /** Admin: keyset page of the pending-appeal queue. */
    suspend fun listPending(
        cursorToken: String?,
        limitText: String?,
    ): AppealListResponseDto {
        val limit = limitText.toLimitOrThrow()
        val cursor = cursorToken?.let { decodeCursor(it) }
        val rows = repository.listPendingAppeals(cursor, limit + 1)
        val page = rows.take(limit)
        val nextCursor =
            if (rows.size > limit && page.isNotEmpty()) {
                encodeCursor(AppealCursor(createdAt = page.last().createdAt, id = page.last().id))
            } else {
                null
            }
        return AppealListResponseDto(items = page.map { it.toDto() }, nextCursor = nextCursor)
    }

    /**
     * Admin resolution. Approving an `account` appeal restores the account by clearing risk to the
     * `clean` tier via [AdminRiskService.setRiskTier]; denial only records the resolution.
     */
    suspend fun resolve(
        appealIdText: String,
        request: AdminResolveAppealRequestDto,
        adminId: String,
    ): AppealDto {
        val appealId = appealIdText.toAppealUuidOrThrow()
        val actor = adminId.normalizedActorIdOrThrow()
        val reason = request.reason.normalizedAdminReasonOrThrow()

        val appeal = repository.getAppeal(appealId) ?: throw AppealException("APPEAL_NOT_FOUND")
        if (appeal.status != STATUS_PENDING) throw AppealException("APPEAL_NOT_PENDING")

        // Restore BEFORE marking approved so a restore failure does not leave the appeal
        // approved-but-not-restored. setRiskTier is idempotent for an already-clean account.
        if (request.approve && appeal.subjectType == SUBJECT_ACCOUNT) {
            adminRiskService.setRiskTier(
                userIdText = appeal.userId.toString(),
                tierText = "clean",
                actorId = actor,
                reason = reason,
            )
        }

        val nextStatus = if (request.approve) STATUS_APPROVED else STATUS_DENIED
        val resolved =
            repository.resolveAppeal(
                id = appealId,
                status = nextStatus,
                adminId = actor,
                note = reason,
            ) ?: throw AppealException("APPEAL_NOT_PENDING")
        return resolved.toDto()
    }

    private suspend fun enforceAccountEligibility(userId: UUID) {
        val riskState =
            eligibilityRepository.riskStateForUser(userId)
                ?: throw AppealException("APPEAL_NOT_ALLOWED")
        if (riskState.tier !in APPEALABLE_ACCOUNT_TIERS) throw AppealException("APPEAL_NOT_ALLOWED")
        val deadline = riskState.actionedAt.plus(appealWindow)
        if (clock.instant().isAfter(deadline)) throw AppealException("APPEAL_WINDOW_EXPIRED")
    }

    private fun AppealRecord.toDto(): AppealDto =
        AppealDto(
            id = id.toString(),
            subjectType = subjectType,
            subjectRef = subjectRef,
            status = status,
            createdAt = createdAt.toString(),
            resolvedAt = resolvedAt?.toString(),
            // Note: resolution_note is admin-facing context; safe to echo to the appealing user.
            resolutionNote = resolutionNote,
        )

    private fun String.normalizedAppealReasonOrThrow(): String {
        val normalized = trim()
        if (normalized.isEmpty()) throw AppealException("APPEAL_REASON_REQUIRED")
        if (normalized.length > MAX_REASON_LENGTH) throw AppealException("APPEAL_REASON_TOO_LONG")
        return normalized
    }

    /**
     * Admin resolution reason. Capped at 500 to satisfy the admin_audit_log reason constraint and to
     * mirror the moderation queues' reason length, using the moderation error code so the admin
     * surfaces share one contract.
     */
    private fun String?.normalizedAdminReasonOrThrow(): String {
        val normalized = this?.trim().orEmpty()
        if (normalized.isEmpty()) throw AppealException("MODERATION_REASON_REQUIRED")
        if (normalized.length > MAX_ADMIN_REASON_LENGTH) throw AppealException("MODERATION_REASON_TOO_LONG")
        return normalized
    }

    private fun String.normalizedActorIdOrThrow(): String {
        val normalized = trim()
        if (normalized.length !in 2..120) throw AppealException("INVALID_ADMIN_ACTOR")
        return normalized
    }

    private fun String.toAppealUuidOrThrow(): UUID = runCatching { UUID.fromString(this) }.getOrElse { throw AppealException("INVALID_APPEAL_ID") }

    private fun String?.toLimitOrThrow(): Int {
        val normalized = this?.takeIf { it.isNotBlank() } ?: return DEFAULT_LIMIT
        return normalized.toIntOrNull()?.takeIf { it in 1..MAX_LIMIT }
            ?: throw AppealException("INVALID_REVIEW_LIMIT")
    }

    private fun encodeCursor(cursor: AppealCursor): String =
        Base64.getUrlEncoder().withoutPadding().encodeToString(
            "${cursor.createdAt.toEpochMilli()}:${cursor.id}".toByteArray(Charsets.UTF_8),
        )

    private fun decodeCursor(token: String): AppealCursor =
        runCatching {
            val decoded = String(Base64.getUrlDecoder().decode(token), Charsets.UTF_8)
            val (millis, idText) = decoded.split(":", limit = 2)
            AppealCursor(createdAt = Instant.ofEpochMilli(millis.toLong()), id = UUID.fromString(idText))
        }.getOrElse { throw AppealException("INVALID_APPEAL_CURSOR") }

    companion object {
        const val SUBJECT_ACCOUNT = "account"
        private val SUBJECT_TYPES = setOf(SUBJECT_ACCOUNT, "content", "message", "media")
        private val APPEALABLE_ACCOUNT_TIERS = setOf("quarantine", "terminate")
        private val DEFAULT_APPEAL_WINDOW: Duration = Duration.ofDays(30)
        private const val STATUS_PENDING = "pending"
        private const val STATUS_APPROVED = "approved"
        private const val STATUS_DENIED = "denied"
        private const val MAX_REASON_LENGTH = 2000
        private const val MAX_ADMIN_REASON_LENGTH = 500
        private const val DEFAULT_LIMIT = 50
        private const val MAX_LIMIT = 100
    }
}

class AppealException(
    val code: String,
) : RuntimeException(code)

/**
 * Decides whether a given moderation action is non-appealable (confirmed CSAM, severe legal/fraud).
 * Default production wiring treats everything as appealable; a future ops integration can supply a
 * policy backed by the moderation case record. The hook exists so the server can enforce the
 * spec's "no appeal for confirmed CSAM or severe legal/fraud triggers" rule per appeal.
 */
fun interface NotAppealablePolicy {
    suspend fun isNotAppealable(
        userId: UUID,
        subjectType: String,
        subjectRef: String?,
    ): Boolean

    companion object {
        val AllAppealable = NotAppealablePolicy { _, _, _ -> false }
    }
}
