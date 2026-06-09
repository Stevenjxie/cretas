-- SP7: 工厂盘点任务表
-- 蓝图 §3.4: 盘点发起→财务批→生效，月底强制，差异留痕
-- 依赖: factory_warehouses(id), material_batches(id)

CREATE TABLE IF NOT EXISTS factory_stocktakes (
    id              VARCHAR(64)  NOT NULL,
    factory_id      VARCHAR(64)  NOT NULL,
    stocktake_no    VARCHAR(50)  NOT NULL,
    warehouse_id    VARCHAR(64)  NOT NULL,
    period_month    VARCHAR(7)   NOT NULL,  -- "2026-06" 格式
    status          VARCHAR(30)  NOT NULL DEFAULT 'INITIATED',
                    -- INITIATED / COUNTING / PENDING_APPROVAL / APPROVED / APPLIED / REJECTED
    initiated_by    BIGINT       NOT NULL,
    initiated_at    TIMESTAMP    NOT NULL DEFAULT NOW(),
    submitted_by    BIGINT,
    submitted_at    TIMESTAMP,
    approved_by     BIGINT,
    approved_at     TIMESTAMP,
    reject_reason   TEXT,
    applied_at      TIMESTAMP,
    notes           TEXT,
    created_at      TIMESTAMP    NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMP    NOT NULL DEFAULT NOW(),
    deleted_at      TIMESTAMP,
    CONSTRAINT pk_factory_stocktakes PRIMARY KEY (id),
    CONSTRAINT uk_stocktake_no_factory UNIQUE (factory_id, stocktake_no)
);

CREATE INDEX IF NOT EXISTS idx_stocktake_factory_month
    ON factory_stocktakes (factory_id, period_month);

CREATE INDEX IF NOT EXISTS idx_stocktake_warehouse
    ON factory_stocktakes (warehouse_id);

CREATE INDEX IF NOT EXISTS idx_stocktake_status
    ON factory_stocktakes (status);

-- 盘点明细行
CREATE TABLE IF NOT EXISTS factory_stocktake_items (
    id                    VARCHAR(64)    NOT NULL,
    stocktake_id          VARCHAR(64)    NOT NULL,
    material_batch_id     VARCHAR(191)   NOT NULL,
    raw_material_type_id  VARCHAR(191),
    system_qty            DECIMAL(12, 4) NOT NULL,  -- 账面快照(发起时记录)
    actual_qty            DECIMAL(12, 4),            -- 仓管填写实盘数
    difference_qty        DECIMAL(12, 4),            -- 实盘 - 账面，应用层计算
    difference_type       VARCHAR(20),               -- SURPLUS / SHORTAGE / MATCH
    photo_urls            TEXT,                      -- JSON 数组，差异照片
    notes                 TEXT,
    created_at            TIMESTAMP      NOT NULL DEFAULT NOW(),
    updated_at            TIMESTAMP      NOT NULL DEFAULT NOW(),
    deleted_at            TIMESTAMP,
    CONSTRAINT pk_factory_stocktake_items PRIMARY KEY (id),
    CONSTRAINT fk_stocktake_item_stocktake
        FOREIGN KEY (stocktake_id) REFERENCES factory_stocktakes (id)
);

CREATE INDEX IF NOT EXISTS idx_stocktake_item_stocktake
    ON factory_stocktake_items (stocktake_id);

CREATE INDEX IF NOT EXISTS idx_stocktake_item_batch
    ON factory_stocktake_items (material_batch_id);
