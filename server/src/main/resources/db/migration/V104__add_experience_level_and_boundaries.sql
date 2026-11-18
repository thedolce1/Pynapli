-- Round 4: swinger profile depth.
--
-- 1. Lifestyle experience becomes ONE value per account in profiles.experience_level (a column V3
--    created but nothing ever wrote), instead of two independent, contradictory tags in
--    lifestyle_tags. Any stray value in the dormant column is cleared first so the new CHECK holds.
--    Backfill: a profile with exactly one of the old tags keeps it as its level; a profile with BOTH
--    gets no level (we cannot tell which is true, and unset is safer than a guess). Both old tags
--    are then removed from lifestyle_tags, so nothing renders twice.
-- 2. Boundaries: a curated, consent-oriented set of "<item>:<stance>" tokens (stance is ask_first or
--    not_for_me). Optional; NULL/empty means nothing stated. Values are validated by the server
--    against DiscoveryTagTaxonomies.boundaryTokens.

ALTER TABLE profiles
    ADD COLUMN IF NOT EXISTS experience_level text NULL,
    ADD COLUMN IF NOT EXISTS boundaries text[] NULL;

UPDATE profiles
SET experience_level = NULL
WHERE experience_level IS NOT NULL
  AND experience_level NOT IN ('new_to_lifestyle', 'some_experience', 'experienced');

ALTER TABLE profiles
    ADD CONSTRAINT profiles_experience_level_check
        CHECK (experience_level IS NULL OR experience_level IN ('new_to_lifestyle', 'some_experience', 'experienced'));

UPDATE profiles
SET experience_level =
        CASE
            WHEN lifestyle_tags @> ARRAY['new_to_lifestyle']::text[]
                 AND lifestyle_tags @> ARRAY['experienced']::text[] THEN NULL
            WHEN lifestyle_tags @> ARRAY['new_to_lifestyle']::text[] THEN 'new_to_lifestyle'
            WHEN lifestyle_tags @> ARRAY['experienced']::text[] THEN 'experienced'
        END,
    lifestyle_tags = array_remove(array_remove(lifestyle_tags, 'new_to_lifestyle'), 'experienced')
WHERE lifestyle_tags && ARRAY['new_to_lifestyle', 'experienced']::text[];
