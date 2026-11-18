-- One active Boost per user (MONETIZATION_SPEC "Boost"), as defence in depth under the activation
-- advisory lock. Nothing used to move a Boost off 'active' when it ran out (readers filter on
-- ends_at), so activateBoost now expires the user's lapsed rows under that lock, in the same
-- transaction, before it inserts the next one.

-- 1. Boosts that have already ended become 'expired'. Readers ignore them either way.
UPDATE boost_activations
SET status = 'expired',
    updated_at = now()
WHERE status = 'active'
  AND ends_at <= now();

-- 2. If a user still has more than one running Boost (a double tap from before the lock existed),
--    keep the one the app already shows (the same order as findActiveBoostActivation) and cancel the
--    rest.
WITH ranked AS (
    SELECT id,
           ROW_NUMBER() OVER (
               PARTITION BY user_id
               ORDER BY ends_at DESC, starts_at DESC, id ASC
           ) AS rn
    FROM boost_activations
    WHERE status = 'active'
)
UPDATE boost_activations ba
SET status = 'canceled',
    updated_at = now()
FROM ranked
WHERE ba.id = ranked.id
  AND ranked.rn > 1;

CREATE UNIQUE INDEX IF NOT EXISTS idx_boost_activations_one_active_per_user
ON boost_activations(user_id)
WHERE status = 'active';
