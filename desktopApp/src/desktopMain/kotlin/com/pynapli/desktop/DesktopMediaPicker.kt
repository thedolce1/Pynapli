package com.pynapli.desktop

import com.pynapli.app.conversation.ConversationStateHolder
import com.pynapli.app.media.MediaAlbumKindUi
import com.pynapli.app.media.MediaAlbumsStateHolder
import com.pynapli.app.media.PickedMediaForUpload
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
    private val conversationStateHolder: ConversationStateHolder,
    private val mediaAlbumsStateHolder: MediaAlbumsStateHolder,
    private val scope: CoroutineScope,
) {
    fun pickConversationPhoto() {
        pick { media -> scope.launch { conversationStateHolder.sendPhoto(media) } }
    }

    fun pickForAlbum(kind: MediaAlbumKindUi) {
        pick { media -> scope.launch { mediaAlbumsStateHolder.stagePickedMedia(kind, media) } }
    }

    private fun pick(onPicked: (PickedMediaForUpload) -> Unit) {
        scope.launch {
            // Swing dialogs and file reads both block; keeping them off the Compose UI dispatcher
            // is what stops the window freezing while the chooser is open.
            val file = withContext(Dispatchers.IO) { chooseImageFile() } ?: return@launch
            val bytes = withContext(Dispatchers.IO) { runCatching { file.readBytes() }.getOrNull() }
            if (bytes == null || bytes.isEmpty()) {
                mediaAlbumsStateHolder.showError("Could not read the selected image.")
                return@launch
            }
            onPicked(
                PickedMediaForUpload(
                    contentType = contentTypeFor(file),
                    byteSize = bytes.size.toLong(),
                    pixelWidth = null,
                    pixelHeight = null,
                    bytes = bytes,
                ),
            )
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
