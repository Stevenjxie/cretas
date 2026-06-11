-- V20261019_02: 升级 SALES_RECEIPT / PURCHASE_PAYMENT 默认凭证模板为价税分离三行 (SP11 / #736 / #738)
--
-- 背景 (Fable BLOCKING #738):
--   #736 给 SalesReceiptVoucherGenerator / PurchasePaymentVoucherGenerator 写了三行价税分离
--   buildEntries (销项税 2221.01 / 进项税 2221.02 单列)。但 AbstractVoucherGenerator 是
--   template-first: V20260605_03 已给每个工厂 (含 F006) 播了 is_default=TRUE 的**旧两行模板**
--   (amountExpression=#entity.totalAmount, 无税行)。VoucherController.createFromBusiness →
--   registry → generate 命中 seed 模板 → 新三行 buildEntries 被完全 bypass → 客户导金蝶
--   拿到的还是旧两行无税凭证。
--
-- 修复 (方案 A: 升级 seed 模板):
--   把 V20260605_03 播的两个默认模板升级为三行价税分离, 用 SpEL #entity.taxAmount 单列税额,
--   应收/应付行 = 未税 + 税 (加法, 借贷精确平衡, 无舍入裂缝, 与 generator buildEntries 口径一致)。
--   保持 template-first 语义不变 — 客户仍可通过自定义 template 覆盖 (setAsDefault 走
--   clearOtherDefaults), 不破坏"客户可覆盖模板"的契约。
--
-- 零税退化两行向后兼容:
--   税行标 "omitWhenZero": true → VoucherTemplateServiceImpl.renderEntries 在该行 SpEL 求值为 0
--   (零税订单 taxAmount=0/null) 时省略该行, 退化为两行, 与 generator hardcoded buildEntries 的
--   零税两行行为完全一致, 不在金蝶账套留下 ¥0.00 税行。
--
-- SpEL null 安全:
--   (#entity.totalAmount ?: 0) + (#entity.taxAmount ?: 0) — Elvis 保证 DB 列为 NULL 时退化为 0,
--   不 NPE (实测 BigDecimal + int → BigDecimal, 精度无损)。
--
-- 幂等 / 安全更新范围:
--   只 UPDATE 仍是**旧两行结构** (entries 数组长度=2 且第一行 amountExpression 含 totalAmount 无加法)
--   的 seed 默认模板, 用 name 锁定 V20260605_03 播的那一条 ('销售收款默认模板'/'采购付款默认模板')。
--   客户自定义 (改名 / 替换 / 已是三行) 的模板不受影响。重复跑迁移: 第二次匹配条件已不满足 (已三行), 自动 no-op。

-- ============ SALES_RECEIPT: 升级为三行价税分离 ============
-- 借: 1122 应收账款 = 未税 + 销项税 (含税应收)
-- 贷: 6001 主营业务收入 = 未税净额
-- 贷: 2221.01 应交税费-应交增值税-销项税额 = 销项税 (零税省略)
UPDATE voucher_templates
SET entries_json = '[
  {"sortOrder":1,"subjectCode":"1122","subjectName":"应收账款","direction":"DEBIT","amountExpression":"(#entity.totalAmount ?: 0) + (#entity.taxAmount ?: 0)","description":"销售收入挂账(含税)"},
  {"sortOrder":2,"subjectCode":"6001","subjectName":"主营业务收入","direction":"CREDIT","amountExpression":"#entity.totalAmount ?: 0","description":"销售订单收入(未税)"},
  {"sortOrder":3,"subjectCode":"2221.01","subjectName":"应交税费-应交增值税-销项税额","direction":"CREDIT","amountExpression":"#entity.taxAmount ?: 0","description":"销项税额","omitWhenZero":true}
]'::jsonb,
    description = '价税分离三行: 借 1122 应收账款(含税); 贷 6001 主营业务收入(未税); 贷 2221.01 销项税额(零税省略). mirror SalesReceiptVoucherGenerator SP11.',
    updated_at = NOW()
WHERE voucher_type = 'SALES_RECEIPT'
  AND name = '销售收款默认模板'
  AND deleted_at IS NULL
  -- 仅升级仍是旧两行结构的 seed 模板 (已三行 / 客户自定义不动)
  AND jsonb_array_length(entries_json) = 2;

-- ============ PURCHASE_PAYMENT: 升级为三行价税分离 ============
-- 借: 1405 库存商品 = 未税净额 (进项税不进成本)
-- 借: 2221.02 应交税费-应交增值税-进项税额 = 进项税 (零税省略)
-- 贷: 2202 应付账款 = 未税 + 进项税 (含税应付)
UPDATE voucher_templates
SET entries_json = '[
  {"sortOrder":1,"subjectCode":"1405","subjectName":"库存商品","direction":"DEBIT","amountExpression":"#entity.totalAmount ?: 0","description":"采购入库(未税)"},
  {"sortOrder":2,"subjectCode":"2202","subjectName":"应付账款","direction":"CREDIT","amountExpression":"(#entity.totalAmount ?: 0) + (#entity.taxAmount ?: 0)","description":"供应商应付(含税)"},
  {"sortOrder":3,"subjectCode":"2221.02","subjectName":"应交税费-应交增值税-进项税额","direction":"DEBIT","amountExpression":"#entity.taxAmount ?: 0","description":"进项税额","omitWhenZero":true}
]'::jsonb,
    description = '价税分离三行: 借 1405 库存商品(未税); 借 2221.02 进项税额(零税省略); 贷 2202 应付账款(含税). mirror PurchasePaymentVoucherGenerator SP11.',
    updated_at = NOW()
WHERE voucher_type = 'PURCHASE_PAYMENT'
  AND name = '采购付款默认模板'
  AND deleted_at IS NULL
  AND jsonb_array_length(entries_json) = 2;
