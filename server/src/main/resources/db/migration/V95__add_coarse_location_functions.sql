-- Location privacy (AUDIT-07..14): every distance a client can observe — displayed distance,
-- result ordering, radius membership, Playing Tonight zones — is computed from COARSE CELLS,
-- never from raw coordinates. A cell is the point snapped to a 0.01-degree grid (~1.1 km
-- north-south, narrower east-west away from the equator). Moving a few metres therefore never
-- changes what another user's distance reads, which defeats boundary-crossing trilateration.
--
-- These helpers are IMMUTABLE so they can be inlined by the planner; GiST index use is kept by
-- callers pre-filtering on the raw geom with a radius padded by PYNAPLI cell slack
-- (CoarseLocation.CELL_PAD_METERS in the server) before the exact cell-to-cell test.

CREATE OR REPLACE FUNCTION pynapli_location_cell(g geography)
RETURNS geography
LANGUAGE sql
IMMUTABLE
PARALLEL SAFE
AS $$
    SELECT ST_SnapToGrid(g::geometry, 0.01)::geography
$$;

-- Distance between the two points' cells, in metres.
CREATE OR REPLACE FUNCTION pynapli_coarse_distance_m(a geography, b geography)
RETURNS double precision
LANGUAGE sql
IMMUTABLE
PARALLEL SAFE
AS $$
    SELECT ST_Distance(pynapli_location_cell(a), pynapli_location_cell(b))
$$;

-- Public distance bucket, mirrored exactly by DistanceRounding.toPublicDistanceMeters():
--   < 2 km -> 1 km; < 10 km -> whole km; < 50 km -> 5 km steps; otherwise 10 km steps.
CREATE OR REPLACE FUNCTION pynapli_public_distance_m(d double precision)
RETURNS integer
LANGUAGE sql
IMMUTABLE
PARALLEL SAFE
AS $$
    SELECT CASE
        WHEN d < 2000 THEN 1000
        WHEN d < 10000 THEN (floor(d / 1000) * 1000)::integer
        WHEN d < 50000 THEN (floor(d / 5000) * 5000)::integer
        ELSE (floor(d / 10000) * 10000)::integer
    END
$$;
