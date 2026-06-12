package com.pynapli.app.media

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.graphics.Matrix
import android.media.ExifInterface
import android.os.Build
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asImageBitmap
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer

internal actual fun decodePickedImageBitmap(bytes: ByteArray): ImageBitmap? =
    runCatching {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            // ImageDecoder applies EXIF orientation itself. Software allocation is
            // required: the cropped square is drawn onto a software ImageBitmap, and
            // hardware bitmaps cannot be read back by that canvas.
            ImageDecoder.decodeBitmap(ImageDecoder.createSource(ByteBuffer.wrap(bytes))) { decoder, _, _ ->
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            }
        } else {
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.uprightByExif(bytes)
        }?.asImageBitmap()
    }.getOrNull()

internal actual fun encodeCroppedJpegBytes(
    bitmap: ImageBitmap,
    quality: Int,
): ByteArray? =
    runCatching {
        val stream = ByteArrayOutputStream()
        val compressed = bitmap.asAndroidBitmap().compress(Bitmap.CompressFormat.JPEG, quality, stream)
        if (compressed) stream.toByteArray() else null
    }.getOrNull()

private fun Bitmap.uprightByExif(bytes: ByteArray): Bitmap {
    // The stream ExifInterface constructor needs API 24; on the API 23 floor the
    // photo is decoded as stored (worst case: a pre-rotated camera photo).
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return this
    val orientation =
        runCatching {
            ExifInterface(ByteArrayInputStream(bytes))
                .getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
        }.getOrDefault(ExifInterface.ORIENTATION_NORMAL)
    val degrees =
        when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> 90f
            ExifInterface.ORIENTATION_ROTATE_180 -> 180f
            ExifInterface.ORIENTATION_ROTATE_270 -> 270f
            else -> return this
        }
    val matrix = Matrix().apply { postRotate(degrees) }
    return Bitmap.createBitmap(this, 0, 0, width, height, matrix, true)
}
