-- 字段映射候选表 (V20260601_01) 建表时表归 postgres, 但漏写 GRANT。
-- 结果 app 用户 smartbi_user 只有默认 SELECT (r), 无 INSERT/UPDATE/DELETE →
-- field_promotion.capture_candidate 的 upsert 在 prod 每次都
--   "permission denied for table smart_bi_field_mapping_candidates"
-- 被 capture 的 fail-open try/except 吞掉, 候选表永远 0 行 —— 毕业闭环的
-- capture 环节实际从未生效 (2026-06-01 端到端验证发现)。
--
-- 补全 DML + sequence USAGE, 对齐 V20260531_01 (smart_bi_distillation_samples)
-- 已确立的 grant 约定 ("表归 postgres → 必须 GRANT 给 app 用户 smartbi_user")。
-- 幂等: GRANT 可重复执行, 不影响 RLS 策略 (RLS 在 grant 之后才求值)。
GRANT SELECT, INSERT, UPDATE, DELETE ON smart_bi_field_mapping_candidates TO smartbi_user;
GRANT USAGE, SELECT ON SEQUENCE smart_bi_field_mapping_candidates_id_seq TO smartbi_user;
