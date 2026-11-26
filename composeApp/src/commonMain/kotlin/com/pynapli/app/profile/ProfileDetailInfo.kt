package com.pynapli.app.profile

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.pynapli.app.design.PynapliColors
import com.pynapli.app.design.PynapliPill
import com.pynapli.app.design.PynapliShape
import com.pynapli.app.design.handCursor
import com.pynapli.app.design.icons.Icons
import com.pynapli.app.design.icons.automirrored.filled.ArrowForward
import com.pynapli.app.design.icons.filled.Person
import com.pynapli.app.grid.Boundary
import com.pynapli.app.grid.BoundaryStance
import com.pynapli.app.grid.GridLinkedPartnerUi
import com.pynapli.app.grid.Intent
import com.pynapli.app.grid.LifestyleGroup
import com.pynapli.app.grid.LifestyleTag
import com.pynapli.app.grid.LogisticsTag
import com.pynapli.app.grid.discoveryTagLabel
import com.pynapli.app.media.rememberCachedImageModel
import com.pynapli.shared.network.dto.LinkedPartnerMiniDto
import com.pynapli.shared.profile.ProfileTaxonomies

@Composable
internal fun ProfileFacts(profileState: ProfileDetailUiState) {
    val card = profileState.selectedCard ?: return
    val profile = profileState.profile ?: return
    val chips =
        remember(profile, card.linkedPartner) {
            buildList {
                // Age now lives next to the name in the hero; keep it out of the chip row.
                add(discoveryTagLabel(profile.discoveryStyle))
                profile.relationshipContext?.let { add(it.toDisplayLabel()) }
                profile.gender?.let { add(selfDescribableLabel(it, profile.genderDetail)) }
                profile.orientation?.let { add(selfDescribableLabel(it, profile.orientationDetail)) }
                if (profile.linkedPartner != null || card.linkedPartner != null) add("Linked")
                // A "linked" relationship context and an actual linked partner both surface the
                // same label; show it once.
            }.distinct()
        }
    val tagChips =
        remember(profile) {
            buildList {
                profile.logisticsTags.forEach { value -> add(logisticsTagLabel(value)) }
                profile.intents.forEach { value -> add(intentLabel(value)) }
            }
        }
    val lifestyleRows = remember(profile) { lifestyleRows(profile.experienceLevel, profile.lifestyleTags, profile.boundaries) }

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        profile.bio?.takeIf { it.isNotBlank() }?.let { bio ->
            Text(
                text = bio,
                color = MaterialTheme.colorScheme.onSurface,
                style = MaterialTheme.typography.bodyMedium,
            )
        }

        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            chips.forEach { label ->
                PynapliPill(
                    text = label,
                    selected = false,
                    leading =
                        if (label == "Linked") {
                            {
                                Icon(
                                    imageVector = Icons.Filled.Person,
                                    contentDescription = null,
                                    tint = PynapliColors.Accent,
                                    modifier = Modifier.size(14.dp),
                                )
                            }
                        } else {
                            null
                        },
                )
            }
        }

        if (lifestyleRows.isNotEmpty()) {
            LifestyleFacts(lifestyleRows)
        }

        if (tagChips.isNotEmpty()) {
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                tagChips.forEach { label ->
                    PynapliPill(text = label, selected = false)
                }
            }
        }
    }
}

private fun selfDescribableLabel(
    key: String,
    detail: String?,
): String =
    if (key == ProfileTaxonomies.SELF_DESCRIBE) {
        detail?.takeIf { it.isNotBlank() } ?: "Self-described"
    } else {
        key.toDisplayLabel()
    }

/** One labeled row of the profile's "Lifestyle" block (e.g. "Swap" -> ["Soft swap"]). */
internal data class LifestyleFactRow(
    val label: String,
    val values: List<String>,
)

/**
 * The person's own lifestyle answers, grouped swinger-first: experience, swap, room, other terms,
 * then boundaries split into "Ask first" and "Not for me". Unknown tokens are dropped.
 */
internal fun lifestyleRows(
    experienceLevel: String?,
    lifestyleTags: List<String>,
    boundaries: List<String>,
): List<LifestyleFactRow> =
    buildList {
        val tags = lifestyleTags.mapNotNull { LifestyleTag.fromServerValue(it) }
        LifestyleTag.fromServerValue(experienceLevel)?.let { add(LifestyleFactRow(LifestyleGroup.Experience.label, listOf(it.label))) }
        listOf(LifestyleGroup.Swap, LifestyleGroup.Room, LifestyleGroup.Other).forEach { group ->
            val labels = tags.filter { it.group == group }.map { it.label }
            if (labels.isNotEmpty()) add(LifestyleFactRow(group.label, labels))
        }
        val stances = BoundaryStance.parse(boundaries)
        BoundaryStance.entries.forEach { stance ->
            val labels = Boundary.entries.filter { stances[it] == stance }.map { it.label }
            if (labels.isNotEmpty()) add(LifestyleFactRow(stance.label, labels))
        }
    }

@Composable
private fun LifestyleFacts(rows: List<LifestyleFactRow>) {
    Column(
        modifier = Modifier.fillMaxWidth().testTag(ProfileDetailTags.LIFESTYLE),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(
            text = "Lifestyle",
            color = MaterialTheme.colorScheme.onSurface,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
        )
        rows.forEach { row ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = row.label,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.width(88.dp),
                )
                FlowRow(
                    modifier = Modifier.weight(1f),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    row.values.forEach { value -> PynapliPill(text = value, selected = false) }
                }
            }
        }
    }
}

/**
 * Partner preview line: the linked partner's OWN orientation, experience and swap/room, as
 * declared on their own account ("Bisexual · Experienced · Soft swap"). Null when none is set.
 */
internal fun partnerPreviewSummary(partner: LinkedPartnerMiniDto?): String? {
    partner ?: return null
    val parts =
        buildList {
            partner.orientation?.let { add(selfDescribableLabel(it, partner.orientationDetail)) }
            LifestyleTag.fromServerValue(partner.experienceLevel)?.let { add(it.label) }
            partner.lifestyleTags.mapNotNull { LifestyleTag.fromServerValue(it) }.forEach { add(it.label) }
        }
    return parts.takeIf { it.isNotEmpty() }?.joinToString(" · ")
}

private fun logisticsTagLabel(value: String): String = LogisticsTag.entries.firstOrNull { it.serverValue == value }?.label ?: value.toDisplayLabel()

private fun intentLabel(value: String): String = Intent.entries.firstOrNull { it.serverValue == value }?.label ?: value.toDisplayLabel()

@Composable
internal fun LinkedPartnerSection(
    linkedPartner: LinkedPartnerMiniDto?,
    cardLinkedPartner: GridLinkedPartnerUi?,
    onOpenLinkedPartner: (GridLinkedPartnerUi) -> Unit,
) {
    val partnerUserId = linkedPartner?.userId ?: cardLinkedPartner?.userId ?: return
    val displayName =
        linkedPartner?.displayName
            ?: cardLinkedPartner?.displayName
            ?: "Profile ${partnerUserId.take(8)}"
    val thumbnailUrl = linkedPartner?.thumbnailUrl ?: cardLinkedPartner?.thumbnailUrl
    // G2-2: the key that belongs to whichever URL was chosen.
    val thumbnailCacheKey = if (linkedPartner?.thumbnailUrl != null) linkedPartner.thumbnailCacheKey else cardLinkedPartner?.thumbnailCacheKey
    val partner =
        GridLinkedPartnerUi(
            userId = partnerUserId,
            displayName = displayName,
            thumbnailUrl = thumbnailUrl,
            thumbnailCacheKey = thumbnailCacheKey,
        )

    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .clip(PynapliShape.CardShape)
                .handCursor()
                .clickable { onOpenLinkedPartner(partner) }
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .border(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.65f), PynapliShape.CardShape)
                .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(
            modifier =
                Modifier
                    .size(44.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.background)
                    .border(1.dp, MaterialTheme.colorScheme.outline, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            if (thumbnailUrl != null) {
                AsyncImage(
                    model = rememberCachedImageModel(thumbnailUrl, thumbnailCacheKey),
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                Icon(
                    imageVector = Icons.Filled.Person,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(22.dp),
                )
            }
        }
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                text = "Linked with",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.labelMedium,
                maxLines = 1,
            )
            Text(
                text = displayName,
                color = MaterialTheme.colorScheme.onSurface,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            partnerPreviewSummary(linkedPartner)?.let { summary ->
                Text(
                    text = summary,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.testTag(ProfileDetailTags.PARTNER_SUMMARY),
                )
            }
        }
        Icon(
            imageVector = Icons.AutoMirrored.Filled.ArrowForward,
            contentDescription = "Open linked partner profile",
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(20.dp),
        )
    }
}

private fun String.toDisplayLabel(): String =
    split('_').joinToString(" ") { part ->
        part.take(1).uppercase() + part.drop(1)
    }
