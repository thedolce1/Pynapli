-- Intro Notes are no longer a separate product (2026-11-17): a Pineapple Like can carry an optional
-- note instead. Everything already stored as an Intro Note becomes the Pineapple Like it now is.

-- 1. A sent Intro Note is a Pineapple Like that kept its note. It now counts as a like everywhere
--    (matching, like-back, Psync), which an Intro Note never did.
UPDATE swipe_actions SET action = 'pineapple_like' WHERE action = 'intro_note';
UPDATE swipe_deck_cards SET action = 'pineapple_like' WHERE action = 'intro_note';

-- Only a Pineapple Like carries a note, of 1-140 characters. The server never stored a note on any
-- other action; clear any that would break the rule rather than fail the migration.
UPDATE swipe_actions
SET note_text = NULL
WHERE note_text IS NOT NULL
  AND (action <> 'pineapple_like' OR char_length(trim(note_text)) NOT BETWEEN 1 AND 140);

ALTER TABLE swipe_actions DROP CONSTRAINT IF EXISTS swipe_actions_action_check;
ALTER TABLE swipe_actions
ADD CONSTRAINT swipe_actions_action_check
CHECK (action IN ('like', 'pass', 'pineapple_like'));

ALTER TABLE swipe_actions DROP CONSTRAINT IF EXISTS swipe_actions_intro_note_text_check;
ALTER TABLE swipe_actions DROP CONSTRAINT IF EXISTS swipe_actions_note_text_check;
ALTER TABLE swipe_actions
ADD CONSTRAINT swipe_actions_note_text_check
CHECK (
    note_text IS NULL
    OR (action = 'pineapple_like' AND char_length(trim(note_text)) BETWEEN 1 AND 140)
);

ALTER TABLE swipe_deck_cards DROP CONSTRAINT IF EXISTS swipe_deck_cards_action_check;
ALTER TABLE swipe_deck_cards
ADD CONSTRAINT swipe_deck_cards_action_check
CHECK (action IS NULL OR action IN ('like', 'pass', 'pineapple_like'));

-- 2. Activity: an Intro Note card becomes a Pineapple Like card. One live swipe-interest card per
--    (recipient, actor, type, target) is allowed, so where the sender already has a live Pineapple
--    Like card for that person the Intro Note card is retired instead of colliding with it.
UPDATE activity_events legacy
SET deleted_at = now(), updated_at = now()
WHERE legacy.event_type = 'intro_note'
  AND legacy.deleted_at IS NULL
  AND EXISTS (
      SELECT 1
      FROM activity_events existing
      WHERE existing.event_type = 'pineapple_like'
        AND existing.deleted_at IS NULL
        AND existing.recipient_user_id = legacy.recipient_user_id
        AND existing.actor_user_id = legacy.actor_user_id
        AND existing.target_user_id IS NOT DISTINCT FROM legacy.target_user_id
  );
UPDATE activity_events SET event_type = 'pineapple_like' WHERE event_type = 'intro_note';

-- 3. Unspent Intro Notes (bought or granted) become the same number of Pineapple Likes, so nobody
--    loses what they paid for. play_store_product_purchases keeps its history as recorded.
UPDATE premium_inventory SET inventory_type = 'pineapple_like', updated_at = now() WHERE inventory_type = 'intro_note';

ALTER TABLE premium_inventory DROP CONSTRAINT IF EXISTS premium_inventory_type_check;
ALTER TABLE premium_inventory
ADD CONSTRAINT premium_inventory_type_check
CHECK (inventory_type IN ('boost', 'super_boost', 'pineapple_like', 'extra_pick'));
