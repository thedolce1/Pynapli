-- Crash-safe match side effects (SWIPE_IMPLEMENTATION_SPEC "Match side effects"). A swipe, like-back
-- or Psync resolution that records a like or makes a match writes the follow-up work it owes (the
-- Likes You / match Activity rows, the match conversation, the match push) into this table in the
-- SAME transaction. The request delivers the rows straight after commit and marks them delivered;
-- if the process dies first, MatchSideEffectDrainWorker delivers them once the fast path's lease
-- (next_attempt_at) has passed. Every step is idempotent, so a redelivery can never duplicate.
CREATE TABLE match_side_effect_outbox (
    id UUID PRIMARY KEY,
    kind TEXT NOT NULL CHECK (kind IN ('swipe_interest', 'match')),
    actor_user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    target_user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    -- swipe_interest: the Activity event type of the like.
    event_type TEXT CHECK (event_type IS NULL OR event_type IN ('swipe_like', 'pineapple_like')),
    -- match: the match it announces. An unmatch deletes the match and, with it, anything not yet
    -- delivered for it, so a late drain never resurrects a match that no longer exists.
    match_id UUID REFERENCES matches(id) ON DELETE CASCADE,
    -- A Psync like made together with the linked partner (shown as one "liked you together" row).
    pair_user_id UUID,
    pair_session_id UUID,
    created_at TIMESTAMPTZ NOT NULL,
    next_attempt_at TIMESTAMPTZ NOT NULL,
    attempts INTEGER NOT NULL DEFAULT 0,
    delivered_at TIMESTAMPTZ,
    -- Exception class of the last failed attempt only (never message text, which can carry user data).
    last_error TEXT,
    CHECK (
        (kind = 'swipe_interest' AND event_type IS NOT NULL)
        OR (kind = 'match' AND match_id IS NOT NULL)
    )
);

-- Drainer claim: pending rows whose lease or back-off has passed, oldest first.
CREATE INDEX match_side_effect_outbox_due_idx
    ON match_side_effect_outbox (next_attempt_at)
    WHERE delivered_at IS NULL;

-- Pruning of delivered rows.
CREATE INDEX match_side_effect_outbox_delivered_idx
    ON match_side_effect_outbox (delivered_at)
    WHERE delivered_at IS NOT NULL;
