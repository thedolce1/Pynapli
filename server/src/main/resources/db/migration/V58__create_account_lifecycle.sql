CREATE TABLE IF NOT EXISTS account_data_export_requests (
    id UUID PRIMARY KEY,
    user_id UUID NOT NULL REFERENCES users(id),
    status TEXT NOT NULL DEFAULT 'requested',
    requested_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    completed_at TIMESTAMPTZ,
    expires_at TIMESTAMPTZ,
    download_url TEXT,
    error_message TEXT,
    CONSTRAINT account_data_export_requests_status_check
        CHECK (status IN ('requested', 'processing', 'ready', 'failed', 'expired')),
    CONSTRAINT account_data_export_requests_completion_check
        CHECK (
            (status = 'ready' AND completed_at IS NOT NULL AND download_url IS NOT NULL)
            OR status <> 'ready'
        )
);

CREATE INDEX IF NOT EXISTS idx_account_data_export_requests_user_requested
ON account_data_export_requests(user_id, requested_at DESC);

CREATE TABLE IF NOT EXISTS account_deletion_events (
    id UUID PRIMARY KEY,
    user_id UUID NOT NULL REFERENCES users(id),
    requested_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    completed_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    revoked_session_count INTEGER NOT NULL DEFAULT 0,
    retention_note TEXT NOT NULL,
    CONSTRAINT account_deletion_events_revoked_session_count_check
        CHECK (revoked_session_count >= 0)
);

CREATE INDEX IF NOT EXISTS idx_account_deletion_events_user_completed
ON account_deletion_events(user_id, completed_at DESC);
