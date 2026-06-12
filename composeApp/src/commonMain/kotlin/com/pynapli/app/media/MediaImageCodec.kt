package com.pynapli.app.media

import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import kotlin.math.min

/**
 * Decodes picked image bytes into an upright [ImageBitmap], or null when the bytes
 * are not a decodable image. "Upright" matters: the Android actual applies EXIF
 * orientation so the crop preview and the encoded result match what the user sees.
 */
internal expect fun decodePickedImageBitmap(bytes: ByteArray): ImageBitmap?

/** Encodes a bitmap as JPEG bytes, or null when the platform encoder fails. */
internal expect fun encodeCroppedJpegBytes(
    bitmap: ImageBitmap,
    quality: Int,
): ByteArray?

internal const val CROPPED_PHOTO_JPEG_QUALITY = 90

/** Cropped profile photos are capped at this side so uploads stay lean. */
internal const val CROPPED_PHOTO_MAX_SIDE = 1080

/**
 * Renders [rect] of [source] into a new square bitmap, downscaling to
 * [CROPPED_PHOTO_MAX_SIDE] when the crop region is larger.
 */
internal fun cropToSquareBitmap(
    source: ImageBitmap,
    rect: PhotoCropRect,
): ImageBitmap {
    val outputSide = min(rect.side, CROPPED_PHOTO_MAX_SIDE)
    val output = ImageBitmap(outputSide, outputSide)
    val canvas = Canvas(output)
    CanvasDrawScope().draw(
        density = Density(1f),
        layoutDirection = LayoutDirection.Ltr,
        canvas = canvas,
        size = Size(outputSide.toFloat(), outputSide.toFloat()),
    ) {
        drawImage(
            image = source,
            srcOffset = IntOffset(rect.left, rect.top),
            srcSize = IntSize(rect.side, rect.side),
            dstOffset = IntOffset.Zero,
            dstSize = IntSize(outputSide, outputSide),
            filterQuality = FilterQuality.High,
        )
    }
    return output
}
