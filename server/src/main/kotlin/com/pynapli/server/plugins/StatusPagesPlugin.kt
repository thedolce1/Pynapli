package com.pynapli.server.plugins

import com.pynapli.server.account.AccountLifecycleException
import com.pynapli.server.activity.ActivityException
import com.pynapli.server.auth.AuthException
import com.pynapli.server.auth.authStatusFor
import com.pynapli.server.grid.GridException
import com.pynapli.server.grid.SavedGridFilterException
import com.pynapli.server.location.LocationException
import com.pynapli.server.location.TravelLocationException
import com.pynapli.server.media.MediaException
import com.pynapli.server.messaging.MessagingException
import com.pynapli.server.moderation.ModerationException
import com.pynapli.server.notifications.NotificationException
import com.pynapli.server.profile.ProfileException
import com.pynapli.server.profile.TonightAvailabilityException
import com.pynapli.server.profilelink.ProfileLinkException
import com.pynapli.server.safety.SafetyException
import com.pynapli.server.subscriptions.SubscriptionException
import com.pynapli.server.swipe.SavedSwipeFilterException
import com.pynapli.server.swipe.SwipeException
import com.pynapli.server.swipe.TopPicksException
import com.pynapli.server.tonight.TonightSessionException
import com.pynapli.server.verification.VerificationException
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
                message =
                    ApiErrorDto(
                        code = cause.code,
                        message = userSafeMessage(cause.code),
                        requestId = call.request.headers["X-Request-Id"] ?: "unknown",
                    ),
            )
        }

        exception<AccountLifecycleException> { call, cause ->
            call.respond(
                status = accountLifecycleStatusFor(cause.code),
                message =
                    ApiErrorDto(
                        code = cause.code,
                        message = userSafeMessage(cause.code),
                        requestId = call.request.headers["X-Request-Id"] ?: "unknown",
                    ),
            )
        }

        exception<ProfileException> { call, cause ->
            call.respond(
                status = profileStatusFor(cause.code),
                message =
                    ApiErrorDto(
                        code = cause.code,
                        message = userSafeMessage(cause.code),
                        requestId = call.request.headers["X-Request-Id"] ?: "unknown",
                    ),
            )
        }

        exception<ProfileLinkException> { call, cause ->
            call.respond(
                status = profileLinkStatusFor(cause.code),
                message =
                    ApiErrorDto(
                        code = cause.code,
                        message = userSafeMessage(cause.code),
                        requestId = call.request.headers["X-Request-Id"] ?: "unknown",
                    ),
            )
        }

        exception<LocationException> { call, cause ->
            call.respond(
                status = HttpStatusCode.BadRequest,
                message =
                    ApiErrorDto(
                        code = cause.code,
                        message = userSafeMessage(cause.code),
                        requestId = call.request.headers["X-Request-Id"] ?: "unknown",
                    ),
            )
        }

        exception<GridException> { call, cause ->
            call.respond(
                status = gridStatusFor(cause.code),
                message =
                    ApiErrorDto(
                        code = cause.code,
                        message = userSafeMessage(cause.code),
                        requestId = call.request.headers["X-Request-Id"] ?: "unknown",
                    ),
            )
        }

        exception<SavedGridFilterException> { call, cause ->
            call.respond(
                status = savedGridFilterStatusFor(cause.code),
                message =
                    ApiErrorDto(
                        code = cause.code,
                        message = userSafeMessage(cause.code),
                        requestId = call.request.headers["X-Request-Id"] ?: "unknown",
                    ),
            )
        }

        exception<SavedSwipeFilterException> { call, cause ->
            call.respond(
                status = savedSwipeFilterStatusFor(cause.code),
                message =
                    ApiErrorDto(
                        code = cause.code,
                        message = userSafeMessage(cause.code),
                        requestId = call.request.headers["X-Request-Id"] ?: "unknown",
                    ),
            )
        }

        exception<TopPicksException> { call, cause ->
            call.respond(
                status = topPicksStatusFor(cause.code),
                message =
                    ApiErrorDto(
                        code = cause.code,
                        message = userSafeMessage(cause.code),
                        requestId = call.request.headers["X-Request-Id"] ?: "unknown",
                    ),
            )
        }

        exception<TravelLocationException> { call, cause ->
            call.respond(
                status = travelLocationStatusFor(cause.code),
                message =
                    ApiErrorDto(
                        code = cause.code,
                        message = userSafeMessage(cause.code),
                        requestId = call.request.headers["X-Request-Id"] ?: "unknown",
                    ),
            )
        }

        exception<TonightAvailabilityException> { call, cause ->
            call.respond(
                status = tonightAvailabilityStatusFor(cause.code),
                message =
                    ApiErrorDto(
                        code = cause.code,
                        message = userSafeMessage(cause.code),
                        requestId = call.request.headers["X-Request-Id"] ?: "unknown",
                    ),
            )
        }

        exception<TonightSessionException> { call, cause ->
            call.respond(
                status = tonightSessionStatusFor(cause.code),
                message =
                    ApiErrorDto(
                        code = cause.code,
                        message = userSafeMessage(cause.code),
                        requestId = call.request.headers["X-Request-Id"] ?: "unknown",
                    ),
            )
        }

        exception<ActivityException> { call, cause ->
            call.respond(
                status = activityStatusFor(cause.code),
                message =
                    ApiErrorDto(
                        code = cause.code,
                        message = userSafeMessage(cause.code),
                        requestId = call.request.headers["X-Request-Id"] ?: "unknown",
                    ),
            )
        }

        exception<MessagingException> { call, cause ->
            call.respond(
                status = messagingStatusFor(cause.code),
                message =
                    ApiErrorDto(
                        code = cause.code,
                        message = userSafeMessage(cause.code),
                        requestId = call.request.headers["X-Request-Id"] ?: "unknown",
                    ),
            )
        }

        exception<MediaException> { call, cause ->
            call.respond(
                status = mediaStatusFor(cause.code),
                message =
                    ApiErrorDto(
                        code = cause.code,
                        message = userSafeMessage(cause.code),
                        requestId = call.request.headers["X-Request-Id"] ?: "unknown",
                    ),
            )
        }

        exception<SafetyException> { call, cause ->
            call.respond(
                status = safetyStatusFor(cause.code),
                message =
                    ApiErrorDto(
                        code = cause.code,
                        message = userSafeMessage(cause.code),
                        requestId = call.request.headers["X-Request-Id"] ?: "unknown",
                    ),
            )
        }

        exception<SwipeException> { call, cause ->
            call.respond(
                status = swipeStatusFor(cause.code),
                message =
                    ApiErrorDto(
                        code = cause.code,
                        message = userSafeMessage(cause.code),
                        requestId = call.request.headers["X-Request-Id"] ?: "unknown",
                    ),
            )
        }

        exception<NotificationException> { call, cause ->
            call.respond(
                status = notificationStatusFor(cause.code),
                message =
                    ApiErrorDto(
                        code = cause.code,
                        message = userSafeMessage(cause.code),
                        requestId = call.request.headers["X-Request-Id"] ?: "unknown",
                    ),
            )
        }

        exception<SubscriptionException> { call, cause ->
            call.respond(
                status = subscriptionStatusFor(cause.code),
                message =
                    ApiErrorDto(
                        code = cause.code,
                        message = userSafeMessage(cause.code),
                        requestId = call.request.headers["X-Request-Id"] ?: "unknown",
                    ),
            )
        }

        exception<ModerationException> { call, cause ->
            call.respond(
                status = moderationStatusFor(cause.code),
                message =
                    ApiErrorDto(
                        code = cause.code,
                        message = userSafeMessage(cause.code),
                        requestId = call.request.headers["X-Request-Id"] ?: "unknown",
                    ),
            )
        }

        exception<VerificationException> { call, cause ->
            call.respond(
                status = verificationStatusFor(cause.code),
                message =
                    ApiErrorDto(
                        code = cause.code,
                        message = userSafeMessage(cause.code),
                        requestId = call.request.headers["X-Request-Id"] ?: "unknown",
                    ),
            )
        }

        exception<Throwable> { call, cause ->
            statusPagesLogger.error(
                "Unhandled request failure: ${call.request.httpMethod.value} ${call.request.uri}",
                cause,
            )
            call.respond(
                status = HttpStatusCode.InternalServerError,
                message =
                    ApiErrorDto(
                        code = "INTERNAL_ERROR",
                        message = "Something went wrong.",
                        requestId = call.request.headers["X-Request-Id"] ?: "unknown",
                    ),
            )
        }
    }
}
