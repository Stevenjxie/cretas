-- 补 V20261101_03 漏掉的 GRANT
--
-- V20261101_03 建了 platform_ingest_dead_letter 但**没有授权给 smartbi_user**
-- (应用实际连库用的账号)。库里的 default ACL 只给读:
--   pg_default_acl -> {smartbi_user=r/postgres}
-- 于是应用 INSERT 时 `permission denied for table platform_ingest_dead_letter`,
-- 隔离写必然失败 → 按设计抛错 → 游标停住 → **退回到「卡住」的老行为**。
-- 失败是安全的(不丢数据), 但死信表等于没生效。
--
-- 根因: V20261101_03 的注释写着「runner 以 sudo -u postgres 执行(超级用户绕过
-- RLS), 故本文件不需要额外的 GRANT」—— 那句话说的是**跑 migration 本身**不需要,
-- 不代表**应用账号**有权限。同批的 V20261101_01 其实两张表都显式 GRANT 了
-- (第 64/117 行), 抄注释时漏看了 GRANT 语句。
--
-- 🔴 只有以 smartbi_user 身份才验得出来: 超级用户 postgres 绕过权限, 拿它验
--    一切正常。

BEGIN;

GRANT SELECT, INSERT, UPDATE, DELETE ON platform_ingest_dead_letter TO smartbi_user;
GRANT USAGE, SELECT ON SEQUENCE platform_ingest_dead_letter_id_seq TO smartbi_user;

COMMIT;
