-- A worker that crashes mid-export used to leave the request in 'processing' forever. The worker now
-- stamps processing_started_at when it claims a request and counts attempts, so a stale claim can be
-- taken over by another worker and a request that keeps failing is given up on.
ALTER TABLE account_data_export_requests
    ADD COLUMN IF NOT EXISTS processing_started_at TIMESTAMPTZ,
    ADD COLUMN IF NOT EXISTS attempts INT NOT NULL DEFAULT 0;
