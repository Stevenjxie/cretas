-- v1 smart_bi_field_mapping_candidates(当前空)泛化为多域学习候选表 smart_bi_learning_candidates。
-- 把 field-mapping 专用的 (column_name, standard_field) 泛化为 (source_key, target_value),
-- 加 learning_type(field_mapping/classification/data_cleaning) + business_type(行业分支 scope)。
-- rename 保留 grant(#367 已授给 smartbi_user)+ RLS policy(tenant_insert/select/update)。
-- 仍无 DELETE policy(清理用 postgres superuser, 同 v1)。全部 IF [NOT] EXISTS 幂等。
ALTER TABLE IF EXISTS smart_bi_field_mapping_candidates RENAME TO smart_bi_learning_candidates;
ALTER TABLE smart_bi_learning_candidates RENAME COLUMN column_name TO source_key;
ALTER TABLE smart_bi_learning_candidates RENAME COLUMN standard_field TO target_value;
ALTER TABLE smart_bi_learning_candidates ADD COLUMN IF NOT EXISTS learning_type VARCHAR(32) NOT NULL DEFAULT 'field_mapping';
ALTER TABLE smart_bi_learning_candidates ADD COLUMN IF NOT EXISTS business_type VARCHAR(50) NOT NULL DEFAULT 'unknown';
-- UNIQUE 从 (column_name, standard_field, factory_id) 重建为含 learning_type。
ALTER TABLE smart_bi_learning_candidates DROP CONSTRAINT IF EXISTS smart_bi_field_mapping_candid_column_name_standard_field_fa_key;
ALTER TABLE smart_bi_learning_candidates ADD CONSTRAINT uq_learning_candidate
  UNIQUE (learning_type, source_key, target_value, factory_id);
-- 旧索引 idx_fmc_colstd 随列 rename 自动指向 (source_key, target_value), 保留。
