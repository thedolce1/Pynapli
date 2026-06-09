CREATE TABLE IF NOT EXISTS moderation_appeals (
    id UUID PRIMARY KEY,
    user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    action_type TEXT NOT NULL,
    target_type TEXT NOT NULL,
    target_id TEXT,
    related_report_id UUID REFERENCES safety_reports(id) ON DELETE SET NULL,
    status TEXT NOT NULL DEFAULT 'open',
    user_reason TEXT NOT NULL,
    admin_reason TEXT,
    admin_note TEXT,
    reviewed_by_admin_id TEXT,
    reviewed_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT moderation_appeals_action_type_check CHECK (
        action_type IN (
            'account_suspension',
            'account_ban',
            'risk_restriction',
            'media_removal',
            'message_removal',
            'other'
        )
    ),
    CONSTRAINT moderation_appeals_target_type_check CHECK (
        target_type IN ('account', 'media', 'message', 'profile', 'other')
    ),
    CONSTRAINT moderation_appeals_status_check CHECK (
        status IN (
            'open',
            'under_review',
            'upheld',
            'reduced',
            'restored',
            'verification_required',
            'escalated',
            'withdrawn'
        )
    ),
    CONSTRAINT moderation_appeals_reason_length_check CHECK (
        char_length(user_reason) BETWEEN 1 AND 2000
    ),
    CONSTRAINT moderation_appeals_admin_reason_length_check CHECK (
        admin_reason IS NULL OR char_length(admin_reason) BETWEEN 1 AND 500
    ),
    CONSTRAINT moderation_appeals_admin_note_length_check CHECK (
        admin_note IS NULL OR char_length(admin_note) <= 2000
    ),
    CONSTRAINT moderation_appeals_reviewer_length_check CHECK (
        reviewed_by_admin_id IS NULL OR char_length(reviewed_by_admin_id) BETWEEN 2 AND 120
    ),
    CONSTRAINT moderation_appeals_target_id_required_check CHECK (
        target_type IN ('account', 'other') OR target_id IS NOT NULL
    ),
    CONSTRAINT moderation_appeals_account_target_check CHECK (
        target_type <> 'account' OR target_id IS NULL
    )
);

CREATE INDEX IF NOT EXISTS idx_moderation_appeals_user_recent
ON moderation_appeals(user_id, created_at DESC);

CREATE INDEX IF NOT EXISTS idx_moderation_appeals_queue
ON moderation_appeals(status, created_at ASC)
WHERE status IN ('open', 'under_review');

CREATE UNIQUE INDEX IF NOT EXISTS uniq_moderation_appeals_active_target
ON moderation_appeals(user_id, action_type, target_type, COALESCE(target_id, ''))
WHERE status IN ('open', 'under_review');
