package com.pynapli.server.safety

import com.pynapli.shared.monetization.FeatureAccessPolicy
import com.pynapli.shared.monetization.PynapliFeature
import com.pynapli.shared.network.dto.HiddenProfileSummaryDto
import com.pynapli.shared.network.dto.HiddenProfilesListDto
import java.util.UUID

class HiddenProfileService(
    private val repository: HiddenProfileRepository,
) {
    suspend fun hideProfile(
        hiderUserId: UUID,
        targetUserIdText: String,
    ): HiddenProfileSummaryDto {
        FeatureAccessPolicy.requireAlwaysFreeSafetyFeature(PynapliFeature.HideProfile)
        val targetUserId = targetUserIdText.toHiddenProfileUuidOrThrow()
        if (targetUserId == hiderUserId) throw SafetyException("HIDE_SELF_FORBIDDEN")

        val record = repository.hideProfile(hiderUserId, targetUserId)
        return HiddenProfileSummaryDto(
            hiddenUserId = record.hiddenUserId.toString(),
            hiddenDisplayName = null,
            createdAt = record.createdAt.toString(),
        )
    }

    suspend fun unhideProfile(
        hiderUserId: UUID,
        targetUserIdText: String,
    ): Boolean {
        FeatureAccessPolicy.requireAlwaysFreeSafetyFeature(PynapliFeature.UnhideProfile)
        val targetUserId = targetUserIdText.toHiddenProfileUuidOrThrow()
        if (targetUserId == hiderUserId) throw SafetyException("HIDE_SELF_FORBIDDEN")
        return repository.unhideProfile(hiderUserId, targetUserId)
    }

    suspend fun listHiddenProfiles(hiderUserId: UUID): HiddenProfilesListDto {
        FeatureAccessPolicy.requireAlwaysFreeSafetyFeature(PynapliFeature.ListHiddenProfiles)
        return HiddenProfilesListDto(
            hiddenProfiles =
                repository.listHiddenProfiles(hiderUserId).map { record ->
                    HiddenProfileSummaryDto(
                        hiddenUserId = record.hiddenUserId.toString(),
                        hiddenDisplayName = record.hiddenDisplayName,
                        createdAt = record.createdAt.toString(),
                    )
                },
        )
    }
}

private fun String.toHiddenProfileUuidOrThrow(): UUID =
    runCatching { UUID.fromString(this) }.getOrElse {
        throw SafetyException("INVALID_HIDDEN_PROFILE_TARGET")
    }
