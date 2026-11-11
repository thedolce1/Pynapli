package com.pynapli.app.tonight

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.pynapli.app.design.OnlineDot
import com.pynapli.app.design.PynapliButton
import com.pynapli.app.design.PynapliButtonSize
import com.pynapli.app.design.PynapliButtonStyle
import com.pynapli.app.design.PynapliColors
import com.pynapli.app.design.PynapliEmptyState
import com.pynapli.app.design.PynapliPill
import com.pynapli.app.design.PynapliShape
import com.pynapli.app.design.PynapliWindowSizeClass
import com.pynapli.app.design.currentPynapliWindowSizeClass
import com.pynapli.app.design.handCursor
import com.pynapli.app.design.icons.Icons
import com.pynapli.app.design.icons.automirrored.filled.ArrowBack
import com.pynapli.app.design.icons.automirrored.filled.Chat
import com.pynapli.app.design.icons.filled.Add
import com.pynapli.app.design.icons.filled.DarkMode
import com.pynapli.app.design.pynapliIconButtonSize
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.abs

/**
 * Playing Tonight map-first surface (PT-4, PLAYING_TONIGHT_SPEC + Claude handoff
 * `PlayingTonight.jsx`). A zone-only map (deliberately approximate — never street-level) under a
 * draggable bottom sheet that lists live sessions, with a top floater for the viewer's own session.
 * All location is coarse: dots are jittered within their `zoneKey` bucket from the session id.
 * Screens never call the network — everything flows through [PlayingTonightStateHolder].
 */
@Composable
fun PlayingTonightScreen(
    stateHolder: PlayingTonightStateHolder,
    onBack: () -> Unit,
    onOpenProfile: (String) -> Unit,
    onMessage: (String) -> Unit,
    viewerUserId: String? = null,
    modifier: Modifier = Modifier,
) {
    val state by stateHolder.state.collectAsState()
    val scope = rememberCoroutineScope()
    var showStartSheet by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) { stateHolder.load() }

    // No realtime channel for Playing Tonight: while a together session depends on the partner,
    // re-check it on a slow timer so an approval, decline or end shows up without leaving.
    val watchPartner = stateHolder.shouldWatchPartner(viewerUserId) && state.currentSession != null
    LaunchedEffect(watchPartner, state.currentSession?.id) {
        while (watchPartner) {
            delay(TONIGHT_PARTNER_REFETCH_MILLIS)
            stateHolder.refreshSession()
        }
    }
    state.notice?.let { notice ->
        LaunchedEffect(notice) {
            delay(TONIGHT_NOTICE_VISIBLE_MILLIS)
            stateHolder.clearNotice()
        }
    }

    val floater: @Composable (Modifier) -> Unit = { floaterModifier ->
        TonightTopFloater(
            session = state.currentSession,
            viewerUserId = viewerUserId,
            onBack = onBack,
            onStartSession = { showStartSheet = true },
            onApprove = { scope.launch { stateHolder.approveSession() } },
            onDecline = { scope.launch { stateHolder.declineSession() } },
            onEnd = { scope.launch { stateHolder.endCurrentSession() } },
            modifier = floaterModifier,
        )
    }
    val feed: @Composable (Modifier) -> Unit = { feedModifier ->
        TonightFeed(
            state = state,
            onIntentSelected = { scope.launch { stateHolder.setIntentFilter(it) } },
            onOpenProfile = onOpenProfile,
            onMessage = onMessage,
            modifier = feedModifier,
        )
    }
    val error: @Composable (Modifier) -> Unit = { errorModifier ->
        state.errorMessage?.let { message -> TonightError(message, stateHolder::clearError, errorModifier) }
        state.notice?.let { message -> TonightError(message, stateHolder::clearNotice, errorModifier, isError = false) }
    }

    if (currentPynapliWindowSizeClass() == PynapliWindowSizeClass.Compact) {
        CompactTonightLayout(state.feedItems, floater, feed, error, modifier)
    } else {
        WideTonightLayout(state.feedItems, floater, feed, error, modifier)
    }

    if (showStartSheet) {
        StartTonightSheet(
            isSubmitting = state.isMutating,
            photoOptions = state.photoOptions,
            onDismiss = { showStartSheet = false },
            onSubmit = { request ->
                scope.launch {
                    stateHolder.propose(request)
                    showStartSheet = false
                }
            },
        )
    }
}

/**
 * Phone layout: full-bleed map under a top floater and a draggable bottom sheet. The map projects
 * into the strip between them (measured floater height, live sheet height), so no dot and not the
 * "you" marker can land behind either.
 */
@Composable
private fun CompactTonightLayout(
    items: List<TonightFeedItemUi>,
    floater: @Composable (Modifier) -> Unit,
    feed: @Composable (Modifier) -> Unit,
    error: @Composable (Modifier) -> Unit,
    modifier: Modifier,
) {
    var floaterHeightPx by remember { mutableStateOf(0) }
    var snapIndex by remember { mutableStateOf(1) }
    val sheetFraction by animateFloatAsState(TonightSheetSnaps[snapIndex], label = "tonight-sheet")
    val density = LocalDensity.current

    BoxWithConstraints(
        modifier =
            modifier
                .fillMaxSize()
                .background(PynapliColors.Black)
                .testTag(PlayingTonightTags.SCREEN),
    ) {
        val sheetHeight = maxHeight * sheetFraction
        val floaterHeight = with(density) { floaterHeightPx.toDp() }
        ZoneMap(
            items = items,
            topInset = floaterHeight,
            bottomInset = sheetHeight,
            modifier = Modifier.fillMaxSize(),
        )

        floater(
            Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth()
                .onSizeChanged { floaterHeightPx = it.height }
                // The map is deliberately full-bleed behind the status bar, but the floater's
                // controls are not: without this the back button collides with the system clock.
                // Every other surface inherits this inset from PynapliScreenHeader; the floater is
                // hand-rolled precisely because it is not a header, so it applies the inset itself.
                .statusBarsPadding()
                .padding(12.dp),
        )

        TonightSheet(
            height = sheetHeight,
            onDragUp = { if (snapIndex < TonightSheetSnaps.lastIndex) snapIndex++ },
            onDragDown = { if (snapIndex > 0) snapIndex-- },
            modifier = Modifier.align(Alignment.BottomCenter),
        ) {
            feed(Modifier.fillMaxWidth())
        }

        error(
            Modifier
                .align(Alignment.TopCenter)
                .padding(top = floaterHeight, start = 12.dp, end = 12.dp),
        )
    }
}

/**
 * Tablet and desktop layout: a side panel (back, the viewer's session card, the feed) beside the
 * map, the way desktop map apps do it. The phone sheet stretched across a wide window left rows
 * 1,400px long with the message button stranded at the far edge, and covered most of the map.
 */
@Composable
private fun WideTonightLayout(
    items: List<TonightFeedItemUi>,
    floater: @Composable (Modifier) -> Unit,
    feed: @Composable (Modifier) -> Unit,
    error: @Composable (Modifier) -> Unit,
    modifier: Modifier,
) {
    Row(
        modifier =
            modifier
                .fillMaxSize()
                .background(PynapliColors.Black)
                .testTag(PlayingTonightTags.SCREEN),
    ) {
        Column(
            modifier =
                Modifier
                    .width(TonightPanelWidth)
                    .fillMaxHeight()
                    .background(PynapliColors.Surface)
                    .statusBarsPadding(),
        ) {
            floater(Modifier.fillMaxWidth().padding(12.dp))
            feed(Modifier.fillMaxWidth().weight(1f))
        }
        VerticalDivider(thickness = 1.dp, color = PynapliColors.BorderSoft)
        Box(modifier = Modifier.weight(1f).fillMaxHeight()) {
            ZoneMap(items = items, topInset = 0.dp, bottomInset = 0.dp, modifier = Modifier.fillMaxSize())
            error(Modifier.align(Alignment.TopCenter).statusBarsPadding().padding(12.dp))
        }
    }
}

@Composable
private fun TonightError(
    message: String,
    onDismiss: () -> Unit,
    modifier: Modifier,
    // False for a passing notice: same chip, quieter border, its own test tag.
    isError: Boolean = true,
) {
    Text(
        text = message,
        color = PynapliColors.TextPrimary,
        style = MaterialTheme.typography.bodySmall,
        modifier =
            modifier
                .clip(PynapliShape.CardShape)
                .background(PynapliColors.TonightMapScrim)
                .border(1.dp, if (isError) PynapliColors.Warning.copy(alpha = 0.5f) else PynapliColors.Border, PynapliShape.CardShape)
                .handCursor()
                .clickable(onClick = onDismiss)
                .testTag(if (isError) PlayingTonightTags.ERROR else PlayingTonightTags.NOTICE)
                .padding(horizontal = 12.dp, vertical = 8.dp),
    )
}

/** Wide enough for a row's name, badges and note without wrapping; the map gets the rest. */
private val TonightPanelWidth = 380.dp

private val TonightSheetSnaps = listOf(0.22f, 0.56f, 0.9f)

// ── Zone map ────────────────────────────────────────────────────────────────
// Abstract, privacy-first map: zone halos sized by session count, dots jittered within
// their coarse zone bucket. No real geocoding, no precise coordinates, no street level.
@Composable
private fun ZoneMap(
    items: List<TonightFeedItemUi>,
    topInset: Dp,
    bottomInset: Dp,
    modifier: Modifier = Modifier,
) {
    val accent = PynapliColors.Accent
    val online = PynapliColors.Success
    Canvas(modifier = modifier.background(PynapliColors.Black)) {
        val w = size.width
        val h = size.height
        // The street grid stays full-bleed for texture; everything meaningful is placed in the
        // visible band between [topInset] and [bottomInset]. When the sheet is dragged nearly to
        // the top that band collapses, so it never shrinks below a minimum strip.
        val areaTop = topInset.toPx().coerceIn(0f, h)
        val areaBottom = (h - bottomInset.toPx()).coerceAtLeast((areaTop + MIN_MAP_BAND_PX).coerceAtMost(h))
        val areaHeight = areaBottom - areaTop
        val areaCenter = Offset(w / 2f, areaTop + areaHeight / 2f)

        // Faint street grid for texture.
        val gridColor = PynapliColors.BorderSoft
        val step = 48f
        var x = 0f
        while (x < w) {
            drawLine(gridColor, Offset(x, 0f), Offset(x, h), strokeWidth = 1f)
            x += step
        }
        var y = 0f
        while (y < h) {
            drawLine(gridColor, Offset(0f, y), Offset(w, y), strokeWidth = 1f)
            y += step
        }

        // Normalize coarse zone buckets into the canvas. When there's no spread we still
        // scatter deterministically so the map never collapses to a single point.
        val placed = items.mapNotNull { it.zoneKey?.let { _ -> it } }
        val coords = placed.map { parseZone(it.zoneKey!!) }
        val lons = coords.map { it.first }
        val lats = coords.map { it.second }
        val minLon = lons.minOrNull() ?: 0
        val maxLon = lons.maxOrNull() ?: 0
        val minLat = lats.minOrNull() ?: 0
        val maxLat = lats.maxOrNull() ?: 0
        val lonSpan = (maxLon - minLon).coerceAtLeast(1)
        val latSpan = (maxLat - minLat).coerceAtLeast(1)
        val pad = 0.16f

        fun project(
            zoneKey: String,
            seed: Int,
        ): Offset {
            val (lon, lat) = parseZone(zoneKey)
            val nx = if (placed.size <= 1) 0.5f else (lon - minLon).toFloat() / lonSpan
            val ny = if (placed.size <= 1) 0.5f else (lat - minLat).toFloat() / latSpan
            // Deterministic jitter within the zone so dots never imply a precise spot.
            val jx = (hash(seed) % 1000 / 1000f - 0.5f) * 0.08f
            val jy = (hash(seed * 7 + 13) % 1000 / 1000f - 0.5f) * 0.08f
            val fx = (pad + (1 - 2 * pad) * nx + jx).coerceIn(0.04f, 0.96f)
            val fy = (pad + (1 - 2 * pad) * (1 - ny) + jy).coerceIn(0.04f, 0.96f)
            return Offset(fx * w, areaTop + fy * areaHeight)
        }

        // Zone halos: one soft glow per distinct bucket, larger where more sessions cluster.
        placed
            .groupBy { it.zoneKey }
            .forEach { (zoneKey, group) ->
                val center = project(zoneKey!!, zoneKey.hashCode())
                val radius = (28f + group.size * 10f)
                drawCircle(
                    brush =
                        Brush.radialGradient(
                            colors = listOf(accent.copy(alpha = 0.30f), Color.Transparent),
                            center = center,
                            radius = radius,
                        ),
                    radius = radius,
                    center = center,
                )
            }

        // Session dots — approximate, with a faint "not precise" ring.
        placed.forEach { item ->
            val p = project(item.zoneKey!!, item.sessionId.hashCode())
            drawCircle(
                color = (if (item.online) accent else PynapliColors.Offline).copy(alpha = 0.4f),
                radius = 11f,
                center = p,
            )
            drawCircle(color = if (item.online) accent else PynapliColors.Offline, radius = 5.5f, center = p)
            if (item.online) {
                drawCircle(color = online, radius = 2.2f, center = Offset(p.x + 4.5f, p.y - 4.5f))
            }
        }

        // "You" reference marker at the center of the visible band.
        drawCircle(color = PynapliColors.TextPrimary, radius = 5f, center = areaCenter)
        drawCircle(
            color = PynapliColors.TextPrimary.copy(alpha = 0.35f),
            radius = 12f,
            center = areaCenter,
            style =
                androidx.compose.ui.graphics.drawscope
                    .Stroke(width = 1.5f),
        )
    }

    // Legend — the visual contract that this is approximate. Bottom-left of the visible band:
    // the top-left corner it used to sit in is under the status bar on phones and under the back
    // button once the floater is laid out over it.
    Box(modifier = modifier.padding(bottom = bottomInset)) {
        Text(
            text = "Approximate · never street-level",
            color = PynapliColors.TextTertiary,
            style = MaterialTheme.typography.labelSmall,
            modifier =
                Modifier
                    .align(Alignment.BottomStart)
                    .padding(12.dp)
                    .clip(PynapliShape.CardShape)
                    .background(PynapliColors.Black.copy(alpha = 0.65f))
                    .padding(horizontal = 8.dp, vertical = 4.dp),
        )
    }
}

private const val MIN_MAP_BAND_PX = 96f

private fun parseZone(zoneKey: String): Pair<Int, Int> {
    val parts = zoneKey.split("_")
    val lon = parts.getOrNull(0)?.toIntOrNull() ?: 0
    val lat = parts.getOrNull(1)?.toIntOrNull() ?: 0
    return lon to lat
}

private fun hash(seed: Int): Int {
    var x = seed xor 0x9E3779B9.toInt()
    x = (x xor (x ushr 16)) * 0x45D9F3B
    x = (x xor (x ushr 16)) * 0x45D9F3B
    x = x xor (x ushr 16)
    return abs(x)
}

// ── Top floater ─────────────────────────────────────────────────────────────
// Four states: no session (Start CTA), live (End), proposed + viewer is the pending
// partner (Approve/Decline), proposed + viewer is the owner (waiting + Cancel).
@Composable
private fun TonightTopFloater(
    session: TonightSessionUi?,
    viewerUserId: String?,
    onBack: () -> Unit,
    onStartSession: () -> Unit,
    onApprove: () -> Unit,
    onDecline: () -> Unit,
    onEnd: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(
            modifier =
                Modifier
                    // Keeps the compact 40dp disc (it floats over the map, so it stays visually
                    // light) while reserving the >=48dp touch target PynapliLayout.MinTouchTarget
                    // requires — the guarantee PynapliHeaderActionButton encodes and that this
                    // map-specific button lost by being hand-rolled. Translucent on purpose, so it
                    // cannot simply reuse that primitive.
                    .minimumInteractiveComponentSize()
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(PynapliColors.SurfaceRaised.copy(alpha = 0.8f))
                    .border(1.dp, PynapliColors.Border, CircleShape)
                    .handCursor()
                    .clickable(onClick = onBack)
                    .testTag(PlayingTonightTags.BACK),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = PynapliColors.TextPrimary, modifier = Modifier.size(20.dp))
        }

        when {
            session == null ->
                FloaterCard(
                    modifier =
                        Modifier
                            .weight(1f)
                            .handCursor()
                            .clickable(onClick = onStartSession)
                            .testTag(PlayingTonightTags.START),
                    leadingDashed = true,
                ) {
                    Icon(Icons.Filled.Add, contentDescription = null, tint = PynapliColors.Accent, modifier = Modifier.size(18.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Start a session", color = PynapliColors.TextPrimary, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                        Text("Solo or propose together · 2-hour window", color = PynapliColors.TextTertiary, style = MaterialTheme.typography.labelSmall)
                    }
                }

            session.pendingApprovalFor(viewerUserId) ->
                FloaterCard(modifier = Modifier.weight(1f).testTag(PlayingTonightTags.APPROVAL)) {
                    Icon(Icons.Filled.DarkMode, contentDescription = null, tint = PynapliColors.Accent, modifier = Modifier.size(18.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "${session.ownerDisplayName() ?: "Your partner"} invited you",
                            color = PynapliColors.TextPrimary,
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Text("Together · ${humanizeHostMode(session.hostMode)}", color = PynapliColors.TextTertiary, style = MaterialTheme.typography.labelSmall)
                    }
                    PynapliButton("Decline", onDecline, Modifier.testTag(PlayingTonightTags.DECLINE), style = PynapliButtonStyle.Secondary, size = PynapliButtonSize.Compact)
                    PynapliButton("Approve", onApprove, Modifier.testTag(PlayingTonightTags.APPROVE), style = PynapliButtonStyle.Primary, size = PynapliButtonSize.Compact)
                }

            session.isProposed ->
                FloaterCard(modifier = Modifier.weight(1f).testTag(PlayingTonightTags.WAITING)) {
                    Icon(Icons.Filled.DarkMode, contentDescription = null, tint = PynapliColors.Accent, modifier = Modifier.size(18.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Waiting for your partner", color = PynapliColors.TextPrimary, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                        Text("They need to approve before you go live", color = PynapliColors.TextTertiary, style = MaterialTheme.typography.labelSmall)
                    }
                    PynapliButton("Cancel", onEnd, Modifier.testTag(PlayingTonightTags.END), style = PynapliButtonStyle.Secondary, size = PynapliButtonSize.Compact)
                }

            else ->
                FloaterCard(modifier = Modifier.weight(1f).testTag(PlayingTonightTags.LIVE_FLOATER)) {
                    Icon(Icons.Filled.DarkMode, contentDescription = null, tint = PynapliColors.Accent, modifier = Modifier.size(18.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text("You're live · ${intentLabel(session.intent, session.intentRaw)}", color = PynapliColors.TextPrimary, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                        Text(humanizeHostMode(session.hostMode), color = PynapliColors.TextTertiary, style = MaterialTheme.typography.labelSmall)
                    }
                    PynapliButton("End", onEnd, Modifier.testTag(PlayingTonightTags.END), style = PynapliButtonStyle.Secondary, size = PynapliButtonSize.Compact)
                }
        }
    }
}

@Composable
private fun FloaterCard(
    modifier: Modifier = Modifier,
    leadingDashed: Boolean = false,
    content: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit,
) {
    Row(
        modifier =
            modifier
                .clip(PynapliShape.CardShape)
                .background(PynapliColors.SurfaceRaised.copy(alpha = 0.92f))
                .border(
                    1.dp,
                    if (leadingDashed) PynapliColors.Border else PynapliColors.Accent.copy(alpha = 0.35f),
                    PynapliShape.CardShape,
                ).padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        content = content,
    )
}

// ── Draggable sheet (phone) and the feed it carries ───────────────────────────
@Composable
private fun TonightSheet(
    height: Dp,
    onDragUp: () -> Unit,
    onDragDown: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Column(
        modifier =
            modifier
                .fillMaxWidth()
                .height(height)
                .clip(PynapliShape.SheetShape)
                .background(PynapliColors.Surface)
                .border(
                    width = 1.dp,
                    color = PynapliColors.Border,
                    shape = PynapliShape.SheetShape,
                ),
    ) {
        // Drag handle: drag up/down cycles snap points.
        Box(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .pointerInput(Unit) {
                        detectVerticalDragGestures { _, dragAmount ->
                            if (dragAmount < -6f) onDragUp()
                            if (dragAmount > 6f) onDragDown()
                        }
                    }.padding(vertical = 10.dp),
            contentAlignment = Alignment.Center,
        ) {
            Box(
                modifier =
                    Modifier
                        .width(36.dp)
                        .height(4.dp)
                        .clip(PynapliShape.PillShape)
                        .background(PynapliColors.Border),
            )
        }
        content()
    }
}

/** Header, intent filters and the session list; carried by the phone sheet and the wide panel. */
@Composable
private fun TonightFeed(
    state: PlayingTonightUiState,
    onIntentSelected: (TonightIntent?) -> Unit,
    onOpenProfile: (String) -> Unit,
    onMessage: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("Playing Tonight", color = PynapliColors.TextPrimary, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Text(
                text = "${state.feedItems.size}",
                color = PynapliColors.TextSecondary,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
                modifier =
                    Modifier
                        .clip(PynapliShape.PillShape)
                        .background(PynapliColors.SurfaceRaised)
                        .padding(horizontal = 8.dp, vertical = 2.dp),
            )
        }

        IntentFilterRow(selected = state.intentFilter, onSelected = onIntentSelected)

        if (state.feedItems.isEmpty() && state.isLoading) {
            Box(modifier = Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(strokeWidth = 2.dp, modifier = Modifier.size(22.dp))
            }
        } else if (state.feedItems.isEmpty()) {
            PynapliEmptyState(
                icon = Icons.Filled.DarkMode,
                title = "Nobody here yet",
                subtitle = "When someone nearby starts a night out, they'll show up here.",
                modifier = Modifier.fillMaxWidth().padding(vertical = 24.dp),
            )
        } else {
            LazyColumn(modifier = Modifier.fillMaxWidth().testTag(PlayingTonightTags.LIST)) {
                items(state.feedItems, key = { it.sessionId }) { item ->
                    TonightSessionRow(item = item, onOpenProfile = onOpenProfile, onMessage = onMessage)
                }
            }
        }
    }
}

@Composable
private fun IntentFilterRow(
    selected: TonightIntent?,
    onSelected: (TonightIntent?) -> Unit,
) {
    // Scrolls rather than squeezing: five pills overflow the wide-window side panel (and a phone)
    // once text is enlarged.
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 14.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        PynapliPill(
            text = "All",
            selected = selected == null,
            onClick = { onSelected(null) },
            modifier = Modifier.testTag(PlayingTonightTags.intentPill("All")),
        )
        TonightIntent.entries.forEach { intent ->
            PynapliPill(
                text = intent.label,
                selected = selected == intent,
                onClick = { onSelected(intent) },
                modifier = Modifier.testTag(PlayingTonightTags.intentPill(intent.label)),
            )
        }
    }
}

@Composable
private fun TonightSessionRow(
    item: TonightFeedItemUi,
    onOpenProfile: (String) -> Unit,
    onMessage: (String) -> Unit,
) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .handCursor()
                .clickable { onOpenProfile(item.userId) }
                .testTag(PlayingTonightTags.row(item.sessionId))
                .padding(horizontal = 14.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box {
            Box(
                modifier =
                    Modifier
                        .size(44.dp)
                        .clip(PynapliShape.CardShape)
                        .background(PynapliColors.SurfaceRaised),
            ) {
                // Prefer the (approved) session photo when attached; fall back to the profile thumbnail.
                (item.photoUrl ?: item.thumbnailUrl)?.let {
                    AsyncImage(model = it, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                }
            }
            if (item.online) {
                OnlineDot(size = 9.dp, modifier = Modifier.align(Alignment.TopStart))
            }
            item.linkedPartner?.let { partner ->
                Box(
                    modifier =
                        Modifier
                            .align(Alignment.BottomEnd)
                            .size(20.dp)
                            .clip(PynapliShape.SmallShape)
                            .background(PynapliColors.Surface)
                            .border(1.5.dp, PynapliColors.Accent, PynapliShape.SmallShape),
                ) {
                    partner.thumbnailUrl?.let {
                        AsyncImage(model = it, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                    }
                }
            }
        }

        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(item.displayName, color = PynapliColors.TextPrimary, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                item.distanceLabel?.let {
                    Text("· $it", color = PynapliColors.TextTertiary, style = MaterialTheme.typography.labelSmall)
                }
            }
            Spacer(Modifier.height(4.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                IntentBadge(item.intent, item.intentRaw)
                HostModePill(item.hostMode)
            }
            item.note?.let {
                Spacer(Modifier.height(6.dp))
                Text("\"$it\"", color = PynapliColors.TextSecondary, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }

        Box(
            modifier =
                Modifier
                    .size(pynapliIconButtonSize(32.dp))
                    .clip(PynapliShape.CardShape)
                    .background(PynapliColors.SurfaceRaised)
                    .border(1.dp, PynapliColors.Border, PynapliShape.CardShape)
                    .handCursor()
                    .clickable { onMessage(item.userId) }
                    .testTag(PlayingTonightTags.message(item.sessionId)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.AutoMirrored.Filled.Chat, contentDescription = "Message ${item.displayName}", tint = PynapliColors.TextSecondary, modifier = Modifier.size(16.dp))
        }
    }
}

@Composable
private fun IntentBadge(
    intent: TonightIntent?,
    raw: String,
) {
    val color =
        when (intent) {
            TonightIntent.Solo -> PynapliColors.Info
            TonightIntent.Together -> PynapliColors.Accent
            TonightIntent.Social -> PynapliColors.Success
            TonightIntent.Event -> PynapliColors.Warning
            null -> PynapliColors.TextSecondary
        }
    Text(
        text = intentLabel(intent, raw),
        color = color,
        style = MaterialTheme.typography.labelSmall,
        fontWeight = FontWeight.SemiBold,
        modifier =
            Modifier
                .clip(PynapliShape.PillShape)
                .background(color.copy(alpha = 0.12f))
                .border(1.dp, color.copy(alpha = 0.30f), PynapliShape.PillShape)
                .padding(horizontal = 7.dp, vertical = 2.dp),
    )
}

@Composable
private fun HostModePill(hostMode: String) {
    Text(
        text = humanizeHostMode(hostMode),
        color = PynapliColors.TextSecondary,
        style = MaterialTheme.typography.labelSmall,
        modifier =
            Modifier
                .clip(PynapliShape.PillShape)
                .background(PynapliColors.Surface)
                .border(1.dp, PynapliColors.Border, PynapliShape.PillShape)
                .padding(horizontal = 7.dp, vertical = 2.dp),
    )
}

private fun intentLabel(
    intent: TonightIntent?,
    raw: String,
): String = intent?.label ?: raw.replaceFirstChar { it.uppercase() }

private fun humanizeHostMode(hostMode: String): String =
    when (hostMode) {
        "hosting" -> "Hosting"
        "traveling" -> "Traveling"
        "public_meet_first" -> "Public meet first"
        else -> hostMode.replace('_', ' ').replaceFirstChar { it.uppercase() }
    }

internal object PlayingTonightTags {
    const val SCREEN = "tonight-screen"
    const val BACK = "tonight-back"
    const val LIST = "tonight-list"
    const val LIVE_FLOATER = "tonight-live-floater"
    const val START = "tonight-start"
    const val END = "tonight-end"
    const val APPROVAL = "tonight-approval"
    const val APPROVE = "tonight-approve"
    const val DECLINE = "tonight-decline"
    const val WAITING = "tonight-waiting"
    const val NOTICE = "tonight-notice"
    const val ERROR = "tonight-error"

    fun row(sessionId: String): String = "tonight-row-$sessionId"

    fun message(sessionId: String): String = "tonight-message-$sessionId"

    fun intentPill(label: String): String = "tonight-pill-$label"
}

private const val TONIGHT_PARTNER_REFETCH_MILLIS = 15_000L
private const val TONIGHT_NOTICE_VISIBLE_MILLIS = 5_000L
