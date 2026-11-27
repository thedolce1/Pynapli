-- G2-2 ticket 1: server-made grid thumbnails (GRID_IMPLEMENTATION_SPEC "Image Loading",
-- TRUST_AND_SAFETY_SPEC "Photo pre-screening": thumbnails only from approved photos).
--
-- A thumbnail is a derived object stored next to the original at a deterministic key
-- (media/{assetId}/{variant}.jpg, see MediaThumbnails.kt). These columns record it:
--
--   thumbnail_variant     set when a thumbnail object MAY exist at the variant's key (written as a
--                         claim BEFORE the object is uploaded, so a crash mid-upload still leaves a
--                         row the reconciler can find and clean up). NULL = no object.
--   thumbnail_ready_at    set only after the object was written AND the asset was still an
--                         approved, live profile photo. The only column a read path trusts.
--   thumbnail_claimed_at  lease: one generator at a time per asset (upload hook vs. backfill).
--   thumbnail_attempts    transient failures so far; the backfill gives up after a few.
--   thumbnail_failed_at   terminal: the original can't be made into a thumbnail (an image format
--                         ImageIO can't decode, too many pixels). Surfaces keep the original.
--
-- Additive only. Existing rows start with no thumbnail; the backfill worker fills them in.

ALTER TABLE media_assets
    ADD COLUMN IF NOT EXISTS thumbnail_variant TEXT,
    ADD COLUMN IF NOT EXISTS thumbnail_ready_at TIMESTAMPTZ,
    ADD COLUMN IF NOT EXISTS thumbnail_claimed_at TIMESTAMPTZ,
    ADD COLUMN IF NOT EXISTS thumbnail_attempts INTEGER NOT NULL DEFAULT 0,
    ADD COLUMN IF NOT EXISTS thumbnail_failed_at TIMESTAMPTZ;

ALTER TABLE media_assets
    ADD CONSTRAINT media_assets_thumbnail_variant_check
        CHECK (thumbnail_variant IS NULL OR thumbnail_variant IN ('v1-thumb-480'));

-- Readiness needs a variant.
ALTER TABLE media_assets
    ADD CONSTRAINT media_assets_thumbnail_ready_needs_variant_check
        CHECK (thumbnail_ready_at IS NULL OR thumbnail_variant IS NOT NULL);

-- Backfill scan: approved, live profile images that still need a thumbnail, oldest first.
CREATE INDEX IF NOT EXISTS idx_media_assets_thumbnail_backfill
ON media_assets(created_at, id)
WHERE thumbnail_ready_at IS NULL
  AND thumbnail_failed_at IS NULL
  AND deleted_at IS NULL
  AND media_type = 'image'
  AND processing_status = 'ready'
  AND moderation_status = 'approved'
  AND intended_album_kind = 'profile';

-- Reconciler scan: every asset that has (or may have) a thumbnail object, walked by id.
CREATE INDEX IF NOT EXISTS idx_media_assets_thumbnail_present
ON media_assets(id)
WHERE thumbnail_variant IS NOT NULL;
