-- G7 取数自动化 Tier A — 供应商送货单 OCR / 人工录入 (cretas_db)
-- 版本说明: origin/main 最高 Java Flyway = V20260915_07 (out-of-order=false), 故用 V20260916_01.
-- 配套 Python smartbi 迁移 V20260916_01__agg_supplier_price.sql 写 gold 进价表.

CREATE TABLE IF NOT EXISTS supplier_delivery_notes (
    id                  VARCHAR(191)    NOT NULL PRIMARY KEY,
    factory_id          VARCHAR(100)    NOT NULL,
    -- OCR/人工录入来源
    source_type         VARCHAR(20)     NOT NULL DEFAULT 'OCR',  -- OCR | MANUAL
    -- 照片幂等键 (Rule 4)
    photo_hash          VARCHAR(64)     UNIQUE,                  -- SHA-256 of uploaded bytes
    photo_oss_url       VARCHAR(500),
    -- 供应商信息 (来自 OCR 或下拉选择)
    supplier_id         VARCHAR(191),                            -- FK suppliers.id (nullable for OCR-created)
    supplier_name       VARCHAR(200),
    delivery_date       DATE            NOT NULL,
    note_number         VARCHAR(100),                            -- 送货单号 (OCR 提取或人工)
    -- 汇总
    total_amount        NUMERIC(15,2),
    -- OCR 置信度 (0.000-1.000)
    ocr_confidence      NUMERIC(4,3),
    ocr_raw_json        TEXT,                                    -- LLM raw response for debug
    ocr_error_message   TEXT,
    ocr_parsed_at       TIMESTAMP,
    -- 审核状态
    status              VARCHAR(20)     NOT NULL DEFAULT 'DRAFT',  -- DRAFT | CONFIRMED | REJECTED
    confirmed_by        BIGINT,
    confirmed_at        TIMESTAMP,
    reject_reason_code  VARCHAR(50),                             -- Rule 3 enum
    reject_reason_note  TEXT,
    -- BaseEntity 字段
    created_by          BIGINT,
    created_at          TIMESTAMP       NOT NULL DEFAULT NOW(),
    updated_at          TIMESTAMP       NOT NULL DEFAULT NOW(),
    deleted_at          TIMESTAMP
);

CREATE TABLE IF NOT EXISTS supplier_delivery_note_lines (
    id                  BIGSERIAL       PRIMARY KEY,
    note_id             VARCHAR(191)    NOT NULL REFERENCES supplier_delivery_notes(id) ON DELETE CASCADE,
    factory_id          VARCHAR(100)    NOT NULL,
    ingredient_name     VARCHAR(200)    NOT NULL,                -- OCR 提取原文
    raw_material_type_id VARCHAR(191),                          -- 匹配到的 cretas_db.raw_material_types.id
    quantity            NUMERIC(14,4),
    unit                VARCHAR(20),
    unit_price          NUMERIC(12,4),
    line_amount         NUMERIC(15,2),
    ocr_confidence      NUMERIC(4,3),                           -- 行级置信度
    created_at          TIMESTAMP       NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_sdn_factory_date        ON supplier_delivery_notes (factory_id, delivery_date DESC);
CREATE INDEX IF NOT EXISTS idx_sdn_supplier            ON supplier_delivery_notes (factory_id, supplier_id);
CREATE INDEX IF NOT EXISTS idx_sdn_status              ON supplier_delivery_notes (factory_id, status);
CREATE INDEX IF NOT EXISTS idx_sdnl_note_id            ON supplier_delivery_note_lines (note_id);
CREATE INDEX IF NOT EXISTS idx_sdnl_factory_material   ON supplier_delivery_note_lines (factory_id, raw_material_type_id);
