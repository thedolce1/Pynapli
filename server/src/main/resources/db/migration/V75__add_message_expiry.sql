-- Messaging slice: premium conversation expiry timer (MESSAGING_SPEC "Deletion And Expiry").
-- Each participant may set their own disappearing-messages timer; the effective timer for the
-- conversation is the strictest (smallest) non-null value across active participants. Allowed
-- values mirror the spec: 1 hour / 24 hours / 7 days / 30 days. NULL = off.
ALTER TABLE conversation_participants
    ADD COLUMN IF NOT EXISTS message_expiry_seconds INTEGER;

ALTER TABLE conversation_participants
    DROP CONSTRAINT IF EXISTS conversation_participants_expiry_seconds_check;

ALTER TABLE conversation_participants
    ADD CONSTRAINT conversation_participants_expiry_seconds_check
    CHECK (message_expiry_seconds IS NULL OR message_expiry_seconds IN (3600, 86400, 604800, 2592000));

-- System messages (e.g. "disappearing messages set to 24 hours") are authored by the actor who
-- changed the timer but render as a centered notice. 'user' is the existing message shape.
-- They are exempt from the expiry filter so the timer-change history stays legible.
ALTER TABLE messages
    ADD COLUMN IF NOT EXISTS message_type TEXT NOT NULL DEFAULT 'user';

ALTER TABLE messages
    DROP CONSTRAINT IF EXISTS messages_message_type_check;

ALTER TABLE messages
    ADD CONSTRAINT messages_message_type_check
    CHECK (message_type IN ('user', 'system'));
