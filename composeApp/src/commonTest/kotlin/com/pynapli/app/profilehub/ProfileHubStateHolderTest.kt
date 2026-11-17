package com.pynapli.app.profilehub

import com.pynapli.shared.core.result.AppError
import com.pynapli.shared.core.result.AppResult
import com.pynapli.shared.network.dto.AccountDataExportDto
import com.pynapli.shared.network.dto.AgeVerificationStatusDto
import com.pynapli.shared.network.dto.AuthUserDto
import com.pynapli.shared.network.dto.BlockSummaryDto
import com.pynapli.shared.network.dto.BlocksListDto
import com.pynapli.shared.network.dto.DeleteAccountRequestDto
import com.pynapli.shared.network.dto.DeleteAccountResponseDto
import com.pynapli.shared.network.dto.DiscoverySettingsDto
import com.pynapli.shared.network.dto.EntitlementSource
import com.pynapli.shared.network.dto.HiddenProfileSummaryDto
import com.pynapli.shared.network.dto.HiddenProfilesListDto
import com.pynapli.shared.network.dto.MeDto
import com.pynapli.shared.network.dto.ProfileDto
import com.pynapli.shared.network.dto.ReportCategory
import com.pynapli.shared.network.dto.ReportHistoryItemDto
import com.pynapli.shared.network.dto.ReportHistoryListDto
import com.pynapli.shared.network.dto.StartAgeVerificationRequestDto
import com.pynapli.shared.network.dto.SubscriptionEntitlementDto
import com.pynapli.shared.network.dto.SupportContactDto
import com.pynapli.shared.network.dto.UpdateDiscoverySettingsRequestDto
import com.pynapli.shared.network.dto.UpdateProfileRequestDto
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ProfileHubStateHolderTest {
    @Test
    fun loadKeepsProfileVisibleWhenAgeStatusUnavailable() =
        runTest {
            val repository =
                FakeProfileHubRepository(
                    loadAgeResult = AppResult.Failure(AppError("NETWORK_ERROR", "Offline")),
                )
            val stateHolder = ProfileHubStateHolder(repository)

            stateHolder.load()

            assertEquals(
                "Maya",
                stateHolder.state.value.me
                    ?.profile
                    ?.displayName,
            )
            assertNull(stateHolder.state.value.ageVerificationStatus)
            assertNull(stateHolder.state.value.errorMessage)
        }

    @Test
    fun loadPopulatesSubscriptionEntitlementForSubscriptionSection() =
        runTest {
            val repository =
                FakeProfileHubRepository(
                    entitlementResult =
                        AppResult.Success(
                            SubscriptionEntitlementDto(
                                tier = "select",
                                source = EntitlementSource.OWN_SUBSCRIPTION,
                                displayName = "Pynapli Select",
                                linkedPartnerIncluded = true,
                            ),
                        ),
                )
            val stateHolder = ProfileHubStateHolder(repository)

            stateHolder.load()

            assertEquals(
                "Pynapli Select",
                stateHolder.state.value.entitlement
                    ?.displayName,
            )
            assertEquals("Pynapli Select", currentPlanSummary(stateHolder.state.value))
            assertEquals("Select", planBadgeLabel(stateHolder.state.value))
        }

    @Test
    fun loadKeepsHubUsableWhenEntitlementUnavailable() =
        runTest {
            val repository =
                FakeProfileHubRepository(
                    entitlementResult = AppResult.Failure(AppError("NETWORK_ERROR", "Offline")),
                )
            val stateHolder = ProfileHubStateHolder(repository)

            stateHolder.load()

            assertNull(stateHolder.state.value.entitlement)
            assertEquals("Free", currentPlanSummary(stateHolder.state.value))
            assertEquals("Free", planBadgeLabel(stateHolder.state.value))
            assertNull(stateHolder.state.value.errorMessage)
        }

    @Test
    fun loadStartsIndependentAccountRequestsTogether() =
        runTest {
            val loadMeStarted = CompletableDeferred<Unit>()
            val loadAgeStarted = CompletableDeferred<Unit>()
            val latestExportStarted = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val repository =
                FakeProfileHubRepository(
                    beforeLoadMe = {
                        loadMeStarted.complete(Unit)
                        release.await()
                    },
                    beforeLoadAge = {
                        loadAgeStarted.complete(Unit)
                        release.await()
                    },
                    beforeLatestDataExport = {
                        latestExportStarted.complete(Unit)
                        release.await()
                    },
                )
            val stateHolder = ProfileHubStateHolder(repository)

            val load = async { stateHolder.load() }

            withTimeout(1_000) {
                loadMeStarted.await()
                loadAgeStarted.await()
                latestExportStarted.await()
            }
            release.complete(Unit)
            load.await()

            assertEquals(
                "Maya",
                stateHolder.state.value.me
                    ?.profile
                    ?.displayName,
            )
            assertNull(stateHolder.state.value.errorMessage)
        }

    @Test
    fun startAgeVerificationSendsAdultConfirmationAndShowsFeedback() =
        runTest {
            val repository =
                FakeProfileHubRepository(
                    startAgeResult =
                        AppResult.Success(
                            AgeVerificationStatusDto(
                                status = "pending",
                                method = "manual_review",
                                provider = "internal_manual",
                                requestedAt = "2026-05-12T12:00:00Z",
                                message = "Age verification is pending review.",
                            ),
                        ),
                )
            val stateHolder = ProfileHubStateHolder(repository)
            stateHolder.load()
            stateHolder.openAgeVerification()

            stateHolder.startAgeVerification()

            assertEquals(true, repository.lastStartRequest?.confirmedAdult)
            assertEquals(
                "pending",
                stateHolder.state.value.ageVerificationStatus
                    ?.status,
            )
            assertEquals(false, stateHolder.state.value.ageVerificationDialogVisible)
            assertEquals("Age verification is pending review.", stateHolder.state.value.feedbackMessage)
        }

    @Test
    fun failedAgeVerificationStartKeepsDialogOpenWithError() =
        runTest {
            val repository =
                FakeProfileHubRepository(
                    startAgeResult = AppResult.Failure(AppError("BIRTHDATE_REQUIRED", "Add your birthdate.")),
                )
            val stateHolder = ProfileHubStateHolder(repository)
            stateHolder.load()
            stateHolder.openAgeVerification()

            stateHolder.startAgeVerification()

            assertEquals(true, stateHolder.state.value.ageVerificationDialogVisible)
            assertEquals("Add your birthdate.", stateHolder.state.value.errorMessage)
        }

    @Test
    fun loadBlocksShowsSafetyCenterBlockList() =
        runTest {
            val repository =
                FakeProfileHubRepository(
                    listBlocksResult =
                        AppResult.Success(
                            BlocksListDto(
                                blocks =
                                    listOf(
                                        BlockSummaryDto(
                                            blockedUserId = "blocked-1",
                                            blockedDisplayName = "Jordan",
                                            createdAt = "2026-05-26T12:00:00Z",
                                        ),
                                    ),
                            ),
                        ),
                )
            val stateHolder = ProfileHubStateHolder(repository)

            stateHolder.openSafetyCenter()
            stateHolder.loadBlocks()

            assertEquals(true, stateHolder.state.value.safetyCenterVisible)
            assertEquals(
                "Jordan",
                stateHolder.state.value.blockedUsers
                    .single()
                    .blockedDisplayName,
            )
            assertEquals(null, stateHolder.state.value.errorMessage)
        }

    @Test
    fun loadSafetyListsStartsIndependentRequestsTogether() =
        runTest {
            val blocksStarted = CompletableDeferred<Unit>()
            val hiddenProfilesStarted = CompletableDeferred<Unit>()
            val reportsStarted = CompletableDeferred<Unit>()
            val supportStarted = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val repository =
                FakeProfileHubRepository(
                    beforeListBlocks = {
                        blocksStarted.complete(Unit)
                        release.await()
                    },
                    beforeListHiddenProfiles = {
                        hiddenProfilesStarted.complete(Unit)
                        release.await()
                    },
                    beforeListReportHistory = {
                        reportsStarted.complete(Unit)
                        release.await()
                    },
                    beforeGetSupportContact = {
                        supportStarted.complete(Unit)
                        release.await()
                    },
                )
            val stateHolder = ProfileHubStateHolder(repository)

            stateHolder.openSafetyCenter()
            val load = async { stateHolder.loadSafetyLists() }

            withTimeout(1_000) {
                blocksStarted.await()
                hiddenProfilesStarted.await()
                reportsStarted.await()
                supportStarted.await()
            }
            release.complete(Unit)
            load.await()

            assertEquals(true, stateHolder.state.value.safetyCenterVisible)
            assertNull(stateHolder.state.value.errorMessage)
        }

    @Test
    fun unblockUserRemovesBlockedUserAndShowsFeedback() =
        runTest {
            val repository =
                FakeProfileHubRepository(
                    listBlocksResult =
                        AppResult.Success(
                            BlocksListDto(
                                blocks =
                                    listOf(
                                        BlockSummaryDto(
                                            blockedUserId = "blocked-1",
                                            blockedDisplayName = "Jordan",
                                            createdAt = "2026-05-26T12:00:00Z",
                                        ),
                                    ),
                            ),
                        ),
                    unblockResult = AppResult.Success(Unit),
                )
            val stateHolder = ProfileHubStateHolder(repository)
            stateHolder.openSafetyCenter()
            stateHolder.loadBlocks()

            stateHolder.unblockUser("blocked-1")

            assertEquals("blocked-1", repository.lastUnblockedUserId)
            assertEquals(emptyList(), stateHolder.state.value.blockedUsers)
            assertEquals("User unblocked", stateHolder.state.value.feedbackMessage)
        }

    @Test
    fun loadHiddenProfilesShowsSafetyCenterHiddenList() =
        runTest {
            val repository =
                FakeProfileHubRepository(
                    listHiddenProfilesResult =
                        AppResult.Success(
                            HiddenProfilesListDto(
                                hiddenProfiles =
                                    listOf(
                                        HiddenProfileSummaryDto(
                                            hiddenUserId = "hidden-1",
                                            hiddenDisplayName = "Riley",
                                            createdAt = "2026-05-26T12:00:00Z",
                                        ),
                                    ),
                            ),
                        ),
                )
            val stateHolder = ProfileHubStateHolder(repository)

            stateHolder.openSafetyCenter()
            stateHolder.loadHiddenProfiles()

            assertEquals(true, stateHolder.state.value.safetyCenterVisible)
            assertEquals(
                "Riley",
                stateHolder.state.value.hiddenProfiles
                    .single()
                    .hiddenDisplayName,
            )
            assertEquals(null, stateHolder.state.value.errorMessage)
        }

    @Test
    fun unhideProfileRemovesHiddenProfileAndShowsFeedback() =
        runTest {
            val repository =
                FakeProfileHubRepository(
                    listHiddenProfilesResult =
                        AppResult.Success(
                            HiddenProfilesListDto(
                                hiddenProfiles =
                                    listOf(
                                        HiddenProfileSummaryDto(
                                            hiddenUserId = "hidden-1",
                                            hiddenDisplayName = "Riley",
                                            createdAt = "2026-05-26T12:00:00Z",
                                        ),
                                    ),
                            ),
                        ),
                    unhideResult = AppResult.Success(Unit),
                )
            val stateHolder = ProfileHubStateHolder(repository)
            stateHolder.openSafetyCenter()
            stateHolder.loadHiddenProfiles()

            stateHolder.unhideProfile("hidden-1")

            assertEquals("hidden-1", repository.lastUnhiddenUserId)
            assertEquals(emptyList(), stateHolder.state.value.hiddenProfiles)
            assertEquals("Profile unhidden", stateHolder.state.value.feedbackMessage)
        }

    @Test
    fun loadReportHistoryShowsSafetyCenterReports() =
        runTest {
            val repository =
                FakeProfileHubRepository(
                    listReportsResult =
                        AppResult.Success(
                            ReportHistoryListDto(
                                reports =
                                    listOf(
                                        ReportHistoryItemDto(
                                            reportId = "report-1",
                                            reportedDisplayName = "Casey",
                                            targetType = "profile",
                                            category = ReportCategory.SPAM_OR_FAKE_PROFILE,
                                            status = "received",
                                            createdAt = "2026-05-26T12:00:00Z",
                                        ),
                                    ),
                            ),
                        ),
                )
            val stateHolder = ProfileHubStateHolder(repository)

            stateHolder.openSafetyCenter()
            stateHolder.loadReportHistory()

            assertEquals(true, stateHolder.state.value.safetyCenterVisible)
            assertEquals(
                "Casey",
                stateHolder.state.value.reportHistory
                    .single()
                    .reportedDisplayName,
            )
            assertEquals(null, stateHolder.state.value.errorMessage)
        }

    @Test
    fun loadSupportContactShowsServerBackedEmail() =
        runTest {
            val repository =
                FakeProfileHubRepository(
                    supportContactResult =
                        AppResult.Success(
                            SupportContactDto(
                                email = "support@pynapli.com",
                                subject = "Pynapli support request",
                                body = "Account reference: user-1",
                            ),
                        ),
                )
            val stateHolder = ProfileHubStateHolder(repository)

            stateHolder.openSafetyCenter()
            stateHolder.loadSupportContact()

            assertEquals(
                "support@pynapli.com",
                stateHolder.state.value.supportContact
                    ?.email,
            )
            assertEquals(null, stateHolder.state.value.errorMessage)
        }

    @Test
    fun requestDataExportShowsServerBackedStatus() =
        runTest {
            val repository =
                FakeProfileHubRepository(
                    requestDataExportResult = AppResult.Success(testDataExport()),
                )
            val stateHolder = ProfileHubStateHolder(repository)

            stateHolder.requestDataExport()

            assertEquals(
                "requested",
                stateHolder.state.value.latestDataExport
                    ?.status,
            )
            assertEquals("Your data export request has been received.", stateHolder.state.value.feedbackMessage)
        }

    @Test
    fun tappingAReadyExportReturnsItsDownloadLinkWithoutANewRequest() =
        runTest {
            val ready = testDataExport().copy(status = "ready", downloadUrl = "https://storage.example/export?sig=1")
            val repository = FakeProfileHubRepository(latestDataExportResult = AppResult.Success(ready))
            val stateHolder = ProfileHubStateHolder(repository)
            stateHolder.load()

            assertEquals("https://storage.example/export?sig=1", stateHolder.onDataExportTapped())
            assertEquals(0, repository.requestDataExportCalls)
        }

    @Test
    fun tappingAnExportInProgressRefreshesInsteadOfQueueingAnother() =
        runTest {
            val processing = testDataExport().copy(status = "processing", message = "Your data export is being prepared.")
            val repository = FakeProfileHubRepository(latestDataExportResult = AppResult.Success(processing))
            val stateHolder = ProfileHubStateHolder(repository)
            stateHolder.load()

            assertNull(stateHolder.onDataExportTapped())

            assertEquals(0, repository.requestDataExportCalls)
            assertEquals("Your data export is being prepared.", stateHolder.state.value.feedbackMessage)
        }

    @Test
    fun tappingWithNoExportRequestsOne() =
        runTest {
            val repository = FakeProfileHubRepository(requestDataExportResult = AppResult.Success(testDataExport()))
            val stateHolder = ProfileHubStateHolder(repository)

            assertNull(stateHolder.onDataExportTapped())

            assertEquals(1, repository.requestDataExportCalls)
            assertEquals(
                "requested",
                stateHolder.state.value.latestDataExport
                    ?.status,
            )
        }

    @Test
    fun deleteAccountSendsConfirmationAndReportsSuccess() =
        runTest {
            val repository =
                FakeProfileHubRepository(
                    deleteAccountResult =
                        AppResult.Success(
                            DeleteAccountResponseDto(
                                deleted = true,
                                revokedSessionCount = 2,
                                message = "Your account has been deleted and removed from discovery.",
                            ),
                        ),
                )
            val stateHolder = ProfileHubStateHolder(repository)

            val deleted = stateHolder.deleteAccount("DELETE")

            assertEquals(true, deleted)
            assertEquals("DELETE", repository.lastDeleteAccountRequest?.confirmation)
            assertEquals("Your account has been deleted and removed from discovery.", stateHolder.state.value.feedbackMessage)
        }

    @Test
    fun failedDeleteAccountShowsErrorAndKeepsSession() =
        runTest {
            val repository =
                FakeProfileHubRepository(
                    deleteAccountResult =
                        AppResult.Failure(
                            AppError("INVALID_ACCOUNT_DELETE_CONFIRMATION", "Type DELETE to confirm account deletion."),
                        ),
                )
            val stateHolder = ProfileHubStateHolder(repository)

            val deleted = stateHolder.deleteAccount("delete")

            assertEquals(false, deleted)
            assertEquals("Type DELETE to confirm account deletion.", stateHolder.state.value.errorMessage)
        }
}

private class FakeProfileHubRepository(
    private val loadMeResult: AppResult<MeDto> = AppResult.Success(testMe()),
    private val entitlementResult: AppResult<SubscriptionEntitlementDto> = AppResult.Success(testEntitlement()),
    private val loadAgeResult: AppResult<AgeVerificationStatusDto> =
        AppResult.Success(
            AgeVerificationStatusDto(status = "not_started", message = "Age verification has not started."),
        ),
    private val startAgeResult: AppResult<AgeVerificationStatusDto> =
        AppResult.Failure(
            AppError("UNIMPLEMENTED", "Not implemented."),
        ),
    private val listBlocksResult: AppResult<BlocksListDto> = AppResult.Success(BlocksListDto(emptyList())),
    private val unblockResult: AppResult<Unit> =
        AppResult.Failure(
            AppError("UNIMPLEMENTED", "Not implemented."),
        ),
    private val listHiddenProfilesResult: AppResult<HiddenProfilesListDto> =
        AppResult.Success(
            HiddenProfilesListDto(emptyList()),
        ),
    private val unhideResult: AppResult<Unit> =
        AppResult.Failure(
            AppError("UNIMPLEMENTED", "Not implemented."),
        ),
    private val listReportsResult: AppResult<ReportHistoryListDto> =
        AppResult.Success(
            ReportHistoryListDto(emptyList()),
        ),
    private val supportContactResult: AppResult<SupportContactDto> =
        AppResult.Success(
            SupportContactDto(
                email = "support@pynapli.com",
                subject = "Pynapli support request",
                body = "Account reference: user-1",
            ),
        ),
    private val latestDataExportResult: AppResult<AccountDataExportDto?> = AppResult.Success(null),
    private val requestDataExportResult: AppResult<AccountDataExportDto> =
        AppResult.Failure(
            AppError("UNIMPLEMENTED", "Not implemented."),
        ),
    private val deleteAccountResult: AppResult<DeleteAccountResponseDto> =
        AppResult.Failure(
            AppError("UNIMPLEMENTED", "Not implemented."),
        ),
    private val beforeLoadMe: suspend () -> Unit = {},
    private val beforeLoadAge: suspend () -> Unit = {},
    private val beforeLatestDataExport: suspend () -> Unit = {},
    private val beforeListBlocks: suspend () -> Unit = {},
    private val beforeListHiddenProfiles: suspend () -> Unit = {},
    private val beforeListReportHistory: suspend () -> Unit = {},
    private val beforeGetSupportContact: suspend () -> Unit = {},
) : ProfileHubRepository {
    var lastStartRequest: StartAgeVerificationRequestDto? = null
        private set
    var lastUnblockedUserId: String? = null
        private set
    var lastUnhiddenUserId: String? = null
        private set
    var lastDeleteAccountRequest: DeleteAccountRequestDto? = null
        private set

    override suspend fun loadMe(): AppResult<MeDto> {
        beforeLoadMe()
        return loadMeResult
    }

    override suspend fun getSubscriptionEntitlement(): AppResult<SubscriptionEntitlementDto> = entitlementResult

    override suspend fun loadAgeVerificationStatus(): AppResult<AgeVerificationStatusDto> {
        beforeLoadAge()
        return loadAgeResult
    }

    override suspend fun startAgeVerification(
        request: StartAgeVerificationRequestDto,
    ): AppResult<AgeVerificationStatusDto> {
        lastStartRequest = request
        return startAgeResult
    }

    override suspend fun updateProfile(request: UpdateProfileRequestDto): AppResult<MeDto> = AppResult.Failure(AppError("UNIMPLEMENTED", "Not implemented."))

    override suspend fun updateDiscoverySettings(request: UpdateDiscoverySettingsRequestDto): AppResult<MeDto> = AppResult.Failure(AppError("UNIMPLEMENTED", "Not implemented."))

    override suspend fun listBlocks(): AppResult<BlocksListDto> {
        beforeListBlocks()
        return listBlocksResult
    }

    override suspend fun unblockUser(userId: String): AppResult<Unit> {
        lastUnblockedUserId = userId
        return unblockResult
    }

    override suspend fun listHiddenProfiles(): AppResult<HiddenProfilesListDto> {
        beforeListHiddenProfiles()
        return listHiddenProfilesResult
    }

    override suspend fun unhideProfile(userId: String): AppResult<Unit> {
        lastUnhiddenUserId = userId
        return unhideResult
    }

    override suspend fun listReportHistory(): AppResult<ReportHistoryListDto> {
        beforeListReportHistory()
        return listReportsResult
    }

    override suspend fun getSupportContact(): AppResult<SupportContactDto> {
        beforeGetSupportContact()
        return supportContactResult
    }

    override suspend fun latestDataExportRequest(): AppResult<AccountDataExportDto?> {
        beforeLatestDataExport()
        return latestDataExportResult
    }

    var requestDataExportCalls = 0
        private set

    override suspend fun requestDataExport(): AppResult<AccountDataExportDto> {
        requestDataExportCalls += 1
        return requestDataExportResult
    }

    override suspend fun deleteAccount(request: DeleteAccountRequestDto): AppResult<DeleteAccountResponseDto> {
        lastDeleteAccountRequest = request
        return deleteAccountResult
    }
}

private fun testEntitlement(): SubscriptionEntitlementDto =
    SubscriptionEntitlementDto(
        tier = "free",
        source = EntitlementSource.FREE,
        displayName = "Free",
        linkedPartnerIncluded = false,
    )

private fun testDataExport(): AccountDataExportDto =
    AccountDataExportDto(
        requestId = "export-1",
        status = "requested",
        requestedAt = "2026-05-26T12:00:00Z",
        message = "Your data export request has been received.",
    )

private fun testMe(): MeDto =
    MeDto(
        user = AuthUserDto(userId = "user-1", status = "active"),
        profile =
            ProfileDto(
                userId = "user-1",
                displayName = "Maya",
                birthdate = "1990-01-01",
                displayAge = 36,
                gender = "woman",
                orientation = "open",
                relationshipContext = "linked",
                discoveryStyle = "both",
                profileCompletenessScore = 100,
                moderationStatus = "approved",
                visibilityStatus = "visible",
                ageVerified = false,
            ),
        discoverySettings =
            DiscoverySettingsDto(
                gridVisible = true,
                swipeVisible = true,
                eventsVisible = true,
                showDistance = true,
                showInViewedLists = true,
                incognito = false,
                allowGridMessages = true,
                gridMessagePolicy = "everyone",
                allowFlirts = true,
                allowIntroNotes = true,
                allowAlbumRequests = true,
                soloEnabled = true,
                togetherEnabled = false,
                socialEnabled = true,
            ),
    )
