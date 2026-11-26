-- "Met in person" weekly badge pass (TRUST_AND_SAFETY_SPEC "Met in person"): the pass in progress and
-- how far it got, so a crash or deploy mid-pass resumes the same pass (same evaluation time) on the
-- next tick instead of skipping a week. Each chunk advances the cursor in its own transaction under
-- this row's lock, so no member is stepped twice in one pass, however many runners resume it.
ALTER TABLE vouch_batch_schedule
    ADD COLUMN IF NOT EXISTS pass_id UUID,
    ADD COLUMN IF NOT EXISTS pass_now TIMESTAMPTZ,
    ADD COLUMN IF NOT EXISTS pass_cursor UUID;
