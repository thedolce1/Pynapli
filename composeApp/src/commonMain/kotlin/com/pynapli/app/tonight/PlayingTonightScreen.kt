package com.pynapli.app.tonight

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.pynapli.app.design.OnlineDot
import com.pynapli.app.design.PynapliColors
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

    Box(
        modifier =
            modifier
                .fillMaxSize()
                .background(PynapliColors.Black)
                .testTag(PlayingTonightTags.SCREEN),
    ) {
        ZoneMap(
            items = state.feedItems,
            modifier = Modifier.fillMaxSize(),
        )

        TonightTopFloater(
            session = state.currentSession,
            viewerUserId = viewerUserId,
            onBack = onBack,
            onStartSession = { showStartSheet = true },
            onApprove = { scope.launch { stateHolder.approveSession() } },
            onDecline = { scope.launch { stateHolder.declineSession() } },
            onEnd = { scope.launch { stateHolder.endCurrentSession() } },
            modifier =
                Modifier
                    .align(Alignment.TopCenter)
                    .fillMaxWidth()
                    .padding(12.dp),
        )

        TonightSheet(
            state = state,
            onIntentSelected = { scope.launch { stateHolder.setIntentFilter(it) } },
            onOpenProfile = onOpenProfile,
            onMessage = onMessage,
            modifier = Modifier.align(Alignment.BottomCenter),
        )

        state.errorMessage?.let { message ->
            Text(
                text = message,
                color = PynapliColors.TextPrimary,
                style = MaterialTheme.typography.bodySmall,
                modifier =
                    Modifier
                        .align(Alignment.TopCenter)
                        .padding(top = 72.dp, start = 12.dp, end = 12.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(Color(0xF2201216))
                        .border(1.dp, PynapliColors.Warning.copy(alpha = 0.5f), RoundedCornerShape(10.dp))
                        .clickable { stateHolder.clearError() }
                        .testTag(PlayingTonightTags.ERROR)
                        .padding(horizontal = 12.dp, vertical = 8.dp),
            )
        }
    }

    if (showStartSheet) {
        StartTonightSheet(
            isSubmitting = state.isMutating,
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

// ── Zone map ────────────────────────────────────────────────────────────────
// Abstract, privacy-first map: zone halos sized by session count, dots jittered within
// their coarse zone bucket. No real geocoding, no precise coordinates, no street level.
@Composable
private fun ZoneMap(
    items: List<TonightFeedItemUi>,
    modifier: Modifier = Modifier,
) {
    val accent = PynapliColors.Accent
    val online = PynapliColors.Success
    Canvas(modifier = modifier.background(Color(0xFF0C0C10))) {
        val w = size.width
        val h = size.height

        // Faint street grid for texture.
        val gridColor = Color(0xFF1B1B22)
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
            return Offset(fx * w, fy * h)
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
                color = (if (item.online) accent else Color(0xFF7B91AA)).copy(alpha = 0.4f),
                radius = 11f,
                center = p,
            )
            drawCircle(color = if (item.online) accent else Color(0xFF8A8A93), radius = 5.5f, center = p)
            if (item.online) {
                drawCircle(color = online, radius = 2.2f, center = Offset(p.x + 4.5f, p.y - 4.5f))
            }
        }

        // "You" reference marker at center.
        drawCircle(color = Color.White, radius = 5f, center = Offset(w / 2f, h / 2f))
        drawCircle(
            color = Color.White.copy(alpha = 0.35f),
            radius = 12f,
            center = Offset(w / 2f, h / 2f),
            style =
                androidx.compose.ui.graphics.drawscope
                    .Stroke(width = 1.5f),
        )
    }

    // Legend — the visual contract that this is approximate.
    Box(modifier = modifier) {
        Text(
            text = "Approximate · never street-level",
            color = PynapliColors.TextTertiary,
            style = MaterialTheme.typography.labelSmall,
            modifier =
                Modifier
                    .padding(12.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(Color(0xA60B0B0E))
                    .padding(horizontal = 8.dp, vertical = 4.dp),
        )
    }
}

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
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(Color(0xCC15151B))
                    .border(1.dp, PynapliColors.Border, CircleShape)
                    .clickable(onClick = onBack)
                    .testTag(PlayingTonightTags.BACK),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = PynapliColors.TextPrimary, modifier = Modifier.size(20.dp))
        }

        when {
            session == null ->
                FloaterCard(
                    modifier = Modifier.weight(1f).clickable(onClick = onStartSession).testTag(PlayingTonightTags.START),
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
                    PillButton("Decline", onDecline, filled = false, modifier = Modifier.testTag(PlayingTonightTags.DECLINE))
                    PillButton("Approve", onApprove, filled = true, modifier = Modifier.testTag(PlayingTonightTags.APPROVE))
                }

            session.isProposed ->
                FloaterCard(modifier = Modifier.weight(1f).testTag(PlayingTonightTags.WAITING)) {
                    Icon(Icons.Filled.DarkMode, contentDescription = null, tint = PynapliColors.Accent, modifier = Modifier.size(18.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Waiting for your partner", color = PynapliColors.TextPrimary, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                        Text("They need to approve before you go live", color = PynapliColors.TextTertiary, style = MaterialTheme.typography.labelSmall)
                    }
                    PillButton("Cancel", onEnd, filled = false, modifier = Modifier.testTag(PlayingTonightTags.END))
                }

            else ->
                FloaterCard(modifier = Modifier.weight(1f).testTag(PlayingTonightTags.LIVE_FLOATER)) {
                    Icon(Icons.Filled.DarkMode, contentDescription = null, tint = PynapliColors.Accent, modifier = Modifier.size(18.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text("You're live · ${intentLabel(session.intent, session.intentRaw)}", color = PynapliColors.TextPrimary, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                        Text(humanizeHostMode(session.hostMode), color = PynapliColors.TextTertiary, style = MaterialTheme.typography.labelSmall)
                    }
                    PillButton("End", onEnd, filled = false, modifier = Modifier.testTag(PlayingTonightTags.END))
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
                .clip(RoundedCornerShape(12.dp))
                .background(Color(0xEB15151B))
                .border(
                    1.dp,
                    if (leadingDashed) PynapliColors.Border else PynapliColors.Accent.copy(alpha = 0.35f),
                    RoundedCornerShape(12.dp),
                ).padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        content = content,
    )
}

@Composable
private fun PillButton(
    label: String,
    onClick: () -> Unit,
    filled: Boolean,
    modifier: Modifier = Modifier,
) {
    Text(
        text = label,
        color = if (filled) PynapliColors.TextInverse else PynapliColors.TextSecondary,
        style = MaterialTheme.typography.labelMedium,
        fontWeight = FontWeight.SemiBold,
        modifier =
            modifier
                .clip(RoundedCornerShape(100))
                .background(if (filled) PynapliColors.Accent else PynapliColors.Surface)
                .border(1.dp, if (filled) PynapliColors.Accent else PynapliColors.Border, RoundedCornerShape(100))
                .clickable(onClick = onClick)
                .padding(horizontal = 12.dp, vertical = 6.dp),
    )
}

// ── Draggable sheet ─────────────────────────────────────────────────────────
@Composable
private fun TonightSheet(
    state: PlayingTonightUiState,
    onIntentSelected: (TonightIntent?) -> Unit,
    onOpenProfile: (String) -> Unit,
    onMessage: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val snaps = listOf(0.22f, 0.56f, 0.9f)
    var snapIndex by remember { mutableStateOf(1) }
    val targetFraction by animateFloatAsState(snaps[snapIndex], label = "tonight-sheet")

    Column(
        modifier =
            modifier
                .fillMaxWidth()
                .fillMaxHeight(targetFraction)
                .clip(RoundedCornerShape(topStart = 18.dp, topEnd = 18.dp))
                .background(PynapliColors.Surface)
                .border(
                    width = 1.dp,
                    color = PynapliColors.Border,
                    shape = RoundedCornerShape(topStart = 18.dp, topEnd = 18.dp),
                ),
    ) {
        // Drag handle: drag up/down cycles snap points.
        Box(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .pointerInput(Unit) {
                        detectVerticalDragGestures { _, dragAmount ->
                            if (dragAmount < -6f && snapIndex < snaps.lastIndex) snapIndex++
                            if (dragAmount > 6f && snapIndex > 0) snapIndex--
                        }
                    }.padding(vertical = 10.dp),
            contentAlignment = Alignment.Center,
        ) {
            Box(
                modifier =
                    Modifier
                        .width(36.dp)
                        .height(4.dp)
                        .clip(RoundedCornerShape(100))
                        .background(PynapliColors.Border),
            )
        }

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
                        .clip(RoundedCornerShape(100))
                        .background(PynapliColors.SurfaceRaised)
                        .padding(horizontal = 8.dp, vertical = 2.dp),
            )
        }

        IntentFilterRow(selected = state.intentFilter, onSelected = onIntentSelected)

        if (state.feedItems.isEmpty()) {
            Box(modifier = Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
                Text(
                    text = if (state.isLoading) "Loading…" else "Nobody here yet",
                    color = PynapliColors.TextTertiary,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
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
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        IntentPill(label = "All", active = selected == null, onClick = { onSelected(null) })
        TonightIntent.entries.forEach { intent ->
            IntentPill(
                label = intentLabel(intent, intent.serverValue),
                active = selected == intent,
                onClick = { onSelected(intent) },
            )
        }
    }
}

@Composable
private fun IntentPill(
    label: String,
    active: Boolean,
    onClick: () -> Unit,
) {
    Text(
        text = label,
        color = if (active) PynapliColors.TextInverse else PynapliColors.TextSecondary,
        style = MaterialTheme.typography.labelMedium,
        fontWeight = FontWeight.SemiBold,
        modifier =
            Modifier
                .clip(RoundedCornerShape(100))
                .background(if (active) PynapliColors.Accent else PynapliColors.SurfaceRaised)
                .border(1.dp, if (active) PynapliColors.Accent else PynapliColors.Border, RoundedCornerShape(100))
                .clickable(onClick = onClick)
                .testTag(PlayingTonightTags.intentPill(label))
                .padding(horizontal = 12.dp, vertical = 6.dp),
    )
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
                        .clip(RoundedCornerShape(10.dp))
                        .background(PynapliColors.SurfaceRaised),
            ) {
                item.thumbnailUrl?.let {
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
                            .clip(RoundedCornerShape(6.dp))
                            .background(PynapliColors.Surface)
                            .border(1.5.dp, PynapliColors.Accent, RoundedCornerShape(6.dp)),
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
                    .size(32.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(PynapliColors.SurfaceRaised)
                    .border(1.dp, PynapliColors.Border, RoundedCornerShape(8.dp))
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
                .clip(RoundedCornerShape(100))
                .background(color.copy(alpha = 0.12f))
                .border(1.dp, color.copy(alpha = 0.30f), RoundedCornerShape(100))
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
                .clip(RoundedCornerShape(100))
                .background(PynapliColors.Surface)
                .border(1.dp, PynapliColors.Border, RoundedCornerShape(100))
                .padding(horizontal = 7.dp, vertical = 2.dp),
    )
}

private fun intentLabel(
    intent: TonightIntent?,
    raw: String,
): String =
    when (intent) {
        TonightIntent.Solo -> "Solo"
        TonightIntent.Together -> "Together"
        TonightIntent.Social -> "Social"
        TonightIntent.Event -> "Events"
        null -> raw.replaceFirstChar { it.uppercase() }
    }

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
    const val ERROR = "tonight-error"

    fun row(sessionId: String): String = "tonight-row-$sessionId"

    fun message(sessionId: String): String = "tonight-message-$sessionId"

    fun intentPill(label: String): String = "tonight-pill-$label"
}
