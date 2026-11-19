-- Location privacy: the service never stores a member's precise location. user_locations.geom now
-- holds only the point's CELL (the 0.01-degree grid of V94), accuracy_meters is always NULL, and the
-- geohash is the cell's label. Every distance, ordering key, radius test and Playing Tonight zone was
-- already (or, for zones, is now) computed from cells, so what other members observe is unchanged.
--
-- Precise points written before this migration still exist in database backups and write-ahead
-- logs until those are rotated; that is an operational matter, not something SQL can undo.

-- 1. Pin the cell to explicit arithmetic. On PostGIS <= 3.4 (what dev, CI and docker-compose run)
--    ST_SnapToGrid(g, 0.01) computes, per axis, rint((x - 0) / 0.01) * 0.01 + 0: round to nearest,
--    ties to even (Postgres round(double precision) is rint). Newer PostGIS switched sub-unit grids
--    to rint(x * 100) / 100, which can round a half-way input the other way and differ in the last
--    bit. Spelling the 3.4 formula out keeps the stored cells, and the Kotlin twin in :shared
--    (com.pynapli.shared.location.LocationCell, checked by LocationCellSqlParityTest), identical
--    across a PostGIS upgrade. On 3.4 this returns exactly what V94 returned.
CREATE OR REPLACE FUNCTION pynapli_location_cell(g geography)
RETURNS geography
LANGUAGE sql
IMMUTABLE
PARALLEL SAFE
AS $$
    SELECT ST_SetSRID(
        ST_MakePoint(
            round(ST_X(g::geometry) / 0.01::double precision) * 0.01::double precision + 0.0::double precision,
            round(ST_Y(g::geometry) / 0.01::double precision) * 0.01::double precision + 0.0::double precision
        ),
        4326
    )::geography
$$;

-- 2. The geohash column, made consistent with the cell: "lat:lon" of the cell centre with two
--    decimals, rounded to nearest (it used to be FLOORED from the raw fix, a different grid).
--    Nothing reads it; it is kept only so the column means something.
CREATE OR REPLACE FUNCTION pynapli_location_cell_label(g geography)
RETURNS text
LANGUAGE sql
IMMUTABLE
PARALLEL SAFE
AS $$
    SELECT (round(ST_Y(cell.c) * 100)::numeric / 100)::numeric(10, 2)::text
        || ':'
        || (round(ST_X(cell.c) * 100)::numeric / 100)::numeric(10, 2)::text
    FROM (SELECT pynapli_location_cell(g)::geometry AS c) cell
$$;

-- 3. Playing Tonight zones, from the CELL, in exact integer arithmetic. A zone is two cells wide on
--    each axis (0.02 degree). A cell centre's coordinate is k hundredths of a degree for an integer
--    k; the zone index is floor(k / 2), computed on integers (numeric division, no float), so a
--    cell centre lying exactly on a zone edge (every even k) always belongs to the zone that STARTS
--    at that edge, i.e. the zone to its east / north. floor (not truncation) keeps this true for
--    negative coordinates: k = -235 -> zone -118, which holds k = -236 and k = -235.
--    Every point of one cell has the same k, so it always gets the same zone: a zone can never split
--    a cell, which closed the half-cell leak of zoning the raw point.
CREATE OR REPLACE FUNCTION pynapli_tonight_zone_x(g geography)
RETURNS integer
LANGUAGE sql
IMMUTABLE
PARALLEL SAFE
AS $$
    SELECT floor(round(ST_X(pynapli_location_cell(g)::geometry) * 100)::integer / 2.0)::integer
$$;

CREATE OR REPLACE FUNCTION pynapli_tonight_zone_y(g geography)
RETURNS integer
LANGUAGE sql
IMMUTABLE
PARALLEL SAFE
AS $$
    SELECT floor(round(ST_Y(pynapli_location_cell(g)::geometry) * 100)::integer / 2.0)::integer
$$;

-- 4. Backfill: every stored point becomes its cell centre, the accuracy goes, the geohash follows the
--    cell. updated_at is left alone (the impossible-travel signal compares against it).
--    Idempotent: snapping a cell centre returns it unchanged, so the WHERE matches no row the second
--    time and nothing is rewritten.
--    Locking: a plain UPDATE takes ROW EXCLUSIVE on user_locations (reads and other tables carry on)
--    and row locks on the rows it changes, held until Flyway commits; a member's location write for
--    a row being backfilled waits for that commit. One row per member, so this is short. Changing an
--    indexed column makes these non-HOT updates: the GiST index on geom (and the geohash btree) gets
--    a new entry for each new row version inside the same transaction, so it stays correct; the dead
--    versions are reclaimed by autovacuum.
UPDATE user_locations
SET geom = pynapli_location_cell(geom),
    accuracy_meters = NULL,
    geohash = pynapli_location_cell_label(geom)
WHERE ST_X(geom::geometry) <> ST_X(pynapli_location_cell(geom)::geometry)
   OR ST_Y(geom::geometry) <> ST_Y(pynapli_location_cell(geom)::geometry)
   OR accuracy_meters IS NOT NULL
   OR geohash IS DISTINCT FROM pynapli_location_cell_label(geom);
