-- Section 4 review (TRUST_AND_SAFETY_SPEC "Photo pre-screening", "Completion is once per asset").
--
-- An upload slot is opened for one album kind (intended_album_kind) and completes into that kind's
-- album exactly once. Before this, a held profile photo could be completed again into the private
-- album (private photos are not pre-screened, so it became approved) while its profile-album item
-- stayed, putting it on the public Grid. Public queries now also require
-- intended_album_kind = 'profile'. This migration makes existing rows fit those rules.
--
-- Data safety: nothing is hard-deleted. A surplus album placement is soft-removed (removed_at set,
-- the row and the photo stay and can be restored by ops). Each step logs its row count.
-- Not applied anywhere outside dev/test when it was last edited, so it was revised in place.

ALTER TABLE media_assets
    ADD COLUMN IF NOT EXISTS moderation_scanned_at TIMESTAMPTZ;

DO $$
DECLARE
    n integer;
BEGIN
    -- 0. Every asset that already left pending_upload had a moderation decision applied under the
    --    old code: record it, so a retried upload-complete never scans it again.
    UPDATE media_assets
    SET moderation_scanned_at = updated_at
    WHERE moderation_scanned_at IS NULL
      AND processing_status <> 'pending_upload';
    GET DIAGNOSTICS n = ROW_COUNT;
    RAISE NOTICE 'V128 step 0: % assets marked as already moderated', n;

    -- Live placements: an item not removed, in an album not deleted.
    CREATE TEMP TABLE v128_live ON COMMIT DROP AS
    SELECT i.album_id, i.media_asset_id, a.kind, i.added_at
    FROM media_album_items i
    JOIN media_albums a ON a.id = i.album_id
    WHERE i.removed_at IS NULL AND a.deleted_at IS NULL;

    -- Assets placed live in both a profile and a private album (the bypass, or pre-V72 oddities).
    CREATE TEMP TABLE v128_both ON COMMIT DROP AS
    SELECT media_asset_id
    FROM v128_live
    GROUP BY media_asset_id
    HAVING bool_or(kind = 'profile') AND bool_or(kind = 'private');

    -- 1. Pre-V72 assets have no intended kind. In both albums: keep the PROFILE placement (a member
    --    must not silently lose a profile photo; step 3 sends it back to the human queue, so nothing
    --    from a private album becomes public without a moderator). In one album: that album's kind.
    UPDATE media_assets m
    SET intended_album_kind = 'profile'
    WHERE m.intended_album_kind IS NULL
      AND m.id IN (SELECT media_asset_id FROM v128_both);
    GET DIAGNOSTICS n = ROW_COUNT;
    RAISE NOTICE 'V128 step 1a: % pre-V72 assets in both albums kept as profile photos', n;

    UPDATE media_assets m
    SET intended_album_kind = only_album.kind
    FROM (
        SELECT media_asset_id, min(kind) AS kind
        FROM v128_live
        GROUP BY media_asset_id
        HAVING count(DISTINCT kind) = 1
    ) only_album
    WHERE only_album.media_asset_id = m.id
      AND m.intended_album_kind IS NULL;
    GET DIAGNOSTICS n = ROW_COUNT;
    RAISE NOTICE 'V128 step 1b: % pre-V72 assets given their only album kind', n;

    -- 2. An asset placed live only in an album of the other kind was completed there (allowed until
    --    now) and moderated by that album's kind: record the album's kind.
    UPDATE media_assets m
    SET intended_album_kind = only_album.kind
    FROM (
        SELECT media_asset_id, min(kind) AS kind
        FROM v128_live
        GROUP BY media_asset_id
        HAVING count(DISTINCT kind) = 1
    ) only_album
    WHERE only_album.media_asset_id = m.id
      AND m.intended_album_kind IN ('profile', 'private')
      AND m.intended_album_kind <> only_album.kind;
    GET DIAGNOSTICS n = ROW_COUNT;
    RAISE NOTICE 'V128 step 2: % assets re-kinded to the one album they sit in', n;

    -- 3. A profile-kind asset that was approved while it also sat in a private album may never have
    --    been screened as a profile photo: back to the human queue (uploaded/pending; the owner sees
    --    "Being reviewed") before it can show again.
    UPDATE media_assets m
    SET processing_status = 'uploaded',
        moderation_status = 'pending',
        updated_at = now()
    WHERE m.intended_album_kind = 'profile'
      AND m.moderation_status = 'approved'
      AND m.deleted_at IS NULL
      AND m.id IN (SELECT media_asset_id FROM v128_both);
    GET DIAGNOSTICS n = ROW_COUNT;
    RAISE NOTICE 'V128 step 3: % approved profile assets that also sat in a private album requeued', n;

    -- 4. Soft-remove the live placement whose album kind differs from the asset's kind.
    UPDATE media_album_items i
    SET removed_at = now()
    FROM media_albums a, media_assets m
    WHERE a.id = i.album_id
      AND m.id = i.media_asset_id
      AND i.removed_at IS NULL
      AND m.intended_album_kind IN ('profile', 'private')
      AND a.kind <> m.intended_album_kind
      AND m.id IN (SELECT media_asset_id FROM v128_both);
    GET DIAGNOSTICS n = ROW_COUNT;
    RAISE NOTICE 'V128 step 4: % mismatched album placements soft-removed', n;

    -- 5. Any asset still with more than one non-removed item (e.g. one in a deleted album and one
    --    in its live replacement): keep one, preferring a live album, then the oldest placement;
    --    soft-remove the rest.
    UPDATE media_album_items i
    SET removed_at = now()
    FROM (
        SELECT ranked.album_id, ranked.media_asset_id
        FROM (
            SELECT ii.album_id,
                   ii.media_asset_id,
                   row_number() OVER (
                       PARTITION BY ii.media_asset_id
                       ORDER BY (aa.deleted_at IS NULL) DESC, ii.added_at ASC, ii.album_id ASC
                   ) AS rank
            FROM media_album_items ii
            JOIN media_albums aa ON aa.id = ii.album_id
            WHERE ii.removed_at IS NULL
        ) ranked
        WHERE ranked.rank > 1
    ) surplus
    WHERE i.album_id = surplus.album_id
      AND i.media_asset_id = surplus.media_asset_id;
    GET DIAGNOSTICS n = ROW_COUNT;
    RAISE NOTICE 'V128 step 5: % surplus placements soft-removed', n;
END $$;

-- 6. From now on an asset has one live album placement (the database enforces it, whatever the
--    code does). Soft-removed rows are kept and don't count.
CREATE UNIQUE INDEX IF NOT EXISTS idx_media_album_items_one_live_album_per_asset
ON media_album_items(media_asset_id)
WHERE removed_at IS NULL;
