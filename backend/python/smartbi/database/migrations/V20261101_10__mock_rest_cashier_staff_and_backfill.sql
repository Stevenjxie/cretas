-- MOCK_REST 员工维度补齐 + 生成器漏写的两列回填
--
-- 背景 (2026-08-08 实测):
--   · dim_staff 对**所有租户**都是 0 行 —— 这个维度从来没被造过, 不是 MOCK_REST
--     独有的缺口。fact_pos_transaction.staff_id 因此全 NULL, 而
--     gold.queries.staff_ranking 里有 `t.staff_id IS NOT NULL`, 于是那个
--     REST 端点对任何租户都永远返回空。
--   · has_discount 同样全 NULL: 生成器
--     smartbi/ingestion/platforms/writer.py 的 INSERT 列清单里压根没有这两列。
--
-- ⛔ 人数口径不自己拍: `restaurant_staffing_policy` 是「现有人数」的权威
--    (预测排班答案里的「现有人数合计 280」就来自它)。它的 cashier 角色合计正好
--    40 = 10 店 x 4 时段 x 1 人 —— 所以这里造 **40 名收银员**(每店 4 名, 各守
--    一个时段), 与权威表逐条对得上。造一个别的数字就是制造第二个打架的口径。
--
-- ⚠️ POS 侧 meal_period 只有「午市」「晚市」有值, 另有 34,820 行是 NULL。
--    NULL 的行**不回填 staff_id** —— 不知道哪个时段就不知道谁收的银, 不猜。
--
-- 📌 staff_id 的语义是 **POS 收银操作员**, 不是服务员归属。staff_ranking 的
--    docstring 已经带了这条免责, 这里的数据必须与那个说法一致。
--
-- 回滚: V20261101_10__ROLLBACK.sql (同目录)。回滚把两列置回 NULL 并删掉这 40 行。

BEGIN;

-- 1) 每店 4 名收银员, 按时段顺序插入 (午市/下午茶/晚市/夜宵)。
--    插入顺序决定了下面回填时的 daypart 对应关系, 不靠解析名字。
--    命名规则 `<门店名><时段>收银` 是**契约**: 第 2 步靠反查这个名字定位人,
--    不解析字符串。`(factory_id, name, store_id)` 上有唯一约束, 所以本迁移可重跑。
INSERT INTO dim_staff (factory_id, name, role, store_id, created_at, updated_at)
SELECT 'MOCK_REST',
       s.name || dp.daypart || '收银',
       'cashier',
       s.store_id,
       NOW(), NOW()
  FROM dim_store s
  CROSS JOIN (VALUES ('午市'), ('下午茶'), ('晚市'), ('夜宵')) AS dp(daypart)
 WHERE s.factory_id = 'MOCK_REST'
ON CONFLICT (factory_id, name, store_id) DO NOTHING;

-- 2) 回填 staff_id: 按 (门店, 时段) 唯一确定一名收银员。
--    ⛔ meal_period IS NULL 的行不动 —— 见文件头。
UPDATE fact_pos_transaction t
   SET staff_id = d.staff_id
  FROM dim_staff d
  JOIN dim_store s
    ON s.store_id = d.store_id
   AND s.factory_id = 'MOCK_REST'
 WHERE t.factory_id = 'MOCK_REST'
   AND t.staff_id IS NULL
   AND t.meal_period IS NOT NULL
   AND d.factory_id = 'MOCK_REST'
   AND d.role = 'cashier'
   AND d.store_id = t.store_id
   AND d.name = s.name || t.meal_period || '收银';

-- 3) 回填 has_discount, 口径与 canonical/normalizer.py:189 逐字一致
--    (`row.discount_amount is not None and row.discount_amount > 0`)。
--    ⛔ 不是「有没有折扣记录」而是「折扣金额是否为正」—— 两处必须同口径。
UPDATE fact_pos_transaction
   SET has_discount = (COALESCE(discount_amount, 0) > 0)
 WHERE factory_id = 'MOCK_REST'
   AND has_discount IS NULL;

COMMIT;
