package com.pynapli.server.media

import com.google.auth.oauth2.GoogleCredentials
import com.pynapli.server.verification.SelfieFaceDetector
import com.pynapli.server.verification.StoredSelfie
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.slf4j.LoggerFactory
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/**
 * Counts faces in a verification selfie with Google Cloud Vision `FACE_DETECTION`
 * (https://cloud.google.com/vision/docs/detecting-faces), reading the object by its `gs://` URI with the
 * same credentials as [CloudVisionMediaModerationScanner]. Detection only: Vision has no face
 * comparison, so nothing here matches the selfie against profile photos and a reviewer always decides.
 *
 * Returns null whenever the check can't be trusted (not in GCS, auth/network/quota/parse failure);
 * the submission then waits for a human. Only the face count is read; landmarks and the rest of the
 * response are ignored and never stored or logged.
 */
class CloudVisionSelfieFaceDetector(
    credentials: GoogleCredentials,
    private val httpClient: HttpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build(),
    private val endpoint: String = "https://vision.googleapis.com/v1/images:annotate",
    private val requestTimeout: Duration = Duration.ofSeconds(10),
) : SelfieFaceDetector {
    private val logger = LoggerFactory.getLogger(CloudVisionSelfieFaceDetector::class.java)

    private val scopedCredentials: GoogleCredentials =
        if (credentials.createScopedRequired()) {
            credentials.createScoped(listOf("https://www.googleapis.com/auth/cloud-platform"))
        } else {
            credentials
        }

    override suspend fun countFaces(selfie: StoredSelfie): Int? =
        withContext(Dispatchers.IO) {
            if (!selfie.storageProvider.equals("gcs", ignoreCase = true) || !selfie.contentType.startsWith("image/")) {
                return@withContext null
            }
            runCatching {
                scopedCredentials.refreshIfExpired()
                val token = scopedCredentials.accessToken?.tokenValue ?: return@runCatching null
                val body =
                    """{"requests":[{"image":{"source":{"imageUri":"gs://${selfie.storageBucket}/${selfie.storageObjectKey}"}},""" +
                        """"features":[{"type":"FACE_DETECTION","maxResults":$MAX_FACES}]}]}"""
                val request =
                    HttpRequest
                        .newBuilder()
                        .uri(URI.create(endpoint))
                        .timeout(requestTimeout)
                        .header("Authorization", "Bearer $token")
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(body))
                        .build()
                val response = httpClient.send(request, HttpResponse.BodyHandlers.ofString())
                if (response.statusCode() !in 200..299) {
                    logger.warn("Vision face detection returned HTTP {} for asset {}", response.statusCode(), selfie.mediaAssetId)
                    return@runCatching null
                }
                val parsed = JSON.decodeFromString<FaceAnnotateResponse>(response.body())
                val first = parsed.responses.firstOrNull() ?: return@runCatching null
                // A per-image error (e.g. unreadable object) means the count can't be trusted.
                if (first.error != null) null else first.faceAnnotations.size
            }.getOrElse { error ->
                logger.warn("Vision face detection failed for asset {}: {}", selfie.mediaAssetId, error.javaClass.simpleName)
                null
            }
        }

    private companion object {
        const val MAX_FACES = 5
        val JSON = Json { ignoreUnknownKeys = true }
    }
}

@Serializable
private data class FaceAnnotateResponse(
    val responses: List<FaceAnnotateImageResponse> = emptyList(),
)

@Serializable
private data class FaceAnnotateImageResponse(
    val faceAnnotations: List<JsonObject> = emptyList(),
    val error: JsonObject? = null,
)
