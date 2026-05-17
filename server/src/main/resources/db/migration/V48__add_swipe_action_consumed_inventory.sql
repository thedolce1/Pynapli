-- Pineapple Like inventory-safe rewind: track which premium_inventory row a
-- Pineapple Like swipe action consumed, so the rewind path can refund the exact
-- row atomically. NULL for non-Pineapple actions and for historical Pineapple
-- Likes recorded before this column existed (those remain non-refundable on
-- rewind — the rewind allow-list filter still only rewinds Likes/Passes for
-- historical actions because no inventory id is recorded).
ALTER TABLE swipe_actions
ADD COLUMN IF NOT EXISTS consumed_inventory_id UUID
    REFERENCES premium_inventory(id) ON DELETE SET NULL;
