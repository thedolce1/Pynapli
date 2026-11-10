package com.pynapli.desktop

import com.pynapli.app.media.MediaAlbumKindUi
import com.pynapli.app.media.MediaPickTarget
import com.pynapli.app.media.PickedMediaForUpload
import com.pynapli.app.media.PickedMediaRouter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import javax.swing.JFileChooser
import javax.swing.filechooser.FileNameExtensionFilter

/**
 * Swing file-chooser adapter — the desktop twin of `MainActivity.handlePickedMedia` and
 * [com.pynapli.web.WebMediaPicker]. Conversation photos upload straight into the consent-tap media
 * message path; album photos pause at the shared square-crop step, exactly like Android and web.
 *
 * Pixel bounds are left null for the same reason web does: the crop step decodes the bytes anyway,
 * so measuring here would only duplicate that work.
 */
class DesktopMediaPicker(
    private val router: PickedMediaRouter,
    private val scope: CoroutineScope,
) {
    fun pickConversationPhoto() {
        pick(MediaPickTarget.Conversation)
    }

    fun pickForAlbum(kind: MediaAlbumKindUi) {
        pick(MediaPickTarget.Album(kind))
    }

    private fun pick(target: MediaPickTarget) {
        scope.launch {
            // Swing dialogs and file reads both block; keeping them off the Compose UI dispatcher
            // is what stops the window freezing while the chooser is open.
            val file = withContext(Dispatchers.IO) { chooseImageFile() } ?: return@launch
            val bytes = withContext(Dispatchers.IO) { runCatching { file.readBytes() }.getOrNull() }
            val media =
                bytes?.takeIf { it.isNotEmpty() }?.let {
                    PickedMediaForUpload(
                        contentType = contentTypeFor(file),
                        byteSize = it.size.toLong(),
                        pixelWidth = null,
                        pixelHeight = null,
                        bytes = it,
                    )
                }
            // null (unreadable) shows the shared error; otherwise the router sends or stages it.
            router.deliver(target, media)
        }
    }

    private fun chooseImageFile(): File? {
        val chooser =
            JFileChooser().apply {
                dialogTitle = "Choose a photo"
                isMultiSelectionEnabled = false
                fileFilter = FileNameExtensionFilter("Images", *SUPPORTED_EXTENSIONS)
            }
        return if (chooser.showOpenDialog(null) == JFileChooser.APPROVE_OPTION) chooser.selectedFile else null
    }

    private fun contentTypeFor(file: File): String =
        when (file.extension.lowercase()) {
            "png" -> "image/png"
            "webp" -> "image/webp"
            else -> "image/jpeg"
        }

    private companion object {
        private val SUPPORTED_EXTENSIONS = arrayOf("jpg", "jpeg", "png", "webp")
    }
}
