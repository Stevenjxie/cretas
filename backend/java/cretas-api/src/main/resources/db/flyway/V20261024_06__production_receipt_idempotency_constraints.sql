CREATE UNIQUE INDEX IF NOT EXISTS uq_prod_settlement_receipt_idem
    ON production_settlements(factory_id, warehouse_receipt_idempotency_key)
    WHERE warehouse_receipt_idempotency_key IS NOT NULL
      AND deleted_at IS NULL;

CREATE UNIQUE INDEX IF NOT EXISTS uq_prod_transit_settlement_type_open
    ON production_transit_ledgers(settlement_id, ledger_type)
    WHERE deleted_at IS NULL;

CREATE UNIQUE INDEX IF NOT EXISTS uq_finished_goods_batch_factory_number
    ON finished_goods_batches(factory_id, batch_number)
    WHERE deleted_at IS NULL;
