-- Warehouse short-receipt closure audit. Confirmed inventory remains untouched;
-- these fields explain why the unreceived purchase balance was closed.
ALTER TABLE purchase_orders
    ADD COLUMN IF NOT EXISTS receiving_close_reason_code VARCHAR(40),
    ADD COLUMN IF NOT EXISTS receiving_close_notes TEXT,
    ADD COLUMN IF NOT EXISTS receiving_closed_by BIGINT,
    ADD COLUMN IF NOT EXISTS receiving_closed_at TIMESTAMP;
