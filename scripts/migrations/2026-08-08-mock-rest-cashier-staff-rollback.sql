-- 回滚 V20261101_10__mock_rest_cashier_staff_and_backfill.sql
--
-- 🔴 这个文件**必须留在 scripts/migrations/ 且文件名不带 V 前缀**。
--    2026-08-08 实测: 它最初叫 `V20261101_10__ROLLBACK.sql` 并放在
--    smartbi/database/migrations/ 里, 而 apply-smartbi-migrations.sh 是按
--    `V*` glob 发现文件的 —— 部署时它先应用了迁移, **紧接着把这个回滚也当成
--    下一条迁移应用了**, 净效果是数据写进去又被自己撤销(日志 "applied: 2")。
--    回滚脚本放进自动应用的目录 = 给自己写了一条撤销自己的迁移。
--
-- 用法(手动, 连库):
--    psql "$SMARTBI_DSN" -f scripts/migrations/2026-08-08-mock-rest-cashier-staff-rollback.sql
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
