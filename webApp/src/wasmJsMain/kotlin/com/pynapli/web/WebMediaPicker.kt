package com.pynapli.web

import com.pynapli.app.conversation.ConversationStateHolder
import com.pynapli.app.media.MediaAlbumKindUi
import com.pynapli.app.media.MediaAlbumsStateHolder
import com.pynapli.app.media.PickedMediaForUpload
import com.pynapli.web.platform.openBrowserImagePicker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

/**
 * Browser file-picker adapter (WEB_PWA_GAMEPLAN.md W3.3/W5.2): the web twin of
 * `MainActivity.handlePickedMedia`. Conversation photos upload straight into the
 * consent-tap media message path; album photos pause at the shared square-crop step,
 * exactly like Android. Pixel bounds are left null — the crop step decodes anyway.
 */
class WebMediaPicker(
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

    @OptIn(ExperimentalEncodingApi::class)
    private fun pick(onPicked: (PickedMediaForUpload) -> Unit) {
        openBrowserImagePicker { base64, contentType ->
            val bytes = runCatching { Base64.decode(base64) }.getOrNull()
            if (bytes == null || bytes.isEmpty()) {
                mediaAlbumsStateHolder.showError("Could not read the selected image.")
                return@openBrowserImagePicker
            }
            onPicked(
                PickedMediaForUpload(
                    contentType = contentType.ifBlank { "image/jpeg" },
                    byteSize = bytes.size.toLong(),
                    pixelWidth = null,
                    pixelHeight = null,
                    bytes = bytes,
                ),
            )
        }
    }
}
