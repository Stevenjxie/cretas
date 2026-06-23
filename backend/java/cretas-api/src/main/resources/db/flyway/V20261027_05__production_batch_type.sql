-- SP-D Fix 1a: WIP ProductionBatch discriminator
-- Adds batch_type column to distinguish CLERK_WIP intermediate batches from REGULAR batches.
-- CLERK_WIP batches (prefix CLK-W-) are internal cost-routing artifacts and must be excluded
-- from dashboard counts and batch list queries to avoid inflating metrics.

ALTER TABLE production_batches
    ADD COLUMN IF NOT EXISTS batch_type VARCHAR(20) NOT NULL DEFAULT 'REGULAR';

COMMENT ON COLUMN production_batches.batch_type IS
    'REGULAR = normal production batch; CLERK_WIP = intermediate WIP batch from clerk process entry (CLK-W- prefix). CLERK_WIP excluded from dashboard counts and batch list.';
