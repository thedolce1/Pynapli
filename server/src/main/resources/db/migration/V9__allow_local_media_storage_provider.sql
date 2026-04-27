ALTER TABLE media_assets
DROP CONSTRAINT IF EXISTS media_assets_storage_provider_check;

ALTER TABLE media_assets
ADD CONSTRAINT media_assets_storage_provider_check
CHECK (storage_provider IN ('local', 'firebase_storage', 'gcs'));
