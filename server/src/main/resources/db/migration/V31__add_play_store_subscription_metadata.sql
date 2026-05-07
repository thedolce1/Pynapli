ALTER TABLE subscriptions
    ADD COLUMN IF NOT EXISTS external_product_id TEXT,
    ADD COLUMN IF NOT EXISTS external_order_id TEXT,
    ADD COLUMN IF NOT EXISTS external_status TEXT,
    ADD COLUMN IF NOT EXISTS acknowledgement_state TEXT,
    ADD COLUMN IF NOT EXISTS acknowledged_at TIMESTAMPTZ,
    ADD COLUMN IF NOT EXISTS last_verified_at TIMESTAMPTZ;

CREATE INDEX IF NOT EXISTS idx_subscriptions_play_store_product
ON subscriptions(platform, external_product_id)
WHERE platform = 'play_store';
