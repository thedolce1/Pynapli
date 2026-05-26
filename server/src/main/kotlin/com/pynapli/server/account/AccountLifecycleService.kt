package com.pynapli.server.account

import com.pynapli.shared.network.dto.AccountDataExportDto
import com.pynapli.shared.network.dto.DeleteAccountResponseDto
import java.util.UUID

class AccountLifecycleService(
    private val repository: AccountLifecycleRepository
) {
    suspend fun latestDataExportRequest(userId: UUID): AccountDataExportDto? =
        repository.latestDataExportRequest(userId)?.toDto()

    suspend fun requestDataExport(userId: UUID): AccountDataExportDto =
        repository.createDataExportRequest(userId).toDto()

    suspend fun deleteAccount(userId: UUID, confirmation: String): DeleteAccountResponseDto {
        if (confirmation.trim() != DELETE_CONFIRMATION) {
            throw AccountLifecycleException("INVALID_ACCOUNT_DELETE_CONFIRMATION")
        }

        val result = repository.deleteAccount(userId)
            ?: throw AccountLifecycleException("ACCOUNT_ALREADY_DELETED")

        return DeleteAccountResponseDto(
            deleted = true,
            revokedSessionCount = result.revokedSessionCount,
            message = "Your account has been deleted and removed from discovery."
        )
    }

    private fun AccountDataExportRecord.toDto(): AccountDataExportDto =
        AccountDataExportDto(
            requestId = id.toString(),
            status = status,
            requestedAt = requestedAt.toString(),
            completedAt = completedAt?.toString(),
            expiresAt = expiresAt?.toString(),
            downloadUrl = downloadUrl,
            message = messageForExportStatus(status, errorMessage)
        )

    private fun messageForExportStatus(status: String, errorMessage: String?): String =
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

class AccountLifecycleException(val code: String) : RuntimeException(code)
