package com.pynapli.server.plugins

import io.ktor.http.HttpStatusCode

internal fun accountLifecycleStatusFor(code: String): HttpStatusCode =
    when (code) {
        "ACCOUNT_ALREADY_DELETED" -> HttpStatusCode.Conflict
        "INVALID_ACCOUNT_DELETE_CONFIRMATION" -> HttpStatusCode.BadRequest
        else -> HttpStatusCode.BadRequest
    }

internal fun profileStatusFor(code: String): HttpStatusCode =
    when (code) {
        "PROFILE_NOT_FOUND" -> HttpStatusCode.NotFound
        "AGE_VERIFICATION_REQUIRED" -> HttpStatusCode.Forbidden
        "RATE_LIMITED" -> HttpStatusCode.TooManyRequests
        "INVALID_DISPLAY_NAME",
        "INVALID_BIRTHDATE",
        "AGE_RESTRICTED",
        "INVALID_DISCOVERY_STYLE",
        "INVALID_PROFILE_FIELD",
        "INVALID_GENDER",
        "INVALID_ORIENTATION",
        "INVALID_BIO",
        "INVALID_NEIGHBORHOOD",
        "INVALID_PROFILE_TAG",
        "BIO_REJECTED",
        "INVALID_PARTNER_VISIBILITY",
        "INVALID_GRID_MESSAGE_POLICY",
        -> HttpStatusCode.BadRequest
        else -> HttpStatusCode.BadRequest
    }

internal fun profileLinkStatusFor(code: String): HttpStatusCode =
    when (code) {
        "RISK_RESTRICTED" -> HttpStatusCode.Forbidden
        "PROFILE_LINK_INVITE_NOT_FOUND",
        "PROFILE_LINK_NOT_FOUND",
        "PROFILE_LINK_TARGET_NOT_FOUND",
        -> HttpStatusCode.NotFound
        "PROFILE_LINK_SELF_FORBIDDEN",
        "PROFILE_LINK_BLOCKED",
        "PROFILE_LINK_ALREADY_LINKED",
        -> HttpStatusCode.Forbidden
        "PROFILE_LINK_INVITE_EXISTS" -> HttpStatusCode.Conflict
        "PROFILE_LINK_COOLDOWN" -> HttpStatusCode.TooManyRequests
        "INVALID_PROFILE_LINK_TARGET" -> HttpStatusCode.BadRequest
        else -> HttpStatusCode.BadRequest
    }

internal fun gridStatusFor(code: String): HttpStatusCode =
    when (code) {
        "LOCATION_REQUIRED" -> HttpStatusCode.BadRequest
        "ENTITLEMENT_REQUIRED",
        "AGE_VERIFICATION_REQUIRED",
        -> HttpStatusCode.Forbidden
        "RATE_LIMITED" -> HttpStatusCode.TooManyRequests
        "INVALID_GRID_FILTER" -> HttpStatusCode.BadRequest
        "INVALID_GRID_CURSOR" -> HttpStatusCode.BadRequest
        else -> HttpStatusCode.BadRequest
    }

internal fun savedGridFilterStatusFor(code: String): HttpStatusCode =
    when (code) {
        "ENTITLEMENT_REQUIRED",
        "AGE_VERIFICATION_REQUIRED",
        -> HttpStatusCode.Forbidden
        "SAVED_FILTER_NAME_TAKEN" -> HttpStatusCode.Conflict
        "SAVED_FILTER_LIMIT_REACHED" -> HttpStatusCode.Conflict
        "INVALID_SAVED_FILTER_NAME" -> HttpStatusCode.BadRequest
        "INVALID_SAVED_FILTER_PAYLOAD" -> HttpStatusCode.BadRequest
        "INVALID_SAVED_FILTER_ID" -> HttpStatusCode.BadRequest
        else -> HttpStatusCode.BadRequest
    }

internal fun topPicksStatusFor(code: String): HttpStatusCode =
    when (code) {
        "LOCATION_REQUIRED" -> HttpStatusCode.UnprocessableEntity
        "STALE_TOP_PICK_SESSION" -> HttpStatusCode.Conflict
        else -> HttpStatusCode.BadRequest
    }

internal fun travelLocationStatusFor(code: String): HttpStatusCode =
    when (code) {
        "ENTITLEMENT_REQUIRED",
        "AGE_VERIFICATION_REQUIRED",
        -> HttpStatusCode.Forbidden
        "TRAVEL_PREVIEW_RATE_LIMITED",
        "RATE_LIMITED",
        -> HttpStatusCode.TooManyRequests
        "INVALID_TRAVEL_CITY",
        "INVALID_TRAVEL_REGION",
        "INVALID_TRAVEL_COUNTRY",
        "INVALID_TRAVEL_COORDINATES",
        "INVALID_TRAVEL_SEARCH_QUERY",
        -> HttpStatusCode.BadRequest
        else -> HttpStatusCode.BadRequest
    }

// Same code surface as `savedGridFilterStatusFor` today, kept as a parallel
// function so the two presets surfaces can diverge later without coupling them
// through a shared lookup table.
internal fun savedSwipeFilterStatusFor(code: String): HttpStatusCode =
    when (code) {
        "ENTITLEMENT_REQUIRED",
        "AGE_VERIFICATION_REQUIRED",
        -> HttpStatusCode.Forbidden
        "SAVED_FILTER_NAME_TAKEN" -> HttpStatusCode.Conflict
        "SAVED_FILTER_LIMIT_REACHED" -> HttpStatusCode.Conflict
        "INVALID_SAVED_FILTER_NAME" -> HttpStatusCode.BadRequest
        "INVALID_SAVED_FILTER_PAYLOAD" -> HttpStatusCode.BadRequest
        "INVALID_SAVED_FILTER_ID" -> HttpStatusCode.BadRequest
        else -> HttpStatusCode.BadRequest
    }

internal fun tonightAvailabilityStatusFor(code: String): HttpStatusCode =
    when (code) {
        "INVALID_TONIGHT_DURATION" -> HttpStatusCode.BadRequest
        "PROFILE_NOT_FOUND" -> HttpStatusCode.NotFound
        else -> HttpStatusCode.BadRequest
    }

internal fun tonightSessionStatusFor(code: String): HttpStatusCode =
    when (code) {
        "RISK_RESTRICTED" -> HttpStatusCode.Forbidden
        "INVALID_TONIGHT_MODE",
        "INVALID_TONIGHT_INTENT",
        "INVALID_TONIGHT_HOST_MODE",
        "INVALID_TONIGHT_DURATION",
        "TONIGHT_NOTE_TOO_LONG",
        "TONIGHT_INVALID_PHOTO",
        "TONIGHT_NO_LINKED_PARTNER",
        -> HttpStatusCode.BadRequest
        "TONIGHT_ALREADY_ACTIVE",
        "TONIGHT_NOT_PENDING",
        -> HttpStatusCode.Conflict
        "TONIGHT_WEEKLY_LIMIT_REACHED",
        "TONIGHT_NOT_PARTICIPANT",
        -> HttpStatusCode.Forbidden
        "TONIGHT_SESSION_NOT_FOUND" -> HttpStatusCode.NotFound
        else -> HttpStatusCode.BadRequest
    }

internal fun psyncStatusFor(code: String): HttpStatusCode =
    when (code) {
        "RISK_RESTRICTED" -> HttpStatusCode.Forbidden
        "PSYNC_NO_LINKED_PARTNER",
        "INVALID_PSYNC_DECK_SIZE",
        "PSYNC_INVALID_ACTION",
        "INVALID_PSYNC_FILTER",
        -> HttpStatusCode.BadRequest
        "PSYNC_SESSION_ALREADY_ACTIVE",
        "PSYNC_INVALID_STATE",
        "PSYNC_DECK_EMPTY",
        -> HttpStatusCode.Conflict
        "PSYNC_NUDGE_COOLDOWN" -> HttpStatusCode.TooManyRequests
        "PSYNC_NOT_PARTICIPANT",
        "PSYNC_DECK_SIZE_NOT_ALLOWED",
        "PSYNC_ADVANCED_FILTERS_NOT_ALLOWED",
        -> HttpStatusCode.Forbidden
        "PSYNC_SESSION_NOT_FOUND",
        "PSYNC_INVALID_CARD",
        -> HttpStatusCode.NotFound
        else -> HttpStatusCode.BadRequest
    }

internal fun activityStatusFor(code: String): HttpStatusCode =
    when (code) {
        "RATE_LIMITED" -> HttpStatusCode.TooManyRequests
        "RISK_RESTRICTED" -> HttpStatusCode.Forbidden
        "ALBUM_REQUEST_NOT_FOUND",
        "ACTIVITY_EVENT_NOT_FOUND",
        -> HttpStatusCode.NotFound
        "FLIRT_NOT_ALLOWED",
        "ALBUM_REQUEST_NOT_ALLOWED",
        -> HttpStatusCode.Forbidden
        "INVALID_FLIRT_TARGET",
        "INVALID_ALBUM_REQUEST_TARGET",
        "INVALID_ACTIVITY_TAB",
        "INVALID_ACTIVITY_CURSOR",
        -> HttpStatusCode.BadRequest
        else -> HttpStatusCode.BadRequest
    }

internal fun messagingStatusFor(code: String): HttpStatusCode =
    when (code) {
        "RATE_LIMITED" -> HttpStatusCode.TooManyRequests
        "RISK_RESTRICTED",
        "ENTITLEMENT_REQUIRED",
        -> HttpStatusCode.Forbidden
        "CONVERSATION_NOT_FOUND",
        "MESSAGE_NOT_FOUND",
        "LINKED_PARTNER_NOT_FOUND",
        "PARTNER_INVITE_NOT_FOUND",
        -> HttpStatusCode.NotFound
        "MESSAGING_NOT_ALLOWED" -> HttpStatusCode.Forbidden
        "MEDIA_NOT_AVAILABLE" -> HttpStatusCode.ServiceUnavailable
        "PARTNER_INVITE_ALREADY_SENT",
        "PARTNER_ALREADY_IN_CONVERSATION",
        -> HttpStatusCode.Conflict
        "INVALID_MESSAGE_EXPIRY",
        "INVALID_MEDIA",
        "INVALID_MEDIA_CONTENT_TYPE",
        "INVALID_MEDIA_BYTE_SIZE",
        "MEDIA_FILE_TOO_LARGE",
        "INVALID_MEDIA_DIMENSIONS",
        "INVALID_ID",
        "INVALID_CONVERSATION_TARGET",
        "INVALID_CONVERSATION_ORIGIN",
        "INVALID_MESSAGE_CURSOR",
        "INVALID_MESSAGE_PAGE_LIMIT",
        "MESSAGE_BODY_EMPTY",
        "MESSAGE_BODY_TOO_LONG",
        "INVALID_IDEMPOTENCY_KEY",
        -> HttpStatusCode.BadRequest
        else -> HttpStatusCode.BadRequest
    }

internal fun safetyStatusFor(code: String): HttpStatusCode =
    when (code) {
        "RATE_LIMITED" -> HttpStatusCode.TooManyRequests
        "APPEAL_NOT_FOUND",
        "APPEAL_REPORT_NOT_FOUND",
        -> HttpStatusCode.NotFound
        "APPEAL_ALREADY_OPEN" -> HttpStatusCode.Conflict
        "APPEAL_TARGET_NOT_APPEALABLE" -> HttpStatusCode.Forbidden
        "BLOCK_SELF_FORBIDDEN",
        "REPORT_SELF_FORBIDDEN",
        -> HttpStatusCode.Forbidden
        "INVALID_APPEAL_ID",
        "INVALID_APPEAL_ACTION",
        "INVALID_APPEAL_TARGET",
        "INVALID_APPEAL_REPORT",
        "APPEAL_TARGET_REQUIRED",
        "APPEAL_REASON_REQUIRED",
        "APPEAL_REASON_TOO_LONG",
        -> HttpStatusCode.BadRequest
        else -> HttpStatusCode.BadRequest
    }

internal fun swipeStatusFor(code: String): HttpStatusCode =
    when (code) {
        "RATE_LIMITED" -> HttpStatusCode.TooManyRequests
        "RISK_RESTRICTED",
        "ENTITLEMENT_REQUIRED",
        "INTRO_NOTE_INVENTORY_REQUIRED",
        "PINEAPPLE_LIKE_INVENTORY_REQUIRED",
        -> HttpStatusCode.Forbidden
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
        "INTRO_NOTE_TOO_LONG",
        -> HttpStatusCode.BadRequest
        else -> HttpStatusCode.BadRequest
    }

internal fun notificationStatusFor(code: String): HttpStatusCode =
    when (code) {
        "NOTIFICATION_PREFERENCES_NOT_FOUND" -> HttpStatusCode.NotFound
        "INVALID_NOTIFICATION_DETAIL_LEVEL",
        "INVALID_PUSH_PLATFORM",
        "INVALID_PUSH_TOKEN",
        -> HttpStatusCode.BadRequest
        else -> HttpStatusCode.BadRequest
    }

internal fun subscriptionStatusFor(code: String): HttpStatusCode =
    when (code) {
        "RATE_LIMITED" -> HttpStatusCode.TooManyRequests
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
        "INVALID_ADMIN_ACTOR",
        -> HttpStatusCode.BadRequest
        else -> HttpStatusCode.BadRequest
    }

internal fun moderationStatusFor(code: String): HttpStatusCode =
    when (code) {
        "ADMIN_UNAUTHENTICATED" -> HttpStatusCode.Unauthorized
        "ADMIN_FORBIDDEN",
        "ADMIN_ROLE_REQUIRED",
        -> HttpStatusCode.Forbidden
        "MESSAGE_REVIEW_NOT_FOUND",
        "REPORT_REVIEW_NOT_FOUND",
        "RISK_REVIEW_NOT_FOUND",
        "APPEAL_REVIEW_NOT_FOUND",
        "USER_NOT_FOUND",
        -> HttpStatusCode.NotFound
        "INVALID_ID",
        "INVALID_USER_ID",
        "INVALID_ADMIN_ACTOR",
        "MODERATION_REASON_REQUIRED",
        "MODERATION_REASON_TOO_LONG",
        "INVALID_REVIEW_LIMIT",
        "INVALID_REPORT_REVIEW_STATUS",
        "INVALID_APPEAL_REVIEW_STATUS",
        "INVALID_APPEAL_OUTCOME",
        "APPEAL_ADMIN_NOTE_TOO_LONG",
        "INVALID_RISK_TIER",
        "INVALID_RISK_TIER_FILTER",
        -> HttpStatusCode.BadRequest
        else -> HttpStatusCode.BadRequest
    }

internal fun verificationStatusFor(code: String): HttpStatusCode =
    when (code) {
        "RATE_LIMITED" -> HttpStatusCode.TooManyRequests
        "PROFILE_NOT_FOUND",
        "VERIFICATION_NOT_FOUND",
        -> HttpStatusCode.NotFound
        "AGE_RESTRICTED",
        "BIRTHDATE_REQUIRED",
        "ADULT_CONFIRMATION_REQUIRED",
        "INVALID_VERIFICATION_METHOD",
        "ENHANCED_VERIFICATION_REQUIRED",
        "INVALID_VERIFICATION_STATUS",
        "INVALID_VERIFICATION_ID",
        "INVALID_VERIFICATION_CALLBACK",
        "VERIFICATION_REASON_TOO_LONG",
        -> HttpStatusCode.BadRequest
        else -> HttpStatusCode.BadRequest
    }

internal fun mediaStatusFor(code: String): HttpStatusCode =
    when (code) {
        "RATE_LIMITED" -> HttpStatusCode.TooManyRequests
        "MEDIA_ASSET_NOT_FOUND",
        "MEDIA_ALBUM_NOT_FOUND",
        "MEDIA_GRANT_NOT_FOUND",
        "ALBUM_REQUEST_NOT_FOUND",
        -> HttpStatusCode.NotFound
        "INVALID_ID",
        "INVALID_ALBUM_REQUEST_TARGET",
        "INVALID_MEDIA_ALBUM_KIND",
        "INVALID_MEDIA_TYPE",
        "INVALID_MEDIA_CONTENT_TYPE",
        "INVALID_MEDIA_BYTE_SIZE",
        "MEDIA_FILE_TOO_LARGE",
        "INVALID_MEDIA_DIMENSIONS",
        "INVALID_MEDIA_SORT_ORDER",
        "MEDIA_ALBUM_PHOTO_CAP_EXCEEDED",
        "MEDIA_ALBUM_ORDER_MISMATCH",
        -> HttpStatusCode.BadRequest
        else -> HttpStatusCode.BadRequest
    }
