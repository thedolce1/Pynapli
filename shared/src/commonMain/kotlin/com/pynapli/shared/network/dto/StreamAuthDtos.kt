package com.pynapli.shared.network.dto

import kotlinx.serialization.Serializable

const val STREAM_AUTH_FRAME_TYPE = "auth"

/**
 * First-frame authentication for realtime WebSocket streams. Native clients send an
 * Authorization header; browsers cannot set WebSocket headers, so the web client sends
 * this single frame immediately after connecting instead. Never a URL parameter —
 * query-string tokens would be captured by access logs.
 */
@Serializable
data class StreamAuthFrameDto(
    val type: String = STREAM_AUTH_FRAME_TYPE,
    val token: String,
)
