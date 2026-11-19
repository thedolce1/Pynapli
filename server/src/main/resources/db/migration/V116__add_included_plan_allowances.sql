-- Included plan allowances (MONETIZATION_SPEC "Included allowances"): Social includes 1 Pineapple
-- Like per week, Select 5 Pineapple Likes per week and 1 Boost per month. They are granted lazily,
-- when the balance is read or about to be spent, never by a background job.
--
-- 1. Each person's allowance cycle is anchored once, the first time they are granted anything, at
--    the start of the paid entitlement in force then (a subscription's current billing-period start,
--    or the subscriber's for a covered partner; clamped to now), and never moves again: weeks are
--    7-day and months 30-day windows counted from this instant. Because the anchor never resets,
--    subscribing/cancelling or linking/unlinking cannot open a second window in the same period.
CREATE TABLE IF NOT EXISTS included_allowance_anchors (
    user_id UUID PRIMARY KEY REFERENCES users(id) ON DELETE CASCADE,
    anchored_at TIMESTAMPTZ NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- 2. One period's included units are one premium_inventory row with source 'subscription' (allowed
--    since V30, never written before) and expires_at = the period end, so they are spent and refunded
--    exactly like purchased units (swipe_actions / psync_session_actions keep its id) and simply stop
--    counting when the period ends. Purchased rows never carry these columns and are never touched
--    by a rollover, a downgrade or a lapse.
--      allowance_period_start  the period this row belongs to (with the user and type, the
--                              once-per-period key below)
--      allowance_granted       the most the period was ever entitled to (a high-water mark: it only
--                              rises, on an upgrade)
--      allowance_withheld      units held back because the current tier is entitled to fewer than
--                              were granted (downgrade, lapse, unlink). They come back if the tier
--                              comes back within the period, never beyond allowance_granted.
--      allowance_tier          the tier that set allowance_granted
--    Units used in the period = allowance_granted - quantity - allowance_withheld.
ALTER TABLE premium_inventory
    ADD COLUMN IF NOT EXISTS allowance_period_start TIMESTAMPTZ,
    ADD COLUMN IF NOT EXISTS allowance_granted INTEGER,
    ADD COLUMN IF NOT EXISTS allowance_withheld INTEGER,
    ADD COLUMN IF NOT EXISTS allowance_tier TEXT;

ALTER TABLE premium_inventory
    ADD CONSTRAINT premium_inventory_included_allowance_check CHECK (
        allowance_period_start IS NULL
        OR (
            source = 'subscription'
            AND expires_at IS NOT NULL
            AND allowance_granted IS NOT NULL
            AND allowance_granted >= 0
            AND allowance_withheld IS NOT NULL
            AND allowance_withheld >= 0
        )
    );

-- The once-per-period guarantee: concurrent grants for the same person, type and period race on this
-- index and INSERT ... ON CONFLICT lets exactly one of them create the row.
CREATE UNIQUE INDEX IF NOT EXISTS idx_premium_inventory_included_allowance_period
ON premium_inventory(user_id, inventory_type, allowance_period_start)
WHERE allowance_period_start IS NOT NULL;
