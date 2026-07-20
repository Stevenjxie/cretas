ALTER TABLE sales_delivery_records
    ADD COLUMN IF NOT EXISTS parent_delivery_id VARCHAR(191),
    ADD COLUMN IF NOT EXISTS record_role VARCHAR(16) NOT NULL DEFAULT 'LEGACY',
    ADD COLUMN IF NOT EXISTS shipment_sequence INTEGER,
    ADD COLUMN IF NOT EXISTS idempotency_key VARCHAR(191),
    ADD COLUMN IF NOT EXISTS delivery_method VARCHAR(30);

ALTER TABLE sales_delivery_items
    ADD COLUMN IF NOT EXISTS sales_order_item_id BIGINT;

CREATE INDEX IF NOT EXISTS idx_sdr_parent_delivery
    ON sales_delivery_records(factory_id, parent_delivery_id);

CREATE UNIQUE INDEX IF NOT EXISTS uk_sdr_business_idempotency
    ON sales_delivery_records(factory_id, COALESCE(parent_delivery_id, sales_order_id), idempotency_key)
    WHERE idempotency_key IS NOT NULL AND deleted_at IS NULL;

CREATE INDEX IF NOT EXISTS idx_sdi_sales_order_item
    ON sales_delivery_items(sales_order_item_id);

CREATE UNIQUE INDEX IF NOT EXISTS uk_sdiba_item_batch_active
    ON sales_delivery_item_batch_allocations(factory_id, delivery_item_id, finished_goods_batch_id)
    WHERE deleted_at IS NULL;

ALTER TABLE sales_delivery_records
    ADD CONSTRAINT chk_sdr_record_role
        CHECK (record_role IN ('LEGACY', 'MASTER', 'SHIPMENT')),
    ADD CONSTRAINT fk_sdr_parent_delivery
        FOREIGN KEY (parent_delivery_id) REFERENCES sales_delivery_records(id);

ALTER TABLE sales_delivery_items
    ADD CONSTRAINT fk_sdi_sales_order_item
        FOREIGN KEY (sales_order_item_id) REFERENCES sales_order_items(id);

CREATE UNIQUE INDEX IF NOT EXISTS uk_sdr_parent_shipment_sequence
    ON sales_delivery_records(factory_id, parent_delivery_id, shipment_sequence)
    WHERE record_role = 'SHIPMENT' AND deleted_at IS NULL;
