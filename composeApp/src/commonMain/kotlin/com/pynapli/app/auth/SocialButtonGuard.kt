package com.pynapli.app.auth

import com.pynapli.app.design.APPLE_MARK_IS_OFFICIAL_ARTWORK
import com.pynapli.app.design.GOOGLE_MARK_IS_OFFICIAL_ARTWORK
import com.pynapli.shared.auth.SocialProvider

/**
 * Whether each provider's official logo file is in (design/PynapliSocialButtons.kt). A provider
 * without it gets no button anywhere: the providers' trademark rules allow only their own artwork.
 */
internal data class SocialMarkArtwork(
    val googleOfficial: Boolean,
    val appleOfficial: Boolean,
) {
    companion object {
        val Current = SocialMarkArtwork(googleOfficial = GOOGLE_MARK_IS_OFFICIAL_ARTWORK, appleOfficial = APPLE_MARK_IS_OFFICIAL_ARTWORK)
    }
}

/** The providers that may show a button: [providers] minus any whose official artwork is not in. */
internal fun visibleSocialProviders(
    providers: List<SocialProvider>,
    artwork: SocialMarkArtwork = SocialMarkArtwork.Current,
): List<SocialProvider> =
    providers.filter { provider ->
        when (provider) {
            SocialProvider.Google -> artwork.googleOfficial
            SocialProvider.Apple -> artwork.appleOfficial
        }
    }
