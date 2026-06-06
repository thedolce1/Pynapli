package com.pynapli.shared.network

import com.pynapli.shared.core.result.AppResult
import com.pynapli.shared.network.dto.AccountDataExportDto
import com.pynapli.shared.network.dto.DeleteAccountRequestDto
import com.pynapli.shared.network.dto.DeleteAccountResponseDto

/**
 * Account slice of the API surface, split out of the monolithic [PynapliApiClient]
 * (which now composes the per-feature interfaces). Covers data-export requests and
 * account deletion. Signatures are unchanged.
 */
interface AccountApi {
    suspend fun getLatestDataExportRequest(): AppResult<AccountDataExportDto?>

    suspend fun requestDataExport(): AppResult<AccountDataExportDto>

    suspend fun deleteAccount(request: DeleteAccountRequestDto): AppResult<DeleteAccountResponseDto>
}
