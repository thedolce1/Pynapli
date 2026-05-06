ALTER TABLE swipe_actions
ADD COLUMN IF NOT EXISTS note_text TEXT;

ALTER TABLE swipe_actions
DROP CONSTRAINT IF EXISTS swipe_actions_action_check;

ALTER TABLE swipe_actions
ADD CONSTRAINT swipe_actions_action_check
CHECK (action IN ('like', 'pass', 'intro_note'));

ALTER TABLE swipe_actions
DROP CONSTRAINT IF EXISTS swipe_actions_intro_note_text_check;

ALTER TABLE swipe_actions
ADD CONSTRAINT swipe_actions_intro_note_text_check
CHECK (
    (
        action = 'intro_note'
        AND note_text IS NOT NULL
        AND char_length(trim(note_text)) BETWEEN 1 AND 140
    )
    OR (
        action <> 'intro_note'
        AND (note_text IS NULL OR char_length(note_text) <= 140)
    )
);

CREATE TABLE IF NOT EXISTS premium_inventory (
    id UUID PRIMARY KEY,
    user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    inventory_type TEXT NOT NULL,
    quantity INTEGER NOT NULL,
    source TEXT NOT NULL,
    expires_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT premium_inventory_type_check CHECK (
        inventory_type IN ('boost', 'super_boost', 'pineapple_like', 'intro_note', 'extra_pick')
    ),
    CONSTRAINT premium_inventory_source_check CHECK (
        source IN ('subscription', 'purchase', 'promo', 'admin', 'dev')
    ),
    CONSTRAINT premium_inventory_quantity_check CHECK (quantity >= 0)
);

CREATE INDEX IF NOT EXISTS idx_premium_inventory_user_available
ON premium_inventory(user_id, inventory_type, expires_at)
WHERE quantity > 0;
