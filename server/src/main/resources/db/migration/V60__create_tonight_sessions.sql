-- PT-1: Playing Tonight session model (PLAYING_TONIGHT_SPEC).
-- An explicit short-lived availability SIGNAL, not a couple account. Solo sessions go
-- live immediately; linked-together sessions require BOTH partners to approve before
-- they go live, and either partner can end one. Location is NEVER stored on the
-- session — the feed/map derive zone + distance live from user_locations (zone-only,
-- never street-level), exactly like the Grid, so a moved/expired location can't leak.
--
-- The legacy solo flag profiles.tonight_available_until (V38) is kept for the existing
-- Grid filter; a later slice (PT-2) unifies the Grid indicator onto active sessions.

CREATE TABLE IF NOT EXISTS tonight_sessions (
    id UUID PRIMARY KEY,
    created_by_user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    mode TEXT NOT NULL,             -- structural: 'solo' | 'together'
    intent TEXT NOT NULL,           -- public label: 'solo' | 'together' | 'social' | 'event'
    host_mode TEXT NOT NULL,        -- 'hosting' | 'traveling' | 'public_meet_first'
    note TEXT,                      -- optional, <= 100 chars, no explicit content (moderated upstream)
    photo_media_asset_id UUID REFERENCES media_assets(id) ON DELETE SET NULL,
    map_visible BOOLEAN NOT NULL DEFAULT true,  -- per-session opt-out of the zone map (still zone-only)
    status TEXT NOT NULL DEFAULT 'proposed',    -- proposed | active | ended | expired | declined
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    started_at TIMESTAMPTZ,         -- when it went active (all participants approved)
    expires_at TIMESTAMPTZ NOT NULL,
    ended_at TIMESTAMPTZ,
    CONSTRAINT tonight_sessions_mode_check CHECK (mode IN ('solo', 'together')),
    CONSTRAINT tonight_sessions_intent_check CHECK (intent IN ('solo', 'together', 'social', 'event')),
    CONSTRAINT tonight_sessions_host_mode_check CHECK (host_mode IN ('hosting', 'traveling', 'public_meet_first')),
    CONSTRAINT tonight_sessions_status_check CHECK (status IN ('proposed', 'active', 'ended', 'expired', 'declined')),
    CONSTRAINT tonight_sessions_note_len_check CHECK (note IS NULL OR char_length(note) <= 100)
);

-- One row per participant. Solo = a single owner row (auto-approved). Together = an
-- owner row (auto-approved at propose time) + a partner row (pending until they
-- approve/decline). The session goes 'active' only when EVERY participant is approved.
CREATE TABLE IF NOT EXISTS tonight_session_participants (
    session_id UUID NOT NULL REFERENCES tonight_sessions(id) ON DELETE CASCADE,
    user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    role TEXT NOT NULL,             -- 'owner' | 'partner'
    approval_status TEXT NOT NULL DEFAULT 'pending',  -- approved | pending | declined
    approved_at TIMESTAMPTZ,
    PRIMARY KEY (session_id, user_id),
    CONSTRAINT tonight_participants_role_check CHECK (role IN ('owner', 'partner')),
    CONSTRAINT tonight_participants_approval_check CHECK (approval_status IN ('approved', 'pending', 'declined'))
);

-- Expiry sweep + feed lookups filter on (status, expires_at).
CREATE INDEX IF NOT EXISTS idx_tonight_sessions_status_expires
    ON tonight_sessions(status, expires_at);

-- "Does this user already have a live/pending session?" and "what are my sessions?"
-- both look up by participant user id.
CREATE INDEX IF NOT EXISTS idx_tonight_session_participants_user
    ON tonight_session_participants(user_id);
