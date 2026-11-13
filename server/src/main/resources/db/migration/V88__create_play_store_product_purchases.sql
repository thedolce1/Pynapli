-- One-time Google Play purchases (Boosts, Pineapple Likes, Intro Notes). The purchase token is the
-- key, so a purchase is credited exactly once however many times the app (or a restore) submits it.
CREATE TABLE IF NOT EXISTS play_store_product_purchases (
    purchase_token TEXT PRIMARY KEY,
    user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    product_id TEXT NOT NULL,
    order_id TEXT,
    inventory_type TEXT NOT NULL,
    quantity INTEGER NOT NULL CHECK (quantity > 0),
    credited_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_play_store_product_purchases_user
    ON play_store_product_purchases (user_id, credited_at DESC);
