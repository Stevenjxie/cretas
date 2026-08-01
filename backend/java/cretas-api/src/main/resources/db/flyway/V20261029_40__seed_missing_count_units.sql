-- 把权威别名表里有、而单位名录 unit_of_measurements 里没有的计数/包装单位补齐。
--
-- ## 背景 (2026-08-01 走查)
-- 单位有两个来源, 运行时是**两层**的:
--   ① `UnitContractServiceImpl#factoryCatalog()` 读 DB 名录
--      (`UnitOfMeasurementRepository#findAllByFactoryId`: `factoryId IN (:factoryId, '*')`)
--   ② 查不到才落到 Java 硬编码 `SYSTEM_ALIASES`(24 组) 兜底
-- 也就是 **DB 名录优先, 硬编码只是兜底** —— 业务加单位本该只改这张表, 不用发版。
--
-- 但两边长期对不齐: 名录缺 roll/slice/portion/crate/pail/item, 而它们在权威别名表里都有。
--
-- ## 补了之后修的是什么 (⚠️ 不是「识别不了」)
-- 这些单位**运行时一直认得** —— 走的是上面第 ② 层兜底。补名录真正解决的是:
--   1. **SQL 迁移能映射到它们**。`V20261029_32__unit_codes_to_chinese.sql` 是
--      `JOIN unit_of_measurements` 做的, SQL 看不见 Java 硬编码, 所以 slice/roll 那批
--      SKU 当时一条都没被处理 (F006 现存 slice 6 / roll 1)。
--   2. 单位字典 UI 能看到、能选到。
--   3. 两个来源不再靠兜底维持一致。
--
-- ## 取值
-- 完全照 `box` 那行的形状 (category=COUNT / base_unit=pcs / conversion_factor=1 /
-- decimal_places=0 / conversion_family=COUNT / is_system=t / factory_id='*'),
-- 中文名取权威别名表 `systemAliases()` 的第一个中文值。
-- usage_scopes_json 与其余 COUNT 单位一致, 否则写入侧
-- `RawMaterialTypeServiceImpl#normalizeInventoryUnit` 的 `supportsUsage(INVENTORY_QUANTITY)`
-- 会把它们判成「不能用于入库计量」——**加了名录却不给 scope, 等于没加**。
--
-- ## 幂等
-- `WHERE NOT EXISTS` 按 (factory_id, unit_code) 判重 —— 与表上的
-- UNIQUE (factory_id, unit_code) 同键, 重复执行不会插入第二份, 也不会撞唯一约束。
-- 已存在的行一律不改 (含工厂自建的同名私有单位, 例如 F006 的「半只」)。
--
-- ## 刻意不动
-- 名录里的 `ton` 与权威表里的 `t` 是**同一个物理单位两个码**, 同理名录缺 `km`。
-- 那属于 WEIGHT/LENGTH 且牵涉换算系数, 不在本次「补计数单位」的范围内, 单独记录待处理。

INSERT INTO unit_of_measurements (
    id, factory_id, unit_code, unit_name, unit_symbol,
    category, conversion_family, base_unit, is_base_unit, conversion_factor, decimal_places,
    is_system, is_active, sort_order, usage_scopes_json, created_at, updated_at
)
SELECT
    gen_random_uuid()::varchar, '*', seed.unit_code, seed.unit_name, seed.unit_name,
    'COUNT', 'COUNT', 'pcs', false, 1.000000, 0,
    true, true, seed.sort_order,
    '["INVENTORY_QUANTITY", "PURCHASE_QUANTITY", "BOM_QUANTITY", "SPECIFICATION"]'::jsonb,
    now(), now()
FROM (VALUES
    ('roll',    '卷', 11),
    ('slice',   '片', 12),
    ('portion', '份', 13),
    ('crate',   '框', 14),
    ('pail',    '桶', 15),
    ('item',    '项', 16)
) AS seed(unit_code, unit_name, sort_order)
WHERE NOT EXISTS (
    SELECT 1 FROM unit_of_measurements existing
    WHERE existing.factory_id = '*'
      AND lower(existing.unit_code) = lower(seed.unit_code)
);
