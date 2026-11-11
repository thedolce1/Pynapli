package com.pynapli.app.grid

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.pynapli.app.design.PynapliColors
import com.pynapli.app.design.PynapliLayout
import com.pynapli.app.design.PynapliPill
import com.pynapli.app.design.PynapliShape

/** Stable test tags for the Travel mode row on the Grid. */
object GridTravelTags {
    const val ROW = "grid_travel_row"
    const val TURN_OFF = "grid_travel_turn_off"
}

/** "Browsing Las Vegas · Travel mode", with a way back home right there. */
@Composable
internal fun GridTravelRow(
    city: String,
    onTurnOff: () -> Unit,
) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(horizontal = PynapliLayout.ScreenPadding, vertical = 6.dp)
                .clip(PynapliShape.CardShape)
                .background(PynapliColors.Surface)
                .padding(start = 12.dp, end = 6.dp, top = 4.dp, bottom = 4.dp)
                .testTag(GridTravelTags.ROW),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = "Browsing $city · Travel mode",
            color = PynapliColors.TextPrimary,
            style = MaterialTheme.typography.bodySmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        PynapliPill(
            text = "Turn off",
            onClick = onTurnOff,
            modifier = Modifier.testTag(GridTravelTags.TURN_OFF),
        )
    }
}
