package com.pynapli.server.media

import com.google.auth.oauth2.GoogleCredentials
import com.pynapli.shared.network.dto.ReportCategory
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/**
 * Production automated moderation: scans a freshly-uploaded image with Google Cloud Vision
 * SafeSearch (`images:annotate` with feature `SAFE_SEARCH_DETECTION`) and maps the returned
 * likelihoods to a [MediaModerationDecision].
 *
 * No new dependency: the call uses the JDK [HttpClient] + kotlinx.serialization, and the OAuth2
 * bearer token is minted from the same [GoogleCredentials] (`google-auth`, already on the classpath
 * via firebase-admin) used for GCS signing. The image is referenced by its `gs://bucket/key` URI, so
 * no bytes leave Pynapli's own infrastructure to reach Vision.
 *
 * Fail-safe by construction: any error (network, auth, quota, parse, non-image asset) returns
 * [MediaModerationDecision.PendingReview], never Approved — a scan we cannot trust holds the asset
 * for a human, matching the [ManualReviewMediaModerationScanner] fallback. Public discovery only
 * surfaces ready+approved assets, so a held asset stays invisible until a moderator acts.
 *
 * Likelihood thresholds (per category) are configurable; defaults reject the two strongest
 * likelihoods and hold the middle one for review.
 *
 * Vision SafeSearch reference:
 * https://cloud.google.com/vision/docs/detecting-safe-search
 */
class CloudVisionMediaModerationScanner(
    private val credentials: GoogleCredentials,
    private val httpClient: HttpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build(),
    private val endpoint: String = "https://vision.googleapis.com/v1/images:annotate",
    private val requestTimeout: Duration = Duration.ofSeconds(10),
    private val policy: SafeSearchPolicy = SafeSearchPolicy(),
) : MediaModerationScanner {
    private val logger = LoggerFactory.getLogger(CloudVisionMediaModerationScanner::class.java)

    // The credentials need the cloud-platform scope to call Vision; scope once, refresh per call.
    private val scopedCredentials: GoogleCredentials =
        if (credentials.createScopedRequired()) {
            credentials.createScoped(listOf("https://www.googleapis.com/auth/cloud-platform"))
        } else {
            credentials
        }

    override suspend fun scan(asset: MediaAssetRecord): MediaModerationDecision {
        // Only image assets are SafeSearch-scannable; anything else stays human-reviewed.
        if (!asset.contentType.startsWith("image/")) {
            return MediaModerationDecision.PendingReview
        }
        if (asset.storageBucket.isBlank() || asset.storageObjectKey.isBlank()) {
            return MediaModerationDecision.PendingReview
        }

        return runCatching {
            val token = freshAccessToken() ?: return MediaModerationDecision.PendingReview
            val gcsUri = "gs://${asset.storageBucket}/${asset.storageObjectKey}"
            val body = JSON.encodeToString(AnnotateRequest.safeSearch(gcsUri))

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
                logger.warn("Vision SafeSearch returned HTTP {} for asset {}; holding for review", response.statusCode(), asset.mediaAssetId)
                return MediaModerationDecision.PendingReview
            }

            val parsed = JSON.decodeFromString<AnnotateResponse>(response.body())
            val annotation =
                parsed.responses.firstOrNull()?.safeSearchAnnotation
                    ?: return MediaModerationDecision.PendingReview
            policy.decide(annotation)
        }.getOrElse { error ->
            // Never auto-approve on failure; the human queue is the safe default.
            logger.warn("Vision SafeSearch failed for asset {}; holding for review", asset.mediaAssetId, error)
            MediaModerationDecision.PendingReview
        }
    }

    private fun freshAccessToken(): String? =
        runCatching {
            scopedCredentials.refreshIfExpired()
            scopedCredentials.accessToken?.tokenValue
        }.getOrNull()

    companion object {
        private val JSON =
            Json {
                ignoreUnknownKeys = true
                encodeDefaults = true
            }
    }
}

/**
 * Maps Vision SafeSearch likelihoods to a moderation decision. A category counts as a "reject" when
 * its likelihood is at or above [rejectAtOrAbove], and "hold for review" at or above [reviewAtOrAbove].
 * Reject wins over review wins over approve. Defaults: reject on LIKELY/VERY_LIKELY, hold on POSSIBLE.
 */
data class SafeSearchPolicy(
    val rejectAtOrAbove: SafeSearchLikelihood = SafeSearchLikelihood.LIKELY,
    val reviewAtOrAbove: SafeSearchLikelihood = SafeSearchLikelihood.POSSIBLE,
    // Categories that participate in the decision. `adult` and `racy` are the lifestyle-relevant ones;
    // `violence` guards against disallowed content. `medical`/`spoof` are ignored by default.
    val evaluatedCategories: Set<SafeSearchCategory> =
        setOf(SafeSearchCategory.ADULT, SafeSearchCategory.RACY, SafeSearchCategory.VIOLENCE),
) {
    fun decide(annotation: SafeSearchAnnotation): MediaModerationDecision {
        val likelihoods =
            buildList {
                if (SafeSearchCategory.ADULT in evaluatedCategories) add(SafeSearchCategory.ADULT to annotation.adult.toLikelihood())
                if (SafeSearchCategory.RACY in evaluatedCategories) add(SafeSearchCategory.RACY to annotation.racy.toLikelihood())
                if (SafeSearchCategory.VIOLENCE in evaluatedCategories) add(SafeSearchCategory.VIOLENCE to annotation.violence.toLikelihood())
                if (SafeSearchCategory.MEDICAL in evaluatedCategories) add(SafeSearchCategory.MEDICAL to annotation.medical.toLikelihood())
                if (SafeSearchCategory.SPOOF in evaluatedCategories) add(SafeSearchCategory.SPOOF to annotation.spoof.toLikelihood())
            }

        val worst = likelihoods.maxByOrNull { it.second.ordinal }
        return when {
            worst == null -> MediaModerationDecision.PendingReview
            worst.second.ordinal >= rejectAtOrAbove.ordinal ->
                MediaModerationDecision.Rejected(
                    reason = "safe_search_${worst.first.name.lowercase()}_${worst.second.name.lowercase()}",
                    severity = worst.first.toModerationSeverity(),
                    reportCategory = worst.first.toReportCategory(),
                )
            worst.second.ordinal >= reviewAtOrAbove.ordinal -> MediaModerationDecision.PendingReview
            else -> MediaModerationDecision.Approved
        }
    }
}

private fun SafeSearchCategory.toModerationSeverity(): MediaModerationSeverity =
    when (this) {
        SafeSearchCategory.VIOLENCE -> MediaModerationSeverity.SEVERE
        else -> MediaModerationSeverity.POLICY
    }

private fun SafeSearchCategory.toReportCategory(): String =
    when (this) {
        SafeSearchCategory.VIOLENCE -> ReportCategory.HARASSMENT_OR_THREATS
        else -> ReportCategory.EXPLICIT_PUBLIC_CONTENT
    }

enum class SafeSearchCategory { ADULT, RACY, VIOLENCE, MEDICAL, SPOOF }

/** Vision likelihood scale, ordered weakest → strongest. */
enum class SafeSearchLikelihood {
    UNKNOWN,
    VERY_UNLIKELY,
    UNLIKELY,
    POSSIBLE,
    LIKELY,
    VERY_LIKELY,
}

private fun String?.toLikelihood(): SafeSearchLikelihood =
    when (this?.uppercase()) {
        "VERY_UNLIKELY" -> SafeSearchLikelihood.VERY_UNLIKELY
        "UNLIKELY" -> SafeSearchLikelihood.UNLIKELY
        "POSSIBLE" -> SafeSearchLikelihood.POSSIBLE
        "LIKELY" -> SafeSearchLikelihood.LIKELY
        "VERY_LIKELY" -> SafeSearchLikelihood.VERY_LIKELY
        else -> SafeSearchLikelihood.UNKNOWN
    }

// ---- Vision API wire models (only the fields we use; ignoreUnknownKeys covers the rest) ----

@Serializable
private data class AnnotateRequest(
    val requests: List<ImageRequest>,
) {
    companion object {
        fun safeSearch(gcsUri: String): AnnotateRequest =
            AnnotateRequest(
                requests =
                    listOf(
                        ImageRequest(
                            image = ImageSource(source = GcsSource(imageUri = gcsUri)),
                            features = listOf(Feature(type = "SAFE_SEARCH_DETECTION")),
                        ),
                    ),
            )
    }
}

@Serializable
private data class ImageRequest(
    val image: ImageSource,
    val features: List<Feature>,
)

@Serializable
private data class ImageSource(
    val source: GcsSource,
)

@Serializable
private data class GcsSource(
    @SerialName("imageUri") val imageUri: String,
)

@Serializable
private data class Feature(
    val type: String,
)

@Serializable
private data class AnnotateResponse(
    val responses: List<AnnotateImageResponse> = emptyList(),
)

@Serializable
private data class AnnotateImageResponse(
    val safeSearchAnnotation: SafeSearchAnnotation? = null,
)

@Serializable
data class SafeSearchAnnotation(
    val adult: String? = null,
    val spoof: String? = null,
    val medical: String? = null,
    val violence: String? = null,
    val racy: String? = null,
)
