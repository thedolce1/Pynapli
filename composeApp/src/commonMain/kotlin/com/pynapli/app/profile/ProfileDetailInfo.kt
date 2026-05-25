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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.pynapli.app.grid.GridLinkedPartnerUi
import com.pynapli.shared.network.dto.LinkedPartnerMiniDto

@Composable
internal fun ProfileFacts(profileState: ProfileDetailUiState) {
    val card = profileState.selectedCard ?: return
    val profile = profileState.profile ?: return
    val chips = buildList {
        profile.displayAge?.let { add("$it") }
        add(profile.discoveryStyle.toDisplayLabel())
        profile.relationshipContext?.let { add(it.toDisplayLabel()) }
        profile.gender?.let { add(it.toDisplayLabel()) }
        profile.orientation?.let { add(it.toDisplayLabel()) }
        if (profile.linkedPartner != null || card.linkedPartner != null) add("Linked")
    }

    FlowRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        chips.forEach { label ->
            AssistChip(
                onClick = {},
                label = {
                    Text(
                        text = label,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                },
                leadingIcon = if (label == "Linked") {
                    {
                        Icon(
                            imageVector = Icons.Filled.Person,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                } else {
                    null
                },
                colors = AssistChipDefaults.assistChipColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant,
                    labelColor = MaterialTheme.colorScheme.onSurface,
                    leadingIconContentColor = MaterialTheme.colorScheme.primary
                ),
                border = AssistChipDefaults.assistChipBorder(
                    enabled = true,
                    borderColor = MaterialTheme.colorScheme.outline
                )
            )
        }
    }
}

@Composable
internal fun LinkedPartnerSection(
    linkedPartner: LinkedPartnerMiniDto?,
    cardLinkedPartner: GridLinkedPartnerUi?,
    onOpenLinkedPartner: (GridLinkedPartnerUi) -> Unit
) {
    val partnerUserId = linkedPartner?.userId ?: cardLinkedPartner?.userId ?: return
    val displayName = linkedPartner?.displayName
        ?: cardLinkedPartner?.displayName
        ?: "Profile ${partnerUserId.take(8)}"
    val thumbnailUrl = linkedPartner?.thumbnailUrl ?: cardLinkedPartner?.thumbnailUrl
    val partner = GridLinkedPartnerUi(
        userId = partnerUserId,
        displayName = displayName,
        thumbnailUrl = thumbnailUrl
    )

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .clickable { onOpenLinkedPartner(partner) }
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .border(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.65f), RoundedCornerShape(8.dp))
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Box(
            modifier = Modifier
                .size(44.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.background)
                .border(1.dp, MaterialTheme.colorScheme.outline, CircleShape),
            contentAlignment = Alignment.Center
        ) {
            if (thumbnailUrl != null) {
                AsyncImage(
                    model = thumbnailUrl,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
            } else {
                Icon(
                    imageVector = Icons.Filled.Person,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(22.dp)
                )
            }
        }
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            Text(
                text = "Linked with",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.labelMedium,
                maxLines = 1
            )
            Text(
                text = displayName,
                color = MaterialTheme.colorScheme.onSurface,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        Icon(
            imageVector = Icons.AutoMirrored.Filled.ArrowForward,
            contentDescription = "Open linked partner profile",
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(20.dp)
        )
    }
}

private fun String.toDisplayLabel(): String =
    split('_').joinToString(" ") { part ->
        part.take(1).uppercase() + part.drop(1)
    }
