-- 回滚 V20261101_10__mock_rest_cashier_staff_and_backfill.sql
--
-- ⛔ 顺序不能反: 先把 fact 表的 staff_id 置回 NULL, 再删 dim_staff 行。
--    dim_staff 上没有指向它的外键(fact_pos_transaction.staff_id 是裸 bigint),
--    所以先删父行不会报错 —— 它会**静默留下指向已删 id 的孤儿**。
--    这正是 2026-08-07 那次租户清理踩到的形态(370 张表删父行一声不吭留孤儿)。
--
-- 📌 这个回滚只还原「本迁移写进去的东西」:
--    · staff_id: 迁移前 MOCK_REST 全表都是 NULL(已实测 236,954/236,954), 所以
--      整列置 NULL 就是逐字还原, 不需要行级备份。
--    · has_discount: 同上, 迁移前全 NULL。
--    如果将来这两列已有别的来源写入, **这个回滚就不再安全**, 必须改成行级备份。

BEGIN;

UPDATE fact_pos_transaction
   SET staff_id = NULL
 WHERE factory_id = 'MOCK_REST'
   AND staff_id IS NOT NULL;

UPDATE fact_pos_transaction
   SET has_discount = NULL
 WHERE factory_id = 'MOCK_REST'
   AND has_discount IS NOT NULL;

DELETE FROM dim_staff
 WHERE factory_id = 'MOCK_REST'
   AND role = 'cashier'
   AND name LIKE '%收银';

DELETE FROM smartbi_migrations
 WHERE version = 'V20261101_10';

COMMIT;
