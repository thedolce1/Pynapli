CREATE TABLE IF NOT EXISTS admin_audit_log (
    id UUID PRIMARY KEY,
    admin_actor_id TEXT NOT NULL,
    admin_role TEXT NOT NULL DEFAULT 'bootstrap_admin',
    action TEXT NOT NULL,
    target_type TEXT NOT NULL,
    target_id UUID,
    reason TEXT,
    metadata JSONB NOT NULL DEFAULT '{}'::jsonb,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT admin_audit_log_actor_check CHECK (char_length(admin_actor_id) BETWEEN 2 AND 120),
    CONSTRAINT admin_audit_log_role_check CHECK (char_length(admin_role) BETWEEN 2 AND 80),
    CONSTRAINT admin_audit_log_action_check CHECK (char_length(action) BETWEEN 1 AND 120),
    CONSTRAINT admin_audit_log_target_type_check CHECK (char_length(target_type) BETWEEN 1 AND 80),
    CONSTRAINT admin_audit_log_reason_check CHECK (reason IS NULL OR char_length(reason) <= 500)
);

CREATE INDEX IF NOT EXISTS idx_admin_audit_log_created
ON admin_audit_log(created_at DESC);

CREATE INDEX IF NOT EXISTS idx_admin_audit_log_target
ON admin_audit_log(target_type, target_id, created_at DESC);
