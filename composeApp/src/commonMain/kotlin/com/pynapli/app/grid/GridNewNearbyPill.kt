package com.pynapli.app.grid

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.pynapli.app.design.PynapliColors
import com.pynapli.app.design.PynapliMotion
import com.pynapli.app.design.PynapliShape
import com.pynapli.app.design.PynapliType
import com.pynapli.app.design.handCursor
import com.pynapli.app.design.icons.Icons
import com.pynapli.app.design.icons.filled.ArrowUpward

internal object GridNewNearbyTags {
    const val PILL: String = "grid-new-nearby-pill"
}

/** Copy for the pill: "1 new nearby", "5 new nearby". Capped so a huge refresh never shouts. */
internal fun newNearbyLabel(count: Int): String = if (count > 99) "99+ new nearby" else "$count new nearby"

/**
 * "N new nearby" (plan G7): floats over the top of the Grid when a quiet refresh found people who
 * are not on screen yet. The cards underneath never move on their own; tapping this applies the new
 * order and scrolls to the top. A transient action, so it takes the gold accent.
 */
@Composable
internal fun GridNewNearbyPill(
    count: Int,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // Keep the last count while the pill animates out, so it never reads "0 new nearby".
    var shownCount by remember { mutableIntStateOf(count) }
    LaunchedEffect(count) { if (count > 0) shownCount = count }
    AnimatedVisibility(
        visible = count > 0,
        enter = fadeIn(PynapliMotion.standardTween()) + slideInVertically(PynapliMotion.standardTween()) { -it },
        exit = fadeOut(PynapliMotion.standardTween()) + slideOutVertically(PynapliMotion.standardTween()) { -it },
        modifier = modifier,
    ) {
        Row(
            modifier =
                Modifier
                    .padding(top = 8.dp)
                    .minimumInteractiveComponentSize()
                    .clip(PynapliShape.PillShape)
                    .background(PynapliColors.Accent)
                    .handCursor()
                    .clickable(onClickLabel = "Show new people nearby", role = Role.Button, onClick = onClick)
                    .padding(horizontal = 14.dp, vertical = 7.dp)
                    .testTag(GridNewNearbyTags.PILL),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Icon(
                imageVector = Icons.Filled.ArrowUpward,
                contentDescription = null,
                tint = PynapliColors.TextInverse,
                modifier = Modifier.size(14.dp),
            )
            Text(
                text = newNearbyLabel(shownCount),
                color = PynapliColors.TextInverse,
                fontSize = PynapliType.Caption,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
            )
        }
    }
}
