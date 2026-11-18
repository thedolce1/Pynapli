package com.pynapli.app.media

import com.pynapli.app.conversation.ConversationStateHolder
import com.pynapli.app.verification.PhotoVerificationStateHolder

/** Where a photo the user picks should go. */
sealed interface MediaPickTarget {
    /** Profile or private album: pauses at the shared square-crop step. */
    data class Album(
        val kind: MediaAlbumKindUi,
    ) : MediaPickTarget

    /** The open conversation: uploaded and sent as a per-recipient consent-tap media message. */
    data object Conversation : MediaPickTarget

    /** A photo verification selfie: sent as-is (no crop, so the pose stays in frame), never to an album. */
    data object VerificationSelfie : MediaPickTarget
}

/**
 * Delivers a picked photo to its [MediaPickTarget]. Each shell owns only the OS picker and reading the
 * bytes; what happens to the photo is the same on every platform, so it's decided here once.
 */
class PickedMediaRouter(
    private val mediaAlbumsStateHolder: MediaAlbumsStateHolder,
    private val conversationStateHolder: ConversationStateHolder,
    private val photoVerificationStateHolder: PhotoVerificationStateHolder? = null,
) {
    /** [media] is null when the picked file couldn't be read. */
    suspend fun deliver(
        target: MediaPickTarget,
        media: PickedMediaForUpload?,
    ) {
        when {
            media == null && target is MediaPickTarget.VerificationSelfie ->
                photoVerificationStateHolder?.showError("Could not read the selected image.")
            media == null -> mediaAlbumsStateHolder.showError("Could not read the selected image.")
            target is MediaPickTarget.Conversation -> conversationStateHolder.sendPhoto(media)
            target is MediaPickTarget.Album -> mediaAlbumsStateHolder.stagePickedMedia(target.kind, media)
            target is MediaPickTarget.VerificationSelfie -> photoVerificationStateHolder?.submitSelfie(media)
        }
    }
}
