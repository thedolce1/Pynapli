ALTER TABLE safety_reports
ADD COLUMN IF NOT EXISTS updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
ADD COLUMN IF NOT EXISTS reviewed_at TIMESTAMPTZ,
ADD COLUMN IF NOT EXISTS review_note TEXT;

DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1
        FROM pg_constraint
        WHERE conname = 'safety_reports_review_note_length_check'
    ) THEN
        ALTER TABLE safety_reports
        ADD CONSTRAINT safety_reports_review_note_length_check
        CHECK (review_note IS NULL OR length(review_note) <= 500);
    END IF;
END $$;

CREATE INDEX IF NOT EXISTS idx_safety_reports_status_updated
ON safety_reports(status, updated_at DESC);
