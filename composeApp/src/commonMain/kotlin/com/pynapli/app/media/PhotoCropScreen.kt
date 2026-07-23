package com.pynapli.app.media

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.backhandler.BackHandler
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.pynapli.app.design.PynapliShape
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

/**
 * Full-screen host for the square crop step. Renders above whatever surface picked
 * the photo (media albums or the onboarding profile step) while
 * [MediaAlbumsUiState.pendingCropMedia] is staged.
 *
 * [scope] must outlive this overlay (the app-level scope, like [PynapliOverlays]
 * receives): confirming clears the staged media, which removes the overlay from
 * composition immediately — a scope remembered here would cancel the upload it
 * just launched.
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
internal fun PhotoCropOverlay(
    stateHolder: MediaAlbumsStateHolder,
    scope: CoroutineScope,
) {
    val state by stateHolder.state.collectAsState()
    val pending = state.pendingCropMedia ?: return

    BackHandler { stateHolder.cancelPendingCrop() }
    PhotoCropScreen(
        media = pending,
        onCancel = stateHolder::cancelPendingCrop,
        onUsePhoto = { cropped -> scope.launch { stateHolder.confirmPendingCrop(cropped) } },
        modifier = Modifier.fillMaxSize(),
    )
}

@Composable
internal fun PhotoCropScreen(
    media: PickedMediaForUpload,
    onCancel: () -> Unit,
    onUsePhoto: (PickedMediaForUpload) -> Unit,
    modifier: Modifier = Modifier,
) {
    val imageBitmap = remember(media) { decodePickedImageBitmap(media.bytes, DECODE_MAX_SIDE) }

    if (imageBitmap == null) {
        // Undecodable bytes skip the crop and upload as picked — the square is a UX
        // default, not a server rule, so the photo must never get stuck here.
        LaunchedEffect(media) { onUsePhoto(media) }
        return
    }

    val controller =
        remember(imageBitmap) {
            PhotoSquareCropController(imageWidth = imageBitmap.width, imageHeight = imageBitmap.height)
        }
    var gestureTick by remember(controller) { mutableIntStateOf(0) }
    var viewportSidePx by remember { mutableIntStateOf(0) }
    var processing by remember(media) { mutableStateOf(false) }

    if (processing) {
        LaunchedEffect(media) {
            val cropped =
                withContext(Dispatchers.Default) {
                    val bitmap = cropToSquareBitmap(imageBitmap, controller.cropRect())
                    encodeCroppedJpegBytes(bitmap, CROPPED_PHOTO_JPEG_QUALITY)?.let { bytes ->
                        PickedMediaForUpload(
                            contentType = "image/jpeg",
                            byteSize = bytes.size.toLong(),
                            pixelWidth = bitmap.width,
                            pixelHeight = bitmap.height,
                            bytes = bytes,
                        )
                    }
                }
            // Encoder failure falls back to the original picked media.
            onUsePhoto(cropped ?: media)
        }
    }

    Column(
        modifier =
            modifier
                .background(MaterialTheme.colorScheme.background)
                .statusBarsPadding()
                .navigationBarsPadding()
                .padding(20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                text = "Crop photo",
                color = MaterialTheme.colorScheme.onSurface,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = "Photos are cropped to a square.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodyMedium,
            )
        }

        Spacer(modifier = Modifier.weight(1f))

        Canvas(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .aspectRatio(1f)
                    .clip(PynapliShape.CardShape)
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .border(1.dp, MaterialTheme.colorScheme.outline, PynapliShape.CardShape)
                    .onSizeChanged { viewportSidePx = it.width }
                    .pointerInput(controller) {
                        detectTransformGestures { _, pan, zoomFactor, _ ->
                            controller.applyGesture(
                                panX = pan.x,
                                panY = pan.y,
                                zoomFactor = zoomFactor,
                                viewportSide = viewportSidePx.toFloat(),
                            )
                            gestureTick += 1
                        }
                    },
        ) {
            // Reading the tick makes the draw block re-run after each gesture; the
            // controller itself is plain state for testability.
            gestureTick
            val rect = controller.cropRect()
            drawImage(
                image = imageBitmap,
                srcOffset = IntOffset(rect.left, rect.top),
                srcSize = IntSize(rect.side, rect.side),
                dstOffset = IntOffset.Zero,
                dstSize = IntSize(size.width.roundToInt(), size.height.roundToInt()),
                filterQuality = FilterQuality.High,
            )
        }

        Text(
            text = "Drag to reposition · Pinch to zoom",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.labelMedium,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 10.dp),
        )

        Spacer(modifier = Modifier.weight(1f))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.End),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(onClick = onCancel, enabled = !processing) {
                Text(text = "Cancel")
            }
            Button(onClick = { processing = true }, enabled = !processing) {
                if (processing) {
                    CircularProgressIndicator(
                        color = MaterialTheme.colorScheme.onPrimary,
                        strokeWidth = 2.dp,
                        modifier = Modifier.size(18.dp),
                    )
                } else {
                    Text(text = "Use photo")
                }
            }
        }
    }
}
