package com.pynapli.server.plugins

import com.pynapli.server.activity.ActivityException
import com.pynapli.server.auth.AuthException
import com.pynapli.server.auth.authStatusFor
import com.pynapli.server.grid.GridException
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

private fun userSafeMessage(code: String): String = when (code) {
    "EMAIL_ALREADY_EXISTS" -> "An account already exists for this email."
    "INVALID_CREDENTIALS" -> "Invalid email or password."
    "INVALID_REFRESH_TOKEN", "UNAUTHENTICATED" -> "Please sign in again."
    "ACCOUNT_NOT_ACTIVE" -> "This account is not active."
    "INVALID_EMAIL" -> "Enter a valid email address."
    "PASSWORD_TOO_SHORT" -> "Password must be at least 12 characters."
    "PROFILE_NOT_FOUND" -> "Profile was not found."
    "INVALID_DISPLAY_NAME" -> "Display name must be 2 to 32 characters."
    "INVALID_BIRTHDATE" -> "Enter a valid birthdate."
    "AGE_RESTRICTED" -> "You must be 18 or older to use Pynapli."
    "INVALID_DISCOVERY_STYLE" -> "Choose a valid discovery style."
    "INVALID_PROFILE_FIELD" -> "Profile field is not valid."
    "INVALID_PROFILE_LINK_TARGET" -> "Request could not be completed."
    "PROFILE_LINK_SELF_FORBIDDEN" -> "You cannot link your own profile."
    "PROFILE_LINK_TARGET_NOT_FOUND" -> "Profile could not be linked."
    "PROFILE_LINK_BLOCKED" -> "Profile could not be linked."
    "PROFILE_LINK_ALREADY_LINKED" -> "One of these profiles is already linked."
    "PROFILE_LINK_INVITE_EXISTS" -> "A link invite already exists."
    "PROFILE_LINK_INVITE_NOT_FOUND" -> "Link invite was not found."
    "PROFILE_LINK_NOT_FOUND" -> "Linked profile was not found."
    "PROFILE_LINK_COOLDOWN" -> "Partner linking is temporarily limited after a recent unlink."
    "INVALID_LATITUDE" -> "Latitude is not valid."
    "INVALID_LONGITUDE" -> "Longitude is not valid."
    "INVALID_ACCURACY" -> "Location accuracy is not valid."
    "INVALID_LOCATION_SOURCE" -> "Location source is not valid."
    "INVALID_LOCATION_LABEL" -> "Location label is not valid."
    "LOCATION_REQUIRED" -> "Location is required before loading the grid."
    "ENTITLEMENT_REQUIRED" -> "Upgrade is required for this feature."
    "INVALID_GRID_FILTER" -> "Grid filters are not valid."
    "INVALID_GRID_CURSOR" -> "Grid position is no longer valid."
    "INVALID_FLIRT_TARGET" -> "Spark could not be sent."
    "FLIRT_NOT_ALLOWED" -> "This profile is not accepting Sparks."
    "INVALID_ALBUM_REQUEST_TARGET" -> "Album request could not be sent."
    "ALBUM_REQUEST_NOT_ALLOWED" -> "This profile is not accepting album requests."
    "ALBUM_REQUEST_NOT_FOUND" -> "Album request was not found."
    "INVALID_ID", "INVALID_CONVERSATION_TARGET", "INVALID_CONVERSATION_ORIGIN" -> "Request could not be completed."
    "MESSAGING_NOT_ALLOWED" -> "This profile is not accepting grid messages."
    "CONVERSATION_NOT_FOUND" -> "Conversation was not found."
    "LINKED_PARTNER_NOT_FOUND" -> "No linked partner is available."
    "PARTNER_INVITE_NOT_FOUND" -> "Chat invite was not found."
    "PARTNER_INVITE_ALREADY_SENT" -> "Partner invite was already sent."
    "PARTNER_ALREADY_IN_CONVERSATION" -> "Partner is already in this chat."
    "INVALID_MESSAGE_CURSOR", "INVALID_MESSAGE_PAGE_LIMIT" -> "Message history could not be loaded."
    "MESSAGE_BODY_EMPTY" -> "Enter a message."
    "MESSAGE_BODY_TOO_LONG" -> "Message is too long."
    "INVALID_MEDIA_ALBUM_KIND" -> "Choose a valid album."
    "INVALID_MEDIA_TYPE" -> "Choose a valid media type."
    "INVALID_MEDIA_CONTENT_TYPE" -> "This file type is not supported."
    "INVALID_MEDIA_BYTE_SIZE" -> "File size is not valid."
    "MEDIA_FILE_TOO_LARGE" -> "File is too large."
    "INVALID_MEDIA_DIMENSIONS" -> "Media dimensions are not valid."
    "INVALID_MEDIA_SORT_ORDER" -> "Media order is not valid."
    "MEDIA_ASSET_NOT_FOUND" -> "Media item was not found."
    "MEDIA_ALBUM_NOT_FOUND" -> "Album was not found."
    "MEDIA_GRANT_NOT_FOUND" -> "Album access was not found."
    "INVALID_SWIPE_TARGET" -> "That profile could not be swiped."
    "INVALID_SWIPE_ACTION" -> "Choose a valid swipe action."
    "INVALID_SWIPE_FILTER" -> "Swipe filters are not valid."
    "INTRO_NOTE_EMPTY" -> "Enter an Intro Note."
    "INTRO_NOTE_TOO_LONG" -> "Intro Note is too long."
    "INTRO_NOTE_INVENTORY_REQUIRED" -> "You do not have an Intro Note available."
    "PINEAPPLE_LIKE_INVENTORY_REQUIRED" -> "You do not have a Pineapple Like available."
    "BOOST_INVENTORY_REQUIRED" -> "You do not have a Boost available."
    "INVALID_NOTIFICATION_DETAIL_LEVEL" -> "Choose a valid notification detail level."
    "INVALID_PUSH_PLATFORM", "INVALID_PUSH_TOKEN" -> "Push device could not be registered."
    "NOTIFICATION_PREFERENCES_NOT_FOUND" -> "Notification settings were not found."
    "INVALID_SUBSCRIPTION_PRODUCT" -> "Choose a valid subscription."
    "INVALID_PURCHASE_TOKEN", "INVALID_PURCHASE_PACKAGE" -> "Purchase could not be verified."
    "PURCHASE_ALREADY_LINKED" -> "This purchase is already linked to another account."
    "INVALID_ENTITLEMENT_USER" -> "User entitlement could not be updated."
    "INVALID_ENTITLEMENT_TIER" -> "Choose a valid entitlement tier."
    "INVALID_ENTITLEMENT_SOURCE" -> "Choose a valid entitlement source."
    "INVALID_ENTITLEMENT_PERIOD" -> "Choose a valid entitlement period."
    "ENTITLEMENT_NOT_FOUND" -> "Entitlement was not found."
    "ADMIN_REASON_REQUIRED" -> "Admin reason is required."
    "INVALID_RTDN_MESSAGE" -> "Notification payload is not valid."
    "RTDN_UNAUTHENTICATED" -> "Webhook authentication is required."
    "RTDN_FORBIDDEN" -> "Webhook access was denied."
    "RTDN_VERIFICATION_UNAVAILABLE" -> "Purchase verification is temporarily unavailable."
    "INVALID_BLOCK_TARGET" -> "That user could not be blocked."
    "BLOCK_SELF_FORBIDDEN" -> "You cannot block yourself."
    "BLOCK_REASON_TOO_LONG" -> "Block reason is too long."
    "INVALID_REPORT_TARGET" -> "That target could not be reported."
    "INVALID_REPORT_CATEGORY" -> "Choose a valid report reason."
    "REPORT_SELF_FORBIDDEN" -> "You cannot report yourself."
    "REPORT_NOTE_TOO_LONG" -> "Report note is too long."
    "RATE_LIMITED" -> "Too many actions. Try again later."
    "RISK_RESTRICTED" -> "This action is unavailable right now."
    "ADMIN_UNAUTHENTICATED" -> "Admin authentication is required."
    "ADMIN_FORBIDDEN" -> "Admin access was denied."
    "INVALID_ADMIN_ACTOR" -> "Admin actor is required."
    "MODERATION_REASON_REQUIRED" -> "Moderation reason is required."
    "MODERATION_REASON_TOO_LONG" -> "Moderation reason is too long."
    "INVALID_REVIEW_LIMIT" -> "Review queue limit is not valid."
    "MESSAGE_REVIEW_NOT_FOUND" -> "Message review item was not found."
    "REPORT_REVIEW_NOT_FOUND" -> "Report review item was not found."
    "INVALID_REPORT_REVIEW_STATUS" -> "Report review status is not valid."
    "RISK_REVIEW_NOT_FOUND" -> "Risk review item was not found."
    "INVALID_RISK_TIER" -> "Risk tier is not valid."
    "INVALID_RISK_TIER_FILTER" -> "Risk tier filter is not valid."
    else -> "Request could not be completed."
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
    "ENTITLEMENT_REQUIRED" -> HttpStatusCode.Forbidden
    "INVALID_GRID_FILTER" -> HttpStatusCode.BadRequest
    "INVALID_GRID_CURSOR" -> HttpStatusCode.BadRequest
    else -> HttpStatusCode.BadRequest
}

private fun activityStatusFor(code: String): HttpStatusCode = when (code) {
    "RATE_LIMITED" -> HttpStatusCode.TooManyRequests
    "RISK_RESTRICTED" -> HttpStatusCode.Forbidden
    "ALBUM_REQUEST_NOT_FOUND" -> HttpStatusCode.NotFound
    "FLIRT_NOT_ALLOWED",
    "ALBUM_REQUEST_NOT_ALLOWED" -> HttpStatusCode.Forbidden
    "INVALID_FLIRT_TARGET",
    "INVALID_ALBUM_REQUEST_TARGET" -> HttpStatusCode.BadRequest
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
    "INVALID_SWIPE_FILTER",
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
    "ENTITLEMENT_NOT_FOUND" -> HttpStatusCode.NotFound
    "BOOST_INVENTORY_REQUIRED" -> HttpStatusCode.Forbidden
    "PURCHASE_ALREADY_LINKED" -> HttpStatusCode.Conflict
    "INVALID_SUBSCRIPTION_PRODUCT",
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
