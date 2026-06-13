-- User Profile & Photos slice 4: structured gender/orientation write path.
-- profiles.gender / profiles.orientation now store a canonical taxonomy key
-- (ProfileTaxonomies); when the key is 'self_describe' the user's free-text label
-- lives in these companion columns. Self-described profiles are excluded from
-- structured filters by construction (the key never matches a structured filter
-- value), per PROFILE_SETTINGS_SPEC.
ALTER TABLE profiles
    ADD COLUMN IF NOT EXISTS gender_detail TEXT;

ALTER TABLE profiles
    ADD COLUMN IF NOT EXISTS orientation_detail TEXT;
