-- V20260519_06: Sprint 5 H-1 — 扩 attachments.file_category 加 'CONTRACT' 值
--
-- 业务背景 (per dispatch §H H-1 + Round 12 §B.6 X2):
-- HJ 销售单 list 行内 link counter 是 `文件(N) 图片(N) 合同(N)` 三个独立分类.
-- Cretas 现有 file_category 5 类 (PHOTO/VIDEO/DOCUMENT/VOUCHER/SIGNATURE/OTHER)
-- 缺 CONTRACT 类. CONTRACT 跟 DOCUMENT 区别在于:
--   - DOCUMENT: 通用 PDF/Word/Excel
--   - CONTRACT: 合同 PDF (法律效力, 需触发 vflag 业务流, e.g. 销售合同 → 应收账款触发)
--   - VOUCHER:  财务凭证扫描件 (跟 Voucher 实体绑定)
--
-- 这条 migration 仅扩 enum + CHECK constraint, 不改 schema 结构.
--
-- 关联 Java 改动 (本 PR 同步): entity/Attachment.java FileCategory enum 加 CONTRACT.
--
-- Sprint 6 follow-up:
--   - SalesOrderListDTO 加 linkCounts: {file: N, image: N, contract: N}
--     (跟 Track C-2 inline link counter 集成)
--   - Vue 列 + chip row 展示 (per Track C-2 brief)
--   - AttachmentRecord 独立 entity 提案 (per H-1 brief) 评估: 现有 Attachment 已是
--     polymorphic + 多态关联, 不需再拆 — 用 business_tag = 'CONTRACT_*' + file_category
--     CONTRACT 即可满足 HJ link_type 三分类需求.

ALTER TABLE attachments
    DROP CONSTRAINT chk_att_category;

ALTER TABLE attachments
    ADD CONSTRAINT chk_att_category
    CHECK (file_category IN ('PHOTO', 'VIDEO', 'DOCUMENT', 'VOUCHER', 'SIGNATURE', 'CONTRACT', 'OTHER'));

COMMENT ON COLUMN attachments.file_category IS '文件分类: PHOTO/VIDEO/DOCUMENT/VOUCHER/SIGNATURE/CONTRACT/OTHER. 跟 Java FileCategory enum 同步. CONTRACT 是 Sprint 5 H-1 新加, 用于 SalesOrderListDTO linkCounts 区分 文件/图片/合同 三类 (HJ link_type 对齐).';
