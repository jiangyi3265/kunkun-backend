-- Read-only security deposit balance, in integer fen. No collection/refund flow is enabled.
-- Keep this independent from spendable funds; existing members start with zero.
ALTER TABLE boost_member ADD COLUMN deposit_cents BIGINT NOT NULL DEFAULT 0 CHECK (deposit_cents >= 0);
