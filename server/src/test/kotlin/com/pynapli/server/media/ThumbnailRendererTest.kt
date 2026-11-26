package com.pynapli.server.media

import java.awt.Color
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ThumbnailRendererTest {
    private val renderer = ThumbnailRenderer()

    @Test
    fun aPortraitOriginalShrinksToTheLongestEdgeKeepingItsAspect() {
        val thumbnail = renderer.render(jpeg(width = 1080, height = 1440))

        assertEquals(360, thumbnail.width)
        assertEquals(480, thumbnail.height)
        val decoded = ImageIO.read(ByteArrayInputStream(thumbnail.bytes))
        assertEquals(360, decoded.width)
        assertEquals(480, decoded.height)
    }

    @Test
    fun aSmallOriginalIsNeverUpscaled() {
        val thumbnail = renderer.render(png(width = 200, height = 100))

        assertEquals(200, thumbnail.width)
        assertEquals(100, thumbnail.height)
    }

    @Test
    fun theOutputIsAJpegWithNoExifCarriedOver() {
        val withExif = withExifOrientation(jpeg(width = 800, height = 600), orientation = 1)
        assertTrue(withExif.containsExifSegment(), "test setup: the input carries EXIF")

        val thumbnail = renderer.render(withExif)

        assertEquals(0xFF, thumbnail.bytes[0].toInt() and 0xFF)
        assertEquals(0xD8, thumbnail.bytes[1].toInt() and 0xFF)
        assertFalse(thumbnail.bytes.containsExifSegment(), "EXIF (GPS, camera, time) must be stripped")
    }

    @Test
    fun exifOrientationSixIsAppliedSoTheThumbnailIsUpright() {
        // A phone stores a portrait shot as landscape pixels plus "rotate 90 clockwise".
        val stored = withExifOrientation(jpeg(width = 1440, height = 1080, leftHalf = Color.RED), orientation = 6)

        val thumbnail = renderer.render(stored)

        assertEquals(360, thumbnail.width)
        assertEquals(480, thumbnail.height)
        // The stored image's left half (red) ends up on top after a clockwise turn.
        val decoded = ImageIO.read(ByteArrayInputStream(thumbnail.bytes))
        val top = Color(decoded.getRGB(180, 40))
        val bottom = Color(decoded.getRGB(180, 440))
        assertTrue(top.red > 180 && top.blue < 90, "top should be red, was $top")
        assertTrue(bottom.blue > 180 && bottom.red < 90, "bottom should be blue, was $bottom")
    }

    @Test
    fun orientationReadsAsUprightWhenThereIsNoExif() {
        assertEquals(1, ExifOrientation.read(jpeg(width = 10, height = 10)))
        assertEquals(1, ExifOrientation.read(png(width = 10, height = 10)))
        assertEquals(1, ExifOrientation.read(byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0x00)))
    }

    @Test
    fun tooManyPixelsIsRefusedBeforeDecoding() {
        val small = ThumbnailRenderer(maxSourcePixels = 1_000)

        val error = assertFailsWith<ThumbnailRenderException> { small.render(jpeg(width = 100, height = 100)) }

        assertEquals("TOO_MANY_PIXELS", error.reason)
    }

    @Test
    fun bytesNoReaderUnderstandsAreRefused() {
        val error = assertFailsWith<ThumbnailRenderException> { renderer.render("not an image".toByteArray()) }

        assertEquals("UNSUPPORTED_FORMAT", error.reason)
    }

    private fun jpeg(
        width: Int,
        height: Int,
        leftHalf: Color = Color.BLUE,
    ): ByteArray {
        val image = BufferedImage(width, height, BufferedImage.TYPE_INT_RGB)
        val graphics = image.createGraphics()
        graphics.color = Color.BLUE
        graphics.fillRect(0, 0, width, height)
        graphics.color = leftHalf
        graphics.fillRect(0, 0, width / 2, height)
        graphics.dispose()
        return ByteArrayOutputStream().also { ImageIO.write(image, "jpeg", it) }.toByteArray()
    }

    private fun png(
        width: Int,
        height: Int,
    ): ByteArray {
        val image = BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB)
        return ByteArrayOutputStream().also { ImageIO.write(image, "png", it) }.toByteArray()
    }

    /** Splices a minimal big-endian EXIF APP1 (IFD0 with one Orientation entry) right after SOI. */
    private fun withExifOrientation(
        jpeg: ByteArray,
        orientation: Int,
    ): ByteArray {
        val tiff =
            byteArrayOf(
                'M'.code.toByte(), 'M'.code.toByte(), 0, 42, // big-endian TIFF header
                0, 0, 0, 8, // IFD0 at offset 8
                0, 1, // one entry
                0x01, 0x12, // tag 0x0112 Orientation
                0, 3, // type SHORT
                0, 0, 0, 1, // count 1
                0, orientation.toByte(), 0, 0, // value
                0, 0, 0, 0, // no next IFD
            )
        val payload = "Exif".toByteArray() + byteArrayOf(0, 0) + tiff
        val length = payload.size + 2
        val segment = byteArrayOf(0xFF.toByte(), 0xE1.toByte(), (length shr 8).toByte(), length.toByte()) + payload
        return jpeg.copyOfRange(0, 2) + segment + jpeg.copyOfRange(2, jpeg.size)
    }

    private fun ByteArray.containsExifSegment(): Boolean {
        val marker = "Exif".toByteArray()
        return (0..size - marker.size).any { start -> marker.indices.all { this[start + it] == marker[it] } }
    }
}
