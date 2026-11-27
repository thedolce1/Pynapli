-- Durable partner-link history for "Met in person" (TRUST_AND_SAFETY_SPEC "Met in person").
-- `user_profile_links` rows are reused: a re-invite resets status and accepted_at, so a link that was
-- once accepted can look as if it never was after an invite/decline dance. This append-only record,
-- per directed row, keeps the first invite sent (user_id invited linked_user_id) and the first
-- acceptance. A trigger in the same transaction as every write keeps it, so no code path can skip it;
-- nothing ever clears it.
CREATE TABLE IF NOT EXISTS profile_link_history (
    user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    linked_user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    first_invited_at TIMESTAMPTZ,
    first_accepted_at TIMESTAMPTZ,
    PRIMARY KEY (user_id, linked_user_id)
);

CREATE INDEX IF NOT EXISTS idx_profile_link_history_linked ON profile_link_history (linked_user_id, user_id);

-- Existing rows: pending or declined rows were sent by user_id; active or unlinked rows were accepted
-- (legacy rows may lack accepted_at). Acceptances erased by a re-invite before this migration are lost.
INSERT INTO profile_link_history (user_id, linked_user_id, first_invited_at, first_accepted_at)
SELECT user_id,
       linked_user_id,
       CASE WHEN status IN ('pending', 'declined') THEN created_at END,
       CASE WHEN accepted_at IS NOT NULL OR status IN ('active', 'unlinked') THEN COALESCE(accepted_at, created_at) END
FROM user_profile_links
ON CONFLICT (user_id, linked_user_id) DO NOTHING;

CREATE OR REPLACE FUNCTION pynapli_record_profile_link_history() RETURNS trigger AS $$
BEGIN
    IF NEW.status = 'pending' THEN
        INSERT INTO profile_link_history (user_id, linked_user_id, first_invited_at)
        VALUES (NEW.user_id, NEW.linked_user_id, now())
        ON CONFLICT (user_id, linked_user_id) DO UPDATE
        SET first_invited_at = COALESCE(profile_link_history.first_invited_at, EXCLUDED.first_invited_at);
    END IF;
    IF NEW.accepted_at IS NOT NULL OR NEW.status = 'active' THEN
        INSERT INTO profile_link_history (user_id, linked_user_id, first_accepted_at)
        VALUES (NEW.user_id, NEW.linked_user_id, COALESCE(NEW.accepted_at, now()))
        ON CONFLICT (user_id, linked_user_id) DO UPDATE
        SET first_accepted_at = LEAST(
            COALESCE(profile_link_history.first_accepted_at, EXCLUDED.first_accepted_at),
            EXCLUDED.first_accepted_at
        );
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

DROP TRIGGER IF EXISTS trg_user_profile_links_history ON user_profile_links;
CREATE TRIGGER trg_user_profile_links_history
    AFTER INSERT OR UPDATE OF status, accepted_at ON user_profile_links
    FOR EACH ROW
    EXECUTE FUNCTION pynapli_record_profile_link_history();
