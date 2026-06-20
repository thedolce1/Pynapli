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
import kotlin.math.roundToInt

/**
 * Decodes picked image bytes into an upright [ImageBitmap] no larger than [maxSide] on its
 * longest edge, or null when the bytes are not a decodable image. "Upright" matters: the
 * Android actual applies EXIF orientation so the crop preview and the encoded result match
 * what the user sees.
 *
 * [maxSide] bounds peak memory: a modern phone photo is 12MP+, so decoding it at full
 * resolution would allocate a ~48MB ARGB bitmap before the crop ever downscales it — enough
 * to OOM or stall a low-end device. The Android actual decodes sampled (it never allocates the
 * full bitmap); the skia actuals decode then downscale.
 */
internal expect fun decodePickedImageBitmap(
    bytes: ByteArray,
    maxSide: Int,
): ImageBitmap?

/** Encodes a bitmap as JPEG bytes, or null when the platform encoder fails. */
internal expect fun encodeCroppedJpegBytes(
    bitmap: ImageBitmap,
    quality: Int,
): ByteArray?

internal const val CROPPED_PHOTO_JPEG_QUALITY = 90

/** Cropped profile photos are capped at this side so uploads stay lean. */
internal const val CROPPED_PHOTO_MAX_SIDE = 1080

/**
 * Longest-edge cap for the decoded picked image held by the crop screen. Comfortably above
 * [CROPPED_PHOTO_MAX_SIDE] so pinch-zoomed crops stay sharp, while keeping the in-memory bitmap
 * around ~16MB worst case instead of the tens of MB a raw camera capture would cost.
 */
internal const val DECODE_MAX_SIDE = 2048

/**
 * Returns [source] unchanged when it already fits within [maxSide], otherwise a new bitmap
 * scaled so its longest edge is [maxSide] (aspect preserved). Used by the skia actuals, whose
 * decoders do not sample on the way in.
 */
internal fun downscaleToMaxSide(
    source: ImageBitmap,
    maxSide: Int,
): ImageBitmap {
    val longest = maxOf(source.width, source.height)
    if (longest <= maxSide) return source
    val scale = maxSide.toFloat() / longest
    val outputWidth = (source.width * scale).roundToInt().coerceAtLeast(1)
    val outputHeight = (source.height * scale).roundToInt().coerceAtLeast(1)
    val output = ImageBitmap(outputWidth, outputHeight)
    val canvas = Canvas(output)
    CanvasDrawScope().draw(
        density = Density(1f),
        layoutDirection = LayoutDirection.Ltr,
        canvas = canvas,
        size = Size(outputWidth.toFloat(), outputHeight.toFloat()),
    ) {
        drawImage(
            image = source,
            srcOffset = IntOffset.Zero,
            srcSize = IntSize(source.width, source.height),
            dstOffset = IntOffset.Zero,
            dstSize = IntSize(outputWidth, outputHeight),
            filterQuality = FilterQuality.High,
        )
    }
    return output
}

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
