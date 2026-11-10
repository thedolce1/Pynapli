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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.pynapli.app.design.PynapliColors
import com.pynapli.app.design.PynapliPill
import com.pynapli.app.design.PynapliShape
import com.pynapli.app.design.icons.Icons
import com.pynapli.app.design.icons.automirrored.filled.ArrowForward
import com.pynapli.app.design.icons.filled.Person
import com.pynapli.app.grid.GridLinkedPartnerUi
import com.pynapli.app.grid.Intent
import com.pynapli.app.grid.LifestyleTag
import com.pynapli.app.grid.LogisticsTag
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
                add(profile.discoveryStyle.toDisplayLabel())
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
                profile.lifestyleTags.forEach { value -> add(lifestyleTagLabel(value)) }
                profile.logisticsTags.forEach { value -> add(logisticsTagLabel(value)) }
                profile.intents.forEach { value -> add(intentLabel(value)) }
            }
        }

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

private fun lifestyleTagLabel(value: String): String = LifestyleTag.entries.firstOrNull { it.serverValue == value }?.label ?: value.toDisplayLabel()

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
    val partner =
        GridLinkedPartnerUi(
            userId = partnerUserId,
            displayName = displayName,
            thumbnailUrl = thumbnailUrl,
        )

    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .clip(PynapliShape.CardShape)
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
                    model = thumbnailUrl,
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
