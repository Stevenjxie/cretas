-- Sprint 5 Track D — P-REQUISITION-1 请购单 entity
--
-- Source: Round 12 §B.2 X2 finding — HJ 采购模块 有 "请购单" entity (Material Requisition)
-- + "请购汇总" sub-menu. Cretas 仅 ShortageAnalysisService (无 user-facing entity).
-- 在谈食品厂客户问"我能不能从生产员发请购单上来" — Cretas 当前不行.
--
-- 设计要点:
-- * 工厂隔离 (factory_id)
-- * State machine: DRAFT → PENDING_APPROVAL → APPROVED → CONVERTED_TO_PO / REJECTED
-- * 行项目用 JSONB 存 (Sprint 5 MVP; Sprint 6 可拆 RequisitionItem 关系表)
-- * approved_at + converted_po_id (linkno) 完整审计链
-- * 软删除 (deleted_at) — 继承 BaseEntity
--
-- Version note: 用 V20260625_01 而非 V20260519_xx 是因为
-- spring.flyway.out-of-order=false (application-pg.properties:57). 当前
-- repo HEAD 最新 migration 是 V20260624_02, 必须 > 这个版本号才会被 Flyway 应用.

CREATE TABLE IF NOT EXISTS purchase_requisitions (
    id                   VARCHAR(191) PRIMARY KEY,
    factory_id           VARCHAR(64)  NOT NULL,
    requisition_number   VARCHAR(50)  NOT NULL,
    requester_id         BIGINT       NOT NULL,
    requester_dept_id    VARCHAR(64),
    requested_items      JSONB        NOT NULL DEFAULT '[]'::jsonb,
    status               VARCHAR(32)  NOT NULL DEFAULT 'DRAFT'
        CHECK (status IN ('DRAFT', 'PENDING_APPROVAL', 'APPROVED', 'CONVERTED_TO_PO', 'REJECTED')),
    expected_date        DATE,
    reason               TEXT,
    approver_id          BIGINT,
    approved_at          TIMESTAMP,
    rejection_reason     TEXT,
    converted_po_id      VARCHAR(191),
    converted_at         TIMESTAMP,
    remark               TEXT,
    version              BIGINT       NOT NULL DEFAULT 0,
    created_at           TIMESTAMP    DEFAULT NOW(),
    updated_at           TIMESTAMP    DEFAULT NOW(),
    deleted_at           TIMESTAMP,
    CONSTRAINT uk_pr_factory_number UNIQUE (factory_id, requisition_number)
);

CREATE INDEX IF NOT EXISTS idx_pr_factory ON purchase_requisitions(factory_id);
CREATE INDEX IF NOT EXISTS idx_pr_status ON purchase_requisitions(factory_id, status);
CREATE INDEX IF NOT EXISTS idx_pr_requester ON purchase_requisitions(factory_id, requester_id);
CREATE INDEX IF NOT EXISTS idx_pr_converted_po ON purchase_requisitions(converted_po_id)
    WHERE converted_po_id IS NOT NULL;

COMMENT ON TABLE purchase_requisitions IS
    'Sprint 5 Track D — 请购单 (Material Requisition). 生产员/仓管员发起 → 采购经理审批 → 自动转 PO. Round 12 B.2 X2 HJ baseline 对齐.';
COMMENT ON COLUMN purchase_requisitions.status IS
    'State machine: DRAFT → PENDING_APPROVAL → APPROVED → CONVERTED_TO_PO / REJECTED';
COMMENT ON COLUMN purchase_requisitions.requested_items IS
    'JSONB array: [{materialTypeId, materialName, quantity, unit, suggestedSupplierId, remark}]. Sprint 6 可拆独立 RequisitionItem 关系表.';
COMMENT ON COLUMN purchase_requisitions.converted_po_id IS
    'linkno to purchase_orders.id. APPROVED 状态触发 convertToPO 后填. NULL 时表示尚未转单或被驳回.';
