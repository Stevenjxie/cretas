-- 修 supplier_price_ingest 幂等去重在 prod 失效 (已重复插 2x, F006 16→8 手动清过)。
--
-- 根因: supplier_price_ingest_etl._load_existing_source_ids 用 set_config(...,true)
--       (transaction-local) 但在 asyncpg autocommit 模式下查询, 设置在查询前已被重置 →
--       RLS USING (factory_id = current_setting('app.factory_id', true)) 评估为 NULL →
--       读 0 行 existing → 所有 mb: 行当新行插 → 每次 ingest / daily refresh 重复污染。
--
-- 兜底: partial 唯一索引 ONLY ON material-batch ingest 的行 (source_note_id LIKE 'mb:%')。
--       delivery_note append 行 (source_note_id = note UUID, NOT 'mb:%') 不受约束, 保 append 语义。
--       配合 ingest 端 INSERT ... ON CONFLICT DO NOTHING (走此 partial 索引) 做幂等。
--
-- 版本: origin/main 最高 Python smartbi 迁移 V20261002_01, 故 V20261003_01 严格 > frontier 安全。

-- 清理任何残留的 mb: 重复行 (prod 已手动清 F006, 此为防御性全租户清理: 保留每组最小 id)。
-- 注: partial 唯一索引创建若遇残留重复会失败, 故先 dedup。
DELETE FROM agg_supplier_price a
 USING agg_supplier_price b
 WHERE a.source_note_id LIKE 'mb:%'
   AND a.factory_id      = b.factory_id
   AND a.source_note_id  = b.source_note_id
   AND a.id              > b.id;

-- Partial 唯一索引: 仅约束 mb: ingest 行的 (factory_id, source_note_id)。
-- delivery_note 行 (source_note_id NOT LIKE 'mb:%' 或 NULL) 不进此索引 → append 语义不变。
CREATE UNIQUE INDEX IF NOT EXISTS uq_agg_sp_mb_source
    ON agg_supplier_price (factory_id, source_note_id)
    WHERE source_note_id LIKE 'mb:%';

COMMENT ON INDEX uq_agg_sp_mb_source IS
  'Partial unique: 防 supplier_price_ingest (material_batches) 重复插. '
  'WHERE mb:% 仅约束 ingest 行, delivery-note append 行不受影响. Added 2026-06-11 (dedup fix).';
