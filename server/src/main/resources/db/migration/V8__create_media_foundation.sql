CREATE TABLE IF NOT EXISTS media_assets (
    id UUID PRIMARY KEY,
    owner_user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    media_type TEXT NOT NULL,
    storage_provider TEXT NOT NULL,
    storage_bucket TEXT NOT NULL,
    storage_object_key TEXT NOT NULL,
    content_type TEXT NOT NULL,
    byte_size BIGINT NOT NULL,
    pixel_width INTEGER,
    pixel_height INTEGER,
    moderation_status TEXT NOT NULL DEFAULT 'pending',
    processing_status TEXT NOT NULL DEFAULT 'pending_upload',
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    deleted_at TIMESTAMPTZ,
    UNIQUE (id, owner_user_id),
    UNIQUE (storage_provider, storage_bucket, storage_object_key),
    CONSTRAINT media_assets_media_type_check CHECK (media_type IN ('image', 'video')),
    CONSTRAINT media_assets_storage_provider_check CHECK (storage_provider IN ('firebase_storage', 'gcs')),
    CONSTRAINT media_assets_byte_size_check CHECK (byte_size > 0),
    CONSTRAINT media_assets_pixel_width_check CHECK (pixel_width IS NULL OR pixel_width > 0),
    CONSTRAINT media_assets_pixel_height_check CHECK (pixel_height IS NULL OR pixel_height > 0),
    CONSTRAINT media_assets_moderation_status_check CHECK (moderation_status IN ('pending', 'approved', 'held', 'removed')),
    CONSTRAINT media_assets_processing_status_check CHECK (processing_status IN ('pending_upload', 'uploaded', 'processing', 'ready', 'failed'))
);

CREATE INDEX IF NOT EXISTS idx_media_assets_owner_created
ON media_assets(owner_user_id, created_at DESC)
WHERE deleted_at IS NULL;

CREATE INDEX IF NOT EXISTS idx_media_assets_moderation
ON media_assets(moderation_status, processing_status)
WHERE deleted_at IS NULL;

CREATE TABLE IF NOT EXISTS media_albums (
    id UUID PRIMARY KEY,
    owner_user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    kind TEXT NOT NULL,
    title TEXT NOT NULL,
    visibility_status TEXT NOT NULL DEFAULT 'visible',
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    deleted_at TIMESTAMPTZ,
    UNIQUE (id, owner_user_id),
    CONSTRAINT media_albums_kind_check CHECK (kind IN ('profile', 'private')),
    CONSTRAINT media_albums_visibility_status_check CHECK (visibility_status IN ('visible', 'hidden', 'archived'))
);

CREATE UNIQUE INDEX IF NOT EXISTS idx_media_albums_owner_kind_active
ON media_albums(owner_user_id, kind)
WHERE deleted_at IS NULL;

CREATE INDEX IF NOT EXISTS idx_media_albums_owner_created
ON media_albums(owner_user_id, created_at DESC)
WHERE deleted_at IS NULL;

CREATE TABLE IF NOT EXISTS media_album_items (
    album_id UUID NOT NULL,
    media_asset_id UUID NOT NULL,
    owner_user_id UUID NOT NULL,
    sort_order INTEGER NOT NULL DEFAULT 0,
    added_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    removed_at TIMESTAMPTZ,
    PRIMARY KEY (album_id, media_asset_id),
    FOREIGN KEY (album_id, owner_user_id) REFERENCES media_albums(id, owner_user_id) ON DELETE CASCADE,
    FOREIGN KEY (media_asset_id, owner_user_id) REFERENCES media_assets(id, owner_user_id) ON DELETE CASCADE,
    CONSTRAINT media_album_items_sort_order_check CHECK (sort_order >= 0)
);

CREATE UNIQUE INDEX IF NOT EXISTS idx_media_album_items_active_order
ON media_album_items(album_id, sort_order)
WHERE removed_at IS NULL;

CREATE INDEX IF NOT EXISTS idx_media_album_items_asset
ON media_album_items(media_asset_id)
WHERE removed_at IS NULL;

CREATE TABLE IF NOT EXISTS media_album_access_requests (
    id UUID PRIMARY KEY,
    album_id UUID NOT NULL,
    owner_user_id UUID NOT NULL,
    requester_user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    status TEXT NOT NULL DEFAULT 'pending',
    requested_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    decided_at TIMESTAMPTZ,
    decided_by_user_id UUID REFERENCES users(id),
    FOREIGN KEY (album_id, owner_user_id) REFERENCES media_albums(id, owner_user_id) ON DELETE CASCADE,
    CONSTRAINT media_album_access_requests_status_check CHECK (status IN ('pending', 'approved', 'denied', 'canceled')),
    CONSTRAINT media_album_access_requests_not_self_check CHECK (owner_user_id <> requester_user_id),
    CONSTRAINT media_album_access_requests_decision_check CHECK (
        (status = 'pending' AND decided_at IS NULL AND decided_by_user_id IS NULL)
        OR (status <> 'pending' AND decided_at IS NOT NULL)
    )
);

CREATE UNIQUE INDEX IF NOT EXISTS idx_media_album_access_requests_pending
ON media_album_access_requests(album_id, requester_user_id)
WHERE status = 'pending';

CREATE INDEX IF NOT EXISTS idx_media_album_access_requests_owner
ON media_album_access_requests(owner_user_id, requested_at DESC)
WHERE status = 'pending';

CREATE TABLE IF NOT EXISTS media_album_access_grants (
    album_id UUID NOT NULL,
    owner_user_id UUID NOT NULL,
    grantee_user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    granted_by_user_id UUID NOT NULL REFERENCES users(id),
    granted_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    revoked_at TIMESTAMPTZ,
    revoked_by_user_id UUID REFERENCES users(id),
    PRIMARY KEY (album_id, grantee_user_id),
    FOREIGN KEY (album_id, owner_user_id) REFERENCES media_albums(id, owner_user_id) ON DELETE CASCADE,
    CONSTRAINT media_album_access_grants_not_self_check CHECK (owner_user_id <> grantee_user_id),
    CONSTRAINT media_album_access_grants_revoke_check CHECK (
        (revoked_at IS NULL AND revoked_by_user_id IS NULL)
        OR (revoked_at IS NOT NULL AND revoked_by_user_id IS NOT NULL)
    )
);

CREATE INDEX IF NOT EXISTS idx_media_album_access_grants_active
ON media_album_access_grants(grantee_user_id, album_id)
WHERE revoked_at IS NULL;

CREATE INDEX IF NOT EXISTS idx_media_album_access_grants_owner
ON media_album_access_grants(owner_user_id, granted_at DESC)
WHERE revoked_at IS NULL;
