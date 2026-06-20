package com.pynapli.app.media

import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.lazy.grid.LazyGridItemInfo
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.zIndex
import kotlinx.coroutines.channels.Channel

/*
 * Long-press drag-to-reorder for the album LazyVerticalGrid — the familiar gallery interaction
 * (Hinge / iOS Photos) replacing the per-tile arrow buttons. Self-contained (no third-party
 * reorderable dependency) and cheap enough for low-end devices: albums are small (≤6 / ≤12), the
 * reorder is local/optimistic during the drag, and a single server write lands on drop.
 *
 * Tiles are keyed with [albumPhotoKey] so hit-testing only ever targets photo tiles of the *same*
 * album — the spanned section headers, the Add tile, and grants are never drag targets, and a
 * profile photo can never be dropped into the private album.
 */

private const val ALBUM_PHOTO_KEY_PREFIX = "album-photo"
private const val AUTO_SCROLL_THRESHOLD_PX = 96f
private const val AUTO_SCROLL_MAX_STEP_PX = 48f

internal fun albumPhotoKey(
    kind: MediaAlbumKindUi,
    assetId: String,
): String = "$ALBUM_PHOTO_KEY_PREFIX:${kind.apiValue}:$assetId"

private data class AlbumPhotoKey(
    val kind: MediaAlbumKindUi,
    val assetId: String,
)

private fun parseAlbumPhotoKey(key: Any?): AlbumPhotoKey? {
    val parts = (key as? String)?.split(":") ?: return null
    if (parts.size != 3 || parts[0] != ALBUM_PHOTO_KEY_PREFIX) return null
    val kind = MediaAlbumKindUi.entries.firstOrNull { it.apiValue == parts[1] } ?: return null
    return AlbumPhotoKey(kind, parts[2])
}

private fun LazyGridItemInfo.topLeft(): Offset = Offset(offset.x.toFloat(), offset.y.toFloat())

private fun LazyGridItemInfo.contains(point: Offset): Boolean =
    point.x >= offset.x &&
        point.x <= offset.x + size.width &&
        point.y >= offset.y &&
        point.y <= offset.y + size.height

internal class AlbumReorderState(
    private val gridState: LazyGridState,
    private val onMove: (kind: MediaAlbumKindUi, fromAssetId: String, toAssetId: String) -> Unit,
    private val onDrop: (kind: MediaAlbumKindUi) -> Unit,
) {
    var draggingKey by mutableStateOf<String?>(null)
        private set

    private var dragging: AlbumPhotoKey? = null
    private var draggedDelta by mutableStateOf(Offset.Zero)
    private var initialTopLeft = Offset.Zero
    private var moved = false

    private val autoScroll = Channel<Float>(Channel.CONFLATED)

    private val draggingItemInfo: LazyGridItemInfo?
        get() = draggingKey?.let { key -> gridState.layoutInfo.visibleItemsInfo.firstOrNull { it.key == key } }

    /** Translation to keep the lifted tile under the finger as the layout reflows beneath it. */
    val draggingOffset: Offset
        get() = draggingItemInfo?.let { info -> initialTopLeft + draggedDelta - info.topLeft() } ?: Offset.Zero

    fun onDragStart(offset: Offset) {
        val hit = gridState.layoutInfo.visibleItemsInfo.firstOrNull { it.contains(offset) && parseAlbumPhotoKey(it.key) != null } ?: return
        draggingKey = hit.key as String
        dragging = parseAlbumPhotoKey(hit.key)
        initialTopLeft = hit.topLeft()
        draggedDelta = Offset.Zero
        moved = false
    }

    fun onDrag(amount: Offset) {
        val current = dragging ?: return
        draggedDelta += amount
        val info = draggingItemInfo ?: return
        // The rendered tile's top-left is always initialTopLeft + draggedDelta (the translation
        // cancels the layout offset), so its center tracks the finger regardless of reflow.
        val center = initialTopLeft + draggedDelta + Offset(info.size.width / 2f, info.size.height / 2f)

        val target =
            gridState.layoutInfo.visibleItemsInfo.firstOrNull { item ->
                item.key != draggingKey &&
                    parseAlbumPhotoKey(item.key)?.kind == current.kind &&
                    item.contains(center)
            }
        if (target != null) {
            val targetKey = parseAlbumPhotoKey(target.key) ?: return
            onMove(current.kind, current.assetId, targetKey.assetId)
            moved = true
        }
        requestAutoScroll(center.y)
    }

    fun onDragEnd() {
        val kind = dragging?.kind
        val didMove = moved
        reset()
        if (kind != null && didMove) onDrop(kind)
    }

    fun onDragCancel() = onDragEnd()

    private fun reset() {
        draggingKey = null
        dragging = null
        draggedDelta = Offset.Zero
        initialTopLeft = Offset.Zero
        moved = false
    }

    private fun requestAutoScroll(centerY: Float) {
        val viewportHeight = gridState.layoutInfo.viewportSize.height
        if (viewportHeight <= 0) return
        val overflow =
            when {
                centerY < AUTO_SCROLL_THRESHOLD_PX -> centerY - AUTO_SCROLL_THRESHOLD_PX
                centerY > viewportHeight - AUTO_SCROLL_THRESHOLD_PX -> centerY - (viewportHeight - AUTO_SCROLL_THRESHOLD_PX)
                else -> 0f
            }
        if (overflow != 0f) {
            autoScroll.trySend(overflow.coerceIn(-AUTO_SCROLL_MAX_STEP_PX, AUTO_SCROLL_MAX_STEP_PX))
        }
    }

    suspend fun runAutoScroll() {
        for (delta in autoScroll) {
            if (draggingKey == null) continue
            gridState.scrollBy(delta)
        }
    }
}

@Composable
internal fun rememberAlbumReorderState(
    gridState: LazyGridState,
    onMove: (kind: MediaAlbumKindUi, fromAssetId: String, toAssetId: String) -> Unit,
    onDrop: (kind: MediaAlbumKindUi) -> Unit,
): AlbumReorderState {
    val currentOnMove by rememberUpdatedState(onMove)
    val currentOnDrop by rememberUpdatedState(onDrop)
    val state =
        remember(gridState) {
            AlbumReorderState(
                gridState = gridState,
                onMove = { kind, from, to -> currentOnMove(kind, from, to) },
                onDrop = { kind -> currentOnDrop(kind) },
            )
        }
    LaunchedEffect(state) { state.runAutoScroll() }
    return state
}

/** Attach to the grid: a long-press lifts the photo tile under the finger and drives reordering. */
internal fun Modifier.albumReorderable(state: AlbumReorderState): Modifier =
    pointerInput(state) {
        detectDragGesturesAfterLongPress(
            onDragStart = { offset -> state.onDragStart(offset) },
            onDrag = { change, dragAmount ->
                change.consume()
                state.onDrag(dragAmount)
            },
            onDragEnd = { state.onDragEnd() },
            onDragCancel = { state.onDragCancel() },
        )
    }

/**
 * Per-tile lift treatment: the dragged tile follows the finger above its peers; everyone else gets
 * a placement animation so the grid reflows smoothly. Must be applied inside the item scope so the
 * caller can also attach `Modifier.animateItem()` for the non-dragged tiles.
 */
internal fun Modifier.albumDraggedTile(
    state: AlbumReorderState,
    key: String,
): Modifier =
    if (state.draggingKey == key) {
        graphicsLayer {
            translationX = state.draggingOffset.x
            translationY = state.draggingOffset.y
            shadowElevation = 12f
            scaleX = 1.04f
            scaleY = 1.04f
        }.zIndex(1f)
    } else {
        this
    }
