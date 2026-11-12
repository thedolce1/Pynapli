-- What the reporter saw. For a message report this is the message text at the moment of the report,
-- so moderators can act even if the sender later deletes it. Never logged; read only by moderators.
ALTER TABLE safety_reports
    ADD COLUMN IF NOT EXISTS evidence_text TEXT;
