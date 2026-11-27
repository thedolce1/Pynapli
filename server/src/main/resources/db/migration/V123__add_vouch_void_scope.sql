-- "Met in person" admin voids (TRUST_AND_SAFETY_SPEC "Met in person"): which kind of void voided a
-- row, so undoing a voucher-level void restores only the rows that void voided and never a row an
-- admin voided on its own. A flag, not a batch id: a shared id would tie one voucher's rows together
-- for anyone holding only the database.
ALTER TABLE vouches
    ADD COLUMN IF NOT EXISTS voided_with_voucher BOOLEAN NOT NULL DEFAULT false;
