-- 六扇门 配料员按锅配料单 (转录 [87:50-88:00]): product_types 加 单锅产能。
-- 1 锅可产出本产品的数量 (与计划产量同单位)。配料单据此算 锅数 = ceil(计划量/单锅产能)。
-- prod ddl-auto=validate, 故必须迁移 (否则实体新字段校验失败启动报错)。幂等 ADD COLUMN IF NOT EXISTS。
ALTER TABLE product_types ADD COLUMN IF NOT EXISTS single_pot_capacity NUMERIC(14, 3);

COMMENT ON COLUMN product_types.single_pot_capacity IS '单锅产能: 1锅产出数量(同计划产量单位), 配料单算锅数用; null=未配置';
