-- "Hide from people you know" (TRUST_AND_SAFETY_SPEC, "Contact Blocks"), follow-up to V119.
--
-- Removing an address that is bound no longer deletes the row: it becomes a tombstone with
-- released_at. The person it pointed at sees the member again from then on, but nothing the member
-- did before released_at ever reaches them (so a removal can't dump a backlog on them whose timing
-- would line up with the removal). Pending rows are still deleted. Only rows with released_at NULL
-- are "the member's addresses": the count, the cap, duplicates and the resolver ignore tombstones,
-- so a re-added address gets a new row of its own beside its tombstone.
ALTER TABLE contact_blocks ADD COLUMN IF NOT EXISTS released_at TIMESTAMPTZ;

ALTER TABLE contact_blocks DROP CONSTRAINT IF EXISTS contact_blocks_blocker_email_key;

CREATE UNIQUE INDEX IF NOT EXISTS uq_contact_blocks_active_address
    ON contact_blocks (blocker_user_id, email_token)
    WHERE released_at IS NULL;

-- When the resolver last paged through every pending row (at most once a day, under its job lock).
CREATE TABLE IF NOT EXISTS contact_block_job_state (
    job_name TEXT PRIMARY KEY,
    last_full_sweep_at TIMESTAMPTZ NOT NULL
);
