CREATE TABLE IF NOT EXISTS supplier_import_receipts (
    id VARCHAR(64) PRIMARY KEY,
    factory_id VARCHAR(64) NOT NULL,
    idempotency_key VARCHAR(100) NOT NULL,
    file_digest VARCHAR(64) NOT NULL,
    created_by BIGINT NOT NULL,
    created_count INTEGER NOT NULL,
    supplier_ids TEXT,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP,
    deleted_at TIMESTAMP,
    CONSTRAINT uk_supplier_import_factory_key UNIQUE (factory_id, idempotency_key)
);

CREATE INDEX IF NOT EXISTS idx_supplier_import_factory_digest
    ON supplier_import_receipts(factory_id, file_digest);
