-- Message-send idempotency: a client-supplied key makes a retried send safe.
-- "Do not retry non-idempotent mutations unless an idempotency key exists"
-- (MOBILE_APP_ARCHITECTURE_SPEC). Before this, a network retry of a send could create a
-- duplicate message (the body-fingerprint heuristic only guards first messages for spam).
--
-- The key is scoped per (conversation, sender): the same sender retrying the same logical send
-- in the same conversation collapses to one row; different conversations/senders never collide.
-- NULL is allowed (legacy + clients that don't send a key) and is excluded from the unique index.

ALTER TABLE messages
    ADD COLUMN IF NOT EXISTS client_idempotency_key TEXT;

ALTER TABLE messages
    ADD CONSTRAINT messages_client_idempotency_key_length_check
        CHECK (client_idempotency_key IS NULL OR char_length(client_idempotency_key) BETWEEN 1 AND 128);

CREATE UNIQUE INDEX IF NOT EXISTS uq_messages_conversation_sender_idempotency_key
    ON messages (conversation_id, sender_user_id, client_idempotency_key)
    WHERE client_idempotency_key IS NOT NULL;
