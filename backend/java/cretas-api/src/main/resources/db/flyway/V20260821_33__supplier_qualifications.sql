-- V20260821_33__supplier_qualifications.sql
--
-- Sprint 9 P2.C 食品行业法定 — 供应商食品资质过期预警 (2026-05-21)
-- 法定: 食品安全法第 51 条 + 供应商资质审核要求
--
-- 资质类型: SC 证 / 进口商资质 / HACCP 认证 / 营业执照 / ISO 22000 / FSSC 22000 / 第三方检测
-- 预警逻辑: 30 天提前 / 7 天紧急 / 过期阻止下单 (PO BLOCKING)
--
-- vs HJ: HJ Supplier 仅有 lastAuditDate 字段, 无主动预警 + 锁单.
-- Cretas 通过 supplier_qualifications + SupplierQualificationExpiryScheduler 实现差异化护城河 +1.
--
-- Pattern mirrors V20260820_03 (PG standard). 所有 BaseEntity audit fields
-- (created_at / updated_at / deleted_at) 含 — per .claude/rules/database-entity-sync.md HARD rule.

CREATE TABLE IF NOT EXISTS supplier_qualifications (
    id BIGSERIAL PRIMARY KEY,
    factory_id VARCHAR(64) NOT NULL,
    supplier_id VARCHAR(191) NOT NULL,
    qualification_type VARCHAR(50) NOT NULL
        CHECK (qualification_type IN (
            'SC_LICENSE','IMPORT_CERT','HACCP_CERT','BUSINESS_LICENSE',
            'ISO22000','FSSC22000','THIRD_PARTY_TEST','OTHER'
        )),
    certificate_number VARCHAR(200) NOT NULL,
    issuing_authority VARCHAR(200) NOT NULL,
    issue_date DATE NOT NULL,
    expiry_date DATE NOT NULL,
    attachment_oss_url VARCHAR(500),
    status VARCHAR(30) NOT NULL DEFAULT 'VALID'
        CHECK (status IN ('VALID','EXPIRING','EXPIRED','REVOKED')),
    last_reviewed_at TIMESTAMP,
    last_reviewed_by_user_id BIGINT,
    notes TEXT,
    created_at TIMESTAMP NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMP NOT NULL DEFAULT NOW(),
    deleted_at TIMESTAMP,
    CONSTRAINT uk_supplier_qual_factory_supplier_type_cert
        UNIQUE (factory_id, supplier_id, qualification_type, certificate_number)
);

-- 按供应商 + 状态查 (Tool query / PO gate check 主路径).
CREATE INDEX IF NOT EXISTS idx_supplier_qualifications_supplier
    ON supplier_qualifications(factory_id, supplier_id, status)
    WHERE deleted_at IS NULL;

-- 按 factory + 过期日 + 状态 (Scheduler 扫描即将过期 / 已过期主路径).
CREATE INDEX IF NOT EXISTS idx_supplier_qualifications_expiry
    ON supplier_qualifications(factory_id, expiry_date, status)
    WHERE status IN ('VALID', 'EXPIRING') AND deleted_at IS NULL;

-- 跨 factory 全局 expiry scan (Scheduler 每日 8 am 用, partial index 只索引未过期).
CREATE INDEX IF NOT EXISTS idx_supplier_qualifications_global_expiry
    ON supplier_qualifications(expiry_date, status)
    WHERE status IN ('VALID', 'EXPIRING') AND deleted_at IS NULL;

COMMENT ON TABLE supplier_qualifications IS '供应商食品资质 — Sprint 9 P2.C / 食品安全法第 51 条';
COMMENT ON COLUMN supplier_qualifications.expiry_date IS '过期日期 — 主动预警 driver';
COMMENT ON COLUMN supplier_qualifications.status IS 'VALID 有效 / EXPIRING 即将过期 / EXPIRED 已过期 / REVOKED 主动吊销';
