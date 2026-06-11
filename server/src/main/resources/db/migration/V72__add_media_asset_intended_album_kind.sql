-- Pending upload slots are created before the asset joins an album, so the album kind the
-- slot was opened for must be recorded on the asset to enforce per-album photo caps at
-- slot-creation time (otherwise a user could open unlimited slots for a full album).
ALTER TABLE media_assets
    ADD COLUMN IF NOT EXISTS intended_album_kind TEXT;

ALTER TABLE media_assets
    ADD CONSTRAINT media_assets_intended_album_kind_check
    CHECK (intended_album_kind IS NULL OR intended_album_kind IN ('profile', 'private'));

CREATE INDEX IF NOT EXISTS idx_media_assets_pending_by_album_kind
ON media_assets(owner_user_id, intended_album_kind)
WHERE deleted_at IS NULL AND processing_status = 'pending_upload';
