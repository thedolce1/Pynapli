package com.pynapli.server.account

import com.pynapli.shared.network.dto.AccountDataExportDto
import com.pynapli.shared.network.dto.DeleteAccountResponseDto
import java.time.Clock
import java.util.UUID

class AccountLifecycleService(
    private val repository: AccountLifecycleRepository,
    private val exportStorage: AccountExportStorage? = null,
    private val clock: Clock = Clock.systemUTC(),
    /**
     * Revokes the deleted account's Apple grants after the deletion committed (Apple requires it);
     * off the request path and best-effort ([com.pynapli.server.auth.social.AppleGrantRevoker]).
     */
    private val revokeAppleGrants: ((List<com.pynapli.server.auth.social.SealedAppleToken>) -> Unit)? = null,
) {
    suspend fun latestDataExportRequest(userId: UUID): AccountDataExportDto? = repository.latestDataExportRequest(userId)?.toDto()

    suspend fun requestDataExport(userId: UUID): AccountDataExportDto = repository.createDataExportRequest(userId).toDto()

    /**
     * Serves a locally stored export to whoever holds its download link ([key] is the link's secret).
     * Only an unexpired `ready` export with a matching key is returned; every other case throws
     * `EXPORT_NOT_FOUND`, so a wrong key reveals nothing.
     */
    suspend fun downloadExport(
        requestIdText: String,
        key: String,
    ): ExportArchive {
        val storage = exportStorage ?: throw AccountLifecycleException("EXPORT_NOT_FOUND")
        val requestId = runCatching { UUID.fromString(requestIdText) }.getOrElse { throw AccountLifecycleException("INVALID_ID") }
        val record = repository.exportRequestById(requestId) ?: throw AccountLifecycleException("EXPORT_NOT_FOUND")
        if (record.status != "ready" || record.isPastExpiry()) throw AccountLifecycleException("EXPORT_NOT_FOUND")
        return storage.read(requestId, key) ?: throw AccountLifecycleException("EXPORT_NOT_FOUND")
    }

    suspend fun deleteAccount(
        userId: UUID,
        confirmation: String,
    ): DeleteAccountResponseDto {
        if (confirmation.trim() != DELETE_CONFIRMATION) {
            throw AccountLifecycleException("INVALID_ACCOUNT_DELETE_CONFIRMATION")
        }

        val result =
            repository.deleteAccount(userId)
                ?: throw AccountLifecycleException("ACCOUNT_ALREADY_DELETED")
        if (result.appleTokens.isNotEmpty()) runCatching { revokeAppleGrants?.invoke(result.appleTokens) }

        return DeleteAccountResponseDto(
            deleted = true,
            revokedSessionCount = result.revokedSessionCount,
            message = "Your account has been deleted and removed from discovery.",
        )
    }

    private fun AccountDataExportRecord.isPastExpiry(): Boolean = expiresAt?.let { !clock.instant().isBefore(it) } ?: false

    private fun AccountDataExportRecord.toDto(): AccountDataExportDto {
        // Nothing moves rows from 'ready' to 'expired', so report a lapsed link as expired here
        // rather than hand the app a dead URL.
        val effectiveStatus = if (status == "ready" && isPastExpiry()) "expired" else status
        return AccountDataExportDto(
            requestId = id.toString(),
            status = effectiveStatus,
            requestedAt = requestedAt.toString(),
            completedAt = completedAt?.toString(),
            expiresAt = expiresAt?.toString(),
            downloadUrl = downloadUrl.takeIf { effectiveStatus == "ready" },
            message = messageForExportStatus(effectiveStatus, errorMessage),
        )
    }

    private fun messageForExportStatus(
        status: String,
        errorMessage: String?,
    ): String =
        when (status) {
            "requested" -> "Your data export request has been received."
            "processing" -> "Your data export is being prepared."
            "ready" -> "Your data export is ready."
            "failed" -> errorMessage ?: "Your data export could not be prepared."
            "expired" -> "Your previous data export has expired."
            else -> "Your data export status is available."
        }

    companion object {
        const val DELETE_CONFIRMATION = "DELETE"
    }
}

class AccountLifecycleException(
    val code: String,
) : RuntimeException(code)
