-- Location privacy (L-08): discovery ORDER must not be a finer distance signal than the displayed
-- distance. Ordering by exact distance let an attacker bracket a hidden-distance user between two
-- neighbours who show theirs. Every discovery ordering key is now built from these helpers.

-- Stable pseudo-random tiebreak for a candidate within an ordering bucket. The seed is the viewer's
-- location cell (so every viewer sharing a cached rank window shares one total order, and paging is
-- exact); it says nothing about distance. 60 bits of md5 fit a non-negative bigint.
CREATE OR REPLACE FUNCTION pynapli_order_tiebreak(seed text, candidate uuid)
RETURNS bigint
LANGUAGE sql
IMMUTABLE
PARALLEL SAFE
AS $$
    SELECT ('x' || substr(md5(seed || ':' || candidate::text), 1, 15))::bit(60)::bigint
$$;

-- Ordering bucket in metres, mirrored by CoarseLocation in the server:
--   * a candidate who shows distance sorts by their cell-to-cell distance floored to whole km;
--   * a candidate who hides distance gets a pseudo-random km bucket that does not depend on where
--     they are at all (uniform over the first min(radius, 25 km)), so their position in the list
--     says nothing beyond "inside the area I queried".
CREATE OR REPLACE FUNCTION pynapli_order_bucket_m(
    show_distance boolean,
    coarse_distance_m double precision,
    radius_m integer,
    seed text,
    candidate uuid
)
RETURNS integer
LANGUAGE sql
IMMUTABLE
PARALLEL SAFE
AS $$
    SELECT CASE
        WHEN show_distance THEN (floor(GREATEST(coarse_distance_m, 0) / 1000) * 1000)::integer
        ELSE ((pynapli_order_tiebreak(seed || ':hidden', candidate)
               % GREATEST(1, LEAST(radius_m, 25000) / 1000)) * 1000)::integer
    END
$$;
