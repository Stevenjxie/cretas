-- SP7: 报损单表
-- 蓝图 §3.4: 仓库报损→财务批; 工厂报损→厂长批; 照片强制至少1张
-- 支持报损出库计入成本

CREATE TABLE IF NOT EXISTS wastage_reports (
    id                    VARCHAR(64)    NOT NULL,
    factory_id            VARCHAR(64)    NOT NULL,
    report_no             VARCHAR(50)    NOT NULL,
    track_type            VARCHAR(20)    NOT NULL,  -- WAREHOUSE / FACTORY
    warehouse_id          VARCHAR(64),               -- WAREHOUSE 轨必填
    material_batch_id     VARCHAR(191)   NOT NULL,
    raw_material_type_id  VARCHAR(191),              -- 冗余，展示用
    wastage_qty           DECIMAL(12, 4) NOT NULL,
    wastage_reason        VARCHAR(30)    NOT NULL,
                          -- EXPIRED / DAMAGED / CONTAMINATED / THEFT / OTHER
    reason_detail         TEXT,                      -- 选 OTHER 时必填
    photo_urls            TEXT           NOT NULL,   -- JSON 数组，至少1张
    status                VARCHAR(30)    NOT NULL DEFAULT 'DRAFT',
                          -- DRAFT / PENDING_APPROVAL / APPROVED / REJECTED / APPLIED
    submitted_by          BIGINT,
    submitted_at          TIMESTAMP,
    approver_role         VARCHAR(50),               -- FINANCE / FACTORY_MANAGER
    approved_by           BIGINT,
    approved_at           TIMESTAMP,
    reject_reason         TEXT,
    applied_at            TIMESTAMP,
    notes                 TEXT,
    created_at            TIMESTAMP      NOT NULL DEFAULT NOW(),
    updated_at            TIMESTAMP      NOT NULL DEFAULT NOW(),
    deleted_at            TIMESTAMP,
    CONSTRAINT pk_wastage_reports PRIMARY KEY (id),
    CONSTRAINT uk_wastage_report_no UNIQUE (factory_id, report_no)
);

CREATE INDEX IF NOT EXISTS idx_wastage_factory_track_status
    ON wastage_reports (factory_id, track_type, status);

CREATE INDEX IF NOT EXISTS idx_wastage_batch
    ON wastage_reports (material_batch_id);

CREATE INDEX IF NOT EXISTS idx_wastage_warehouse
    ON wastage_reports (warehouse_id)
    WHERE warehouse_id IS NOT NULL;
