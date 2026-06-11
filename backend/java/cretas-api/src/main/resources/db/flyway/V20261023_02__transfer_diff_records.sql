-- V20261023_01: 调拨差异记录表（找单流程）
-- 调拨发货量 ≠ 实收量时，自动生成差异记录，追踪后续找回/核销/报损决策。

CREATE TABLE IF NOT EXISTS transfer_diff_records (
    id                  VARCHAR(191)    NOT NULL PRIMARY KEY,
    source_factory_id   VARCHAR(191)    NOT NULL,
    target_factory_id   VARCHAR(191)    NOT NULL,
    diff_number         VARCHAR(60)     NOT NULL,
    transfer_id         VARCHAR(191)    NOT NULL,
    transfer_number     VARCHAR(60),
    transfer_item_id    BIGINT          NOT NULL,
    item_name           VARCHAR(200),
    material_type_id    VARCHAR(191),
    product_type_id     VARCHAR(191),
    shipped_quantity    NUMERIC(15, 4)  NOT NULL,
    received_quantity   NUMERIC(15, 4)  NOT NULL,
    diff_quantity       NUMERIC(15, 4)  NOT NULL,
    unit                VARCHAR(20),
    status              VARCHAR(32)     NOT NULL DEFAULT 'PENDING',
    decision            VARCHAR(32),
    decision_by         BIGINT,
    decision_at         TIMESTAMP,
    decision_notes      TEXT,
    created_by          BIGINT          NOT NULL,
    created_at          TIMESTAMP       NOT NULL DEFAULT NOW(),
    updated_at          TIMESTAMP       NOT NULL DEFAULT NOW(),
    deleted_at          TIMESTAMP
);

CREATE UNIQUE INDEX IF NOT EXISTS uq_tdr_factory_diffnum
    ON transfer_diff_records (source_factory_id, diff_number)
    WHERE deleted_at IS NULL;

CREATE INDEX IF NOT EXISTS idx_tdr_source_factory  ON transfer_diff_records (source_factory_id);
CREATE INDEX IF NOT EXISTS idx_tdr_transfer         ON transfer_diff_records (transfer_id);
CREATE INDEX IF NOT EXISTS idx_tdr_status           ON transfer_diff_records (status);

-- updated_at 自动触发（复用已存在的 update_updated_at 函数）
DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM pg_trigger
        WHERE tgname = 'trigger_tdr_updated_at'
          AND tgrelid = 'transfer_diff_records'::regclass
    ) THEN
        CREATE TRIGGER trigger_tdr_updated_at
            BEFORE UPDATE ON transfer_diff_records
            FOR EACH ROW EXECUTE FUNCTION update_updated_at();
    END IF;
END;
$$;

COMMENT ON TABLE  transfer_diff_records              IS '调拨差异记录：发货量 ≠ 实收量时自动生成，追踪找单流程';
COMMENT ON COLUMN transfer_diff_records.diff_number  IS '差异单号，格式 TDIFF-{factoryId}-{yyyyMMdd}-{seq}';
COMMENT ON COLUMN transfer_diff_records.diff_quantity IS '差异量 = shipped_quantity - received_quantity（>0 少收）';
COMMENT ON COLUMN transfer_diff_records.decision      IS 'FOUND_BACK/WRITE_OFF_LOSS/TRANSFER_DAMAGE';
COMMENT ON COLUMN transfer_diff_records.status        IS 'PENDING = 待处理；RESOLVED = 已处理';
