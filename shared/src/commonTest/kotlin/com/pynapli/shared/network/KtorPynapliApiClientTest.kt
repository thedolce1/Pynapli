package com.pynapli.shared.network

import com.pynapli.shared.core.config.PynapliConfig
import com.pynapli.shared.core.config.PynapliEnvironment
import com.pynapli.shared.core.result.AppResult
import com.pynapli.shared.network.dto.AccountDataExportDto
import com.pynapli.shared.network.dto.ActivitySummaryDto
import com.pynapli.shared.network.dto.BoostActivationDto
import com.pynapli.shared.network.dto.CompleteMediaUploadRequestDto
import com.pynapli.shared.network.dto.ConversationDetailDto
import com.pynapli.shared.network.dto.ConversationDto
import com.pynapli.shared.network.dto.ConversationListResponseDto
import com.pynapli.shared.network.dto.CreateDirectConversationRequestDto
import com.pynapli.shared.network.dto.CreateMediaUploadRequestDto
import com.pynapli.shared.network.dto.CurrentLocationDto
import com.pynapli.shared.network.dto.DeleteAccountRequestDto
import com.pynapli.shared.network.dto.DeleteAccountResponseDto
import com.pynapli.shared.network.dto.GridQueryRequestDto
import com.pynapli.shared.network.dto.GridQueryResponseDto
import com.pynapli.shared.network.dto.MarkActivitySeenRequestDto
import com.pynapli.shared.network.dto.MarkActivitySeenResponseDto
import com.pynapli.shared.network.dto.MeDto
import com.pynapli.shared.network.dto.MediaAlbumItemDto
import com.pynapli.shared.network.dto.MediaUploadSlotDto
import com.pynapli.shared.network.dto.MessageDto
import com.pynapli.shared.network.dto.PublicProfileDto
import com.pynapli.shared.network.dto.SendMessageRequestDto
import com.pynapli.shared.network.dto.StorePurchaseResponseDto
import com.pynapli.shared.network.dto.SubmitDevStorePurchaseRequestDto
import com.pynapli.shared.network.dto.SubmitPlayStoreSubscriptionPurchaseRequestDto
import com.pynapli.shared.network.dto.SubscriptionEntitlementDto
import com.pynapli.shared.network.dto.SubscriptionInventoryResponseDto
import com.pynapli.shared.network.dto.SubscriptionPurchaseSyncResponseDto
import com.pynapli.shared.network.dto.UpdateCurrentLocationRequestDto
import com.pynapli.shared.network.dto.UpdateDiscoverySettingsRequestDto
import com.pynapli.shared.network.dto.UpdateProfileRequestDto
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class KtorPynapliApiClientTest {
    @Test
    fun queryGridSendsBearerTokenAndMapsCards() =
        runBlocking {
            var authorizationHeader: String? = null
            var requestPath: String? = null
            var requestMethod: HttpMethod? = null
            val httpClient =
                testHttpClient { request ->
                    authorizationHeader = request.headers[HttpHeaders.Authorization]
                    requestPath = request.url.encodedPath
                    requestMethod = request.method
                    respond(
                        content =
                            """
                            {
                              "items": [
                                {
                                  "userId": "target-1",
                                  "displayName": "Alex",
                                  "thumbnailUrl": null,
                                  "online": true,
                                  "distanceLabel": "1.2 km",
                                  "linkedPartner": null,
                                  "rankToken": "grid:v1:target-1"
                                }
                              ],
                              "nextCursor": null
                            }
                            """.trimIndent(),
                        status = HttpStatusCode.OK,
                        headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
                    )
                }
            val api = KtorPynapliApiClient(testConfig(), StaticAccessTokenProvider("test-token"), httpClient)

            val result = api.queryGrid(GridQueryRequestDto(limit = 1))

            val success = assertIs<AppResult.Success<*>>(result)
            assertEquals("Bearer test-token", authorizationHeader)
            assertEquals("/v1/grid/query", requestPath)
            assertEquals(HttpMethod.Post, requestMethod)
            assertEquals("Alex", (success.value as GridQueryResponseDto).items.first().displayName)
        }

    @Test
    fun queryGridRequiresAccessToken() =
        runBlocking {
            val api = KtorPynapliApiClient(testConfig(), StaticAccessTokenProvider(null), testHttpClient())

            val result = api.queryGrid()

            val failure = assertIs<AppResult.Failure>(result)
            assertEquals("UNAUTHENTICATED", failure.error.code)
        }

    @Test
    fun queryGridMapsApiErrors() =
        runBlocking {
            val httpClient =
                testHttpClient {
                    respond(
                        content = """{"code":"LOCATION_REQUIRED","message":"Location is required before loading the grid.","requestId":"test"}""",
                        status = HttpStatusCode.BadRequest,
                        headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
                    )
                }
            val api = KtorPynapliApiClient(testConfig(), StaticAccessTokenProvider("test-token"), httpClient)

            val result = api.queryGrid()

            val failure = assertIs<AppResult.Failure>(result)
            assertEquals("LOCATION_REQUIRED", failure.error.code)
            assertEquals("Location is required before loading the grid.", failure.error.message)
        }

    @Test
    fun getMeSendsBearerTokenAndMapsProfile() =
        runBlocking {
            var authorizationHeader: String? = null
            var requestPath: String? = null
            var requestMethod: HttpMethod? = null
            val httpClient =
                testHttpClient { request ->
                    authorizationHeader = request.headers[HttpHeaders.Authorization]
                    requestPath = request.url.encodedPath
                    requestMethod = request.method
                    respond(
                        content = meJson(displayName = "Seb Dev"),
                        status = HttpStatusCode.OK,
                        headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
                    )
                }
            val api = KtorPynapliApiClient(testConfig(), StaticAccessTokenProvider("test-token"), httpClient)

            val result = api.getMe()

            val success = assertIs<AppResult.Success<*>>(result)
            assertEquals("Bearer test-token", authorizationHeader)
            assertEquals("/v1/me", requestPath)
            assertEquals(HttpMethod.Get, requestMethod)
            assertEquals("Seb Dev", (success.value as MeDto).profile.displayName)
        }

    @Test
    fun requestDataExportSendsBearerTokenAndMapsStatus() =
        runBlocking {
            var requestPath: String? = null
            var requestMethod: HttpMethod? = null
            val httpClient =
                testHttpClient { request ->
                    requestPath = request.url.encodedPath
                    requestMethod = request.method
                    respond(
                        content =
                            """
                            {
                              "requestId": "export-1",
                              "status": "requested",
                              "requestedAt": "2026-05-26T12:00:00Z",
                              "message": "Your data export request has been received."
                            }
                            """.trimIndent(),
                        status = HttpStatusCode.OK,
                        headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
                    )
                }
            val api = KtorPynapliApiClient(testConfig(), StaticAccessTokenProvider("test-token"), httpClient)

            val result = api.requestDataExport()

            val success = assertIs<AppResult.Success<*>>(result)
            assertEquals("/v1/account/data-export", requestPath)
            assertEquals(HttpMethod.Post, requestMethod)
            assertEquals("requested", (success.value as AccountDataExportDto).status)
        }

    @Test
    fun deleteAccountSendsConfirmationAndMapsResponse() =
        runBlocking {
            var requestPath: String? = null
            var requestMethod: HttpMethod? = null
            val httpClient =
                testHttpClient { request ->
                    requestPath = request.url.encodedPath
                    requestMethod = request.method
                    respond(
                        content =
                            """
                            {
                              "deleted": true,
                              "revokedSessionCount": 2,
                              "message": "Your account has been deleted and removed from discovery."
                            }
                            """.trimIndent(),
                        status = HttpStatusCode.OK,
                        headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
                    )
                }
            val api = KtorPynapliApiClient(testConfig(), StaticAccessTokenProvider("test-token"), httpClient)

            val result = api.deleteAccount(DeleteAccountRequestDto("DELETE"))

            val success = assertIs<AppResult.Success<*>>(result)
            assertEquals("/v1/account/delete", requestPath)
            assertEquals(HttpMethod.Post, requestMethod)
            assertEquals(true, (success.value as DeleteAccountResponseDto).deleted)
        }

    @Test
    fun updateProfileSendsPatchAndMapsProfile() =
        runBlocking {
            var authorizationHeader: String? = null
            var requestPath: String? = null
            var requestMethod: HttpMethod? = null
            val httpClient =
                testHttpClient { request ->
                    authorizationHeader = request.headers[HttpHeaders.Authorization]
                    requestPath = request.url.encodedPath
                    requestMethod = request.method
                    respond(
                        content = meJson(displayName = "Sebastian"),
                        status = HttpStatusCode.OK,
                        headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
                    )
                }
            val api = KtorPynapliApiClient(testConfig(), StaticAccessTokenProvider("test-token"), httpClient)

            val result =
                api.updateProfile(
                    UpdateProfileRequestDto(
                        displayName = "Sebastian",
                        birthdate = "1990-01-15",
                        gender = "man",
                        orientation = "bisexual",
                        relationshipContext = "single",
                        discoveryStyle = "both",
                    ),
                )

            val success = assertIs<AppResult.Success<*>>(result)
            assertEquals("Bearer test-token", authorizationHeader)
            assertEquals("/v1/me/profile", requestPath)
            assertEquals(HttpMethod.Patch, requestMethod)
            assertEquals("Sebastian", (success.value as MeDto).profile.displayName)
        }

    @Test
    fun updateDiscoverySettingsSendsPatchAndMapsSettings() =
        runBlocking {
            var authorizationHeader: String? = null
            var requestPath: String? = null
            var requestMethod: HttpMethod? = null
            val httpClient =
                testHttpClient { request ->
                    authorizationHeader = request.headers[HttpHeaders.Authorization]
                    requestPath = request.url.encodedPath
                    requestMethod = request.method
                    respond(
                        content =
                            meJson(
                                displayName = "Seb Dev",
                                gridVisible = false,
                                swipeVisible = true,
                                showDistance = false,
                                showInViewedLists = false,
                                allowGridMessages = true,
                                gridMessagePolicy = "matches_only",
                                allowFlirts = false,
                                allowIntroNotes = false,
                                allowAlbumRequests = false,
                            ),
                        status = HttpStatusCode.OK,
                        headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
                    )
                }
            val api = KtorPynapliApiClient(testConfig(), StaticAccessTokenProvider("test-token"), httpClient)

            val result =
                api.updateDiscoverySettings(
                    UpdateDiscoverySettingsRequestDto(
                        gridVisible = false,
                        swipeVisible = true,
                        showDistance = false,
                        showInViewedLists = false,
                        allowGridMessages = true,
                        gridMessagePolicy = "matches_only",
                        allowFlirts = false,
                        allowIntroNotes = false,
                        allowAlbumRequests = false,
                    ),
                )

            val success = assertIs<AppResult.Success<*>>(result)
            val me = success.value as MeDto
            assertEquals("Bearer test-token", authorizationHeader)
            assertEquals("/v1/me/discovery-settings", requestPath)
            assertEquals(HttpMethod.Patch, requestMethod)
            assertEquals(false, me.discoverySettings.gridVisible)
            assertEquals(true, me.discoverySettings.swipeVisible)
            assertEquals(false, me.discoverySettings.showDistance)
            assertEquals(false, me.discoverySettings.showInViewedLists)
            assertEquals(true, me.discoverySettings.allowGridMessages)
            assertEquals("matches_only", me.discoverySettings.gridMessagePolicy)
            assertEquals(false, me.discoverySettings.allowFlirts)
            assertEquals(false, me.discoverySettings.allowIntroNotes)
            assertEquals(false, me.discoverySettings.allowAlbumRequests)
        }

    @Test
    fun updateCurrentLocationSendsBearerToken() =
        runBlocking {
            var authorizationHeader: String? = null
            var requestPath: String? = null
            val httpClient =
                testHttpClient { request ->
                    authorizationHeader = request.headers[HttpHeaders.Authorization]
                    requestPath = request.url.encodedPath
                    respond(
                        content =
                            """
                            {
                              "updatedAt": "2026-04-24T18:00:00Z",
                              "accuracyMeters": 25,
                              "source": "gps",
                              "geohash": "45.50:-73.57",
                              "city": null,
                              "region": null,
                              "country": null
                            }
                            """.trimIndent(),
                        status = HttpStatusCode.OK,
                        headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
                    )
                }
            val api = KtorPynapliApiClient(testConfig(), StaticAccessTokenProvider("test-token"), httpClient)

            val result =
                api.updateCurrentLocation(
                    UpdateCurrentLocationRequestDto(
                        latitude = 45.5017,
                        longitude = -73.5673,
                        accuracyMeters = 25,
                        source = "gps",
                    ),
                )

            val success = assertIs<AppResult.Success<*>>(result)
            assertEquals("Bearer test-token", authorizationHeader)
            assertEquals("/v1/location/current", requestPath)
            assertEquals("gps", (success.value as CurrentLocationDto).source)
        }

    @Test
    fun getPublicProfileSendsBearerTokenAndMapsProfile() =
        runBlocking {
            var authorizationHeader: String? = null
            var requestPath: String? = null
            var requestMethod: HttpMethod? = null
            val httpClient =
                testHttpClient { request ->
                    authorizationHeader = request.headers[HttpHeaders.Authorization]
                    requestPath = request.url.encodedPath
                    requestMethod = request.method
                    respond(
                        content =
                            """
                            {
                              "userId": "target-1",
                              "displayName": "Maya",
                              "displayAge": 35,
                              "gender": "woman",
                              "orientation": "open",
                              "relationshipContext": "linked",
                              "discoveryStyle": "both",
                              "distanceLabel": "1.2 km",
                              "linkedPartner": null,
                              "allowGridMessages": true,
                              "allowFlirts": true,
                              "allowAlbumRequests": true
                            }
                            """.trimIndent(),
                        status = HttpStatusCode.OK,
                        headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
                    )
                }
            val api = KtorPynapliApiClient(testConfig(), StaticAccessTokenProvider("test-token"), httpClient)

            val result = api.getPublicProfile("target-1")

            val success = assertIs<AppResult.Success<*>>(result)
            assertEquals("Bearer test-token", authorizationHeader)
            assertEquals("/v1/profiles/target-1", requestPath)
            assertEquals(HttpMethod.Get, requestMethod)
            assertEquals("Maya", (success.value as PublicProfileDto).displayName)
        }

    @Test
    fun getPublicProfileToleratesOmittedNullableProfileFields() =
        runBlocking {
            val httpClient =
                testHttpClient {
                    respond(
                        content =
                            """
                            {
                              "userId": "target-1",
                              "displayName": "Dense 2550",
                              "discoveryStyle": "social",
                              "allowGridMessages": true,
                              "allowFlirts": true,
                              "allowAlbumRequests": true
                            }
                            """.trimIndent(),
                        status = HttpStatusCode.OK,
                        headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
                    )
                }
            val api = KtorPynapliApiClient(testConfig(), StaticAccessTokenProvider("test-token"), httpClient)

            val result = api.getPublicProfile("target-1")

            val success = assertIs<AppResult.Success<*>>(result)
            val profile = success.value as PublicProfileDto
            assertEquals("Dense 2550", profile.displayName)
            assertEquals(null, profile.gender)
            assertEquals(null, profile.orientation)
            assertEquals(null, profile.relationshipContext)
        }

    @Test
    fun getSubscriptionEntitlementSendsBearerTokenAndMapsTier() =
        runBlocking {
            var authorizationHeader: String? = null
            var requestPath: String? = null
            var requestMethod: HttpMethod? = null
            val httpClient =
                testHttpClient { request ->
                    authorizationHeader = request.headers[HttpHeaders.Authorization]
                    requestPath = request.url.encodedPath
                    requestMethod = request.method
                    respond(
                        content =
                            """
                            {
                              "tier": "select",
                              "source": "linked_partner_benefit",
                              "displayName": "Pynapli Select",
                              "adSupported": false,
                              "linkedPartnerIncluded": true,
                              "sourceSubscriptionId": "sub-1",
                              "sourceUserId": "partner-1",
                              "linkedPartnerUserId": "partner-1",
                              "startsAt": "2026-05-01T00:00:00Z",
                              "endsAt": "2026-06-01T00:00:00Z"
                            }
                            """.trimIndent(),
                        status = HttpStatusCode.OK,
                        headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
                    )
                }
            val api = KtorPynapliApiClient(testConfig(), StaticAccessTokenProvider("test-token"), httpClient)

            val result = api.getSubscriptionEntitlement()

            val success = assertIs<AppResult.Success<*>>(result)
            assertEquals("Bearer test-token", authorizationHeader)
            assertEquals("/v1/subscriptions/entitlement", requestPath)
            assertEquals(HttpMethod.Get, requestMethod)
            val entitlement = success.value as SubscriptionEntitlementDto
            assertEquals("select", entitlement.tier)
            assertEquals("linked_partner_benefit", entitlement.source)
            assertEquals(true, entitlement.linkedPartnerIncluded)
        }

    @Test
    fun getSubscriptionInventorySendsBearerTokenAndMapsPricing() =
        runBlocking {
            var authorizationHeader: String? = null
            var requestPath: String? = null
            var requestMethod: HttpMethod? = null
            val httpClient =
                testHttpClient { request ->
                    authorizationHeader = request.headers[HttpHeaders.Authorization]
                    requestPath = request.url.encodedPath
                    requestMethod = request.method
                    respond(
                        content =
                            """
                            {
                              "tiers": [
                                {
                                  "tier": "free",
                                  "displayName": "Pynapli Free",
                                  "monthlyPriceUsdCents": 0,
                                  "adSupported": true,
                                  "linkedPartnerIncluded": false,
                                  "features": ["Basic grid"]
                                },
                                {
                                  "tier": "social",
                                  "displayName": "Pynapli Social",
                                  "monthlyPriceUsdCents": 499,
                                  "adSupported": false,
                                  "linkedPartnerIncluded": true,
                                  "features": ["No ads"]
                                },
                                {
                                  "tier": "select",
                                  "displayName": "Pynapli Select",
                                  "monthlyPriceUsdCents": 999,
                                  "adSupported": false,
                                  "linkedPartnerIncluded": true,
                                  "features": ["Advanced filters"]
                                }
                              ]
                            }
                            """.trimIndent(),
                        status = HttpStatusCode.OK,
                        headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
                    )
                }
            val api = KtorPynapliApiClient(testConfig(), StaticAccessTokenProvider("test-token"), httpClient)

            val result = api.getSubscriptionInventory()

            val success = assertIs<AppResult.Success<*>>(result)
            assertEquals("Bearer test-token", authorizationHeader)
            assertEquals("/v1/subscriptions/inventory", requestPath)
            assertEquals(HttpMethod.Get, requestMethod)
            val inventory = success.value as SubscriptionInventoryResponseDto
            assertEquals(listOf(0, 499, 999), inventory.tiers.map { it.monthlyPriceUsdCents })
        }

    @Test
    fun submitPlayStorePurchaseSendsBearerTokenAndMapsStatus() =
        runBlocking {
            var authorizationHeader: String? = null
            var requestPath: String? = null
            var requestMethod: HttpMethod? = null
            val httpClient =
                testHttpClient { request ->
                    authorizationHeader = request.headers[HttpHeaders.Authorization]
                    requestPath = request.url.encodedPath
                    requestMethod = request.method
                    respond(
                        content =
                            """
                            {
                              "status": "pending_verification",
                              "entitlement": {
                                "tier": "free",
                                "source": "free",
                                "displayName": "Pynapli Free",
                                "adSupported": true,
                                "linkedPartnerIncluded": false
                              }
                            }
                            """.trimIndent(),
                        status = HttpStatusCode.OK,
                        headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
                    )
                }
            val api = KtorPynapliApiClient(testConfig(), StaticAccessTokenProvider("test-token"), httpClient)

            val result =
                api.submitPlayStoreSubscriptionPurchase(
                    SubmitPlayStoreSubscriptionPurchaseRequestDto(
                        productId = "pynapli_select_monthly",
                        purchaseToken = "purchase-token-1",
                        packageName = "com.pynapli.app",
                    ),
                )

            val success = assertIs<AppResult.Success<*>>(result)
            assertEquals("Bearer test-token", authorizationHeader)
            assertEquals("/v1/subscriptions/play-store/purchases", requestPath)
            assertEquals(HttpMethod.Post, requestMethod)
            val response = success.value as SubscriptionPurchaseSyncResponseDto
            assertEquals("pending_verification", response.status)
            assertEquals("free", response.entitlement.tier)
        }

    @Test
    fun submitDevStorePurchaseSendsBearerTokenAndMapsInventory() =
        runBlocking {
            var authorizationHeader: String? = null
            var requestPath: String? = null
            var requestMethod: HttpMethod? = null
            val httpClient =
                testHttpClient { request ->
                    authorizationHeader = request.headers[HttpHeaders.Authorization]
                    requestPath = request.url.encodedPath
                    requestMethod = request.method
                    respond(
                        content =
                            """
                            {
                              "status": "inventory_granted",
                              "entitlement": {
                                "tier": "select",
                                "source": "own_subscription",
                                "displayName": "Pynapli Select",
                                "adSupported": false,
                                "linkedPartnerIncluded": true
                              },
                              "inventory": {
                                "tiers": [],
                                "premiumProducts": [],
                                "balances": [
                                  {
                                    "inventoryType": "boost",
                                    "quantity": 1,
                                    "nextExpiresAt": "2026-06-01T00:00:00Z"
                                  }
                                ]
                              }
                            }
                            """.trimIndent(),
                        status = HttpStatusCode.OK,
                        headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
                    )
                }
            val api = KtorPynapliApiClient(testConfig(), StaticAccessTokenProvider("test-token"), httpClient)

            val result = api.submitDevStorePurchase(SubmitDevStorePurchaseRequestDto(productId = "pynapli_boost_1"))

            val success = assertIs<AppResult.Success<*>>(result)
            assertEquals("Bearer test-token", authorizationHeader)
            assertEquals("/v1/subscriptions/dev/purchases", requestPath)
            assertEquals(HttpMethod.Post, requestMethod)
            val response = success.value as StorePurchaseResponseDto
            assertEquals("inventory_granted", response.status)
            assertEquals(
                1,
                response.inventory.balances
                    .first()
                    .quantity,
            )
        }

    @Test
    fun activateBoostSendsBearerTokenAndMapsActivation() =
        runBlocking {
            var authorizationHeader: String? = null
            var requestPath: String? = null
            var requestMethod: HttpMethod? = null
            val httpClient =
                testHttpClient { request ->
                    authorizationHeader = request.headers[HttpHeaders.Authorization]
                    requestPath = request.url.encodedPath
                    requestMethod = request.method
                    respond(
                        content =
                            """
                            {
                              "activationId": "boost-1",
                              "boostType": "boost",
                              "startsAt": "2026-05-07T12:00:00Z",
                              "endsAt": "2026-05-07T12:30:00Z",
                              "alreadyActive": false
                            }
                            """.trimIndent(),
                        status = HttpStatusCode.OK,
                        headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
                    )
                }
            val api = KtorPynapliApiClient(testConfig(), StaticAccessTokenProvider("test-token"), httpClient)

            val result = api.activateBoost()

            val success = assertIs<AppResult.Success<*>>(result)
            assertEquals("Bearer test-token", authorizationHeader)
            assertEquals("/v1/boosts", requestPath)
            assertEquals(HttpMethod.Post, requestMethod)
            val activation = success.value as BoostActivationDto
            assertEquals("boost", activation.boostType)
            assertEquals(false, activation.alreadyActive)
        }

    @Test
    fun getActivitySummarySendsBearerTokenAndMapsCounts() =
        runBlocking {
            var authorizationHeader: String? = null
            var requestPath: String? = null
            var requestMethod: HttpMethod? = null
            val httpClient =
                testHttpClient { request ->
                    authorizationHeader = request.headers[HttpHeaders.Authorization]
                    requestPath = request.url.encodedPath
                    requestMethod = request.method
                    respond(
                        content =
                            """
                            {
                              "badgeCount": 5,
                              "profileViewCount": 12,
                              "flirtCount": 1,
                              "likeCount": 2,
                              "matchCount": 1,
                              "albumRequestCount": 1,
                              "partnerInviteCount": 0,
                              "latestBadgeOccurredAt": "2026-05-19T12:30:00Z"
                            }
                            """.trimIndent(),
                        status = HttpStatusCode.OK,
                        headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
                    )
                }
            val api = KtorPynapliApiClient(testConfig(), StaticAccessTokenProvider("test-token"), httpClient)

            val result = api.getActivitySummary()

            val success = assertIs<AppResult.Success<*>>(result)
            assertEquals("Bearer test-token", authorizationHeader)
            assertEquals("/v1/activity/summary", requestPath)
            assertEquals(HttpMethod.Get, requestMethod)
            val summary = success.value as ActivitySummaryDto
            assertEquals(5, summary.badgeCount)
            assertEquals(12, summary.profileViewCount)
            assertEquals(2, summary.likeCount)
        }

    @Test
    fun markActivitySeenSendsBearerTokenAndMapsCount() =
        runBlocking {
            var authorizationHeader: String? = null
            var requestPath: String? = null
            var requestMethod: HttpMethod? = null
            val httpClient =
                testHttpClient { request ->
                    authorizationHeader = request.headers[HttpHeaders.Authorization]
                    requestPath = request.url.encodedPath
                    requestMethod = request.method
                    respond(
                        content =
                            """
                            {
                              "markedCount": 2
                            }
                            """.trimIndent(),
                        status = HttpStatusCode.OK,
                        headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
                    )
                }
            val api = KtorPynapliApiClient(testConfig(), StaticAccessTokenProvider("test-token"), httpClient)

            val result =
                api.markActivitySeen(
                    MarkActivitySeenRequestDto(eventIds = listOf("event-1", "event-2")),
                )

            val success = assertIs<AppResult.Success<*>>(result)
            assertEquals("Bearer test-token", authorizationHeader)
            assertEquals("/v1/activity/seen", requestPath)
            assertEquals(HttpMethod.Post, requestMethod)
            assertEquals(2, (success.value as MarkActivitySeenResponseDto).markedCount)
        }

    @Test
    fun createDirectConversationSendsBearerTokenAndMapsConversation() =
        runBlocking {
            var authorizationHeader: String? = null
            var requestPath: String? = null
            var requestMethod: HttpMethod? = null
            val httpClient =
                testHttpClient { request ->
                    authorizationHeader = request.headers[HttpHeaders.Authorization]
                    requestPath = request.url.encodedPath
                    requestMethod = request.method
                    respond(
                        content = conversationDetailJson(),
                        status = HttpStatusCode.OK,
                        headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
                    )
                }
            val api = KtorPynapliApiClient(testConfig(), StaticAccessTokenProvider("test-token"), httpClient)

            val result =
                api.createDirectConversation(
                    CreateDirectConversationRequestDto(targetUserId = "target-1"),
                )

            val success = assertIs<AppResult.Success<*>>(result)
            assertEquals("Bearer test-token", authorizationHeader)
            assertEquals("/v1/messages/threads", requestPath)
            assertEquals(HttpMethod.Post, requestMethod)
            assertEquals("conversation-1", (success.value as ConversationDetailDto).conversation.conversationId)
        }

    @Test
    fun sendMessageSendsBearerTokenAndMapsMessage() =
        runBlocking {
            var authorizationHeader: String? = null
            var requestPath: String? = null
            val httpClient =
                testHttpClient { request ->
                    authorizationHeader = request.headers[HttpHeaders.Authorization]
                    requestPath = request.url.encodedPath
                    respond(
                        content =
                            """
                            {
                              "messageId": "message-1",
                              "conversationId": "conversation-1",
                              "senderUserId": "user-1",
                              "body": "Hey there",
                              "createdAt": "2026-04-24T18:00:00Z",
                              "status": "visible",
                              "deliveryStatus": "sent"
                            }
                            """.trimIndent(),
                        status = HttpStatusCode.OK,
                        headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
                    )
                }
            val api = KtorPynapliApiClient(testConfig(), StaticAccessTokenProvider("test-token"), httpClient)

            val result = api.sendMessage("conversation-1", SendMessageRequestDto("Hey there"))

            val success = assertIs<AppResult.Success<*>>(result)
            assertEquals("Bearer test-token", authorizationHeader)
            assertEquals("/v1/messages/threads/conversation-1/messages", requestPath)
            val message = success.value as MessageDto
            assertEquals("Hey there", message.body)
            assertEquals("sent", message.deliveryStatus)
        }

    @Test
    fun deleteMessageSendsBearerTokenAndMapsDeletedPlaceholder() =
        runBlocking {
            var authorizationHeader: String? = null
            var requestPath: String? = null
            var requestMethod: HttpMethod? = null
            val httpClient =
                testHttpClient { request ->
                    authorizationHeader = request.headers[HttpHeaders.Authorization]
                    requestPath = request.url.encodedPath
                    requestMethod = request.method
                    respond(
                        content =
                            """
                            {
                              "messageId": "message-1",
                              "conversationId": "conversation-1",
                              "senderUserId": "user-1",
                              "body": "",
                              "createdAt": "2026-04-24T18:00:00Z",
                              "status": "visible",
                              "deliveryStatus": "deleted",
                              "deletedAt": "2026-04-24T18:01:00Z"
                            }
                            """.trimIndent(),
                        status = HttpStatusCode.OK,
                        headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
                    )
                }
            val api = KtorPynapliApiClient(testConfig(), StaticAccessTokenProvider("test-token"), httpClient)

            val result = api.deleteMessage("message-1")

            val success = assertIs<AppResult.Success<*>>(result)
            assertEquals("Bearer test-token", authorizationHeader)
            assertEquals("/v1/messages/message-1/delete", requestPath)
            assertEquals(HttpMethod.Post, requestMethod)
            val message = success.value as MessageDto
            assertEquals("", message.body)
            assertEquals("deleted", message.deliveryStatus)
            assertEquals("2026-04-24T18:01:00Z", message.deletedAt)
        }

    @Test
    fun getConversationDetailSendsPaginationQuery() =
        runBlocking {
            var authorizationHeader: String? = null
            var requestPath: String? = null
            var beforeQuery: String? = null
            var limitQuery: String? = null
            val httpClient =
                testHttpClient { request ->
                    authorizationHeader = request.headers[HttpHeaders.Authorization]
                    requestPath = request.url.encodedPath
                    beforeQuery = request.url.parameters["before"]
                    limitQuery = request.url.parameters["limit"]
                    respond(
                        content = conversationDetailJson(nextCursor = "next-page"),
                        status = HttpStatusCode.OK,
                        headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
                    )
                }
            val api = KtorPynapliApiClient(testConfig(), StaticAccessTokenProvider("test-token"), httpClient)

            val result = api.getConversationDetail("conversation-1", before = "older-page", limit = 25)

            val success = assertIs<AppResult.Success<*>>(result)
            assertEquals("Bearer test-token", authorizationHeader)
            assertEquals("/v1/messages/threads/conversation-1", requestPath)
            assertEquals("older-page", beforeQuery)
            assertEquals("25", limitQuery)
            assertEquals("next-page", (success.value as ConversationDetailDto).nextCursor)
        }

    @Test
    fun listConversationsSendsBearerTokenAndMapsThreads() =
        runBlocking {
            var authorizationHeader: String? = null
            var requestPath: String? = null
            var requestMethod: HttpMethod? = null
            val httpClient =
                testHttpClient { request ->
                    authorizationHeader = request.headers[HttpHeaders.Authorization]
                    requestPath = request.url.encodedPath
                    requestMethod = request.method
                    respond(
                        content =
                            """
                            {
                              "items": [
                                {
                                  "conversationId": "conversation-1",
                                  "kind": "direct",
                                  "origin": "grid",
                                  "participants": [
                                    {"userId": "user-1", "displayName": "Viewer", "online": false},
                                    {"userId": "target-1", "displayName": "Maya", "online": false}
                                  ],
                                  "lastMessage": {
                                    "messageId": "message-1",
                                    "conversationId": "conversation-1",
                                    "senderUserId": "target-1",
                                    "body": "See you tonight",
                                    "createdAt": "2026-04-24T18:00:00Z",
                                    "status": "visible",
                                    "deliveryStatus": "received"
                                  },
                                  "unreadCount": 1,
                                  "updatedAt": "2026-04-24T18:00:00Z"
                                }
                              ],
                              "nextCursor": null
                            }
                            """.trimIndent(),
                        status = HttpStatusCode.OK,
                        headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
                    )
                }
            val api = KtorPynapliApiClient(testConfig(), StaticAccessTokenProvider("test-token"), httpClient)

            val result = api.listConversations()

            val success = assertIs<AppResult.Success<*>>(result)
            assertEquals("Bearer test-token", authorizationHeader)
            assertEquals("/v1/messages/threads", requestPath)
            assertEquals(HttpMethod.Get, requestMethod)
            val response = success.value as ConversationListResponseDto
            assertEquals(
                "Maya",
                response.items
                    .first()
                    .participants
                    .last()
                    .displayName,
            )
            assertEquals(1, response.items.first().unreadCount)
        }

    @Test
    fun markConversationReadSendsBearerTokenAndMapsConversation() =
        runBlocking {
            var authorizationHeader: String? = null
            var requestPath: String? = null
            var requestMethod: HttpMethod? = null
            val httpClient =
                testHttpClient { request ->
                    authorizationHeader = request.headers[HttpHeaders.Authorization]
                    requestPath = request.url.encodedPath
                    requestMethod = request.method
                    respond(
                        content =
                            """
                            {
                              "conversationId": "conversation-1",
                              "kind": "direct",
                              "origin": "grid",
                              "participants": [
                                {"userId": "user-1", "displayName": "Viewer", "online": false},
                                {"userId": "target-1", "displayName": "Maya", "online": false}
                              ],
                              "lastMessage": null,
                              "unreadCount": 0,
                              "updatedAt": "2026-04-24T18:00:00Z"
                            }
                            """.trimIndent(),
                        status = HttpStatusCode.OK,
                        headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
                    )
                }
            val api = KtorPynapliApiClient(testConfig(), StaticAccessTokenProvider("test-token"), httpClient)

            val result = api.markConversationRead("conversation-1")

            val success = assertIs<AppResult.Success<*>>(result)
            assertEquals("Bearer test-token", authorizationHeader)
            assertEquals("/v1/messages/threads/conversation-1/read", requestPath)
            assertEquals(HttpMethod.Post, requestMethod)
            assertEquals(0, (success.value as ConversationDto).unreadCount)
        }

    @Test
    fun createMediaUploadSendsBearerTokenAndMapsUploadSlot() =
        runBlocking {
            var authorizationHeader: String? = null
            var requestPath: String? = null
            var requestMethod: HttpMethod? = null
            val httpClient =
                testHttpClient { request ->
                    authorizationHeader = request.headers[HttpHeaders.Authorization]
                    requestPath = request.url.encodedPath
                    requestMethod = request.method
                    respond(
                        content = mediaUploadSlotJson(),
                        status = HttpStatusCode.OK,
                        headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
                    )
                }
            val api = KtorPynapliApiClient(testConfig(), StaticAccessTokenProvider("test-token"), httpClient)

            val result =
                api.createMediaUpload(
                    CreateMediaUploadRequestDto(
                        albumKind = "private",
                        mediaType = "image",
                        contentType = "image/jpeg",
                        byteSize = 1024,
                    ),
                )

            val success = assertIs<AppResult.Success<*>>(result)
            assertEquals("Bearer test-token", authorizationHeader)
            assertEquals("/v1/media/uploads", requestPath)
            assertEquals(HttpMethod.Post, requestMethod)
            val slot = success.value as MediaUploadSlotDto
            assertEquals("asset-1", slot.mediaAsset.mediaAssetId)
            assertEquals("PUT", slot.uploadMethod)
        }

    @Test
    fun completeMediaUploadSendsBearerTokenAndMapsAlbumItem() =
        runBlocking {
            var authorizationHeader: String? = null
            var requestPath: String? = null
            var requestMethod: HttpMethod? = null
            val httpClient =
                testHttpClient { request ->
                    authorizationHeader = request.headers[HttpHeaders.Authorization]
                    requestPath = request.url.encodedPath
                    requestMethod = request.method
                    respond(
                        content = mediaAlbumItemJson(),
                        status = HttpStatusCode.OK,
                        headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
                    )
                }
            val api = KtorPynapliApiClient(testConfig(), StaticAccessTokenProvider("test-token"), httpClient)

            val result =
                api.completeMediaUpload(
                    "asset-1",
                    CompleteMediaUploadRequestDto(albumId = "album-1", sortOrder = 2),
                )

            val success = assertIs<AppResult.Success<*>>(result)
            assertEquals("Bearer test-token", authorizationHeader)
            assertEquals("/v1/media/uploads/asset-1/complete", requestPath)
            assertEquals(HttpMethod.Post, requestMethod)
            val item = success.value as MediaAlbumItemDto
            assertEquals("album-1", item.albumId)
            assertEquals(2, item.sortOrder)
        }

    private fun conversationDetailJson(nextCursor: String? = null): String =
        """
        {
          "conversation": {
            "conversationId": "conversation-1",
            "kind": "direct",
            "origin": "grid",
            "participants": [
              {"userId": "user-1", "displayName": "Viewer", "online": false},
              {"userId": "target-1", "displayName": "Maya", "online": false}
            ],
            "lastMessage": null,
            "unreadCount": 0,
            "updatedAt": "2026-04-24T18:00:00Z"
          },
          "messages": [],
          "nextCursor": ${nextCursor?.let { "\"$it\"" } ?: "null"}
        }
        """.trimIndent()

    private fun mediaUploadSlotJson(): String =
        """
        {
          "mediaAsset": {
            "mediaAssetId": "asset-1",
            "ownerUserId": "user-1",
            "mediaType": "image",
            "contentType": "image/jpeg",
            "byteSize": 1024,
            "pixelWidth": null,
            "pixelHeight": null,
            "moderationStatus": "pending",
            "processingStatus": "pending_upload",
            "createdAt": "2026-04-24T18:00:00Z",
            "updatedAt": "2026-04-24T18:00:00Z"
          },
          "album": {
            "albumId": "album-1",
            "ownerUserId": "user-1",
            "kind": "private",
            "title": "Private album",
            "visibilityStatus": "visible",
            "createdAt": "2026-04-24T18:00:00Z",
            "updatedAt": "2026-04-24T18:00:00Z"
          },
          "uploadUrl": "https://storage.googleapis.com/pynapli-local-media/users%2Fuser-1%2Fprivate%2Fasset-1.jpg",
          "uploadMethod": "PUT",
          "uploadHeaders": {"Content-Type": "image/jpeg"},
          "storageProvider": "gcs",
          "storageBucket": "pynapli-local-media",
          "storageObjectKey": "users/user-1/private/asset-1.jpg",
          "expiresAt": "2026-04-24T18:10:00Z"
        }
        """.trimIndent()

    private fun mediaAlbumItemJson(): String =
        """
        {
          "albumId": "album-1",
          "mediaAsset": {
            "mediaAssetId": "asset-1",
            "ownerUserId": "user-1",
            "mediaType": "image",
            "contentType": "image/jpeg",
            "byteSize": 1024,
            "pixelWidth": null,
            "pixelHeight": null,
            "moderationStatus": "pending",
            "processingStatus": "uploaded",
            "createdAt": "2026-04-24T18:00:00Z",
            "updatedAt": "2026-04-24T18:01:00Z"
          },
          "sortOrder": 2,
          "addedAt": "2026-04-24T18:01:00Z"
        }
        """.trimIndent()

    private fun meJson(
        displayName: String,
        gridVisible: Boolean = true,
        swipeVisible: Boolean = true,
        showDistance: Boolean = true,
        showInViewedLists: Boolean = true,
        allowGridMessages: Boolean = true,
        gridMessagePolicy: String = "everyone",
        allowFlirts: Boolean = true,
        allowIntroNotes: Boolean = true,
        allowAlbumRequests: Boolean = true,
    ): String =
        """
        {
          "user": {
            "userId": "user-1",
            "status": "active"
          },
          "profile": {
            "userId": "user-1",
            "displayName": "$displayName",
            "birthdate": "1990-01-15",
            "displayAge": 36,
            "gender": "man",
            "orientation": "bisexual",
            "relationshipContext": "single",
            "discoveryStyle": "both",
            "profileCompletenessScore": 100,
            "moderationStatus": "approved",
            "visibilityStatus": "visible"
          },
          "discoverySettings": {
            "gridVisible": $gridVisible,
            "swipeVisible": $swipeVisible,
            "eventsVisible": true,
            "showDistance": $showDistance,
            "showInViewedLists": $showInViewedLists,
            "incognito": false,
            "allowGridMessages": $allowGridMessages,
            "gridMessagePolicy": "$gridMessagePolicy",
            "allowFlirts": $allowFlirts,
            "allowIntroNotes": $allowIntroNotes,
            "allowAlbumRequests": $allowAlbumRequests,
            "soloEnabled": true,
            "togetherEnabled": true,
            "socialEnabled": true
          }
        }
        """.trimIndent()

    private fun testHttpClient(
        handler: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData = {
            respond("", HttpStatusCode.OK)
        },
    ): HttpClient =
        HttpClient(MockEngine(handler)) {
            expectSuccess = false
            install(ContentNegotiation) {
                json(PynapliJson)
            }
        }

    private fun testConfig(): PynapliConfig =
        PynapliConfig(
            environment = PynapliEnvironment.Local,
            apiBaseUrl = "http://localhost:8080",
            websocketBaseUrl = "ws://localhost:8080",
        )
}
