package com.pynapli.app.media

import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Integer source rectangle of a square crop, in image pixels. Guaranteed to lie
 * fully inside the source image.
 */
data class PhotoCropRect(
    val left: Int,
    val top: Int,
    val side: Int,
)

/**
 * Pure pan/zoom state for a square crop over an image, working entirely in image
 * pixels so it can be unit-tested without Compose. The crop square always covers
 * `min(imageWidth, imageHeight) / zoom` pixels and is clamped inside the image, so
 * what the viewport renders (the crop rect scaled to the viewport) is exactly what
 * gets uploaded.
 */
class PhotoSquareCropController(
    val imageWidth: Int,
    val imageHeight: Int,
    private val maxZoom: Float = MAX_ZOOM,
) {
    init {
        require(imageWidth > 0 && imageHeight > 0) { "Image dimensions must be positive." }
    }

    var zoom: Float = 1f
        private set

    var centerX: Float = imageWidth / 2f
        private set

    var centerY: Float = imageHeight / 2f
        private set

    /** Side of the crop square in image pixels at the current zoom. */
    val cropSide: Float
        get() = min(imageWidth, imageHeight) / zoom

    /**
     * Applies one transform-gesture frame. [panX]/[panY] are in viewport pixels;
     * [viewportSide] converts them to image pixels at the current zoom. Dragging the
     * image right moves the crop window left, hence the subtraction.
     */
    fun applyGesture(
        panX: Float,
        panY: Float,
        zoomFactor: Float,
        viewportSide: Float,
    ) {
        if (zoomFactor.isFinite() && zoomFactor > 0f) {
            zoom = (zoom * zoomFactor).coerceIn(1f, maxZoom)
        }
        if (viewportSide > 0f) {
            val imagePixelsPerViewportPixel = cropSide / viewportSide
            centerX -= panX * imagePixelsPerViewportPixel
            centerY -= panY * imagePixelsPerViewportPixel
        }
        clampCenter()
    }

    private fun clampCenter() {
        val half = cropSide / 2f
        centerX = centerX.coerceIn(half, imageWidth - half)
        centerY = centerY.coerceIn(half, imageHeight - half)
    }

    fun cropRect(): PhotoCropRect {
        val side =
            cropSide
                .roundToInt()
                .coerceIn(1, min(imageWidth, imageHeight))
        val left = (centerX - side / 2f).roundToInt().coerceIn(0, imageWidth - side)
        val top = (centerY - side / 2f).roundToInt().coerceIn(0, imageHeight - side)
        return PhotoCropRect(left = left, top = top, side = side)
    }

    companion object {
        const val MAX_ZOOM = 4f
    }
}
