package com.pynapli.shared.network

import com.pynapli.shared.network.dto.StreamAuthFrameDto

/** The one serialized [StreamAuthFrameDto] shape every stream session factory sends. */
internal fun streamAuthFrameJson(token: String): String = PynapliJson.encodeToString(StreamAuthFrameDto.serializer(), StreamAuthFrameDto(token = token))
