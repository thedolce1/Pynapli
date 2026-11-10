-- A Pineapple Like made in a Psync session now spends one from the actor's inventory, as it does in
-- the ordinary Swipe deck. This records which premium_inventory row paid for it, so the delivered
-- like can carry it (and be refunded on rewind) and an undelivered one can be refunded when the
-- session ends. NULL for likes and passes, and once an undelivered Pineapple Like has been refunded.
ALTER TABLE psync_session_actions
    ADD COLUMN IF NOT EXISTS consumed_inventory_id UUID REFERENCES premium_inventory(id) ON DELETE SET NULL;

CREATE INDEX IF NOT EXISTS idx_psync_session_actions_consumed_inventory
    ON psync_session_actions(session_id)
    WHERE consumed_inventory_id IS NOT NULL;
