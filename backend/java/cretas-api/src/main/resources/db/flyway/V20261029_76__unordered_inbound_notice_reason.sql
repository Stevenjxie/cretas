-- Generalize the existing customer-material coordination notice into the warehouse page's
-- non-order inbound request. Request creation remains zero-inventory; warehouse receipt is
-- still the only operation that creates a material batch.

ALTER TABLE customer_material_arrival_notices
    ADD COLUMN IF NOT EXISTS inbound_reason VARCHAR(32)
        NOT NULL DEFAULT 'CUSTOMER_MATERIAL';

ALTER TABLE customer_material_arrival_notices
    ALTER COLUMN customer_id DROP NOT NULL;

ALTER TABLE customer_material_arrival_notices
    DROP CONSTRAINT IF EXISTS chk_cman_inbound_reason;
ALTER TABLE customer_material_arrival_notices
    ADD CONSTRAINT chk_cman_inbound_reason
    CHECK (inbound_reason IN ('CUSTOMER_MATERIAL', 'GIFT', 'OTHER'));

ALTER TABLE customer_material_arrival_notices
    DROP CONSTRAINT IF EXISTS chk_cman_customer_requirement;
ALTER TABLE customer_material_arrival_notices
    ADD CONSTRAINT chk_cman_customer_requirement
    CHECK (inbound_reason <> 'CUSTOMER_MATERIAL' OR customer_id IS NOT NULL);

COMMENT ON TABLE customer_material_arrival_notices IS
    'Operations-created non-order inbound request; inventory is written only by warehouse receipt';
COMMENT ON COLUMN customer_material_arrival_notices.inbound_reason IS
    'CUSTOMER_MATERIAL creates customer-owned inventory; GIFT and OTHER create company-owned inventory';
