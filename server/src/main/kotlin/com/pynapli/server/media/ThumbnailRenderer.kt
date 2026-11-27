package com.pynapli.server.media

import java.awt.Color
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import javax.imageio.IIOImage
import javax.imageio.ImageIO
import javax.imageio.ImageReadParam
import javax.imageio.ImageWriteParam
import javax.imageio.stream.MemoryCacheImageInputStream
import javax.imageio.stream.MemoryCacheImageOutputStream
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Makes the Grid thumbnail from a stored original with the JDK's ImageIO only (no new dependency):
 *
 * - Reads the dimensions first and refuses more than [maxSourcePixels] (a decompression bomb never
 *   gets decoded), then decodes with source subsampling so a big original costs little memory.
 * - Scales so the longest side is at most [maxEdgePx] (never upscales), in halving steps with
 *   bilinear filtering for a clean downscale.
 * - Applies the EXIF orientation (ImageIO ignores it) so the stripped thumbnail is upright.
 * - Writes a baseline JPEG at [quality] from an `IIOImage` with NO metadata: the output carries only
 *   the JFIF header ImageIO writes by default, so EXIF (GPS, camera, time) never survives.
 * - Uses in-memory ImageIO streams only, so no photo bytes touch a temp file.
 *
 * Throws [ThumbnailRenderException] when the input can't be made into a thumbnail (anything but JPEG
 * or PNG, e.g. WebP, HEIC, GIF, BMP, TIFF; a CMYK JPEG; more than 24 MP; a corrupt file). That is
 * terminal for the asset: surfaces keep using the original.
 */
class ThumbnailRenderer(
    private val maxEdgePx: Int = MediaThumbnails.MAX_EDGE_PX,
    private val quality: Float = MediaThumbnails.JPEG_QUALITY,
    private val maxSourcePixels: Long = DEFAULT_MAX_SOURCE_PIXELS,
) {
    init {
        // Process-wide, and what we want everywhere: no disk cache for image streams.
        ImageIO.setUseCache(false)
    }

    fun render(original: ByteArray): RenderedThumbnail {
        if (original.isEmpty()) throw ThumbnailRenderException("EMPTY")
        return try {
            val orientation = ExifOrientation.read(original)
            val decoded = decodeSubsampled(original)
            val scaled = scaleToFit(decoded, maxEdgePx)
            val upright = ExifOrientation.apply(scaled, orientation)
            val bytes = encodeJpeg(upright)
            RenderedThumbnail(bytes = bytes, width = upright.width, height = upright.height)
        } catch (error: ThumbnailRenderException) {
            throw error
        } catch (error: Exception) {
            // A corrupt header (IIOException from getWidth, say). Type only: messages can echo input.
            throw ThumbnailRenderException("DECODE_FAILED:${error.javaClass.simpleName}")
        }
    }

    private fun decodeSubsampled(original: ByteArray): BufferedImage {
        MemoryCacheImageInputStream(ByteArrayInputStream(original)).use { input ->
            // JPEG and PNG only, chosen by the file's own signature and read with that format's reader:
            // never whichever reader the bytes happen to select (GIF, BMP, TIFF, WBMP and any plugin on
            // the classpath stay out, along with their decoders' attack surface).
            val format = sourceFormat(original) ?: throw ThumbnailRenderException("UNSUPPORTED_FORMAT")
            val reader =
                ImageIO.getImageReadersByFormatName(format).asSequence().firstOrNull()
                    ?: throw ThumbnailRenderException("UNSUPPORTED_FORMAT")
            try {
                // ignoreMetadata = true: we read orientation ourselves; skipping metadata is faster.
                reader.setInput(input, true, true)
                val width = reader.getWidth(0)
                val height = reader.getHeight(0)
                if (width <= 0 || height <= 0) throw ThumbnailRenderException("INVALID_DIMENSIONS")
                if (width.toLong() * height.toLong() > maxSourcePixels) throw ThumbnailRenderException("TOO_MANY_PIXELS")
                val param: ImageReadParam = reader.defaultReadParam
                // Subsample down to no less than twice the target, then filter the rest of the way.
                val factor = (max(width, height) / (maxEdgePx * 2)).coerceAtLeast(1)
                if (factor > 1) param.setSourceSubsampling(factor, factor, 0, 0)
                return try {
                    reader.read(0, param)
                } catch (error: ThumbnailRenderException) {
                    throw error
                } catch (error: Exception) {
                    // Message text can echo stream details; keep only the type.
                    throw ThumbnailRenderException("DECODE_FAILED:${error.javaClass.simpleName}")
                }
            } finally {
                reader.dispose()
            }
        }
    }

    private fun encodeJpeg(image: BufferedImage): ByteArray {
        val writer =
            ImageIO.getImageWritersByFormatName("jpeg").asSequence().firstOrNull()
                ?: throw ThumbnailRenderException("NO_JPEG_WRITER")
        val output = ByteArrayOutputStream()
        try {
            MemoryCacheImageOutputStream(output).use { stream ->
                writer.output = stream
                val param =
                    writer.defaultWriteParam.apply {
                        compressionMode = ImageWriteParam.MODE_EXPLICIT
                        compressionQuality = quality
                    }
                // Null metadata: nothing from the original (EXIF/XMP/ICC) is carried over.
                writer.write(null, IIOImage(image, null, null), param)
            }
        } finally {
            writer.dispose()
        }
        return output.toByteArray()
    }

    companion object {
        // 24 MP: above any phone upload the app sends (~1080 px) and most camera originals, and low
        // enough that a progressive JPEG's full-size coefficient buffers (which subsampling does not
        // shrink) stay bounded. Checked from the header before any pixel is decoded.
        const val DEFAULT_MAX_SOURCE_PIXELS: Long = 24_000_000L

        /** "jpeg" or "png" from the leading signature bytes, else null (refused). */
        internal fun sourceFormat(bytes: ByteArray): String? {
            fun at(index: Int): Int = if (index < bytes.size) bytes[index].toInt() and 0xFF else -1
            if (at(0) == 0xFF && at(1) == 0xD8 && at(2) == 0xFF) return "jpeg"
            val png = intArrayOf(0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)
            if (png.indices.all { at(it) == png[it] }) return "png"
            return null
        }

        /** Longest side <= [maxEdge], aspect kept, never upscaled; opaque RGB on white. */
        internal fun scaleToFit(
            source: BufferedImage,
            maxEdge: Int,
        ): BufferedImage {
            val longest = max(source.width, source.height)
            val scale = if (longest > maxEdge) maxEdge.toDouble() / longest else 1.0
            val targetWidth = (source.width * scale).roundToInt().coerceAtLeast(1)
            val targetHeight = (source.height * scale).roundToInt().coerceAtLeast(1)
            var current = source.toOpaqueRgb()
            // Halve while more than 2x away: single-step bilinear from far above aliases badly.
            while (current.width / 2 >= targetWidth && current.height / 2 >= targetHeight) {
                current = current.resized(current.width / 2, current.height / 2)
            }
            if (current.width != targetWidth || current.height != targetHeight) {
                current = current.resized(targetWidth, targetHeight)
            }
            return current
        }

        private fun BufferedImage.toOpaqueRgb(): BufferedImage {
            if (type == BufferedImage.TYPE_INT_RGB) return this
            val rgb = BufferedImage(width, height, BufferedImage.TYPE_INT_RGB)
            val graphics = rgb.createGraphics()
            try {
                // A transparent PNG becomes white-backed, never black.
                graphics.color = Color.WHITE
                graphics.fillRect(0, 0, width, height)
                graphics.drawImage(this, 0, 0, null)
            } finally {
                graphics.dispose()
            }
            return rgb
        }

        private fun BufferedImage.resized(
            newWidth: Int,
            newHeight: Int,
        ): BufferedImage {
            val target = BufferedImage(newWidth, newHeight, BufferedImage.TYPE_INT_RGB)
            val graphics = target.createGraphics()
            try {
                graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
                graphics.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY)
                graphics.drawImage(this, 0, 0, newWidth, newHeight, null)
            } finally {
                graphics.dispose()
            }
            return target
        }
    }
}

class RenderedThumbnail(
    val bytes: ByteArray,
    val width: Int,
    val height: Int,
)

class ThumbnailRenderException(
    val reason: String,
) : RuntimeException(reason)

/**
 * EXIF orientation (TIFF tag 0x0112) from a JPEG's APP1 "Exif" segment, and the pixel transform that
 * makes the image upright. A small parser on purpose: ImageIO's JPEG metadata tree does not expose
 * EXIF tags, and no image library is on the server classpath. Anything unexpected reads as 1
 * (already upright), which is what a viewer that ignores EXIF would show anyway.
 */
internal object ExifOrientation {
    fun read(bytes: ByteArray): Int =
        try {
            readOrNull(bytes) ?: 1
        } catch (_: IndexOutOfBoundsException) {
            1
        }

    private fun readOrNull(bytes: ByteArray): Int? {
        // JPEG only: SOI marker.
        if (bytes.size < 4 || bytes.u8(0) != 0xFF || bytes.u8(1) != 0xD8) return null
        var offset = 2
        while (offset + 4 <= bytes.size) {
            if (bytes.u8(offset) != 0xFF) return null
            val marker = bytes.u8(offset + 1)
            // Start of scan or end of image: no EXIF before the pixels.
            if (marker == 0xDA || marker == 0xD9) return null
            val length = (bytes.u8(offset + 2) shl 8) or bytes.u8(offset + 3)
            if (length < 2) return null
            val segmentStart = offset + 4
            if (marker == 0xE1 && length >= 8 && bytes.isExifHeader(segmentStart)) {
                return tiffOrientation(bytes, tiffStart = segmentStart + 6, tiffEnd = offset + 2 + length)
            }
            offset += 2 + length
        }
        return null
    }

    private fun ByteArray.isExifHeader(at: Int): Boolean =
        u8(at) == 'E'.code && u8(at + 1) == 'x'.code && u8(at + 2) == 'i'.code && u8(at + 3) == 'f'.code &&
            u8(at + 4) == 0 && u8(at + 5) == 0

    private fun tiffOrientation(
        bytes: ByteArray,
        tiffStart: Int,
        tiffEnd: Int,
    ): Int? {
        val littleEndian =
            when {
                bytes.u8(tiffStart) == 'I'.code && bytes.u8(tiffStart + 1) == 'I'.code -> true
                bytes.u8(tiffStart) == 'M'.code && bytes.u8(tiffStart + 1) == 'M'.code -> false
                else -> return null
            }

        fun u16(at: Int): Int = if (littleEndian) bytes.u8(at) or (bytes.u8(at + 1) shl 8) else (bytes.u8(at) shl 8) or bytes.u8(at + 1)

        fun u32(at: Int): Long =
            if (littleEndian) {
                (u16(at).toLong()) or (u16(at + 2).toLong() shl 16)
            } else {
                (u16(at).toLong() shl 16) or u16(at + 2).toLong()
            }
        if (u16(tiffStart + 2) != 42) return null
        val ifd0 = tiffStart + u32(tiffStart + 4).toInt()
        if (ifd0 + 2 > tiffEnd) return null
        val entries = u16(ifd0)
        for (index in 0 until entries) {
            val entry = ifd0 + 2 + index * 12
            if (entry + 12 > tiffEnd) return null
            if (u16(entry) == ORIENTATION_TAG) {
                // Type SHORT (3), count 1: the value sits in the first two bytes of the value field.
                if (u16(entry + 2) != 3) return null
                return u16(entry + 8).takeIf { it in 1..8 }
            }
        }
        return null
    }

    /** Returns [source] as it should be displayed for EXIF [orientation] (1..8; others = as is). */
    fun apply(
        source: BufferedImage,
        orientation: Int,
    ): BufferedImage {
        if (orientation !in 2..8) return source
        val w = source.width
        val h = source.height
        val swaps = orientation >= 5
        val out = BufferedImage(if (swaps) h else w, if (swaps) w else h, BufferedImage.TYPE_INT_RGB)
        for (y in 0 until out.height) {
            for (x in 0 until out.width) {
                val (sx, sy) =
                    when (orientation) {
                        2 -> (w - 1 - x) to y // mirror horizontal
                        3 -> (w - 1 - x) to (h - 1 - y) // rotate 180
                        4 -> x to (h - 1 - y) // mirror vertical
                        5 -> y to x // transpose
                        6 -> y to (h - 1 - x) // rotate 90 clockwise
                        7 -> (w - 1 - y) to (h - 1 - x) // transverse
                        else -> (w - 1 - y) to x // 8: rotate 90 counter-clockwise
                    }
                out.setRGB(x, y, source.getRGB(sx, sy))
            }
        }
        return out
    }

    private const val ORIENTATION_TAG = 0x0112

    private fun ByteArray.u8(at: Int): Int = this[at].toInt() and 0xFF
}
