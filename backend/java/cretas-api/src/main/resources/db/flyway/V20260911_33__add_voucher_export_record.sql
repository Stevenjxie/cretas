-- SP11: 凭证导出历史记录表 (幂等去重 + 审计)

CREATE TABLE IF NOT EXISTS voucher_export_records (
    id              VARCHAR(191) PRIMARY KEY,
    factory_id      VARCHAR(191) NOT NULL,
    export_type     VARCHAR(32)  NOT NULL,  -- SEQUENTIAL_LEDGER / SUBJECT_BALANCE / INVENTORY_LEDGER
    target_system   VARCHAR(32),
    period_start    DATE         NOT NULL,
    period_end      DATE         NOT NULL,
    row_count       INT          NOT NULL DEFAULT 0,
    file_name       VARCHAR(255),
    file_hash       VARCHAR(64),
    exported_by     BIGINT       NOT NULL,
    created_at      TIMESTAMP    NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMP    NOT NULL DEFAULT NOW(),
    deleted_at      TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_ver_factory_type ON voucher_export_records(factory_id, export_type);
CREATE INDEX IF NOT EXISTS idx_ver_period ON voucher_export_records(factory_id, period_start, period_end);

CREATE TRIGGER trg_ver_updated_at
    BEFORE UPDATE ON voucher_export_records
    FOR EACH ROW EXECUTE FUNCTION update_updated_at();
