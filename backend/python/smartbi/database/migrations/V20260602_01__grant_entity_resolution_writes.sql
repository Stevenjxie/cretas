-- entity_resolution_* 三张写表 (V20260426_01 建, owner=postgres) 从未 GRANT 给运行时角色
-- smartbi_user → 只有默认 SELECT。结果: orchestrator 写 entity_resolution_history /
-- admin 确认 UPDATE entity_resolution_admin_queue / 写 entity_resolution_labels 全部
-- "permission denied for table" → 整个实体解析写路径死在 prod (history+queue 都 0 行)。
-- 由 #389 (admin 确认毕业进 history) 的真库 E2E 实测抓出。
-- 补齐 DML + sequence, 对齐既定授权约定 (V20260601_02 candidate / V20260531_01 egress/distillation)。
-- GRANT 幂等, 可安全重复 apply。runner 以 postgres 连 (表 owner) 故可授权。
GRANT SELECT, INSERT, UPDATE, DELETE ON entity_resolution_history TO smartbi_user;
GRANT SELECT, INSERT, UPDATE, DELETE ON entity_resolution_admin_queue TO smartbi_user;
GRANT SELECT, INSERT, UPDATE, DELETE ON entity_resolution_labels TO smartbi_user;
GRANT USAGE, SELECT ON SEQUENCE entity_resolution_history_id_seq TO smartbi_user;
GRANT USAGE, SELECT ON SEQUENCE entity_resolution_admin_queue_id_seq TO smartbi_user;
GRANT USAGE, SELECT ON SEQUENCE entity_resolution_labels_id_seq TO smartbi_user;
