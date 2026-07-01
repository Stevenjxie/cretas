-- 半成品盘点 (Semi-finished stock-take) — 镜像 SP7 仓库盘点 (factory_stocktakes)
-- 客户张权: 半成品 (SemiFinishedInventory) 周期盘点校准, 必须走审批流 (复用 INVENTORY_ADJUSTMENT)
-- 状态机: INITIATED → COUNTING → PENDING_APPROVAL → APPROVED → APPLIED / REJECTED
-- 生效 (apply) 写预留的 semi_finished_inventory_transactions ADJUST/STOCKTAKE 流水留痕
-- 依赖: semi_finished_inventory(id)

CREATE TABLE IF NOT EXISTS semi_finished_stocktakes (
    id                   VARCHAR(64)  NOT NULL,
    factory_id           VARCHAR(64)  NOT NULL,
    stocktake_no         VARCHAR(50)  NOT NULL,
    period_month         VARCHAR(7)   NOT NULL,   -- "2026-06" 格式
    status               VARCHAR(30)  NOT NULL DEFAULT 'INITIATED',
                         -- INITIATED / COUNTING / PENDING_APPROVAL / APPROVED / APPLIED / REJECTED
    initiated_by         BIGINT       NOT NULL,
    initiated_at         TIMESTAMP    NOT NULL DEFAULT NOW(),
    submitted_by         BIGINT,
    submitted_at         TIMESTAMP,
    approved_by          BIGINT,
    approved_at          TIMESTAMP,
    reject_reason        TEXT,
    applied_at           TIMESTAMP,
    notes                TEXT,
    workflow_instance_id VARCHAR(191),            -- 复用 INVENTORY_ADJUSTMENT workflow 实例
    created_at           TIMESTAMP    NOT NULL DEFAULT NOW(),
    updated_at           TIMESTAMP    NOT NULL DEFAULT NOW(),
    deleted_at           TIMESTAMP,
    CONSTRAINT pk_semi_finished_stocktakes PRIMARY KEY (id),
    CONSTRAINT uk_sf_stocktake_no_factory UNIQUE (factory_id, stocktake_no)
);

CREATE INDEX IF NOT EXISTS idx_sf_stocktake_factory_month
    ON semi_finished_stocktakes (factory_id, period_month);

CREATE INDEX IF NOT EXISTS idx_sf_stocktake_status
    ON semi_finished_stocktakes (status);

-- 半成品盘点明细行
CREATE TABLE IF NOT EXISTS semi_finished_stocktake_items (
    id                    VARCHAR(64)    NOT NULL,
    stocktake_id          VARCHAR(64)    NOT NULL,
    semi_finished_id      BIGINT         NOT NULL,   -- FK → semi_finished_inventory.id
    intermediate_batch_no VARCHAR(64)    NOT NULL,   -- 工序批次号 (SFI 幂等键, apply 锁定位)
    product_type_id       VARCHAR(100),
    system_qty            DECIMAL(12, 4) NOT NULL,    -- 账面快照 = 发起时 availableQuantity
    actual_qty            DECIMAL(12, 4),             -- 仓管填写实盘数
    difference_qty        DECIMAL(12, 4),             -- 实盘 - 账面, 应用层计算
    difference_type       VARCHAR(20),                -- SURPLUS / SHORTAGE / MATCH
    unit                  VARCHAR(16),
    photo_urls            TEXT,                       -- JSON 数组, 差异照片
    notes                 TEXT,
    created_at            TIMESTAMP      NOT NULL DEFAULT NOW(),
    updated_at            TIMESTAMP      NOT NULL DEFAULT NOW(),
    deleted_at            TIMESTAMP,
    CONSTRAINT pk_semi_finished_stocktake_items PRIMARY KEY (id),
    CONSTRAINT fk_sf_stocktake_item_stocktake
        FOREIGN KEY (stocktake_id) REFERENCES semi_finished_stocktakes (id)
);

CREATE INDEX IF NOT EXISTS idx_sf_stocktake_item_stocktake
    ON semi_finished_stocktake_items (stocktake_id);

CREATE INDEX IF NOT EXISTS idx_sf_stocktake_item_semi
    ON semi_finished_stocktake_items (semi_finished_id);

CREATE INDEX IF NOT EXISTS idx_sf_stocktake_item_batch_no
    ON semi_finished_stocktake_items (intermediate_batch_no);
