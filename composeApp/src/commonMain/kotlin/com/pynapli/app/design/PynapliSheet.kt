package com.pynapli.app.design

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

/*
 * The single bottom-sheet system every Pynapli modal consumes. Before this, each sheet
 * hand-rolled its own `ModalBottomSheet` + scroll column + padding + footer with slightly
 * different values, so the app's sheets visibly drifted apart. These primitives encode the one
 * canonical container (dark background, 16dp top corners, shared drag handle), body rhythm, header,
 * and footer so Grid/Swipe filters, Travel, Start Tonight, Conversation, and the profile peek all
 * feel like the same product. Tokens live in [PynapliSheetTokens] (design/PynapliDimens.kt).
 */

/**
 * How a sheet presents on Medium+ windows (WEB_PWA_GAMEPLAN.md W2.2/W2.3). Compact windows
 * always get the bottom sheet; a full-width sheet on a desktop monitor reads as a stretched
 * phone, so wide windows re-home the same content instead:
 * - [CenteredDialog] — forms and pickers (filters, travel, tonight) become a centered card.
 * - [SidePanel] — browsing companions (the profile peek) anchor to the right edge so the
 *   surface behind stays visible, inspector-style.
 */
enum class PynapliWideSheetPresentation { CenteredDialog, SidePanel }

/** The container a sheet body is actually mounted in; [PynapliSheetScaffold] adapts to it. */
enum class PynapliSheetHost { BottomSheet, CenteredDialog, SidePanel }

val LocalPynapliSheetHost = compositionLocalOf { PynapliSheetHost.BottomSheet }

/**
 * Canonical modal sheet: dark [PynapliColors.Black] container, [PynapliShape.SheetShape]
 * corners, default Material drag handle, always fully expanded. Callers pass [modifier] to keep
 * their `testTag`, and compose the body with [PynapliSheetColumn] (scrolling form) or
 * [PynapliSheetScaffold] (pinned footer). [containerColor] is overridable for the rare surface
 * that needs a lifted background. The experimental sheet state stays internal so call sites don't
 * have to opt in to the Material3 experimental API. On Medium+ windows the same content mounts
 * per [widePresentation] instead of edge-to-edge (see [PynapliWideSheetPresentation]).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PynapliBottomSheet(
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    containerColor: Color = PynapliColors.Black,
    widePresentation: PynapliWideSheetPresentation = PynapliWideSheetPresentation.CenteredDialog,
    content: @Composable ColumnScope.() -> Unit,
) {
    val sizeClass = currentPynapliWindowSizeClass()
    if (sizeClass == PynapliWindowSizeClass.Compact) {
        ModalBottomSheet(
            onDismissRequest = onDismiss,
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
            shape = PynapliShape.SheetShape,
            containerColor = containerColor,
            contentColor = MaterialTheme.colorScheme.onSurface,
            modifier = modifier,
        ) {
            val columnScope = this
            CompositionLocalProvider(LocalPynapliSheetHost provides PynapliSheetHost.BottomSheet) {
                columnScope.content()
            }
        }
        return
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        val dismissInteractions = remember { MutableInteractionSource() }
        val consumeInteractions = remember { MutableInteractionSource() }
        when (widePresentation) {
            PynapliWideSheetPresentation.CenteredDialog ->
                BoxWithConstraints(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) {
                    val maxCardHeight = maxHeight * WIDE_DIALOG_MAX_HEIGHT_FRACTION
                    Box(
                        modifier =
                            Modifier
                                .matchParentSize()
                                .clickable(interactionSource = dismissInteractions, indication = null, onClick = onDismiss),
                    )
                    Surface(
                        shape = PynapliShape.DialogShape,
                        color = containerColor,
                        contentColor = MaterialTheme.colorScheme.onSurface,
                        border = BorderStroke(1.dp, PynapliColors.BorderSoft),
                        modifier =
                            modifier
                                .widthIn(max = WideDialogMaxWidth)
                                .heightIn(max = maxCardHeight)
                                // Consume clicks so taps inside the card never reach the dismiss layer.
                                .clickable(interactionSource = consumeInteractions, indication = null) {},
                    ) {
                        Column {
                            CompositionLocalProvider(LocalPynapliSheetHost provides PynapliSheetHost.CenteredDialog) {
                                content()
                            }
                        }
                    }
                }

            PynapliWideSheetPresentation.SidePanel ->
                Box(modifier = Modifier.fillMaxSize()) {
                    Box(
                        modifier =
                            Modifier
                                .matchParentSize()
                                .clickable(interactionSource = dismissInteractions, indication = null, onClick = onDismiss),
                    )
                    Surface(
                        color = containerColor,
                        contentColor = MaterialTheme.colorScheme.onSurface,
                        modifier =
                            modifier
                                .align(Alignment.CenterEnd)
                                .fillMaxHeight()
                                .width(SidePanelWidth)
                                .clickable(interactionSource = consumeInteractions, indication = null) {},
                    ) {
                        Row {
                            VerticalDivider(thickness = 1.dp, color = PynapliColors.BorderSoft)
                            Column(modifier = Modifier.weight(1f)) {
                                CompositionLocalProvider(LocalPynapliSheetHost provides PynapliSheetHost.SidePanel) {
                                    content()
                                }
                            }
                        }
                    }
                }
        }
    }
}

/** Widest a wide-window dialog card grows; forms read badly past this. */
private val WideDialogMaxWidth = 560.dp

/** Right-anchored inspector width for the profile peek on wide windows. */
private val SidePanelWidth = 420.dp

private const val WIDE_DIALOG_MAX_HEIGHT_FRACTION = 0.92f

/**
 * Standard scrolling sheet body: full width, padded with the sheet content tokens, sections
 * spaced by [spacing]. Replaces the repeated `Column { fillMaxWidth + verticalScroll + padding }`
 * across the form sheets. Set [scrollable] = false when the caller manages its own scroll region.
 */
@Composable
fun PynapliSheetColumn(
    modifier: Modifier = Modifier,
    spacing: Dp = PynapliSheetTokens.SectionSpacing,
    scrollable: Boolean = true,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier =
            modifier
                .fillMaxWidth()
                .then(if (scrollable) Modifier.verticalScroll(rememberScrollState()) else Modifier)
                .padding(horizontal = PynapliSheetTokens.ContentHorizontalPadding)
                .padding(
                    top = PynapliSheetTokens.ContentTopPadding,
                    bottom = PynapliSheetTokens.ContentBottomPadding,
                ),
        verticalArrangement = Arrangement.spacedBy(spacing),
        content = content,
    )
}

/**
 * Tall sheet body with a scroll region that takes the available height and a [footer] pinned to
 * the bottom edge — the profile-peek pattern, so primary actions are always reachable regardless
 * of how much content there is. Defaults to [PynapliSheetTokens.TALL_HEIGHT_FRACTION] so short and
 * rich profiles open at the same consistent height. Horizontal padding follows the sheet token;
 * [verticalPadding] trims the top/bottom so a hero photo sits close to the sheet edge.
 */
@Composable
fun PynapliSheetScaffold(
    modifier: Modifier = Modifier,
    heightFraction: Float = PynapliSheetTokens.TALL_HEIGHT_FRACTION,
    horizontalPadding: Dp = PynapliSheetTokens.ContentHorizontalPadding,
    verticalPadding: Dp = PynapliSpacing.sm,
    bodySpacing: Dp = PynapliSheetTokens.SectionSpacing,
    footer: (@Composable ColumnScope.() -> Unit)? = null,
    body: @Composable ColumnScope.() -> Unit,
) {
    // A side panel already fills the window edge-to-edge; the bottom-sheet height
    // fraction would leave a dead strip at its foot, so the scaffold fills it fully.
    val effectiveHeightFraction =
        if (LocalPynapliSheetHost.current == PynapliSheetHost.SidePanel) 1f else heightFraction
    Column(
        modifier =
            modifier
                .fillMaxWidth()
                .fillMaxHeight(effectiveHeightFraction)
                .padding(horizontal = horizontalPadding, vertical = verticalPadding),
    ) {
        Column(
            modifier =
                Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(bodySpacing),
            content = body,
        )
        footer?.invoke(this)
    }
}

/** One canonical sheet title (warm off-white, titleMedium, semibold). */
@Composable
fun PynapliSheetTitle(
    text: String,
    modifier: Modifier = Modifier,
) {
    Text(
        text = text,
        modifier = modifier,
        color = PynapliColors.TextPrimary,
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.SemiBold,
    )
}

/** Sheet header: title that takes the row, optional [trailing] slot, optional close button. */
@Composable
fun PynapliSheetHeader(
    title: String,
    modifier: Modifier = Modifier,
    onClose: (() -> Unit)? = null,
    trailing: (@Composable RowScope.() -> Unit)? = null,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(PynapliSpacing.sm),
    ) {
        PynapliSheetTitle(text = title, modifier = Modifier.weight(1f))
        trailing?.invoke(this)
        if (onClose != null) {
            IconButton(onClick = onClose) {
                Icon(
                    imageVector = Icons.Filled.Close,
                    contentDescription = "Close",
                    tint = PynapliColors.TextSecondary,
                )
            }
        }
    }
}

/**
 * Canonical sheet footer action row: an optional [start] action (e.g. "Clear all") pinned left, a
 * flexible spacer, then the trailing primary/secondary buttons. Keeps button spacing and the top
 * gap consistent across every sheet footer.
 */
@Composable
fun PynapliSheetActionRow(
    modifier: Modifier = Modifier,
    start: (@Composable RowScope.() -> Unit)? = null,
    content: @Composable RowScope.() -> Unit,
) {
    Row(
        modifier =
            modifier
                .fillMaxWidth()
                .padding(top = PynapliSpacing.sm),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(PynapliSpacing.sm + PynapliSpacing.xs),
    ) {
        start?.invoke(this)
        Spacer(modifier = Modifier.weight(1f))
        content(this)
    }
}
