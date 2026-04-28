CREATE TABLE IF NOT EXISTS user_blocks (
    id UUID PRIMARY KEY,
    blocker_user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    blocked_user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    reason TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (blocker_user_id, blocked_user_id),
    CONSTRAINT user_blocks_not_self_check CHECK (blocker_user_id <> blocked_user_id)
);

CREATE INDEX IF NOT EXISTS idx_user_blocks_blocker
ON user_blocks(blocker_user_id, created_at DESC);

CREATE INDEX IF NOT EXISTS idx_user_blocks_blocked
ON user_blocks(blocked_user_id);

CREATE TABLE IF NOT EXISTS safety_reports (
    id UUID PRIMARY KEY,
    reporter_user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    reported_user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    target_type TEXT NOT NULL,
    target_id TEXT,
    category TEXT NOT NULL,
    note TEXT,
    status TEXT NOT NULL DEFAULT 'open',
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT safety_reports_target_type_check CHECK (
        target_type IN ('profile', 'message', 'media', 'album', 'session')
    ),
    CONSTRAINT safety_reports_category_check CHECK (
        category IN (
            'underage_user',
            'harassment_or_threats',
            'explicit_public_content',
            'spam_or_fake_profile',
            'impersonation',
            'solicitation',
            'non_consensual_content',
            'private_info_doxxing',
            'other'
        )
    ),
    CONSTRAINT safety_reports_status_check CHECK (
        status IN ('open', 'under_review', 'resolved', 'dismissed')
    ),
    CONSTRAINT safety_reports_not_self_check CHECK (reporter_user_id <> reported_user_id),
    CONSTRAINT safety_reports_note_length_check CHECK (note IS NULL OR length(note) <= 2000)
);

CREATE INDEX IF NOT EXISTS idx_safety_reports_reported_recent
ON safety_reports(reported_user_id, created_at DESC);

CREATE INDEX IF NOT EXISTS idx_safety_reports_queue
ON safety_reports(status, created_at DESC)
WHERE status IN ('open', 'under_review');

CREATE INDEX IF NOT EXISTS idx_safety_reports_reporter_recent
ON safety_reports(reporter_user_id, created_at DESC);
