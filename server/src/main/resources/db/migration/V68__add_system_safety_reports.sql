ALTER TABLE safety_reports
ADD COLUMN IF NOT EXISTS source TEXT NOT NULL DEFAULT 'user';

DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1
        FROM pg_constraint
        WHERE conname = 'safety_reports_source_check'
    ) THEN
        ALTER TABLE safety_reports
        ADD CONSTRAINT safety_reports_source_check
        CHECK (source IN ('user', 'system'));
    END IF;
END $$;

ALTER TABLE safety_reports
DROP CONSTRAINT IF EXISTS safety_reports_not_self_check;

ALTER TABLE safety_reports
ADD CONSTRAINT safety_reports_not_self_check
CHECK (source = 'system' OR reporter_user_id <> reported_user_id);

CREATE INDEX IF NOT EXISTS idx_safety_reports_source_queue
ON safety_reports(source, status, created_at DESC)
WHERE status IN ('open', 'under_review');
