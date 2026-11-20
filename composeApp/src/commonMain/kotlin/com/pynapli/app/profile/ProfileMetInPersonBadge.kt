package com.pynapli.app.profile

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.pynapli.app.design.PynapliColors
import com.pynapli.app.design.icons.Icons
import com.pynapli.app.design.icons.filled.People
import com.pynapli.shared.network.dto.PublicProfileDto

/**
 * The "Met in person" badge on the profile sheet (TRUST_AND_SAFETY_SPEC "Met in person"). The server
 * decides it (weekly batch, the person's opt-in, and for the couple variant both partners' opt-ins and
 * a visible partner); the client only renders the boolean. Never a count, never the verified tick.
 */
@Immutable
internal data class MetInPersonBadgeUi(
    val label: String,
    val helper: String,
)

internal fun PublicProfileDto.metInPersonBadgeUi(): MetInPersonBadgeUi? {
    val label =
        when {
            metInPersonAsCouple && linkedPartner != null -> "Met in person as a couple"
            metInPerson -> "Met in person"
            else -> return null
        }
    return MetInPersonBadgeUi(label = label, helper = MET_IN_PERSON_HELPER)
}

internal const val MET_IN_PERSON_HELPER = "Confirmed by several members who've met them. Not checked by Pynapli."

@Composable
internal fun MetInPersonBadgeRow(badge: MetInPersonBadgeUi) {
    Column(
        verticalArrangement = Arrangement.spacedBy(2.dp),
        modifier = Modifier.semantics(mergeDescendants = true) {}.testTag(MET_IN_PERSON_BADGE_TAG),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Icon(
                imageVector = Icons.Filled.People,
                contentDescription = null,
                tint = PynapliColors.MetInPerson,
                modifier = Modifier.size(16.dp),
            )
            Text(
                text = badge.label,
                color = PynapliColors.MetInPerson,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
            )
        }
        Text(
            text = badge.helper,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

internal const val MET_IN_PERSON_BADGE_TAG = "profile-met-in-person-badge"
