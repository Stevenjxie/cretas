-- ============================================================
-- 授予 smartbi 应用角色 (smartbi_user) 对动态化配置表的写权限
-- 日期: 2026-07-11
-- 数据库: smartbi_db (test) / smartbi_prod_db (prod)
--
-- ⛔ 背景: V20260711_01 创建的 8 张动态化配置表由 migration runner (postgres)
-- 创建 → owner=postgres, 但未 GRANT 给应用角色 smartbi_user。核实 (2026-07-11):
-- smartbi_user 只有 SELECT, 无 INSERT/UPDATE/DELETE。后果: DynamicConfigResolver
-- 的写路径 (persist_factory_override 等) 对**所有**餐饮租户 "permission denied
-- for table" 静默失败 —— 客户/审核写入的 config override / 命名归一 / COGS 校准 /
-- 替代记录 / 异常告警 全部落不了库, 表永远空, 相关功能形同虚设。
--
-- 本迁移补齐这 8 表 + 各自 BIGSERIAL 序列的写权限。幂等 (GRANT 可重复执行)。
-- ============================================================

GRANT SELECT, INSERT, UPDATE, DELETE ON
    business_config_overrides,
    restaurant_dish_alias,
    restaurant_cogs_overrides,
    restaurant_loss_factor_baselines,
    restaurant_category_price_calibrated,
    restaurant_substitution_log,
    restaurant_cost_anomalies,
    alias_review_queue
TO smartbi_user;

GRANT USAGE, SELECT ON SEQUENCE
    business_config_overrides_id_seq,
    restaurant_dish_alias_id_seq,
    restaurant_cogs_overrides_id_seq,
    restaurant_loss_factor_baselines_id_seq,
    restaurant_category_price_calibrated_id_seq,
    restaurant_substitution_log_id_seq,
    restaurant_cost_anomalies_id_seq,
    alias_review_queue_id_seq
TO smartbi_user;
