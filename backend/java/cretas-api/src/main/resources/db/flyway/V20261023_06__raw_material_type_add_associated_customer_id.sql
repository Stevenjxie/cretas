-- P8: 包材关联固定客户 ID
-- raw_material_types 新增 associated_customer_id 列 (nullable).
-- 包材可关联一个固定客户 (软引用 customers.id, 不加 FK 约束避免级联删除).
-- 非包材物料此字段留 null, 业务层保证写入时仅包材类目才填.

ALTER TABLE raw_material_types
    ADD COLUMN IF NOT EXISTS associated_customer_id VARCHAR(191);

COMMENT ON COLUMN raw_material_types.associated_customer_id
    IS 'P8: 包材关联固定客户ID (nullable, 软引用 customers.id, 非包材留null)';
