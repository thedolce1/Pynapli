-- PS-6d: opt-in "share my Psync solo passthrough matches with my linked partner"
-- (PSYNC_SPEC "Solo passthrough" / "Privacy Rules"). Private by default (false).
ALTER TABLE discovery_settings
ADD COLUMN IF NOT EXISTS share_psync_solo_matches BOOLEAN NOT NULL DEFAULT false;
