-- F2: Trust filter backing.
-- Verification timestamps live on the profile so a NULL timestamp means "not verified".
-- Filter logic is "verified at column IS NOT NULL"; setters/admin tooling come later.
ALTER TABLE profiles
    ADD COLUMN IF NOT EXISTS verified_at TIMESTAMPTZ,
    ADD COLUMN IF NOT EXISTS face_photo_verified_at TIMESTAMPTZ,
    ADD COLUMN IF NOT EXISTS age_verified_at TIMESTAMPTZ;

-- Partial indexes only over verified rows keep the filtered grid query cheap.
CREATE INDEX IF NOT EXISTS idx_profiles_verified ON profiles(verified_at) WHERE verified_at IS NOT NULL;
CREATE INDEX IF NOT EXISTS idx_profiles_face_photo_verified ON profiles(face_photo_verified_at) WHERE face_photo_verified_at IS NOT NULL;
CREATE INDEX IF NOT EXISTS idx_profiles_age_verified ON profiles(age_verified_at) WHERE age_verified_at IS NOT NULL;
