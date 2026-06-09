-- SP1: 半成品库存流水账 (SemiFinishedInventoryTransaction)
-- 支撑: 移动均价精确计算/撤回回退/盘点调整/二次加工lineage
-- 设计: 流水账不可修改(无updated_at/deleted_at), 冲销走REVERSE类型新行
CREATE TABLE semi_finished_inventory_transactions (
    id                   BIGSERIAL       PRIMARY KEY,
    factory_id           VARCHAR(50)     NOT NULL,
    semi_finished_id     BIGINT          NOT NULL REFERENCES semi_finished_inventory(id),
    txn_type             VARCHAR(20)     NOT NULL,   -- IN/OUT/REVERSE/ADJUST
    source_type          VARCHAR(40)     NOT NULL,   -- PRODUCTION_OUTPUT/SECONDARY_CONSUME/REVERSAL/STOCKTAKE/TRANSFER_OUT
    source_ref           VARCHAR(100),               -- intermediateBatchNo/transferId/reversalLogId/stocktakeTaskId
    quantity             NUMERIC(12,6)   NOT NULL,   -- IN:正数; OUT/REVERSE:负数; ADJUST:可正可负
    unit_cost_at_txn     NUMERIC(14,4),              -- IN:本次产出单位成本; OUT/REVERSE/ADJUST:均价快照
    balance_after        NUMERIC(12,6),              -- 写入后余量快照
    balance_cost_after   NUMERIC(14,4),              -- 写入后均价快照(移动均价)
    report_id            BIGINT REFERENCES production_reports(id),  -- 产生此流水的报工ID(nullable)
    operator_id          BIGINT,                     -- 操作人ID(nullable)
    created_at           TIMESTAMP       NOT NULL DEFAULT NOW()
    -- 注意: 无updated_at/deleted_at — 流水账不可修改设计(冲销走REVERSE行)
);

CREATE INDEX idx_sfit_semi_id          ON semi_finished_inventory_transactions(semi_finished_id);
CREATE INDEX idx_sfit_factory_source   ON semi_finished_inventory_transactions(factory_id, source_ref);
CREATE INDEX idx_sfit_factory_txn_type ON semi_finished_inventory_transactions(factory_id, txn_type);
CREATE INDEX idx_sfit_report_id        ON semi_finished_inventory_transactions(report_id);

COMMENT ON TABLE  semi_finished_inventory_transactions                     IS '半成品库存流水账 — SP1地基; 支撑移动均价回放/撤回回退/盘点调整';
COMMENT ON COLUMN semi_finished_inventory_transactions.txn_type            IS 'IN(产出入库)/OUT(下道领用)/REVERSE(撤回冲销)/ADJUST(盘点调整)';
COMMENT ON COLUMN semi_finished_inventory_transactions.source_type         IS 'PRODUCTION_OUTPUT/SECONDARY_CONSUME/REVERSAL/STOCKTAKE/TRANSFER_OUT';
COMMENT ON COLUMN semi_finished_inventory_transactions.unit_cost_at_txn   IS 'IN时=本次产出单位成本; OUT/REVERSE时=均价快照; null=成本未知';
COMMENT ON COLUMN semi_finished_inventory_transactions.balance_after       IS '写入后可用量快照(available_quantity)';
COMMENT ON COLUMN semi_finished_inventory_transactions.balance_cost_after  IS '写入后移动均价快照(unit_cost)';
