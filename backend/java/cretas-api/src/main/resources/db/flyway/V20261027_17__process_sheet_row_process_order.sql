-- SP-F role-mode fix: 工序唯一 keying 用 process_order
--
-- 背景: role-mode (角色驱动配置) 下多道普通工序 (修油/滚揉/焯水/去舌苔) 全映射到同一
-- archetype process_code (如 'chaoshui')。原 getInventory/getRows 仅按 (factory, plan,
-- process_code) 过滤 → 多道共享同一库存/行集合, 真客户 (LIUSHANMEN) 无法分别录每道。
--
-- 根因: process_code(archetype) 不是唯一工序标识。process_order(链内唯一) 才是。
-- process_order 一直存在于 row_payload JSON (ProcessSheetRowRequest.processOrder @NotNull),
-- 但不是可查询列。本迁移把它提升为独立列, 供后端双键 (process_code, process_order) 过滤。

ALTER TABLE process_sheet_rows ADD COLUMN process_order INTEGER;

-- 回填已有行: 从 row_payload JSON 提取 processOrder。
-- row_payload 由 ObjectMapper 序列化 ProcessSheetRowRequest, processOrder 是 @NotNull 整数,
-- 故所有历史行均含该字段。
UPDATE process_sheet_rows
SET process_order = (row_payload->>'processOrder')::INTEGER
WHERE process_order IS NULL
  AND row_payload ? 'processOrder'
  AND row_payload->>'processOrder' ~ '^[0-9]+$';

-- 部分索引: 双键查询 (factory, plan, process_code, process_order) 走此索引 (排除软删行)。
CREATE INDEX idx_psr_plan_order ON process_sheet_rows (factory_id, plan_id, process_code, process_order)
  WHERE deleted_at IS NULL;
