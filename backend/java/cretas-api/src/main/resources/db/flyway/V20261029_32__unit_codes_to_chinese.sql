-- 计数/包装单位统一用中文写法，业务数据里不再出现 pcs / box / case / bag / slice。
--
-- 背景: 单位在系统里曾经存两套写法 —— 主数据写中文(只/盒/箱)，某些编译与入库路径
-- 写英文等价码(pcs/box/case)。用户从来不认识 pcs, 却会在报工缺料提示里看到
-- "需要 1pcs, 可用 0pcs"; 更麻烦的是同一个物料的批次一半写「只」一半写 pcs,
-- 按字面比较时被当成两种单位, 明明有库存却匹配不上。
--
-- ⛔ 科学计量单位(WEIGHT / VOLUME: kg、g、L、ml)一律不动:
--    它们是国际计量符号而不是"系统内部代码" —— 秤上、单据上、国标上都这么写,
--    换成中文反而不如现状清楚。大小写差异(KG vs kg)由单位契约在查表时折叠,
--    也不需要动数据。
--    每一条 UPDATE 都通过 cur.category NOT IN ('WEIGHT','VOLUME') 把它们排除在外 ——
--    判据落在**当前值**上, 而不是只看目标值, 否则 kg 批次会被改成物料的计数单位。
--
-- 幂等: 全部是条件更新, 重复执行不会产生额外变化。

-- 1) 主数据: 按单位名录把计数/包装英文码换成对应中文名
UPDATE raw_material_types r
SET unit = cur.unit_name
FROM unit_of_measurements cur
WHERE lower(r.unit) = lower(cur.unit_code)
  AND cur.unit_name IS NOT NULL AND cur.unit_name <> ''
  AND cur.category NOT IN ('WEIGHT', 'VOLUME')
  AND r.unit ~ '^[a-zA-Z]+$';

UPDATE product_types p
SET unit = cur.unit_name
FROM unit_of_measurements cur
WHERE lower(p.unit) = lower(cur.unit_code)
  AND cur.unit_name IS NOT NULL AND cur.unit_name <> ''
  AND cur.category NOT IN ('WEIGHT', 'VOLUME')
  AND p.unit ~ '^[a-zA-Z]+$';

-- 2) 批次: 计数/包装类的英文码换成该物料自己的单位。
--    不走单位名录的通名 —— 一批「温氏黄油鸡」存成 pcs 时, 正确值是该物料配的「只」,
--    而不是 pcs 在名录里的通名「件」。
UPDATE material_batches b
SET quantity_unit = r.unit
FROM raw_material_types r, unit_of_measurements cur
WHERE b.material_type_id = r.id
  AND b.deleted_at IS NULL
  AND lower(b.quantity_unit) = lower(cur.unit_code)
  AND cur.category NOT IN ('WEIGHT', 'VOLUME')
  AND b.quantity_unit ~ '^[a-zA-Z]+$'
  AND r.unit !~ '^[a-zA-Z]+$'
  AND b.quantity_unit IS DISTINCT FROM r.unit;

-- 3) Workflow 端口快照: 同理, 且同样只碰计数/包装类。
--    原料端口指向 raw_material_types, 半成品/成品端口指向 product_types。
UPDATE workflow_task_ports w
SET unit = r.unit
FROM raw_material_types r, unit_of_measurements cur
WHERE w.sku_id = r.id
  AND w.deleted_at IS NULL
  AND w.material_kind = 'RAW_MATERIAL'
  AND lower(w.unit) = lower(cur.unit_code)
  AND cur.category NOT IN ('WEIGHT', 'VOLUME')
  AND w.unit ~ '^[a-zA-Z]+$'
  AND r.unit !~ '^[a-zA-Z]+$'
  AND w.unit IS DISTINCT FROM r.unit;

UPDATE workflow_task_ports w
SET unit = p.unit
FROM product_types p, unit_of_measurements cur
WHERE w.sku_id = p.id
  AND w.deleted_at IS NULL
  AND w.material_kind IN ('SEMI_FINISHED', 'FINISHED_GOOD')
  AND lower(w.unit) = lower(cur.unit_code)
  AND cur.category NOT IN ('WEIGHT', 'VOLUME')
  AND w.unit ~ '^[a-zA-Z]+$'
  AND p.unit !~ '^[a-zA-Z]+$'
  AND w.unit IS DISTINCT FROM p.unit;
