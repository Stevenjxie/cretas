-- Task 3 (G3 小结): per-道 输出侧幂等标记。
-- 小结只处理 interim_settled_at IS NULL 的 process_sheet_rows (产出侧: SFI in / SFI out / FG),
-- 处理后打戳。重复点小结 → 无未结行 → 0 产出过账 (天然幂等, 与 material_consumptions.interim_settled_at 对称)。
-- 纯 additive; 默认 NULL (历史行 = 已结/无需结, 不会被首次小结重复过账)。

ALTER TABLE process_sheet_rows
    ADD COLUMN IF NOT EXISTS interim_settled_at TIMESTAMP NULL;

COMMENT ON COLUMN process_sheet_rows.interim_settled_at
    IS 'BY_STOCK 小结产出过账标记: NULL=待小结; 非NULL=已计入某次小结(不重复过账 SFI/FG)';
