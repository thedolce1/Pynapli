CREATE TABLE IF NOT EXISTS play_store_rtdn_events (
    id UUID PRIMARY KEY,
    message_id TEXT NOT NULL UNIQUE,
    package_name TEXT,
    event_type TEXT NOT NULL,
    notification_type INTEGER,
    purchase_token TEXT,
    product_id TEXT,
    event_time TIMESTAMPTZ,
    raw_payload TEXT NOT NULL,
    processing_status TEXT NOT NULL,
    error_code TEXT,
    processed_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT play_store_rtdn_events_status_check CHECK (
        processing_status IN ('received', 'processed', 'ignored', 'duplicate', 'retry')
    )
);

CREATE INDEX IF NOT EXISTS idx_play_store_rtdn_events_purchase_token
ON play_store_rtdn_events(purchase_token, created_at DESC)
WHERE purchase_token IS NOT NULL;
