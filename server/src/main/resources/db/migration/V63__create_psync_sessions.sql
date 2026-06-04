-- PS-1: Psync synchronized-swipe session negotiation lifecycle (PSYNC_SPEC).
-- "Psync is shared discovery without shared accounts." Both linked partners must
-- consent (and re-approve after any filter change) before a session is `ready`.
-- This slice covers the negotiation lifecycle only: create-invite -> accept / deny /
-- suggest-filters (re-approval) -> both-approved = 'ready', plus leave/cancel and
-- expiry. The shared deck, start->active, swipes, matches and recap are later phases;
-- the deck_session_id column is reserved for PS-2 but left nullable/unused here.
--
-- Filters are the verbatim Swipe SwipeDeckRequestDto persisted as JSONB (request-time
-- fields like cursor are stripped before persistence), mirroring saved_swipe_filters.

CREATE TABLE IF NOT EXISTS psync_sessions (
    id UUID PRIMARY KEY,
    partner_link_id UUID NOT NULL REFERENCES user_profile_links(id) ON DELETE CASCADE,
    initiator_user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    status TEXT NOT NULL DEFAULT 'invited',
    filters JSONB NOT NULL DEFAULT '{}'::jsonb,
    deck_size INTEGER NOT NULL,
    deck_session_id UUID,            -- reserved for the shared deck (PS-2); unused in PS-1
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    started_at TIMESTAMPTZ,          -- when it goes active (PS-2); never set in PS-1
    expires_at TIMESTAMPTZ NOT NULL,
    ended_at TIMESTAMPTZ,
    CONSTRAINT psync_sessions_status_check
        CHECK (status IN ('invited', 'configuring', 'ready', 'active', 'paused', 'completed', 'expired', 'cancelled')),
    CONSTRAINT psync_sessions_deck_size_check CHECK (deck_size BETWEEN 1 AND 50)
);

-- One row per participant. Create = an initiator row (consent accepted, approved) + a
-- partner row (consent pending, not approved). The session becomes 'ready' only when
-- EVERY participant is approved. Suggesting filters resets approvals (except the
-- suggester) so the other partner must re-approve the new setup.
CREATE TABLE IF NOT EXISTS psync_session_participants (
    session_id UUID NOT NULL REFERENCES psync_sessions(id) ON DELETE CASCADE,
    user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    role TEXT NOT NULL,             -- 'initiator' | 'partner'
    consent_status TEXT NOT NULL DEFAULT 'pending',  -- pending | accepted | denied
    approved BOOLEAN NOT NULL DEFAULT false,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (session_id, user_id),
    CONSTRAINT psync_participants_role_check CHECK (role IN ('initiator', 'partner')),
    CONSTRAINT psync_participants_consent_check CHECK (consent_status IN ('pending', 'accepted', 'denied'))
);

-- Expiry sweep + "is there a live session for this link?" filter on (status, expires_at).
CREATE INDEX IF NOT EXISTS idx_psync_sessions_status_expires
    ON psync_sessions(status, expires_at);

-- "Does this user have a current session?" looks up by participant user id.
CREATE INDEX IF NOT EXISTS idx_psync_session_participants_user
    ON psync_session_participants(user_id);
