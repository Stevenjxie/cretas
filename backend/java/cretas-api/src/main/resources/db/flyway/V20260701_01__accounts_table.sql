-- Sprint 7 T1 F-VOUCHER-2 Phase B: 会计科目 chart of accounts
-- 配合 Voucher / VoucherEntry (Sprint 3-E + Sprint 5-F + Sprint 6 W4-A 已 shipped) 使用.
-- VoucherEntry.subject_code 当前是自由文本 (后向兼容), 此表用于 admin 维护 + Sprint 7 T3
-- 报表三表 (资产负债 / 利润 / 现金流) 按 category + balance_type 聚合.
--
-- 关键 design:
--   - factory_id NULL = 系统级标准 GAAP 科目, 任何 factory 共享 (seed V20260701_02 写入)
--   - factory_id 非空 = factory 自定义科目, 仅该 factory 可见
--   - code 在 factory 内 unique (含系统级 NULL factoryId 自己一组)
--   - parent_id 形成 4 级树, level 1-4

CREATE TABLE IF NOT EXISTS accounts (
    id            VARCHAR(191) PRIMARY KEY,
    factory_id    VARCHAR(191),               -- NULL = 系统级标准科目
    code          VARCHAR(32)  NOT NULL,
    name          VARCHAR(100) NOT NULL,
    parent_id     VARCHAR(191),
    level         INTEGER      NOT NULL DEFAULT 1
                      CHECK (level BETWEEN 1 AND 4),
    category      VARCHAR(16)  NOT NULL
                      CHECK (category IN ('ASSET','LIABILITY','EQUITY',
                                          'REVENUE','EXPENSE','COST')),
    balance_type  VARCHAR(16)  NOT NULL
                      CHECK (balance_type IN ('DEBIT_NORMAL','CREDIT_NORMAL')),
    description   VARCHAR(500),
    active        BOOLEAN      NOT NULL DEFAULT TRUE,
    sort_order    INTEGER      NOT NULL DEFAULT 0,
    created_at    TIMESTAMP    NOT NULL DEFAULT NOW(),
    updated_at    TIMESTAMP    NOT NULL DEFAULT NOW(),
    deleted_at    TIMESTAMP,

    -- 自引用 FK (parent_id)
    CONSTRAINT fk_account_parent FOREIGN KEY (parent_id)
        REFERENCES accounts(id) ON DELETE RESTRICT
);

-- 索引
CREATE INDEX IF NOT EXISTS idx_account_factory  ON accounts(factory_id);
CREATE INDEX IF NOT EXISTS idx_account_code     ON accounts(code);
CREATE INDEX IF NOT EXISTS idx_account_parent   ON accounts(parent_id);
CREATE INDEX IF NOT EXISTS idx_account_category ON accounts(category);
CREATE INDEX IF NOT EXISTS idx_account_active   ON accounts(active);

-- 同 factory 内 code unique. PostgreSQL 默认 NULL != NULL, 所以多个 NULL factoryId
-- 行 unique(factory_id, code) 不会冲突. 用 partial unique index 处理两种情况:
--   1) factory_id 非空时, (factory_id, code) 联合 unique
--   2) factory_id 为 NULL 时, code 自己 unique (系统级标准科目不可重复 code)
CREATE UNIQUE INDEX IF NOT EXISTS uk_account_factory_code
    ON accounts(factory_id, code)
    WHERE deleted_at IS NULL AND factory_id IS NOT NULL;

CREATE UNIQUE INDEX IF NOT EXISTS uk_account_system_code
    ON accounts(code)
    WHERE deleted_at IS NULL AND factory_id IS NULL;

COMMENT ON TABLE accounts IS
    'Sprint 7 T1: 会计科目 chart of accounts. factory_id NULL = 系统级标准 GAAP, 共享; 非空 = factory 自定义.';
COMMENT ON COLUMN accounts.code IS '科目编码 (中国 GAAP: 1xxx 资产 / 2xxx 负债 / 4xxx 权益 / 5xxx 成本 / 6xxx 收入)';
COMMENT ON COLUMN accounts.balance_type IS 'DEBIT_NORMAL (资产/成本/费用) | CREDIT_NORMAL (负债/权益/收入)';
COMMENT ON COLUMN accounts.category IS 'ASSET / LIABILITY / EQUITY / REVENUE / EXPENSE / COST';
