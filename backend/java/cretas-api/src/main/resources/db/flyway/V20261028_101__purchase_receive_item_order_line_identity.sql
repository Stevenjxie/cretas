ALTER TABLE purchase_receive_items
    ADD COLUMN IF NOT EXISTS purchase_order_item_id BIGINT;

CREATE INDEX IF NOT EXISTS idx_pri_order_item
    ON purchase_receive_items (purchase_order_item_id);

COMMENT ON COLUMN purchase_receive_items.purchase_order_item_id IS
    'Stable PO line identity for receipt allocation; historical rows may be null';
