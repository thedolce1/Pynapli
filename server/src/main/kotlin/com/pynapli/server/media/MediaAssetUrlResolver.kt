package com.pynapli.server.media

import java.util.UUID

interface MediaAssetUrlResolver {
    fun publicContentUrl(mediaAssetId: UUID): String?
}

class LocalMediaAssetUrlResolver(
    private val uploadBaseUrl: String,
) : MediaAssetUrlResolver {
    override fun publicContentUrl(mediaAssetId: UUID): String = "${uploadBaseUrl.trimEnd('/')}/v1/media/uploads/local/$mediaAssetId"
}
