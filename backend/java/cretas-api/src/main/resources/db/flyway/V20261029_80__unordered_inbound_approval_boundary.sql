-- Separate request approval from warehouse receiving. Existing OPEN/PARTIALLY_RECEIVED rows
-- are already active warehouse tasks; only newly created requests start as PENDING_APPROVAL.

ALTER TABLE customer_material_arrival_notices
    ADD COLUMN IF NOT EXISTS reviewed_by BIGINT,
    ADD COLUMN IF NOT EXISTS reviewed_at TIMESTAMP,
    ADD COLUMN IF NOT EXISTS review_remark VARCHAR(1000);

ALTER TABLE customer_material_arrival_notices
    DROP CONSTRAINT IF EXISTS chk_cman_status;
ALTER TABLE customer_material_arrival_notices
    ADD CONSTRAINT chk_cman_status CHECK (status IN (
        'PENDING_APPROVAL', 'OPEN', 'PARTIALLY_RECEIVED', 'RECEIVED', 'REJECTED', 'CANCELLED'));

ALTER TABLE customer_material_arrival_notices
    ALTER COLUMN status SET DEFAULT 'PENDING_APPROVAL';

COMMENT ON COLUMN customer_material_arrival_notices.status IS
    'PENDING_APPROVAL/REJECTED/CANCELLED remain application-only; OPEN and later statuses are warehouse receiving tasks';
COMMENT ON COLUMN customer_material_arrival_notices.reviewed_by IS
    'Warehouse reviewer user id; null until approved or rejected';
COMMENT ON COLUMN customer_material_arrival_notices.reviewed_at IS
    'Warehouse review timestamp; null until approved or rejected';
COMMENT ON COLUMN customer_material_arrival_notices.review_remark IS
    'Optional warehouse approval note or rejection reason';
