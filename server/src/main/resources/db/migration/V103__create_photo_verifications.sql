-- V-1 selfie photo verification (TRUST_AND_SAFETY_SPEC "Photo verification").
--
-- A user asks to verify, the server issues a random pose challenge (valid 10 minutes), the user
-- uploads one selfie doing that pose, and the submission waits in an admin review queue. Approval
-- writes profiles.face_photo_verified_at and profiles.verified_at (read by the verified filters,
-- the verified badge and partner_visibility = 'verified_only').
--
-- The selfie is a media_assets row tagged intended_album_kind = 'verification'. It is never added to
-- an album and never reaches ready/approved, so no profile, album, Tonight or chat query can surface
-- it. Only the owner (upload URL) and admins (short-lived signed review URL) ever get a URL for it.
ALTER TABLE media_assets
    DROP CONSTRAINT IF EXISTS media_assets_intended_album_kind_check;

ALTER TABLE media_assets
    ADD CONSTRAINT media_assets_intended_album_kind_check
    CHECK (intended_album_kind IS NULL OR intended_album_kind IN ('profile', 'private', 'message', 'verification'));

CREATE TABLE IF NOT EXISTS photo_verifications (
    id UUID PRIMARY KEY,
    user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    pose_key TEXT NOT NULL,
    status TEXT NOT NULL,
    challenge_expires_at TIMESTAMPTZ NOT NULL,
    media_asset_id UUID REFERENCES media_assets(id) ON DELETE SET NULL,
    face_count INTEGER,
    submitted_at TIMESTAMPTZ,
    decided_at TIMESTAMPTZ,
    decision_source TEXT,
    reviewer_id TEXT,
    decision_reason TEXT,
    retry_available_at TIMESTAMPTZ,
    selfie_purged_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT photo_verifications_status_check
        CHECK (status IN ('challenged', 'pending', 'approved', 'rejected', 'expired', 'revoked')),
    CONSTRAINT photo_verifications_decision_source_check
        CHECK (decision_source IS NULL OR decision_source IN ('admin', 'face_check')),
    CONSTRAINT photo_verifications_reason_length_check
        CHECK (decision_reason IS NULL OR char_length(decision_reason) <= 500),
    CONSTRAINT photo_verifications_face_count_check
        CHECK (face_count IS NULL OR face_count >= 0)
);

-- At most one open attempt (challenge issued or awaiting review) per user.
CREATE UNIQUE INDEX IF NOT EXISTS idx_photo_verifications_one_open
ON photo_verifications(user_id)
WHERE status IN ('challenged', 'pending');

CREATE INDEX IF NOT EXISTS idx_photo_verifications_user_created
ON photo_verifications(user_id, created_at DESC);

-- Admin review queue, oldest submission first.
CREATE INDEX IF NOT EXISTS idx_photo_verifications_review_queue
ON photo_verifications(submitted_at, id)
WHERE status = 'pending';

-- Retention sweep: decided attempts whose selfie is still stored.
CREATE INDEX IF NOT EXISTS idx_photo_verifications_selfie_retention
ON photo_verifications(decided_at)
WHERE media_asset_id IS NOT NULL AND selfie_purged_at IS NULL AND decided_at IS NOT NULL;

CREATE UNIQUE INDEX IF NOT EXISTS idx_photo_verifications_media_asset
ON photo_verifications(media_asset_id)
WHERE media_asset_id IS NOT NULL;
