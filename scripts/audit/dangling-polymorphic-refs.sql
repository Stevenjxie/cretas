-- ============================================================================
-- 悬空多态引用审计 —— material_batches.source_doc_id
--
-- 用法:
--   ssh root@47.100.235.168 "sudo -u postgres psql -d cretas_prod_db" < scripts/audit/dangling-polymorphic-refs.sql
--   或本地: psql -d cretas_db -f scripts/audit/dangling-polymorphic-refs.sql
--
-- ── 为什么需要这个脚本 ──────────────────────────────────────────────────────
-- `material_batches.source_doc_id` 是 varchar 的**多态外键**: 同一列按
-- `source_doc_type` 指向<b>不同的表</b>。多态列<b>无法加 FK 约束</b>(FK 只能指向单一表),
-- 所以数据库层面<b>没有任何东西</b>阻止它指向一个已被删除的行。
--
-- 触发器也解决不了: 悬空可以由**目标表被删**产生, 而删除方是谁事先并不知道
-- (实测就是一次库外手工操作, 应用代码 `productionBatchRepository.delete*` 全仓 0 处,
--  flyway 里也没有 `DELETE FROM production_batches`)。
-- 对多态引用, <b>周期性检测</b>是唯一可行的手段 —— 故有此脚本。
--
-- ── 2026-08-04 首次运行的 prod 基线 ────────────────────────────────────────
--   PRODUCTION_BATCH      259 行 / **247 悬空**  (production_batches 只剩 19 行,
--                                                 id 10623-10641, 8/2 以前的整批消失)
--   MATERIAL_REQUISITION   28 行 / ** 24 悬空**
--   OPENING                32 行 /   不适用      (值是标签如 LSM-REBUILD-OPENING-20260801,
--                                                 不是行 id, 本就不是引用)
--
-- ⚠️ 悬空**不会**让应用崩: `OrderCostBreakdownService#traceCost` 在上游查不到消耗时
--    优雅降级成叶子(返回该消耗行自身 totalCost)。**成本总额仍正确**, 坏的是
--    <b>成本分桶</b>(上游那段无法再拆成 raw/labor/seasoning)与<b>批次溯源链</b>。
--    所以本脚本是**监测**用途, 报出非零不等于线上故障 —— 先看增量, 别看绝对值。
-- ============================================================================

\echo ''
\echo '=== 1. source_doc_type 分布 (基数) ==='
SELECT COALESCE(source_doc_type, '(null)') AS source_doc_type,
       count(*)            AS 总行数,
       count(source_doc_id) AS 带id行数
FROM material_batches
WHERE deleted_at IS NULL
GROUP BY 1
ORDER BY 2 DESC;

\echo ''
\echo '=== 2. 悬空引用汇总 (核心指标) ==='
-- 只检真正是"行 id"的两种类型; OPENING 是自由文本标签, 不参与。
SELECT 'PRODUCTION_BATCH' AS source_doc_type,
       count(*) AS 总数,
       count(*) FILTER (
           WHERE NOT EXISTS (SELECT 1 FROM production_batches pb
                             WHERE pb.id::text = b.source_doc_id)
       ) AS 悬空
FROM material_batches b
WHERE b.deleted_at IS NULL AND b.source_doc_type = 'PRODUCTION_BATCH'
UNION ALL
SELECT 'MATERIAL_REQUISITION',
       count(*),
       count(*) FILTER (
           WHERE NOT EXISTS (SELECT 1 FROM factory_material_requisitions r
                             WHERE r.id = b.source_doc_id)
       )
FROM material_batches b
WHERE b.deleted_at IS NULL AND b.source_doc_type = 'MATERIAL_REQUISITION';

\echo ''
\echo '=== 3. 悬空按工厂 x 月份 (分辨"历史遗留" vs "还在新增") ==='
-- 判据: 只在旧月份出现 = 一次性事故的残留; 出现在**当月** = 还在持续产生, 要查根因。
SELECT b.factory_id,
       to_char(b.created_at, 'YYYY-MM') AS 月份,
       count(*) AS 悬空数
FROM material_batches b
WHERE b.deleted_at IS NULL
  AND b.source_doc_type = 'PRODUCTION_BATCH'
  AND NOT EXISTS (SELECT 1 FROM production_batches pb
                  WHERE pb.id::text = b.source_doc_id)
GROUP BY 1, 2
ORDER BY 2 DESC, 3 DESC;

\echo ''
\echo '=== 4. 仍有可用余量的悬空批次 (真正要人管的那些) ==='
-- 已 USED_UP 的悬空只影响历史溯源; **仍 AVAILABLE 且有余量**的会被投进后续生产,
-- 那时成本分桶就会降级 —— 这些才需要人决定补不补来源。
SELECT b.factory_id,
       b.batch_number,
       b.status,
       (COALESCE(b.receipt_quantity,0) - COALESCE(b.used_quantity,0)
        - COALESCE(b.reserved_quantity,0)) AS 可用,
       b.quantity_unit AS 单位,
       b.unit_price    AS 单价,
       b.created_at::date AS 建于
FROM material_batches b
WHERE b.deleted_at IS NULL
  AND b.source_doc_type = 'PRODUCTION_BATCH'
  AND NOT EXISTS (SELECT 1 FROM production_batches pb
                  WHERE pb.id::text = b.source_doc_id)
  AND (COALESCE(b.receipt_quantity,0) - COALESCE(b.used_quantity,0)
       - COALESCE(b.reserved_quantity,0)) > 0
ORDER BY 4 DESC
LIMIT 40;

\echo ''
\echo '=== 5. production_batches 现存 id 区间 (整批消失会在这里露出来) ==='
-- 2026-08-04 基线: min=10623 max=10641 count=19。
-- 若 min 明显上移而 material_batches 里旧引用还在 → 又发生了一次库外删除。
SELECT min(id) AS 最小id, max(id) AS 最大id, count(*) AS 现存行数
FROM production_batches;
