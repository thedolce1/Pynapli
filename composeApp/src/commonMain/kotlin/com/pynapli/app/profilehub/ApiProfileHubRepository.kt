package com.pynapli.app.profilehub

import com.pynapli.shared.core.result.AppResult
import com.pynapli.shared.network.PynapliApiClient
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

class ApiProfileHubRepository(
    private val apiClient: PynapliApiClient,
) : ProfileHubRepository {
    override suspend fun loadMe(): AppResult<MeDto> = apiClient.getMe()

    override suspend fun getSubscriptionEntitlement(): AppResult<SubscriptionEntitlementDto> = apiClient.getSubscriptionEntitlement()

    override suspend fun loadAgeVerificationStatus(): AppResult<AgeVerificationStatusDto> = apiClient.getAgeVerificationStatus()

    override suspend fun startAgeVerification(
        request: StartAgeVerificationRequestDto,
    ): AppResult<AgeVerificationStatusDto> = apiClient.startAgeVerification(request)

    override suspend fun updateProfile(request: UpdateProfileRequestDto): AppResult<MeDto> = apiClient.updateProfile(request)

    override suspend fun updateDiscoverySettings(request: UpdateDiscoverySettingsRequestDto): AppResult<MeDto> = apiClient.updateDiscoverySettings(request)

    override suspend fun listBlocks(): AppResult<BlocksListDto> = apiClient.listBlocks()

    override suspend fun unblockUser(userId: String): AppResult<Unit> = apiClient.unblockUser(userId)

    override suspend fun listHiddenProfiles(): AppResult<HiddenProfilesListDto> = apiClient.listHiddenProfiles()

    override suspend fun unhideProfile(userId: String): AppResult<Unit> = apiClient.unhideProfile(userId)

    override suspend fun listReportHistory(): AppResult<ReportHistoryListDto> = apiClient.listReports()

    override suspend fun getSupportContact(): AppResult<SupportContactDto> = apiClient.getSupportContact()

    override suspend fun latestDataExportRequest(): AppResult<AccountDataExportDto?> = apiClient.getLatestDataExportRequest()

    override suspend fun requestDataExport(): AppResult<AccountDataExportDto> = apiClient.requestDataExport()

    override suspend fun deleteAccount(request: DeleteAccountRequestDto): AppResult<DeleteAccountResponseDto> = apiClient.deleteAccount(request)

    override suspend fun changePassword(request: PasswordChangeDto): AppResult<PasswordChangedDto> = apiClient.changePassword(request)
}
