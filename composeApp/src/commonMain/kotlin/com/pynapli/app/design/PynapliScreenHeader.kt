package com.pynapli.app.design

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.pynapli.app.design.icons.Icons
import com.pynapli.app.design.icons.filled.Person

/*
 * Shared screen-header system. One canonical top-of-screen frame so Grid, Swipe, Activity,
 * Messages, and Store stop hand-rolling divergent headers (different paddings, avatars, online
 * dots, and action-button shapes). The frame owns the status-bar inset and [PynapliHeaderTokens]
 * padding; each surface fills the leading / center / action slots with its own content.
 */

/** Subtle neutral placeholder behind the profile avatar before a photo loads (token-driven). */
private val AvatarPlaceholderBrush: Brush =
    Brush.linearGradient(listOf(PynapliColors.SurfaceRaised, PynapliColors.SurfacePressed))

/**
 * Canonical top-of-screen header.
 *
 * @param insets when true (default) the frame applies the status-bar inset and header padding.
 *   Pass false when the header is embedded in a parent that already insets/pads (e.g. the Swipe
 *   content column); the frame then contributes only slot layout + spacing.
 * @param leading optional leading slot (profile avatar or a brand icon badge).
 * @param title optional page title rendered in the weighted middle when [center] is absent.
 * @param subtitle optional secondary line under [title].
 * @param center optional middle slot (e.g. a location pill or segment switcher); replaces the
 *   title block and fills the weighted region.
 * @param actions optional trailing actions laid out in a spaced row.
 */
@Composable
fun PynapliScreenHeader(
    modifier: Modifier = Modifier,
    insets: Boolean = true,
    leading: (@Composable () -> Unit)? = null,
    title: String? = null,
    subtitle: String? = null,
    center: (@Composable () -> Unit)? = null,
    actions: (@Composable RowScope.() -> Unit)? = null,
) {
    Row(
        modifier =
            modifier
                .fillMaxWidth()
                .then(if (insets) Modifier.statusBarsPadding() else Modifier)
                .then(
                    if (insets) {
                        Modifier.padding(
                            horizontal = PynapliHeaderTokens.HorizontalPadding,
                            vertical = PynapliHeaderTokens.VerticalPadding,
                        )
                    } else {
                        Modifier
                    },
                ),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(PynapliHeaderTokens.ItemSpacing),
    ) {
        leading?.invoke()
        Box(
            modifier = Modifier.weight(1f),
            contentAlignment = Alignment.CenterStart,
        ) {
            when {
                center != null -> center()
                title != null ->
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(
                            text = title,
                            color = MaterialTheme.colorScheme.onSurface,
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        subtitle?.let {
                            Text(
                                text = it,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                style = MaterialTheme.typography.labelMedium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
            }
        }
        if (actions != null) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(PynapliHeaderTokens.ItemSpacing),
                content = actions,
            )
        }
    }
}

/**
 * The one profile-avatar treatment for headers: a circular photo (or neutral placeholder + person
 * glyph) with an outline ring and an optional [OnlineDot]. Replaces the per-surface avatars that
 * each hardcoded their own gradient.
 */
@Composable
fun PynapliHeaderAvatar(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    imageUrl: String? = null,
    online: Boolean = false,
    contentDescription: String = "Profile",
) {
    Box(
        modifier =
            modifier
                // Keep the compact 42dp visual but reserve a >=48dp touch target for a11y.
                .minimumInteractiveComponentSize()
                .size(PynapliHeaderTokens.AvatarSize)
                .clip(CircleShape)
                .background(AvatarPlaceholderBrush)
                .border(1.dp, MaterialTheme.colorScheme.outline, CircleShape)
                .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        if (imageUrl != null) {
            AsyncImage(
                model = imageUrl,
                contentDescription = contentDescription,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize().clip(CircleShape),
            )
        } else {
            Icon(
                imageVector = Icons.Filled.Person,
                contentDescription = contentDescription,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(22.dp),
            )
        }
        if (online) {
            OnlineDot(modifier = Modifier.align(Alignment.BottomEnd))
        }
    }
}

/**
 * The one circular icon-action button for headers (Filters, Tonight, Refresh…). Pass a [tint] to
 * highlight (e.g. [PynapliColors.Accent]); defaults to on-surface. Forward a `testTag` via
 * [modifier] so existing instrumentation keeps working.
 */
@Composable
fun PynapliHeaderActionButton(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    tint: Color = Color.Unspecified,
) {
    IconButton(
        onClick = onClick,
        enabled = enabled,
        modifier =
            modifier
                // Keep the compact 44dp visual but reserve a >=48dp touch target for a11y.
                .minimumInteractiveComponentSize()
                .size(PynapliHeaderTokens.ActionButtonSize)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = if (tint == Color.Unspecified) MaterialTheme.colorScheme.onSurface else tint,
            modifier = Modifier.size(PynapliHeaderTokens.ActionIconSize),
        )
    }
}
