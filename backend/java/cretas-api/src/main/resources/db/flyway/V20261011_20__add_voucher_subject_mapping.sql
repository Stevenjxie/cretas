-- SP11: 结算属性→会计科目映射表
-- 目的: 采购 6 种结算方式各自对应借贷科目, 替代硬编码

CREATE TABLE IF NOT EXISTS voucher_subject_mappings (
    id                  VARCHAR(191) PRIMARY KEY,
    factory_id          VARCHAR(191) NOT NULL,
    settlement_type     VARCHAR(32)  NOT NULL,
    business_type       VARCHAR(32),
    debit_subject_code  VARCHAR(32)  NOT NULL,
    debit_subject_name  VARCHAR(64),
    credit_subject_code VARCHAR(32)  NOT NULL,
    credit_subject_name VARCHAR(64),
    remark              VARCHAR(255),
    created_at          TIMESTAMP    NOT NULL DEFAULT NOW(),
    updated_at          TIMESTAMP    NOT NULL DEFAULT NOW(),
    deleted_at          TIMESTAMP,
    UNIQUE (factory_id, settlement_type, business_type)
);

CREATE INDEX IF NOT EXISTS idx_vsm_factory ON voucher_subject_mappings(factory_id);
CREATE INDEX IF NOT EXISTS idx_vsm_settlement ON voucher_subject_mappings(factory_id, settlement_type);

CREATE TRIGGER trg_vsm_updated_at
    BEFORE UPDATE ON voucher_subject_mappings
    FOR EACH ROW EXECUTE FUNCTION update_updated_at();

-- 种子数据: 系统默认科目映射 (__default__ factory)
INSERT INTO voucher_subject_mappings
    (id, factory_id, settlement_type, business_type,
     debit_subject_code, debit_subject_name, credit_subject_code, credit_subject_name)
VALUES
    (gen_random_uuid(), '__default__', 'PREPAID',       'PURCHASE', '1405', '原材料', '1002', '银行存款'),
    (gen_random_uuid(), '__default__', 'CREDIT_FIRST',  'PURCHASE', '1405', '原材料', '2202', '应付账款'),
    (gen_random_uuid(), '__default__', 'NO_INVOICE',    'PURCHASE', '1405', '原材料', '2241', '暂估应付款'),
    (gen_random_uuid(), '__default__', 'MONTHLY',       'PURCHASE', '1405', '原材料', '2202', '应付账款'),
    (gen_random_uuid(), '__default__', 'CREDIT_PERIOD', 'PURCHASE', '1405', '原材料', '2202', '应付账款'),
    (gen_random_uuid(), '__default__', 'IMMEDIATE',     'PURCHASE', '1405', '原材料', '1001', '库存现金')
ON CONFLICT DO NOTHING;
