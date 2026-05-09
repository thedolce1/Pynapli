-- G2: Broader Intent taxonomy backing.
-- Existing `profiles.discovery_style` only models solo/together/both/social/ask_in_chat.
-- This new TEXT[] column captures the rest of the FILTERS_SPEC Intent group:
-- dating, chat_first, events, open_to_singles, open_to_couples, open_to_linked_partners,
-- open_to_groups. Same `&&` overlap pattern as lifestyle_tags / logistics_tags.
ALTER TABLE profiles
    ADD COLUMN IF NOT EXISTS intents TEXT[];

CREATE INDEX IF NOT EXISTS idx_profiles_intents
    ON profiles USING GIN (intents)
    WHERE intents IS NOT NULL;
