-- G2: 工序自定义字段 schema (config-driven 逐工序电子表格自定义列, 如 波美度/添加剂量/备注).
-- additive only: nullable jsonb, 无默认值 (null = 该工序未开启自定义字段, 行为不变).
ALTER TABLE work_processes
    ADD COLUMN IF NOT EXISTS custom_field_schema JSONB;
