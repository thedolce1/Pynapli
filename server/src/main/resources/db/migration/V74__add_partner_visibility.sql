-- Partner Linking slice: owner-controlled partner visibility (PROFILE_SETTINGS_SPEC
-- "Partner Visibility Options"). Governs whether a profile's linked-partner mini is
-- exposed to a given viewer across the public profile, grid, and swipe surfaces:
--   public        - shown to everyone who can see the profile (default; prior behavior)
--   matches_only  - shown only to viewers the owner has matched with
--   verified_only - shown only to verified viewers (profiles.verified_at IS NOT NULL)
--   hidden        - never shown
-- The partner's own block/risk/incognito/visibility gates still apply on top of this.
ALTER TABLE discovery_settings
    ADD COLUMN IF NOT EXISTS partner_visibility TEXT NOT NULL DEFAULT 'public';

ALTER TABLE discovery_settings
    DROP CONSTRAINT IF EXISTS discovery_settings_partner_visibility_check;

ALTER TABLE discovery_settings
    ADD CONSTRAINT discovery_settings_partner_visibility_check
    CHECK (partner_visibility IN ('public', 'matches_only', 'verified_only', 'hidden'));
