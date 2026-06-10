-- At most one pending invite per user pair, regardless of direction. The
-- directed (user_id, linked_user_id) unique index already dedupes same-direction
-- retries via the createInvitation upsert, but two users inviting each other at
-- the same moment could create reverse-direction pending duplicates; this
-- canonical-pair partial index closes that race (the losing insert maps to
-- PROFILE_LINK_INVITE_EXISTS in the repository).

-- Defensive cleanup before the unique index: if reverse-direction pending
-- duplicates already exist, keep the oldest invite and decline the newer ones.
UPDATE user_profile_links upl
SET status = 'declined',
    updated_at = now()
WHERE upl.status = 'pending'
  AND upl.unlinked_at IS NULL
  AND EXISTS (
      SELECT 1
      FROM user_profile_links other
      WHERE other.status = 'pending'
        AND other.unlinked_at IS NULL
        AND LEAST(other.user_id, other.linked_user_id) = LEAST(upl.user_id, upl.linked_user_id)
        AND GREATEST(other.user_id, other.linked_user_id) = GREATEST(upl.user_id, upl.linked_user_id)
        AND (other.created_at, other.id) < (upl.created_at, upl.id)
  );

CREATE UNIQUE INDEX IF NOT EXISTS idx_user_profile_links_pending_pair
ON user_profile_links (LEAST(user_id, linked_user_id), GREATEST(user_id, linked_user_id))
WHERE status = 'pending' AND unlinked_at IS NULL;
