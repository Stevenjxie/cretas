-- SP6 采购到付款 — 采购会计科目配置表（可选）
-- 为 AI 助手提供科目查询支持，默认插入 6 个通用科目
-- 幂等: CREATE IF NOT EXISTS + ON CONFLICT DO NOTHING

CREATE TABLE IF NOT EXISTS purchase_accounting_subjects (
    id          VARCHAR(191)  NOT NULL PRIMARY KEY,
    factory_id  VARCHAR(191)  NOT NULL,
    code        VARCHAR(50)   NOT NULL,
    name        VARCHAR(100)  NOT NULL,
    category    VARCHAR(32)   NOT NULL,    -- ASSET / LIABILITY / EXPENSE
    is_active   BOOLEAN       NOT NULL DEFAULT TRUE,
    sort_order  INTEGER       NOT NULL DEFAULT 0,
    created_at  TIMESTAMP     NOT NULL DEFAULT NOW(),
    updated_at  TIMESTAMP     NOT NULL DEFAULT NOW(),
    deleted_at  TIMESTAMP
);

CREATE UNIQUE INDEX IF NOT EXISTS uidx_purchase_accounting_subjects_code
    ON purchase_accounting_subjects (factory_id, code)
    WHERE deleted_at IS NULL;

-- 默认通用科目（factory_id = '_SYSTEM'，各工厂复用）
INSERT INTO purchase_accounting_subjects (id, factory_id, code, name, category, sort_order)
VALUES
    (gen_random_uuid(), '_SYSTEM', '2202', '应付账款',       'LIABILITY', 10),
    (gen_random_uuid(), '_SYSTEM', '2241', '其他应付款',      'LIABILITY', 20),
    (gen_random_uuid(), '_SYSTEM', '1122', '应收账款',       'ASSET',     30),
    (gen_random_uuid(), '_SYSTEM', '6401', '主营业务成本',    'EXPENSE',   40),
    (gen_random_uuid(), '_SYSTEM', '1403', '原材料',         'ASSET',     50),
    (gen_random_uuid(), '_SYSTEM', '1123', '预付账款',       'ASSET',     60)
ON CONFLICT DO NOTHING;

COMMENT ON TABLE  purchase_accounting_subjects           IS 'SP6: 采购会计科目配置（AI助手查询用）';
COMMENT ON COLUMN purchase_accounting_subjects.category IS 'ASSET=资产, LIABILITY=负债, EXPENSE=费用';
