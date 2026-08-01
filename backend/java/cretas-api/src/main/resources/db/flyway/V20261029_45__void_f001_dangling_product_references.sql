-- F001 悬空产品引用作废: 28 行, 全部软删并逐 id 记台账。
-- Steve 2026-08-01:「2 直接作废删除干净」/ 追问 C 组是否保留时:「都别留了」。
--
-- ## 背景
-- 删 SKU 时走的是硬删, 而 sales_order_items / finished_goods_batches / bom_recipes
-- 对 product_types **没有外键**, 于是删除不被拦, 这些行留下一个指向已不存在 SKU 的
-- product_type_id。页面看着一切正常, 一到建发货单(sales_delivery_items 有外键)才被拒,
-- 报错只给数据库表名。代码侧已在 #2135 修好(删除前查引用 + 报错指到模块), 本迁移清数据。
--
-- ## 🔴 写死 id 而不是用「指向不存在的 SKU」这个动态谓词
-- V20261029_44 的教训: 动态谓词在**部署那一刻**可能与写迁移时不同。
-- 这里若用动态谓词, 从现在到部署之间新产生的悬空行会被一并删掉 ——
-- 而 Steve 点头的是**下面这 28 行**, 新出现的是新决定。写死 id 在任何时刻都确定。
-- (下方 VERIFY 段会核对「写死的 id 数」= 实际命中数, 对不上就是有人动过。)
--
-- ## 三组, 性质不同, 但 Steve 拍板全部作废
--
-- A. sales_order_items 18 行 —— 测试数据
--    「测试01」×17 (¥7,755) + 「E2E测试鱼丸」×1 (¥5,000); delivered_quantity 全 0;
--    实测 sales_delivery_items 引用数 = 0, 作废不牵连任何东西。
--
-- B. bom_recipes 1 行 —— 垃圾
--    BOM-LEGACY-F001-000001, DRAFT, is_current=false,
--    product_name 那一栏存的直接是一串 UUID。
--
-- C. finished_goods_batches 9 行 —— ⚠️ 幽灵库存, 且被发货记录引用
--    2025-01/02 的种子批次(有机全麦面包/香酥鱼柳/黄金虾排/鱿鱼圈/带鱼段/黄鱼片/
--    大虾仁/墨鱼丸) + 1 条 2026-03-10 自动批次。指向 PT-001 / PT-002 / PT003 三个
--    已硬删的 SKU, 却仍是 status=AVAILABLE, **合计约 29,175 kg「可用」库存**。
--    实测 **15 条 sales_delivery_items 引用这些批次**。
--
--    📌 这 15 条发货记录**刻意不动**: 它们是发生过的出货历史, 删掉等于抹掉发货事实。
--    它们此刻本来就解析不出产品(SKU 已硬删), 作废批次不会让它们从「好」变「坏」,
--    只是把幽灵库存从可用池里拿走。
--
-- ## ⚠️ 类型陷阱 (V20261029_44 实测踩过, 只在回滚那一刻才炸)
-- sales_order_items.id 是 **bigint**, 而 finished_goods_batches.id / bom_recipes.id
-- 与台账 object_id 都是 varchar。写台账的 INSERT 有隐式转换, 一切正常;
-- 回滚脚本里的 `id IN (SELECT object_id ...)` 比较才会报
-- `operator does not exist: bigint = character varying`。回滚脚本已显式 ::varchar。
--
-- ## 台账与回滚
-- 独立台账表 backup_f001_dangling_20260801, 与六膳门那张互不干扰。
-- 回滚脚本: db/manual-rollback/V20261029_45__void_f001_dangling_product_references_rollback.sql
--
-- ## 幂等
-- 每条 UPDATE 带 deleted_at IS NULL; 台账 ON CONFLICT DO NOTHING。

CREATE TABLE IF NOT EXISTS backup_f001_dangling_20260801 (
    object_type varchar(64)  NOT NULL,
    object_id   varchar(64)  NOT NULL,
    recorded_at timestamp    NOT NULL DEFAULT now(),
    PRIMARY KEY (object_type, object_id)
);

-- ---------- 台账: 先记 id, 再软删 ----------

-- A. 销售订单明细 18 行 (id 是 bigint, 记进 varchar 台账靠隐式转换)
INSERT INTO backup_f001_dangling_20260801 (object_type, object_id)
SELECT 'v45:sales_order_items', i.id::varchar
FROM sales_order_items i
WHERE i.deleted_at IS NULL
  AND i.id IN (203, 281, 282, 283, 284, 285, 286, 287, 288, 289,
               290, 291, 292, 293, 294, 295, 296, 349)
ON CONFLICT DO NOTHING;

-- B. BOM 1 行
INSERT INTO backup_f001_dangling_20260801 (object_type, object_id)
SELECT 'v45:bom_recipes', r.id
FROM bom_recipes r
WHERE r.deleted_at IS NULL
  AND r.id = '92707c0c-94a3-488f-9743-a998691e31c4'
ON CONFLICT DO NOTHING;

-- C. 成品批次 9 行
INSERT INTO backup_f001_dangling_20260801 (object_type, object_id)
SELECT 'v45:finished_goods_batches', b.id
FROM finished_goods_batches b
WHERE b.deleted_at IS NULL
  AND b.id IN ('FGB-F001-202501-001', 'FGB-F001-202501-002',
               'FGB-F001-202501-003', 'FGB-F001-202501-004',
               'FGB-F001-202502-001', 'FGB-F001-202502-002',
               'FGB-F001-202502-003', 'FGB-F001-202502-004',
               '9f4e0a74-3e44-49b6-a3c6-06d760d955cb')
ON CONFLICT DO NOTHING;

-- ---------- 软删 ----------

UPDATE sales_order_items
SET deleted_at = now(), updated_at = now()
WHERE deleted_at IS NULL
  AND id IN (203, 281, 282, 283, 284, 285, 286, 287, 288, 289,
             290, 291, 292, 293, 294, 295, 296, 349);

UPDATE bom_recipes
SET deleted_at = now(), updated_at = now()
WHERE deleted_at IS NULL
  AND id = '92707c0c-94a3-488f-9743-a998691e31c4';

UPDATE finished_goods_batches
SET deleted_at = now(), updated_at = now()
WHERE deleted_at IS NULL
  AND id IN ('FGB-F001-202501-001', 'FGB-F001-202501-002',
             'FGB-F001-202501-003', 'FGB-F001-202501-004',
             'FGB-F001-202502-001', 'FGB-F001-202502-002',
             'FGB-F001-202502-003', 'FGB-F001-202502-004',
             '9f4e0a74-3e44-49b6-a3c6-06d760d955cb');

-- ---------- VERIFY: 台账必须正好 28 行, 否则说明现场与写迁移时不同 ----------
-- 少于 28 = 有行已被别处删掉或 id 变了; 多于 28 = 台账被污染。
-- 任一情况都应停下来核对, 而不是让迁移「差不多跑过去」。
DO $$
DECLARE
    n integer;
BEGIN
    SELECT count(*) INTO n
    FROM backup_f001_dangling_20260801
    WHERE object_type LIKE 'v45:%';

    IF n <> 28 THEN
        RAISE EXCEPTION
            'V20261029_45 台账应为 28 行(明细18/批次9/BOM1), 实际 %。现场与写迁移时不一致, 请先核对再部署。', n;
    END IF;
END $$;
