package com.pynapli.app.profilehub

import com.pynapli.shared.core.result.AppResult
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update

internal class ProfileHubLoader(
    private val repository: ProfileHubRepository,
    private val state: MutableStateFlow<ProfileHubUiState>,
) {
    /** [quiet]: a background refetch (app resume) that shows no spinner and no error. */
    suspend fun load(quiet: Boolean = false) =
        coroutineScope {
            if (state.value.isLoading) return@coroutineScope
            if (quiet && state.value.me == null) return@coroutineScope
            if (!quiet) state.update { it.copy(isLoading = true, errorMessage = null) }

            val meDeferred = async { repository.loadMe() }
            val ageVerificationDeferred = async { repository.loadAgeVerificationStatus() }
            val latestDataExportDeferred = async { repository.latestDataExportRequest() }
            val entitlementDeferred = async { repository.getSubscriptionEntitlement() }

            when (val result = meDeferred.await()) {
                is AppResult.Success -> {
                    val ageVerificationStatus =
                        when (val status = ageVerificationDeferred.await()) {
                            is AppResult.Success -> status.value
                            is AppResult.Failure -> null
                        }
                    val latestDataExport =
                        when (val export = latestDataExportDeferred.await()) {
                            is AppResult.Success -> export.value
                            is AppResult.Failure -> null
                        }
                    // Entitlement is non-fatal: a failed lookup leaves the Subscription section
                    // on its prior value rather than blocking the whole hub.
                    val entitlement =
                        when (val entitlementResult = entitlementDeferred.await()) {
                            is AppResult.Success -> entitlementResult.value
                            is AppResult.Failure -> state.value.entitlement
                        }
                    state.update { existing ->
                        existing.copy(
                            me = result.value,
                            entitlement = entitlement,
                            ageVerificationStatus = ageVerificationStatus,
                            latestDataExport = latestDataExport,
                            draft = if (existing.editVisible) existing.draft else result.value.profile.toDraft(),
                            privacyDraft =
                                if (existing.privacyVisible) {
                                    existing.privacyDraft
                                } else {
                                    result.value.discoverySettings.toPrivacyDraft()
                                },
                            activityDraft =
                                if (existing.activityVisible) {
                                    existing.activityDraft
                                } else {
                                    result.value.discoverySettings.toActivityDraft()
                                },
                            isLoading = false,
                            errorMessage = null,
                        )
                    }
                }

                is AppResult.Failure -> {
                    ageVerificationDeferred.cancel()
                    latestDataExportDeferred.cancel()
                    entitlementDeferred.cancel()
                    if (!quiet) {
                        state.update {
                            it.copy(
                                isLoading = false,
                                errorMessage = result.error.message,
                            )
                        }
                    }
                }
            }
        }
}
