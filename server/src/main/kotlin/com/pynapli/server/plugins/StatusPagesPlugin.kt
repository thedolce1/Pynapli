package com.pynapli.server.plugins

import com.pynapli.server.activity.ActivityException
import com.pynapli.server.auth.AuthException
import com.pynapli.server.auth.authStatusFor
import com.pynapli.server.grid.GridException
import com.pynapli.server.location.LocationException
import com.pynapli.server.media.MediaException
import com.pynapli.server.messaging.MessagingException
import com.pynapli.server.profile.ProfileException
import com.pynapli.shared.network.dto.ApiErrorDto
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.call
import io.ktor.server.application.install
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.response.respond

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

        exception<Throwable> { call, _ ->
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
    "INVALID_LATITUDE" -> "Latitude is not valid."
    "INVALID_LONGITUDE" -> "Longitude is not valid."
    "INVALID_ACCURACY" -> "Location accuracy is not valid."
    "INVALID_LOCATION_SOURCE" -> "Location source is not valid."
    "INVALID_LOCATION_LABEL" -> "Location label is not valid."
    "LOCATION_REQUIRED" -> "Location is required before loading the grid."
    "INVALID_FLIRT_TARGET" -> "Spark could not be sent."
    "FLIRT_NOT_ALLOWED" -> "This profile is not accepting Sparks."
    "INVALID_ALBUM_REQUEST_TARGET" -> "Album request could not be sent."
    "ALBUM_REQUEST_NOT_ALLOWED" -> "This profile is not accepting album requests."
    "ALBUM_REQUEST_NOT_FOUND" -> "Album request was not found."
    "INVALID_ID", "INVALID_CONVERSATION_TARGET", "INVALID_CONVERSATION_ORIGIN" -> "Request could not be completed."
    "MESSAGING_NOT_ALLOWED" -> "This profile is not accepting grid messages."
    "CONVERSATION_NOT_FOUND" -> "Conversation was not found."
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

private fun gridStatusFor(code: String): HttpStatusCode = when (code) {
    "LOCATION_REQUIRED" -> HttpStatusCode.BadRequest
    else -> HttpStatusCode.BadRequest
}

private fun activityStatusFor(code: String): HttpStatusCode = when (code) {
    "ALBUM_REQUEST_NOT_FOUND" -> HttpStatusCode.NotFound
    "FLIRT_NOT_ALLOWED",
    "ALBUM_REQUEST_NOT_ALLOWED" -> HttpStatusCode.Forbidden
    "INVALID_FLIRT_TARGET",
    "INVALID_ALBUM_REQUEST_TARGET" -> HttpStatusCode.BadRequest
    else -> HttpStatusCode.BadRequest
}

private fun messagingStatusFor(code: String): HttpStatusCode = when (code) {
    "CONVERSATION_NOT_FOUND" -> HttpStatusCode.NotFound
    "MESSAGING_NOT_ALLOWED" -> HttpStatusCode.Forbidden
    "INVALID_ID",
    "INVALID_CONVERSATION_TARGET",
    "INVALID_CONVERSATION_ORIGIN",
    "INVALID_MESSAGE_CURSOR",
    "INVALID_MESSAGE_PAGE_LIMIT",
    "MESSAGE_BODY_EMPTY",
    "MESSAGE_BODY_TOO_LONG" -> HttpStatusCode.BadRequest
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
