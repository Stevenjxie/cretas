-- 补授权: V20260601_01 建表时漏了对运行时角色 smartbi_user 的 INSERT/UPDATE 授权
-- (建表 owner=postgres, smartbi_user 仅从默认权限拿到 SELECT)。
-- 结果: capture_candidate 的 INSERT ... ON CONFLICT DO UPDATE 在 prod 报
-- "permission denied for table smart_bi_field_mapping_candidates", 被 fail-open 吞掉,
-- 自学习 capture 半边从未真正写入。本迁移对齐其它表的标准授权 (见 V20260531_01 egress / distillation)。
-- GRANT 幂等, 可安全重复 apply。
GRANT SELECT, INSERT, UPDATE, DELETE ON smart_bi_field_mapping_candidates TO smartbi_user;
GRANT USAGE, SELECT ON SEQUENCE smart_bi_field_mapping_candidates_id_seq TO smartbi_user;
