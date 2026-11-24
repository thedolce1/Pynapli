-- "Hide from people you know" (TRUST_AND_SAFETY_SPEC, "Contact Blocks"), follow-up to V119/V120.

-- 1. A durable "has written here" marker per participant. The exemption treats "X wrote in a
--    conversation with A" as something A already observed; disappearing messages are purged by the
--    retention job, so the evidence must outlive the messages. Set by a trigger in the same
--    transaction as every insert of a visible message and every release of a held one (text, media,
--    system notices, moderation release), so no write path can miss it.
ALTER TABLE conversation_participants ADD COLUMN IF NOT EXISTS first_written_at TIMESTAMPTZ;

UPDATE conversation_participants cp
SET first_written_at = first_message.created_at
FROM (
    SELECT conversation_id, sender_user_id, MIN(created_at) AS created_at
    FROM messages
    WHERE moderation_status = 'visible'
    GROUP BY conversation_id, sender_user_id
) first_message
WHERE first_message.conversation_id = cp.conversation_id
  AND first_message.sender_user_id = cp.user_id
  AND cp.first_written_at IS NULL;

CREATE OR REPLACE FUNCTION pynapli_mark_participant_written() RETURNS trigger AS $$
BEGIN
    IF NEW.moderation_status = 'visible' THEN
        UPDATE conversation_participants
        SET first_written_at = COALESCE(first_written_at, NEW.created_at)
        WHERE conversation_id = NEW.conversation_id
          AND user_id = NEW.sender_user_id
          AND first_written_at IS NULL;
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

DROP TRIGGER IF EXISTS trg_messages_mark_participant_written ON messages;
CREATE TRIGGER trg_messages_mark_participant_written
    AFTER INSERT OR UPDATE OF moderation_status ON messages
    FOR EACH ROW
    EXECUTE FUNCTION pynapli_mark_participant_written();

-- 2. Who last moved a conversation's updated_at. A viewer's inbox order must only move for something
--    that viewer can see: when the last bump came from someone hiding from them, their sort time falls
--    back to their own last visible message (JdbcMessagingConversationLookupSql).
ALTER TABLE conversations ADD COLUMN IF NOT EXISTS updated_by_user_id UUID;

-- Existing conversations: the sender of the latest message is the best attribution there is (a later
-- non-message bump stays unattributed, so it orders as before).
UPDATE conversations c
SET updated_by_user_id = latest.sender_user_id
FROM (
    SELECT DISTINCT ON (conversation_id) conversation_id, sender_user_id
    FROM messages
    ORDER BY conversation_id, created_at DESC, id DESC
) latest
WHERE latest.conversation_id = c.id
  AND c.updated_by_user_id IS NULL;

-- 3. The resolver's daily full sweep: the claim (an attempt) and the last success are separate, so a
--    failed sweep is retried within the hour instead of a day later.
ALTER TABLE contact_block_job_state ADD COLUMN IF NOT EXISTS last_success_at TIMESTAMPTZ;
