-- One-time backfill of the photo-verification risk reduction (TRUST_AND_SAFETY_SPEC "Recovery And
-- Appeals"). Since V109, an approved selfie verification records verification.photo_verified (-20,
-- RiskService.PHOTO_VERIFIED_DELTA) once per account through RiskRepository.recordEventOnce. Accounts
-- verified before that never got it. This applies the same adjustment a live approval applies:
--   - only to accounts currently photo-verified (profiles.face_photo_verified_at, written by approval
--     and cleared by revoke) that have no verification.photo_verified event yet.
--   - never to an account whose score was set or enforced by an admin (any admin_review.set_* or
--     admin_enforcement.* event). Whether verification may lower an admin-set score is undecided.
--   - -20 off the decayable part first, then the rest, floored at 0, tier recomputed with the
--     thresholds JdbcRiskRepository uses, decay clock cleared when the decayable part reaches 0.
--   - an account with no risk row gets one at 0/clean plus the ledger row, as the live path does, so
--     the credit counts as used.
--   - one ledger row per account with the live source, reason and delta, marked {"backfill":"V110"}.
-- Due decay is not applied first, although the live path does that. It can only leave a score
-- higher than a live approval would have, never lower. Idempotent: a second run finds no candidates.
-- Single statement on purpose: the integration test runs the text of this file as is.
WITH candidates AS (
    SELECT p.user_id
    FROM profiles p
    JOIN users u ON u.id = p.user_id AND u.deleted_at IS NULL
    WHERE p.face_photo_verified_at IS NOT NULL
      AND NOT EXISTS (
          SELECT 1
          FROM account_risk_events e
          WHERE e.user_id = p.user_id
            AND e.reason = 'verification.photo_verified'
      )
      AND NOT EXISTS (
          SELECT 1
          FROM account_risk_events e
          WHERE e.user_id = p.user_id
            AND (
                (e.source = 'admin_review' AND starts_with(e.reason, 'admin_review.set_'))
                OR e.source = 'admin_enforcement'
            )
      )
),
current_state AS (
    SELECT s.user_id,
           GREATEST(0, LEAST(100, s.score + (-20))) AS new_score,
           GREATEST(0, GREATEST(0, LEAST(s.decayable_points, s.score)) + (-20)) AS new_decayable
    FROM account_risk_states s
    JOIN candidates c ON c.user_id = s.user_id
),
updated AS (
    UPDATE account_risk_states s
    SET score = cs.new_score,
        tier = CASE
            WHEN cs.new_score >= 90 THEN 'terminate'
            WHEN cs.new_score >= 70 THEN 'quarantine'
            WHEN cs.new_score >= 50 THEN 'friction'
            WHEN cs.new_score >= 30 THEN 'watch'
            ELSE 'clean'
        END,
        decayable_points = cs.new_decayable,
        decay_anchor_at = CASE
            WHEN cs.new_decayable = 0 THEN NULL
            WHEN s.decay_anchor_at IS NULL THEN now()
            ELSE s.decay_anchor_at
        END,
        last_event_at = now(),
        updated_at = now()
    FROM current_state cs
    WHERE s.user_id = cs.user_id
    RETURNING s.user_id, s.score, s.tier
),
inserted AS (
    INSERT INTO account_risk_states (user_id, score, tier, decayable_points, decay_anchor_at, last_event_at, updated_at)
    SELECT c.user_id, 0, 'clean', 0, NULL, now(), now()
    FROM candidates c
    WHERE NOT EXISTS (SELECT 1 FROM account_risk_states s WHERE s.user_id = c.user_id)
    ON CONFLICT (user_id) DO NOTHING
    RETURNING user_id, score, tier
)
INSERT INTO account_risk_events (
    id,
    user_id,
    actor_user_id,
    source,
    reason,
    delta,
    score_after,
    tier_after,
    metadata
)
SELECT gen_random_uuid(),
       applied.user_id,
       NULL,
       'verification',
       'verification.photo_verified',
       -20,
       applied.score,
       applied.tier,
       '{"backfill":"V110"}'::jsonb
FROM (
    SELECT user_id, score, tier FROM updated
    UNION ALL
    SELECT user_id, score, tier FROM inserted
) applied;
