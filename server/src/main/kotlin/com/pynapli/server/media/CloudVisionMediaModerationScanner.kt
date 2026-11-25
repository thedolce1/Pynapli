package com.pynapli.server.media

import com.google.auth.oauth2.GoogleCredentials
import com.pynapli.shared.network.dto.ReportCategory
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withTimeout
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
 * Production automated pre-screening: scans a freshly-uploaded image with Google Cloud Vision
 * SafeSearch (`images:annotate` with feature `SAFE_SEARCH_DETECTION`) and maps the returned
 * likelihoods to a [MediaModerationDecision] through [SafeSearchPolicy].
 *
 * No new dependency: [HttpSafeSearchClient] uses the JDK [HttpClient] + kotlinx.serialization, and
 * the OAuth2 bearer token is minted from the same [GoogleCredentials] (`google-auth`, already on the
 * classpath via firebase-admin) used for GCS signing. The image is referenced by its
 * `gs://bucket/key` URI, so Vision reads the object straight from Pynapli's bucket.
 *
 * Fail closed by construction: any error (network, auth, quota, parse, a per-image `error` in the
 * response, a missing annotation, a non-image asset) or a scan slower than [scanTimeout] returns
 * [MediaModerationDecision.PendingReview], never Approved. A profile photo then waits in the human
 * queue (`GET /v1/admin/media/pending`); public discovery only surfaces ready+approved assets.
 *
 * Vision SafeSearch reference: https://cloud.google.com/vision/docs/detecting-safe-search
 */
class CloudVisionMediaModerationScanner(
    private val client: SafeSearchClient,
    private val policy: SafeSearchPolicy = SafeSearchPolicy(),
    // Whole-scan budget (token refresh + HTTP). The upload-complete request waits on it, so keep it
    // short; a slow Vision answer only means the photo waits for a person instead.
    private val scanTimeout: Duration = DEFAULT_SCAN_TIMEOUT,
) : MediaModerationScanner {
    constructor(
        credentials: GoogleCredentials,
        endpoint: String = "https://vision.googleapis.com/v1/images:annotate",
        policy: SafeSearchPolicy = SafeSearchPolicy(),
    ) : this(
        client = HttpSafeSearchClient(credentials = credentials, endpoint = endpoint),
        policy = policy,
    )

    private val logger = LoggerFactory.getLogger(CloudVisionMediaModerationScanner::class.java)

    // The JDK HttpClient.send and the credential refresh both block. scan() runs on the
    // upload-complete request path, so the call runs on the IO pool, and runInterruptible lets the
    // timeout interrupt a blocked send instead of waiting it out.
    override suspend fun scan(asset: MediaAssetRecord): MediaModerationDecision {
        // Only image assets are SafeSearch-scannable; anything else stays human-reviewed.
        if (!asset.contentType.startsWith("image/")) return MediaModerationDecision.PendingReview
        if (asset.storageBucket.isBlank() || asset.storageObjectKey.isBlank()) return MediaModerationDecision.PendingReview
        val gcsUri = "gs://${asset.storageBucket}/${asset.storageObjectKey}"

        val annotation =
            try {
                withTimeout(scanTimeout.toMillis()) {
                    runInterruptible(Dispatchers.IO) { client.annotate(gcsUri) }
                }
            } catch (timeout: TimeoutCancellationException) {
                logger.warn("Vision SafeSearch timed out for asset {}; holding for review", asset.mediaAssetId)
                return MediaModerationDecision.PendingReview
            } catch (cancelled: CancellationException) {
                // The caller was cancelled (not our timeout): let it propagate.
                throw cancelled
            } catch (error: Exception) {
                // Never auto-approve on failure; the human queue is the safe default. Log the error
                // type only: messages can echo request details.
                logger.warn(
                    "Vision SafeSearch failed for asset {} ({}); holding for review",
                    asset.mediaAssetId,
                    error.javaClass.simpleName,
                )
                return MediaModerationDecision.PendingReview
            }
        if (annotation == null) {
            logger.warn("Vision SafeSearch gave no usable answer for asset {}; holding for review", asset.mediaAssetId)
            return MediaModerationDecision.PendingReview
        }
        return policy.decide(annotation)
    }

    companion object {
        val DEFAULT_SCAN_TIMEOUT: Duration = Duration.ofSeconds(8)
    }
}

/**
 * The one network call the scanner makes, behind an interface so tests fake Vision without a
 * network. Returns the SafeSearch annotation, or null when the answer can't be trusted (non-2xx, a
 * per-image error, no annotation). May throw; the scanner treats a throw like null.
 */
fun interface SafeSearchClient {
    fun annotate(gcsUri: String): SafeSearchAnnotation?
}

/** Calls Vision `images:annotate` over the JDK HTTP client with a cloud-platform-scoped token. */
class HttpSafeSearchClient(
    credentials: GoogleCredentials,
    private val endpoint: String = "https://vision.googleapis.com/v1/images:annotate",
    private val httpClient: HttpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build(),
    private val requestTimeout: Duration = Duration.ofSeconds(6),
) : SafeSearchClient {
    private val logger = LoggerFactory.getLogger(HttpSafeSearchClient::class.java)

    // The credentials need the cloud-platform scope to call Vision; scope once, refresh per call.
    private val scopedCredentials: GoogleCredentials =
        if (credentials.createScopedRequired()) {
            credentials.createScoped(listOf("https://www.googleapis.com/auth/cloud-platform"))
        } else {
            credentials
        }

    override fun annotate(gcsUri: String): SafeSearchAnnotation? {
        scopedCredentials.refreshIfExpired()
        val token = scopedCredentials.accessToken?.tokenValue ?: return null
        val request =
            HttpRequest
                .newBuilder()
                .uri(URI.create(endpoint))
                .timeout(requestTimeout)
                .header("Authorization", "Bearer $token")
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(JSON.encodeToString(AnnotateRequest.safeSearch(gcsUri))))
                .build()
        val response = httpClient.send(request, HttpResponse.BodyHandlers.ofString())
        if (response.statusCode() !in 200..299) {
            logger.warn("Vision SafeSearch returned HTTP {}", response.statusCode())
            return null
        }
        return parseSafeSearchResponse(response.body())
    }

    private companion object {
        val JSON =
            Json {
                ignoreUnknownKeys = true
                encodeDefaults = true
            }
    }
}

private val RESPONSE_JSON = Json { ignoreUnknownKeys = true }

/**
 * Reads the first image response. Vision reports a per-image failure (unreadable object, missing
 * permission) as an `error` object on an HTTP 200, so an `error` (or no annotation) is null: hold.
 */
internal fun parseSafeSearchResponse(body: String): SafeSearchAnnotation? {
    val first = RESPONSE_JSON.decodeFromString<AnnotateResponse>(body).responses.firstOrNull() ?: return null
    if (first.error != null) return null
    return first.safeSearchAnnotation
}

/**
 * Maps Vision SafeSearch likelihoods to a pre-screening decision (TRUST_AND_SAFETY_SPEC "Photo
 * pre-screening"). Public photos may be suggestive but not explicit, so:
 *
 * - **Approved** only when adult <= [maxAdult], violence <= [maxViolence] and racy <= [maxRacy]
 *   (defaults UNLIKELY / POSSIBLE / LIKELY), and each of the three is a known likelihood.
 * - **PendingReview** for anything above a ceiling, and for an UNKNOWN or missing category (fail
 *   closed). Nothing is removed on these signals; a person decides.
 * - **Rejected(SEVERE)** only when violence >= [severeViolenceAtOrAbove] (default LIKELY). This keeps
 *   the shipped private-album / chat-media safety path (remove + system report). For a profile photo
 *   `MediaUploadActions` never removes on a scanner decision: it holds the photo for the human queue
 *   and still files the escalation report.
 *
 * `medical` and `spoof` are ignored.
 */
data class SafeSearchPolicy(
    val maxAdult: SafeSearchLikelihood = DEFAULT_MAX_ADULT,
    val maxViolence: SafeSearchLikelihood = DEFAULT_MAX_VIOLENCE,
    val maxRacy: SafeSearchLikelihood = DEFAULT_MAX_RACY,
    val severeViolenceAtOrAbove: SafeSearchLikelihood = SafeSearchLikelihood.LIKELY,
) {
    fun decide(annotation: SafeSearchAnnotation): MediaModerationDecision {
        val adult = annotation.adult.toLikelihood()
        val violence = annotation.violence.toLikelihood()
        val racy = annotation.racy.toLikelihood()
        if (violence != SafeSearchLikelihood.UNKNOWN && violence.ordinal >= severeViolenceAtOrAbove.ordinal) {
            return MediaModerationDecision.Rejected(
                reason = "safe_search_violence_${violence.name.lowercase()}",
                severity = MediaModerationSeverity.SEVERE,
                reportCategory = ReportCategory.HARASSMENT_OR_THREATS,
            )
        }
        val withinCeilings =
            adult.isKnownAtMost(maxAdult) &&
                violence.isKnownAtMost(maxViolence) &&
                racy.isKnownAtMost(maxRacy)
        return if (withinCeilings) MediaModerationDecision.Approved else MediaModerationDecision.PendingReview
    }

    companion object {
        val DEFAULT_MAX_ADULT = SafeSearchLikelihood.UNLIKELY
        val DEFAULT_MAX_VIOLENCE = SafeSearchLikelihood.POSSIBLE
        val DEFAULT_MAX_RACY = SafeSearchLikelihood.LIKELY

        /**
         * Ceilings an operator may configure. VERY_LIKELY would switch a category's screening off and
         * UNKNOWN is not a level, so neither is accepted.
         */
        val CONFIGURABLE_CEILINGS: Set<SafeSearchLikelihood> =
            setOf(
                SafeSearchLikelihood.VERY_UNLIKELY,
                SafeSearchLikelihood.UNLIKELY,
                SafeSearchLikelihood.POSSIBLE,
                SafeSearchLikelihood.LIKELY,
            )
    }
}

private fun SafeSearchLikelihood.isKnownAtMost(ceiling: SafeSearchLikelihood): Boolean =
    this != SafeSearchLikelihood.UNKNOWN && ordinal <= ceiling.ordinal

/** Vision likelihood scale, ordered weakest → strongest. */
enum class SafeSearchLikelihood {
    UNKNOWN,
    VERY_UNLIKELY,
    UNLIKELY,
    POSSIBLE,
    LIKELY,
    VERY_LIKELY,
}

/** Parses a Vision likelihood name; anything unrecognised (or null) is UNKNOWN. */
fun String?.toLikelihood(): SafeSearchLikelihood =
    when (this?.trim()?.uppercase()) {
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
    val error: AnnotateError? = null,
)

// google.rpc.Status. Only its presence matters; the message can name the object, so it is not read.
@Serializable
private data class AnnotateError(
    val code: Int? = null,
)

@Serializable
data class SafeSearchAnnotation(
    val adult: String? = null,
    val spoof: String? = null,
    val medical: String? = null,
    val violence: String? = null,
    val racy: String? = null,
)
