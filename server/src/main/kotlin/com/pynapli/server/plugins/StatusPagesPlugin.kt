package com.pynapli.server.plugins

import com.pynapli.server.activity.ActivityException
import com.pynapli.server.auth.AuthException
import com.pynapli.server.auth.authStatusFor
import com.pynapli.server.grid.GridException
import com.pynapli.server.grid.SavedGridFilterException
import com.pynapli.server.location.TravelLocationException
import com.pynapli.server.swipe.SavedSwipeFilterException
import com.pynapli.server.swipe.TopPicksException
import com.pynapli.server.profile.TonightAvailabilityException
import com.pynapli.server.location.LocationException
import com.pynapli.server.media.MediaException
import com.pynapli.server.messaging.MessagingException
import com.pynapli.server.moderation.ModerationException
import com.pynapli.server.notifications.NotificationException
import com.pynapli.server.profile.ProfileException
import com.pynapli.server.profilelink.ProfileLinkException
import com.pynapli.server.safety.SafetyException
import com.pynapli.server.swipe.SwipeException
import com.pynapli.server.subscriptions.SubscriptionException
import com.pynapli.shared.network.dto.ApiErrorDto
import com.pynapli.server.verification.VerificationException
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.call
import io.ktor.server.application.install
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.request.httpMethod
import io.ktor.server.request.uri
import io.ktor.server.response.respond
import org.slf4j.LoggerFactory

private val statusPagesLogger = LoggerFactory.getLogger("com.pynapli.server.plugins.StatusPages")

fun Application.configureStatusPages() {
    install(StatusPages) {
        exception<AuthException> { call, cause ->
            call.respond(
                status = authStatusFor(cause.code),
                message = ApiErrorDto(
                    code = cause.code,
                    message = userSafeMessage(cause.code),
                    requestId = call.request.headers["X-Request-Id"] ?: "unknown"
                )
            )
        }

        exception<ProfileException> { call, cause ->
            call.respond(
                status = profileStatusFor(cause.code),
                message = ApiErrorDto(
                    code = cause.code,
                    message = userSafeMessage(cause.code),
                    requestId = call.request.headers["X-Request-Id"] ?: "unknown"
                )
            )
        }

        exception<ProfileLinkException> { call, cause ->
            call.respond(
                status = profileLinkStatusFor(cause.code),
                message = ApiErrorDto(
                    code = cause.code,
                    message = userSafeMessage(cause.code),
                    requestId = call.request.headers["X-Request-Id"] ?: "unknown"
                )
            )
        }

        exception<LocationException> { call, cause ->
            call.respond(
                status = HttpStatusCode.BadRequest,
                message = ApiErrorDto(
                    code = cause.code,
                    message = userSafeMessage(cause.code),
                    requestId = call.request.headers["X-Request-Id"] ?: "unknown"
                )
            )
        }

        exception<GridException> { call, cause ->
            call.respond(
                status = gridStatusFor(cause.code),
                message = ApiErrorDto(
                    code = cause.code,
                    message = userSafeMessage(cause.code),
                    requestId = call.request.headers["X-Request-Id"] ?: "unknown"
                )
            )
        }

        exception<SavedGridFilterException> { call, cause ->
            call.respond(
                status = savedGridFilterStatusFor(cause.code),
                message = ApiErrorDto(
                    code = cause.code,
                    message = userSafeMessage(cause.code),
                    requestId = call.request.headers["X-Request-Id"] ?: "unknown"
                )
            )
        }

        exception<SavedSwipeFilterException> { call, cause ->
            call.respond(
                status = savedSwipeFilterStatusFor(cause.code),
                message = ApiErrorDto(
                    code = cause.code,
                    message = userSafeMessage(cause.code),
                    requestId = call.request.headers["X-Request-Id"] ?: "unknown"
                )
            )
        }

        exception<TopPicksException> { call, cause ->
            call.respond(
                status = topPicksStatusFor(cause.code),
                message = ApiErrorDto(
                    code = cause.code,
                    message = userSafeMessage(cause.code),
                    requestId = call.request.headers["X-Request-Id"] ?: "unknown"
                )
            )
        }

        exception<TravelLocationException> { call, cause ->
            call.respond(
                status = travelLocationStatusFor(cause.code),
                message = ApiErrorDto(
                    code = cause.code,
                    message = userSafeMessage(cause.code),
                    requestId = call.request.headers["X-Request-Id"] ?: "unknown"
                )
            )
        }

        exception<TonightAvailabilityException> { call, cause ->
            call.respond(
                status = tonightAvailabilityStatusFor(cause.code),
                message = ApiErrorDto(
                    code = cause.code,
                    message = userSafeMessage(cause.code),
                    requestId = call.request.headers["X-Request-Id"] ?: "unknown"
                )
            )
        }

        exception<ActivityException> { call, cause ->
            call.respond(
                status = activityStatusFor(cause.code),
                message = ApiErrorDto(
                    code = cause.code,
                    message = userSafeMessage(cause.code),
                    requestId = call.request.headers["X-Request-Id"] ?: "unknown"
                )
            )
        }

        exception<MessagingException> { call, cause ->
            call.respond(
                status = messagingStatusFor(cause.code),
                message = ApiErrorDto(
                    code = cause.code,
                    message = userSafeMessage(cause.code),
                    requestId = call.request.headers["X-Request-Id"] ?: "unknown"
                )
            )
        }

        exception<MediaException> { call, cause ->
            call.respond(
                status = mediaStatusFor(cause.code),
                message = ApiErrorDto(
                    code = cause.code,
                    message = userSafeMessage(cause.code),
                    requestId = call.request.headers["X-Request-Id"] ?: "unknown"
                )
            )
        }

        exception<SafetyException> { call, cause ->
            call.respond(
                status = safetyStatusFor(cause.code),
                message = ApiErrorDto(
                    code = cause.code,
                    message = userSafeMessage(cause.code),
                    requestId = call.request.headers["X-Request-Id"] ?: "unknown"
                )
            )
        }

        exception<SwipeException> { call, cause ->
            call.respond(
                status = swipeStatusFor(cause.code),
                message = ApiErrorDto(
                    code = cause.code,
                    message = userSafeMessage(cause.code),
                    requestId = call.request.headers["X-Request-Id"] ?: "unknown"
                )
            )
        }

        exception<NotificationException> { call, cause ->
            call.respond(
                status = notificationStatusFor(cause.code),
                message = ApiErrorDto(
                    code = cause.code,
                    message = userSafeMessage(cause.code),
                    requestId = call.request.headers["X-Request-Id"] ?: "unknown"
                )
            )
        }

        exception<SubscriptionException> { call, cause ->
            call.respond(
                status = subscriptionStatusFor(cause.code),
                message = ApiErrorDto(
                    code = cause.code,
                    message = userSafeMessage(cause.code),
                    requestId = call.request.headers["X-Request-Id"] ?: "unknown"
                )
            )
        }

        exception<ModerationException> { call, cause ->
            call.respond(
                status = moderationStatusFor(cause.code),
                message = ApiErrorDto(
                    code = cause.code,
                    message = userSafeMessage(cause.code),
                    requestId = call.request.headers["X-Request-Id"] ?: "unknown"
                )
            )
        }

        exception<VerificationException> { call, cause ->
            call.respond(
                status = verificationStatusFor(cause.code),
                message = ApiErrorDto(
                    code = cause.code,
                    message = userSafeMessage(cause.code),
                    requestId = call.request.headers["X-Request-Id"] ?: "unknown"
                )
            )
        }

        exception<Throwable> { call, cause ->
            statusPagesLogger.error(
                "Unhandled request failure: ${call.request.httpMethod.value} ${call.request.uri}",
                cause
            )
            call.respond(
                status = HttpStatusCode.InternalServerError,
                message = ApiErrorDto(
                    code = "INTERNAL_ERROR",
                    message = "Something went wrong.",
                    requestId = call.request.headers["X-Request-Id"] ?: "unknown"
                )
            )
        }
    }
}

private fun profileStatusFor(code: String): HttpStatusCode = when (code) {
    "PROFILE_NOT_FOUND" -> HttpStatusCode.NotFound
    "INVALID_DISPLAY_NAME",
    "INVALID_BIRTHDATE",
    "AGE_RESTRICTED",
    "INVALID_DISCOVERY_STYLE",
    "INVALID_PROFILE_FIELD" -> HttpStatusCode.BadRequest
    else -> HttpStatusCode.BadRequest
}

private fun profileLinkStatusFor(code: String): HttpStatusCode = when (code) {
    "PROFILE_LINK_INVITE_NOT_FOUND",
    "PROFILE_LINK_NOT_FOUND",
    "PROFILE_LINK_TARGET_NOT_FOUND" -> HttpStatusCode.NotFound
    "PROFILE_LINK_SELF_FORBIDDEN",
    "PROFILE_LINK_BLOCKED",
    "PROFILE_LINK_ALREADY_LINKED" -> HttpStatusCode.Forbidden
    "PROFILE_LINK_INVITE_EXISTS" -> HttpStatusCode.Conflict
    "PROFILE_LINK_COOLDOWN" -> HttpStatusCode.TooManyRequests
    "INVALID_PROFILE_LINK_TARGET" -> HttpStatusCode.BadRequest
    else -> HttpStatusCode.BadRequest
}

private fun gridStatusFor(code: String): HttpStatusCode = when (code) {
    "LOCATION_REQUIRED" -> HttpStatusCode.BadRequest
    "ENTITLEMENT_REQUIRED",
    "AGE_VERIFICATION_REQUIRED" -> HttpStatusCode.Forbidden
    "RATE_LIMITED" -> HttpStatusCode.TooManyRequests
    "INVALID_GRID_FILTER" -> HttpStatusCode.BadRequest
    "INVALID_GRID_CURSOR" -> HttpStatusCode.BadRequest
    else -> HttpStatusCode.BadRequest
}

private fun savedGridFilterStatusFor(code: String): HttpStatusCode = when (code) {
    "ENTITLEMENT_REQUIRED",
    "AGE_VERIFICATION_REQUIRED" -> HttpStatusCode.Forbidden
    "SAVED_FILTER_NAME_TAKEN" -> HttpStatusCode.Conflict
    "SAVED_FILTER_LIMIT_REACHED" -> HttpStatusCode.Conflict
    "INVALID_SAVED_FILTER_NAME" -> HttpStatusCode.BadRequest
    "INVALID_SAVED_FILTER_PAYLOAD" -> HttpStatusCode.BadRequest
    "INVALID_SAVED_FILTER_ID" -> HttpStatusCode.BadRequest
    else -> HttpStatusCode.BadRequest
}

private fun topPicksStatusFor(code: String): HttpStatusCode = when (code) {
    "LOCATION_REQUIRED" -> HttpStatusCode.UnprocessableEntity
    "STALE_TOP_PICK_SESSION" -> HttpStatusCode.Conflict
    else -> HttpStatusCode.BadRequest
}

private fun travelLocationStatusFor(code: String): HttpStatusCode = when (code) {
    "ENTITLEMENT_REQUIRED",
    "AGE_VERIFICATION_REQUIRED" -> HttpStatusCode.Forbidden
    "TRAVEL_PREVIEW_RATE_LIMITED" -> HttpStatusCode.TooManyRequests
    "INVALID_TRAVEL_CITY",
    "INVALID_TRAVEL_REGION",
    "INVALID_TRAVEL_COUNTRY",
    "INVALID_TRAVEL_COORDINATES" -> HttpStatusCode.BadRequest
    else -> HttpStatusCode.BadRequest
}

// Same code surface as `savedGridFilterStatusFor` today, kept as a parallel
// function so the two presets surfaces can diverge later without coupling them
// through a shared lookup table.
private fun savedSwipeFilterStatusFor(code: String): HttpStatusCode = when (code) {
    "ENTITLEMENT_REQUIRED",
    "AGE_VERIFICATION_REQUIRED" -> HttpStatusCode.Forbidden
    "SAVED_FILTER_NAME_TAKEN" -> HttpStatusCode.Conflict
    "SAVED_FILTER_LIMIT_REACHED" -> HttpStatusCode.Conflict
    "INVALID_SAVED_FILTER_NAME" -> HttpStatusCode.BadRequest
    "INVALID_SAVED_FILTER_PAYLOAD" -> HttpStatusCode.BadRequest
    "INVALID_SAVED_FILTER_ID" -> HttpStatusCode.BadRequest
    else -> HttpStatusCode.BadRequest
}

private fun tonightAvailabilityStatusFor(code: String): HttpStatusCode = when (code) {
    "INVALID_TONIGHT_DURATION" -> HttpStatusCode.BadRequest
    "PROFILE_NOT_FOUND" -> HttpStatusCode.NotFound
    else -> HttpStatusCode.BadRequest
}

private fun activityStatusFor(code: String): HttpStatusCode = when (code) {
    "RATE_LIMITED" -> HttpStatusCode.TooManyRequests
    "RISK_RESTRICTED" -> HttpStatusCode.Forbidden
    "ALBUM_REQUEST_NOT_FOUND",
    "ACTIVITY_EVENT_NOT_FOUND" -> HttpStatusCode.NotFound
    "FLIRT_NOT_ALLOWED",
    "ALBUM_REQUEST_NOT_ALLOWED" -> HttpStatusCode.Forbidden
    "INVALID_FLIRT_TARGET",
    "INVALID_ALBUM_REQUEST_TARGET",
    "INVALID_ACTIVITY_TAB",
    "INVALID_ACTIVITY_CURSOR" -> HttpStatusCode.BadRequest
    else -> HttpStatusCode.BadRequest
}

private fun messagingStatusFor(code: String): HttpStatusCode = when (code) {
    "RATE_LIMITED" -> HttpStatusCode.TooManyRequests
    "RISK_RESTRICTED" -> HttpStatusCode.Forbidden
    "CONVERSATION_NOT_FOUND",
    "LINKED_PARTNER_NOT_FOUND",
    "PARTNER_INVITE_NOT_FOUND" -> HttpStatusCode.NotFound
    "MESSAGING_NOT_ALLOWED" -> HttpStatusCode.Forbidden
    "PARTNER_INVITE_ALREADY_SENT",
    "PARTNER_ALREADY_IN_CONVERSATION" -> HttpStatusCode.Conflict
    "INVALID_ID",
    "INVALID_CONVERSATION_TARGET",
    "INVALID_CONVERSATION_ORIGIN",
    "INVALID_MESSAGE_CURSOR",
    "INVALID_MESSAGE_PAGE_LIMIT",
    "MESSAGE_BODY_EMPTY",
    "MESSAGE_BODY_TOO_LONG" -> HttpStatusCode.BadRequest
    else -> HttpStatusCode.BadRequest
}

private fun safetyStatusFor(code: String): HttpStatusCode = when (code) {
    "RATE_LIMITED" -> HttpStatusCode.TooManyRequests
    "BLOCK_SELF_FORBIDDEN",
    "REPORT_SELF_FORBIDDEN" -> HttpStatusCode.Forbidden
    else -> HttpStatusCode.BadRequest
}

private fun swipeStatusFor(code: String): HttpStatusCode = when (code) {
    "RATE_LIMITED" -> HttpStatusCode.TooManyRequests
    "RISK_RESTRICTED",
    "ENTITLEMENT_REQUIRED",
    "INTRO_NOTE_INVENTORY_REQUIRED",
    "PINEAPPLE_LIKE_INVENTORY_REQUIRED" -> HttpStatusCode.Forbidden
    "LOCATION_REQUIRED" -> HttpStatusCode.BadRequest
    "INVALID_SWIPE_TARGET",
    "INVALID_SWIPE_ACTION",
    "INVALID_SWIPE_ACTION_ID",
    "INVALID_SWIPE_FILTER",
    "INVALID_SWIPE_DECK_SESSION",
    "STALE_SWIPE_DECK_SESSION",
    "INVALID_SWIPE_CARD_TOKEN",
    "INVALID_SWIPE_CURSOR",
    "INTRO_NOTE_EMPTY",
    "INTRO_NOTE_TOO_LONG" -> HttpStatusCode.BadRequest
    else -> HttpStatusCode.BadRequest
}

private fun notificationStatusFor(code: String): HttpStatusCode = when (code) {
    "NOTIFICATION_PREFERENCES_NOT_FOUND" -> HttpStatusCode.NotFound
    "INVALID_NOTIFICATION_DETAIL_LEVEL",
    "INVALID_PUSH_PLATFORM",
    "INVALID_PUSH_TOKEN" -> HttpStatusCode.BadRequest
    else -> HttpStatusCode.BadRequest
}

private fun subscriptionStatusFor(code: String): HttpStatusCode = when (code) {
    "RTDN_UNAUTHENTICATED" -> HttpStatusCode.Unauthorized
    "RTDN_FORBIDDEN" -> HttpStatusCode.Forbidden
    "RTDN_VERIFICATION_UNAVAILABLE" -> HttpStatusCode.ServiceUnavailable
    "ADMIN_UNAUTHENTICATED" -> HttpStatusCode.Unauthorized
    "ADMIN_FORBIDDEN" -> HttpStatusCode.Forbidden
    "DEV_STORE_PURCHASES_DISABLED" -> HttpStatusCode.Forbidden
    "ENTITLEMENT_NOT_FOUND" -> HttpStatusCode.NotFound
    "BOOST_INVENTORY_REQUIRED" -> HttpStatusCode.Forbidden
    "PURCHASE_ALREADY_LINKED" -> HttpStatusCode.Conflict
    "INVALID_SUBSCRIPTION_PRODUCT",
    "INVALID_STORE_PRODUCT",
    "INVALID_PURCHASE_TOKEN",
    "INVALID_PURCHASE_PACKAGE",
    "INVALID_ENTITLEMENT_USER",
    "INVALID_ENTITLEMENT_TIER",
    "INVALID_ENTITLEMENT_SOURCE",
    "INVALID_ENTITLEMENT_PERIOD",
    "ADMIN_REASON_REQUIRED",
    "INVALID_RTDN_MESSAGE",
    "INVALID_ADMIN_ACTOR" -> HttpStatusCode.BadRequest
    else -> HttpStatusCode.BadRequest
}

private fun moderationStatusFor(code: String): HttpStatusCode = when (code) {
    "ADMIN_UNAUTHENTICATED" -> HttpStatusCode.Unauthorized
    "ADMIN_FORBIDDEN" -> HttpStatusCode.Forbidden
    "MESSAGE_REVIEW_NOT_FOUND",
    "REPORT_REVIEW_NOT_FOUND",
    "RISK_REVIEW_NOT_FOUND" -> HttpStatusCode.NotFound
    "INVALID_ID",
    "INVALID_ADMIN_ACTOR",
    "MODERATION_REASON_REQUIRED",
    "MODERATION_REASON_TOO_LONG",
    "INVALID_REVIEW_LIMIT",
    "INVALID_REPORT_REVIEW_STATUS",
    "INVALID_RISK_TIER",
    "INVALID_RISK_TIER_FILTER" -> HttpStatusCode.BadRequest
    else -> HttpStatusCode.BadRequest
}

private fun verificationStatusFor(code: String): HttpStatusCode = when (code) {
    "PROFILE_NOT_FOUND",
    "VERIFICATION_NOT_FOUND" -> HttpStatusCode.NotFound
    "AGE_RESTRICTED",
    "BIRTHDATE_REQUIRED",
    "ADULT_CONFIRMATION_REQUIRED",
    "INVALID_VERIFICATION_METHOD",
    "INVALID_VERIFICATION_STATUS",
    "INVALID_VERIFICATION_ID",
    "INVALID_VERIFICATION_CALLBACK",
    "VERIFICATION_REASON_TOO_LONG" -> HttpStatusCode.BadRequest
    else -> HttpStatusCode.BadRequest
}

private fun mediaStatusFor(code: String): HttpStatusCode = when (code) {
    "MEDIA_ASSET_NOT_FOUND",
    "MEDIA_ALBUM_NOT_FOUND",
    "MEDIA_GRANT_NOT_FOUND",
    "ALBUM_REQUEST_NOT_FOUND" -> HttpStatusCode.NotFound
    "INVALID_ID",
    "INVALID_ALBUM_REQUEST_TARGET",
    "INVALID_MEDIA_ALBUM_KIND",
    "INVALID_MEDIA_TYPE",
    "INVALID_MEDIA_CONTENT_TYPE",
    "INVALID_MEDIA_BYTE_SIZE",
    "MEDIA_FILE_TOO_LARGE",
    "INVALID_MEDIA_DIMENSIONS",
    "INVALID_MEDIA_SORT_ORDER" -> HttpStatusCode.BadRequest
    else -> HttpStatusCode.BadRequest
}
