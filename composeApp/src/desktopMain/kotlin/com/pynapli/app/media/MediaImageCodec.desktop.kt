package com.pynapli.app.media

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asSkiaBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Image

internal actual fun decodePickedImageBitmap(
    bytes: ByteArray,
    maxSide: Int,
): ImageBitmap? = runCatching { downscaleToMaxSide(Image.makeFromEncoded(bytes).toComposeImageBitmap(), maxSide) }.getOrNull()

internal actual fun encodeCroppedJpegBytes(
    bitmap: ImageBitmap,
    quality: Int,
): ByteArray? =
    runCatching {
        Image
            .makeFromBitmap(bitmap.asSkiaBitmap())
            .encodeToData(EncodedImageFormat.JPEG, quality)
            ?.bytes
    }.getOrNull()
