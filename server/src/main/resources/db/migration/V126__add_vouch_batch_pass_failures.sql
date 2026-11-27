-- "Met in person" weekly badge pass: how many times the chunk at the current cursor has failed. After a
-- few failures the pass takes that chunk member by member and skips the ones that fail, so one bad
-- member cannot stall every future pass (TRUST_AND_SAFETY_SPEC "Met in person").
ALTER TABLE vouch_batch_schedule
    ADD COLUMN IF NOT EXISTS pass_failures SMALLINT NOT NULL DEFAULT 0;
