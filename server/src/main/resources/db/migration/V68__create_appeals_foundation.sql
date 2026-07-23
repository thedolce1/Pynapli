CREATE TABLE IF NOT EXISTS appeals (
    id UUID PRIMARY KEY,
    user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    subject_type TEXT NOT NULL,
    subject_ref TEXT,
    reason TEXT NOT NULL,
    status TEXT NOT NULL DEFAULT 'pending',
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    resolved_at TIMESTAMPTZ,
    resolved_by_admin_id TEXT,
    resolution_note TEXT,
    CONSTRAINT appeals_subject_type_check
        CHECK (subject_type IN ('account', 'content', 'message', 'media')),
    CONSTRAINT appeals_status_check
        CHECK (status IN ('pending', 'approved', 'denied', 'withdrawn')),
    CONSTRAINT appeals_resolution_check
        CHECK (
            (status = 'pending' AND resolved_at IS NULL)
            OR (status <> 'pending' AND resolved_at IS NOT NULL)
        )
);

-- Admin queue: pending-first keyset pagination (created_at DESC, id DESC tie-break).
CREATE INDEX IF NOT EXISTS idx_appeals_status_recent
ON appeals(status, created_at DESC, id DESC);

-- "My appeals" listing for a user.
CREATE INDEX IF NOT EXISTS idx_appeals_user_recent
ON appeals(user_id, created_at DESC);

-- At most one open (pending) appeal per subject per user. subject_ref is coalesced
-- to '' so account appeals (NULL subject_ref) collapse to a single pending row too.
CREATE UNIQUE INDEX IF NOT EXISTS uq_appeals_pending_subject
ON appeals(user_id, subject_type, COALESCE(subject_ref, ''))
WHERE status = 'pending';
