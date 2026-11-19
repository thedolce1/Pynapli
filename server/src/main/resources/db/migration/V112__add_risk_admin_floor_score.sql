-- Verification must not lower an admin-set score (founder decision, TRUST_AND_SAFETY_SPEC
-- "Recovery And Appeals"). admin_floor_score is the score the latest admin set-tier, lock or ban
-- wrote (moderation/JdbcAdminRiskQueries.upsertRiskState keeps it current). A negative delta
-- (age or photo verification) never takes the score below it (safety/JdbcRiskRepository). NULL
-- means no admin has set the score. Setting clean writes 0, which removes the protection.
ALTER TABLE account_risk_states
    ADD COLUMN IF NOT EXISTS admin_floor_score INTEGER;

ALTER TABLE account_risk_states
    ADD CONSTRAINT account_risk_states_admin_floor_score_check
    CHECK (admin_floor_score IS NULL OR (admin_floor_score >= 0 AND admin_floor_score <= 100));

-- Backfill from the most recent admin set-tier or enforcement event (same sources and reasons the
-- V110 skip used). No score changes: an account a verification already took below its admin score
-- stays where it is. The integration test runs this statement from the file text, from the UPDATE
-- to the end.
UPDATE account_risk_states s
SET admin_floor_score = latest.score_after
FROM (
    SELECT DISTINCT ON (e.user_id)
           e.user_id,
           e.score_after
    FROM account_risk_events e
    WHERE (e.source = 'admin_review' AND starts_with(e.reason, 'admin_review.set_'))
       OR e.source = 'admin_enforcement'
    ORDER BY e.user_id, e.created_at DESC, e.id DESC
) latest
WHERE s.user_id = latest.user_id;
