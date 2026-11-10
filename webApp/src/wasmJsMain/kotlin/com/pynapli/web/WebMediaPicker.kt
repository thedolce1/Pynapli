package com.pynapli.web

import com.pynapli.app.media.MediaAlbumKindUi
import com.pynapli.app.media.MediaPickTarget
import com.pynapli.app.media.PickedMediaForUpload
import com.pynapli.app.media.PickedMediaRouter
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
    private val router: PickedMediaRouter,
    private val scope: CoroutineScope,
) {
    fun pickConversationPhoto() {
        pick(MediaPickTarget.Conversation)
    }

    fun pickForAlbum(kind: MediaAlbumKindUi) {
        pick(MediaPickTarget.Album(kind))
    }

    @OptIn(ExperimentalEncodingApi::class)
    private fun pick(target: MediaPickTarget) {
        openBrowserImagePicker { base64, contentType ->
            val bytes = runCatching { Base64.decode(base64) }.getOrNull()
            val media =
                bytes?.takeIf { it.isNotEmpty() }?.let {
                    PickedMediaForUpload(
                        contentType = contentType.ifBlank { "image/jpeg" },
                        byteSize = it.size.toLong(),
                        pixelWidth = null,
                        pixelHeight = null,
                        bytes = it,
                    )
                }
            // null (unreadable) shows the shared error; otherwise the router sends or stages it.
            scope.launch { router.deliver(target, media) }
        }
    }
}
