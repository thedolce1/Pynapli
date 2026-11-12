package com.pynapli.shared.network

import com.pynapli.shared.core.result.AppError
import com.pynapli.shared.core.result.AppResult
import com.pynapli.shared.network.dto.AppealDto
import com.pynapli.shared.network.dto.AppealListDto
import com.pynapli.shared.network.dto.BlockSummaryDto
import com.pynapli.shared.network.dto.BlockUserRequestDto
import com.pynapli.shared.network.dto.BlocksListDto
import com.pynapli.shared.network.dto.CreateAppealRequestDto
import com.pynapli.shared.network.dto.CreateReportRequestDto
import com.pynapli.shared.network.dto.HiddenProfileSummaryDto
import com.pynapli.shared.network.dto.HiddenProfilesListDto
import com.pynapli.shared.network.dto.ReportDto
import com.pynapli.shared.network.dto.ReportHistoryListDto
import com.pynapli.shared.network.dto.SupportContactDto

/**
 * Safety/trust slice of the API surface, split out of the monolithic [PynapliApiClient]
 * (which now composes the per-feature interfaces). Covers blocks, hidden profiles, reports,
 * and the support contact. Signatures are unchanged.
 */
interface SafetyApi {
    suspend fun blockUser(
        targetUserId: String,
        request: BlockUserRequestDto = BlockUserRequestDto(),
    ): AppResult<BlockSummaryDto>

    suspend fun unblockUser(targetUserId: String): AppResult<Unit>

    suspend fun listBlocks(): AppResult<BlocksListDto>

    suspend fun hideProfile(targetUserId: String): AppResult<HiddenProfileSummaryDto>

    suspend fun unhideProfile(targetUserId: String): AppResult<Unit>

    suspend fun listHiddenProfiles(): AppResult<HiddenProfilesListDto>

    suspend fun submitReport(request: CreateReportRequestDto): AppResult<ReportDto>

    suspend fun listReports(limit: Int? = null): AppResult<ReportHistoryListDto>

    suspend fun getSupportContact(): AppResult<SupportContactDto>

    // Defaults keep test doubles compiling; the Ktor client implements both.
    suspend fun listAppeals(): AppResult<AppealListDto> = AppResult.Failure(AppError(code = "NOT_SUPPORTED", message = "Not available."))

    suspend fun submitAppeal(request: CreateAppealRequestDto): AppResult<AppealDto> = AppResult.Failure(AppError(code = "NOT_SUPPORTED", message = "Not available."))
}
