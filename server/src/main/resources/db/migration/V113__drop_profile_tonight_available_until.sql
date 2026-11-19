-- Drop the dead "Available tonight" profile column (V38). Nothing reads or writes
-- profiles.tonight_available_until any more: the Tonight badge and filter run on
-- tonight_sessions (V60).
--
-- The Grid card covering index (V43) lists the column in its INCLUDE list, and
-- Postgres silently drops every index that references a dropped column. Rebuild
-- that index without the column first, so the Grid hydration lookup keeps its
-- covering index after the drop.
CREATE INDEX IF NOT EXISTS idx_profiles_grid_card_lookup_v2
ON profiles(user_id)
INCLUDE (
    display_name,
    display_age,
    relationship_context,
    discovery_style,
    verified_at,
    face_photo_verified_at,
    age_verified_at,
    lifestyle_tags,
    logistics_tags,
    intents
)
WHERE visibility_status = 'visible';

DROP INDEX IF EXISTS idx_profiles_grid_card_lookup;
ALTER INDEX idx_profiles_grid_card_lookup_v2 RENAME TO idx_profiles_grid_card_lookup;

DROP INDEX IF EXISTS idx_profiles_tonight_available;

ALTER TABLE profiles DROP COLUMN IF EXISTS tonight_available_until;
