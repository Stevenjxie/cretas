-- 副产 SKU 化与盘点抵扣 (2026-07-31)
--
-- 副产落 material_batches, 与 WIP 半成品同一条路 —— prod 实测 source_doc_type='PRODUCTION_BATCH'
-- 的 255 条里有 249 条 material_type_id 指向原料字典, 0 条指向成品字典。副产与 WIP 是同类东西
-- (产出物 + 可再投入), 另起炉灶会变成第二套「产出物字典」。
--
-- 去向是**生产仓**不是原料仓: 它是生产出来的, 不是采购入库的 (Steve 2026-07-31)。
--
-- 🔴 单价刻意允许 NULL: 盘点确认前不臆造 0。0 会被读成「这批副产不值钱」, NULL 才如实表达
--    「还没人确认过」。确认为 0 是另一回事 —— 那是个真实的确认结果, 两者必须分得开。
ALTER TABLE material_batches
    ADD COLUMN IF NOT EXISTS byproduct_source_report_id   BIGINT,
    ADD COLUMN IF NOT EXISTS byproduct_unit_price         NUMERIC(15,4),
    ADD COLUMN IF NOT EXISTS byproduct_price_confirmed_at TIMESTAMP,
    ADD COLUMN IF NOT EXISTS byproduct_price_confirmed_by BIGINT;

COMMENT ON COLUMN material_batches.byproduct_source_report_id IS
    '副产来源报工 ID; 非 NULL 即表示这是一条副产批次';
COMMENT ON COLUMN material_batches.byproduct_unit_price IS
    '副产单价(元/单位), 盘点时确认; NULL = 未确认, 不参与抵扣且展示为「未抵扣」';
COMMENT ON COLUMN material_batches.byproduct_price_confirmed_at IS
    '副产单价确认时间; 与单价一起判定是否已确认 —— 有价无时间 = BOM 带来的参考价, 不算确认';
COMMENT ON COLUMN material_batches.byproduct_price_confirmed_by IS
    '确认副产单价的用户 ID';

-- 盘点侧要捞「待确认单价的副产批次」, 走部分索引避免扫全表 (material_batches 是热表)
CREATE INDEX IF NOT EXISTS idx_material_batches_byproduct_pending
    ON material_batches (factory_id, byproduct_price_confirmed_at)
    WHERE byproduct_source_report_id IS NOT NULL;
