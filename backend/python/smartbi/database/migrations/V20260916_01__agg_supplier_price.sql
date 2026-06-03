-- G7 取数自动化 Tier A — 供应商进价 gold 表 (smartbi_db)
-- 每次 OCR/人工确认送货单写若干行 (append-only), 支持进价趋势分析 + G5 成本卡 / G4 成本侧诊断。
-- 来源: cretas_db.supplier_delivery_notes confirm → Java SupplierPriceGoldClient
--       → POST /api/smartbi/gold/supplier-price/batch-upsert → upsert_supplier_price_batch。
--
-- 版本: origin/main 最高 Python smartbi 迁移 V20260910_01, 故 V20260916_01 安全。
-- 配套 Java Flyway V20260916_01__supplier_delivery_notes.sql。

CREATE TABLE IF NOT EXISTS agg_supplier_price (
    id                  BIGSERIAL       PRIMARY KEY,
    factory_id          VARCHAR(50)     NOT NULL,
    source_note_id      VARCHAR(191),                           -- 来源 supplier_delivery_notes.id
    supplier_id         VARCHAR(191),
    supplier_name       VARCHAR(200),
    ingredient_name     VARCHAR(200)    NOT NULL,
    normalized_name     VARCHAR(200)    NOT NULL,               -- _normalize_name() 一致
    raw_material_type_id VARCHAR(191),                          -- cretas_db FK (denormalized)
    ingredient_id       BIGINT,                                 -- FK dim_ingredient.ingredient_id (nullable, ETL 填)
    delivery_date       DATE            NOT NULL,
    unit_price          NUMERIC(12,4)   NOT NULL,
    quantity            NUMERIC(14,4),
    unit                VARCHAR(20),
    line_amount         NUMERIC(15,2),
    created_at          TIMESTAMP       NOT NULL DEFAULT NOW()
);

ALTER TABLE agg_supplier_price ENABLE ROW LEVEL SECURITY;
ALTER TABLE agg_supplier_price FORCE ROW LEVEL SECURITY;
DROP POLICY IF EXISTS tenant_isolation ON agg_supplier_price;
CREATE POLICY tenant_isolation ON agg_supplier_price FOR ALL
    USING  (factory_id = current_setting('app.factory_id', true))
    WITH CHECK (factory_id = current_setting('app.factory_id', true));

-- 历史进价趋势: (factory, ingredient) → date DESC
CREATE INDEX IF NOT EXISTS idx_agg_sp_factory_ingredient ON agg_supplier_price (factory_id, normalized_name, delivery_date DESC);
CREATE INDEX IF NOT EXISTS idx_agg_sp_factory_supplier   ON agg_supplier_price (factory_id, supplier_id, delivery_date DESC);
CREATE INDEX IF NOT EXISTS idx_agg_sp_factory_date       ON agg_supplier_price (factory_id, delivery_date DESC);

-- GRANT — 历史复发 2 次的陷阱 (#367 candidate / #390 entity_resolution), 必须显式给 smartbi_user DML
GRANT SELECT, INSERT, UPDATE, DELETE ON agg_supplier_price TO smartbi_user;
GRANT USAGE, SELECT ON SEQUENCE agg_supplier_price_id_seq TO smartbi_user;

COMMENT ON TABLE agg_supplier_price IS
  'Gold layer: 供应商进价历史 (append-only). 每条 = 一张确认送货单的一行项. '
  'Source: cretas_db.supplier_delivery_notes (G7 OCR/manual). '
  'Unlocks G5 成本卡 latest-price + G4 进价趋势诊断. Added 2026-06-03 (G7).';
