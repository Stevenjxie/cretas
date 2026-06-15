CREATE TABLE manufacturer_registry (
    id           VARCHAR(191) PRIMARY KEY,
    factory_id   VARCHAR(191) NOT NULL,
    code         VARCHAR(64)  NOT NULL,
    name         VARCHAR(200) NOT NULL,
    origin_place VARCHAR(200),
    is_active    BOOLEAN NOT NULL DEFAULT TRUE,
    remark       VARCHAR(500),
    created_at   TIMESTAMP NOT NULL DEFAULT NOW(),
    updated_at   TIMESTAMP NOT NULL DEFAULT NOW(),
    deleted_at   TIMESTAMP NULL
);

CREATE UNIQUE INDEX uq_manufacturer_factory_code
    ON manufacturer_registry (factory_id, code)
    WHERE deleted_at IS NULL;

CREATE INDEX idx_manufacturer_factory_active
    ON manufacturer_registry (factory_id, is_active)
    WHERE deleted_at IS NULL;

ALTER TABLE purchase_receive_items
    ADD COLUMN IF NOT EXISTS factory_number VARCHAR(100),
    ADD COLUMN IF NOT EXISTS origin_place VARCHAR(200);
