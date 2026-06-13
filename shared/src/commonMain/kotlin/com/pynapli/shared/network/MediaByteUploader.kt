package com.pynapli.shared.network

import com.pynapli.shared.core.result.AppError
import com.pynapli.shared.core.result.AppResult
import com.pynapli.shared.network.dto.MediaUploadSlotDto
import io.ktor.client.HttpClient
import io.ktor.client.request.headers
import io.ktor.client.request.put
import io.ktor.client.request.setBody

interface MediaByteUploader {
    suspend fun upload(
        slot: MediaUploadSlotDto,
        bytes: ByteArray,
    ): AppResult<Unit>

    /** Lower-level overload for slots that aren't album uploads (e.g. chat photo media). */
    suspend fun upload(
        uploadUrl: String,
        uploadHeaders: Map<String, String>,
        bytes: ByteArray,
    ): AppResult<Unit>
}

class KtorMediaByteUploader(
    private val httpClient: HttpClient = createPynapliHttpClient(),
) : MediaByteUploader {
    override suspend fun upload(
        slot: MediaUploadSlotDto,
        bytes: ByteArray,
    ): AppResult<Unit> = upload(slot.uploadUrl, slot.uploadHeaders, bytes)

    override suspend fun upload(
        uploadUrl: String,
        uploadHeaders: Map<String, String>,
        bytes: ByteArray,
    ): AppResult<Unit> =
        runCatching {
            val response =
                httpClient.put(uploadUrl) {
                    headers {
                        uploadHeaders.forEach { (name, value) ->
                            append(name, value)
                        }
                    }
                    setBody(bytes)
                }

            if (response.status.value in 200..299) {
                AppResult.Success(Unit)
            } else {
                AppResult.Failure(
                    AppError(
                        code = "MEDIA_UPLOAD_FAILED",
                        message = "Media upload failed with HTTP ${response.status.value}.",
                    ),
                )
            }
        }.getOrElse { error ->
            AppResult.Failure(
                AppError(
                    code = "MEDIA_UPLOAD_FAILED",
                    message = error.message ?: "Media upload failed.",
                ),
            )
        }
}
