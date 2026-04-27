DROP INDEX IF EXISTS idx_media_album_items_active_order;

CREATE INDEX IF NOT EXISTS idx_media_album_items_active_order
ON media_album_items(album_id, sort_order, added_at DESC)
WHERE removed_at IS NULL;
