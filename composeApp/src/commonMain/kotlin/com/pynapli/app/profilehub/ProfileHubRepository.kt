package com.pynapli.app.profilehub

import com.pynapli.shared.core.result.AppError
import com.pynapli.shared.core.result.AppResult
import com.pynapli.shared.network.dto.AccountDataExportDto
import com.pynapli.shared.network.dto.AgeVerificationStatusDto
import com.pynapli.shared.network.dto.BlocksListDto
import com.pynapli.shared.network.dto.DeleteAccountRequestDto
import com.pynapli.shared.network.dto.DeleteAccountResponseDto
import com.pynapli.shared.network.dto.HiddenProfilesListDto
import com.pynapli.shared.network.dto.MeDto
import com.pynapli.shared.network.dto.PasswordChangeDto
import com.pynapli.shared.network.dto.PasswordChangedDto
import com.pynapli.shared.network.dto.ReportHistoryListDto
import com.pynapli.shared.network.dto.StartAgeVerificationRequestDto
import com.pynapli.shared.network.dto.SubscriptionEntitlementDto
import com.pynapli.shared.network.dto.SupportContactDto
import com.pynapli.shared.network.dto.UpdateDiscoverySettingsRequestDto
import com.pynapli.shared.network.dto.UpdateProfileRequestDto

interface ProfileHubRepository {
    suspend fun loadMe(): AppResult<MeDto>

    suspend fun getSubscriptionEntitlement(): AppResult<SubscriptionEntitlementDto>

    suspend fun loadAgeVerificationStatus(): AppResult<AgeVerificationStatusDto>

    suspend fun startAgeVerification(
        request: StartAgeVerificationRequestDto,
    ): AppResult<AgeVerificationStatusDto>

    suspend fun updateProfile(request: UpdateProfileRequestDto): AppResult<MeDto>

    suspend fun updateDiscoverySettings(request: UpdateDiscoverySettingsRequestDto): AppResult<MeDto>

    suspend fun listBlocks(): AppResult<BlocksListDto>

    suspend fun unblockUser(userId: String): AppResult<Unit>

    suspend fun listHiddenProfiles(): AppResult<HiddenProfilesListDto>

    suspend fun unhideProfile(userId: String): AppResult<Unit>

    suspend fun listReportHistory(): AppResult<ReportHistoryListDto>

    suspend fun listAppeals(): AppResult<com.pynapli.shared.network.dto.AppealListDto> = AppResult.Failure(AppError(code = "NOT_SUPPORTED", message = "Not available."))

    suspend fun withdrawAppeal(appealId: String): AppResult<com.pynapli.shared.network.dto.AppealDto> = AppResult.Failure(AppError(code = "NOT_SUPPORTED", message = "Not available."))

    suspend fun submitAppeal(request: com.pynapli.shared.network.dto.CreateAppealRequestDto): AppResult<com.pynapli.shared.network.dto.AppealDto> = AppResult.Failure(AppError(code = "NOT_SUPPORTED", message = "Not available."))

    suspend fun getSupportContact(): AppResult<SupportContactDto>

    suspend fun latestDataExportRequest(): AppResult<AccountDataExportDto?>

    suspend fun requestDataExport(): AppResult<AccountDataExportDto>

    suspend fun deleteAccount(request: DeleteAccountRequestDto): AppResult<DeleteAccountResponseDto>

    suspend fun changePassword(request: PasswordChangeDto): AppResult<PasswordChangedDto> = AppResult.Failure(AppError(code = "NOT_SUPPORTED", message = "Not available."))

    suspend fun listSignedInDevices(): AppResult<com.pynapli.shared.network.dto.SignedInDevicesDto> = AppResult.Failure(AppError(code = "NOT_SUPPORTED", message = "Not available."))

    suspend fun signOutDevice(sessionId: String): AppResult<com.pynapli.shared.network.dto.SignedOutDevicesDto> = AppResult.Failure(AppError(code = "NOT_SUPPORTED", message = "Not available."))

    suspend fun signOutOtherDevices(): AppResult<com.pynapli.shared.network.dto.SignedOutDevicesDto> = AppResult.Failure(AppError(code = "NOT_SUPPORTED", message = "Not available."))
}
