-- SP11: 财务凭证导出配置表 (per-factory, per-target-system)
-- 目的: 客户可配置金蝶/用友列字段名, 适配不同版本差异

CREATE TABLE IF NOT EXISTS voucher_export_configs (
    id               VARCHAR(191) PRIMARY KEY,
    factory_id       VARCHAR(191) NOT NULL,
    target_system    VARCHAR(32)  NOT NULL DEFAULT 'KINGDEE',
    col_voucher_no   VARCHAR(64)  NOT NULL DEFAULT '凭证字号',
    col_date         VARCHAR(64)  NOT NULL DEFAULT '日期',
    col_summary      VARCHAR(64)  NOT NULL DEFAULT '摘要',
    col_subject_code VARCHAR(64)  NOT NULL DEFAULT '科目编码',
    col_subject_name VARCHAR(64)  NOT NULL DEFAULT '科目名称',
    col_debit        VARCHAR(64)  NOT NULL DEFAULT '借方金额',
    col_credit       VARCHAR(64)  NOT NULL DEFAULT '贷方金额',
    col_auxiliary    VARCHAR(64)  NOT NULL DEFAULT '辅助核算',
    col_currency     VARCHAR(64)  NOT NULL DEFAULT '币别',
    is_active        BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at       TIMESTAMP    NOT NULL DEFAULT NOW(),
    updated_at       TIMESTAMP    NOT NULL DEFAULT NOW(),
    deleted_at       TIMESTAMP,
    UNIQUE (factory_id, target_system)
);

CREATE INDEX IF NOT EXISTS idx_vec_factory ON voucher_export_configs(factory_id);

CREATE TRIGGER trg_vec_updated_at
    BEFORE UPDATE ON voucher_export_configs
    FOR EACH ROW EXECUTE FUNCTION update_updated_at();
