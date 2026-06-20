package com.pynapli.app.media

import androidx.compose.ui.graphics.ImageBitmap
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class MediaImageCodecTest {
    @Test
    fun decodeBoundsLongestEdgeToMaxSide() {
        val encoded = encodeCroppedJpegBytes(ImageBitmap(3000, 2000), CROPPED_PHOTO_JPEG_QUALITY)
        requireNotNull(encoded) { "encoder should produce JPEG bytes for the test bitmap" }

        val decoded = decodePickedImageBitmap(encoded, maxSide = 1024)

        requireNotNull(decoded) { "valid JPEG bytes should decode" }
        assertTrue(
            maxOf(decoded.width, decoded.height) <= 1024,
            "longest edge ${maxOf(decoded.width, decoded.height)} should be bounded to 1024",
        )
        // Aspect ratio (3:2) is preserved within rounding.
        assertTrue(decoded.width > decoded.height)
    }

    @Test
    fun decodeLeavesSmallImagesUntouched() {
        val encoded = encodeCroppedJpegBytes(ImageBitmap(640, 480), CROPPED_PHOTO_JPEG_QUALITY)
        requireNotNull(encoded)

        val decoded = decodePickedImageBitmap(encoded, maxSide = DECODE_MAX_SIDE)

        requireNotNull(decoded)
        assertEquals(640, decoded.width)
        assertEquals(480, decoded.height)
    }

    @Test
    fun decodeReturnsNullForUndecodableBytes() {
        assertNull(decodePickedImageBitmap(byteArrayOf(1, 2, 3, 4), maxSide = DECODE_MAX_SIDE))
    }

    @Test
    fun downscaleIsANoOpWhenAlreadyWithinBound() {
        val source = ImageBitmap(800, 600)
        assertSame(source, downscaleToMaxSide(source, maxSide = 1024))
    }
}
