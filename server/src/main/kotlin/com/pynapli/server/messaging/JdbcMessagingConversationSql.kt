package com.pynapli.server.messaging

/**
 * Hides expired `user` messages from delivery while leaving the rows intact for moderation/legal
 * retention (MESSAGING_SPEC). The two bind params are the same effective expiry-seconds value (null
 * = no timer). System messages never expire from view, so the timer-change history stays legible.
 */
internal const val MESSAGE_EXPIRY_FILTER =
    "(? IS NULL OR message_type <> 'user' OR created_at > now() - make_interval(secs => ?))"

/**
 * SELECT columns for per-recipient media reveal. Correlated subqueries avoid `media_assets` vs
 * `messages` column-name clashes. The single bind param is the viewer; sender-sees-own is applied
 * in the DTO mapper. Place after the message columns, before delivery_status.
 */
internal const val MESSAGE_MEDIA_SELECT_COLUMNS =
    """
    media_asset_id,
    (SELECT ma.content_type FROM media_assets ma WHERE ma.id = messages.media_asset_id) AS media_content_type,
    (SELECT ma.pixel_width FROM media_assets ma WHERE ma.id = messages.media_asset_id) AS media_width,
    (SELECT ma.pixel_height FROM media_assets ma WHERE ma.id = messages.media_asset_id) AS media_height,
    EXISTS (
        SELECT 1 FROM message_media_reveals mr
        WHERE mr.message_id = messages.id AND mr.user_id = ?
    ) AS media_revealed,
    """

internal fun java.sql.ResultSet.enrichMessageMedia(base: MessageRecord): MessageRecord =
    base.copy(
        mediaContentType = getString("media_content_type"),
        mediaWidth = getInt("media_width").takeUnless { wasNull() },
        mediaHeight = getInt("media_height").takeUnless { wasNull() },
        mediaRevealedByViewer = getBoolean("media_revealed"),
    )
