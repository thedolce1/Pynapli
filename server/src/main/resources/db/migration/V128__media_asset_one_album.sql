-- Section 4 review (TRUST_AND_SAFETY_SPEC "Photo pre-screening", "Completion is once per asset").
--
-- An upload slot is opened for one album kind (intended_album_kind) and completes into that kind's
-- album exactly once. Before this, a held profile photo could be completed again into the private
-- album (private photos are not pre-screened, so it became approved) while its profile-album item
-- stayed, putting it on the public Grid. Public queries now also require
-- intended_album_kind = 'profile'. This migration makes existing rows fit those rules.

-- 1. Assets created before V72 have no intended kind: take it from the album they sit in. An asset
--    in a private album at all counts as private (the conservative reading: it never goes public).
UPDATE media_assets m
SET intended_album_kind = CASE
        WHEN EXISTS (
            SELECT 1
            FROM media_album_items i
            JOIN media_albums a ON a.id = i.album_id
            WHERE i.media_asset_id = m.id AND a.kind = 'private'
        ) THEN 'private'
        ELSE 'profile'
    END
WHERE m.intended_album_kind IS NULL
  AND EXISTS (SELECT 1 FROM media_album_items i WHERE i.media_asset_id = m.id);

-- 1b. An asset in exactly one album whose kind differs from its intended kind was completed into the
--     other album (allowed until now) and moderated by that album's kind: record the album's kind.
UPDATE media_assets m
SET intended_album_kind = only_album.kind
FROM (
    SELECT i.media_asset_id, min(a.kind) AS kind
    FROM media_album_items i
    JOIN media_albums a ON a.id = i.album_id
    GROUP BY i.media_asset_id
    HAVING count(*) = 1
) only_album
WHERE only_album.media_asset_id = m.id
  AND m.intended_album_kind IN ('profile', 'private')
  AND m.intended_album_kind <> only_album.kind;

-- 2. Assets attached to more than one album (the bypass, or legacy oddities). A profile-kind asset
--    that was approved while it also sat in a private album may never have been screened as a
--    profile photo: send it back to the human queue (uploaded/pending) before it can show again.
UPDATE media_assets m
SET processing_status = 'uploaded',
    moderation_status = 'pending',
    updated_at = now()
WHERE m.intended_album_kind = 'profile'
  AND m.moderation_status = 'approved'
  AND m.deleted_at IS NULL
  AND EXISTS (
      SELECT 1
      FROM media_album_items i
      JOIN media_albums a ON a.id = i.album_id
      WHERE i.media_asset_id = m.id AND a.kind = 'private'
  );

-- 3. Keep only the item in the album matching the asset's intended kind.
DELETE FROM media_album_items i
USING media_albums a, media_assets m
WHERE a.id = i.album_id
  AND m.id = i.media_asset_id
  AND m.intended_album_kind IN ('profile', 'private')
  AND a.kind <> m.intended_album_kind
  AND EXISTS (
      SELECT 1
      FROM media_album_items other
      WHERE other.media_asset_id = i.media_asset_id AND other.album_id <> i.album_id
  );

-- 4. From now on an asset can be in one album only (the database enforces it, whatever the code does).
--    If two items of one asset are still left (same kind, two albums), keep the oldest.
DELETE FROM media_album_items i
USING media_album_items keep
WHERE keep.media_asset_id = i.media_asset_id
  AND (keep.added_at, keep.album_id) < (i.added_at, i.album_id);

CREATE UNIQUE INDEX IF NOT EXISTS idx_media_album_items_one_album_per_asset
ON media_album_items(media_asset_id);
