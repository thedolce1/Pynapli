package com.pynapli.app.media

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asSkiaBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Image

// Skia does not apply EXIF orientation on decode; iOS camera photos may arrive
// pre-rotated. Revisit with a UIImage-based decode when the iOS shell phase lands.
internal actual fun decodePickedImageBitmap(bytes: ByteArray): ImageBitmap? = runCatching { Image.makeFromEncoded(bytes).toComposeImageBitmap() }.getOrNull()

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
