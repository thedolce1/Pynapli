-- User-declared neighborhood / area label shown in the profile peek meta row
-- ("distance · hood · Online"). This is self-declared free text the user opts into —
-- NOT derived from stored GPS — so it never echoes exact location (the server-authoritative
-- location-privacy rule: current location is stored server-side and is never sent to clients).
ALTER TABLE profiles
    ADD COLUMN neighborhood TEXT;
