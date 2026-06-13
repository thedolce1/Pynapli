-- Messaging slice: per-recipient consent-tap media reveal (MESSAGING_SPEC "Media And Private
-- Albums"). A message can carry one image asset; recipients reveal it independently. Sending a
-- photo is NOT album access — the asset lives outside any album.
ALTER TABLE messages
    ADD COLUMN IF NOT EXISTS media_asset_id UUID REFERENCES media_assets(id);

CREATE INDEX IF NOT EXISTS idx_messages_media_asset
    ON messages(media_asset_id)
    WHERE media_asset_id IS NOT NULL;

-- A media message may carry an empty caption; relax the 1..2000 body rule to allow an empty body
-- only when media is attached. Text messages still require 1..2000 characters.
ALTER TABLE messages
    DROP CONSTRAINT IF EXISTS messages_body_length_check;

ALTER TABLE messages
    ADD CONSTRAINT messages_body_length_check
    CHECK (
        char_length(body) <= 2000
        AND (char_length(body) >= 1 OR media_asset_id IS NOT NULL)
    );

-- Chat photos are tagged intended_album_kind='message' (one-off, never an album asset); widen the
-- V72 allow-list to admit the new kind.
ALTER TABLE media_assets
    DROP CONSTRAINT IF EXISTS media_assets_intended_album_kind_check;

ALTER TABLE media_assets
    ADD CONSTRAINT media_assets_intended_album_kind_check
    CHECK (intended_album_kind IS NULL OR intended_album_kind IN ('profile', 'private', 'message'));

-- Per-recipient reveal ledger: a row means that user has consent-tapped to load the full media.
CREATE TABLE IF NOT EXISTS message_media_reveals (
    message_id UUID NOT NULL REFERENCES messages(id) ON DELETE CASCADE,
    user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    revealed_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (message_id, user_id)
);
