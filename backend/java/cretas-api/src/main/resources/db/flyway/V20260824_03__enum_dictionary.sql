-- Canvas-Phase C — enum_dictionary table + seed (2026-05-22).
--
-- 统一 dropdown 值存储 (防呆 Rule 3 自由文本改约束选择).
-- 覆盖 8 大类: CANCEL_REASON / RETURN_REASON / APPROVAL_OPINION / DEFECT_SEVERITY /
-- NONCONFORM_TYPE / WASTAGE_REASON / RECALL_LEVEL / URGENCY_LEVEL.
--
-- factory_id semantics:
--   '*' = global default (resolver falls back here when no per-factory rows enabled)
--   F001/F006/... = per-factory override
--
-- Resolver service (EnumDictionaryResolverServiceImpl) reads with Caffeine cache TTL 5 min.

CREATE TABLE enum_dictionary (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),

  factory_id VARCHAR(50) NOT NULL,
  category VARCHAR(50) NOT NULL,
  code VARCHAR(50) NOT NULL,
  label VARCHAR(200) NOT NULL,

  display_order INT NOT NULL DEFAULT 0,
  enabled BOOLEAN NOT NULL DEFAULT TRUE,
  parent_code VARCHAR(50),
  description VARCHAR(500),
  locale VARCHAR(10) NOT NULL DEFAULT 'zh-CN',

  version BIGINT NOT NULL DEFAULT 0,

  -- BaseEntity audit columns
  created_at TIMESTAMP NOT NULL DEFAULT NOW(),
  updated_at TIMESTAMP NOT NULL DEFAULT NOW(),
  deleted_at TIMESTAMP NULL
);

-- Partial unique index — same (factory_id, category, code) cannot have two non-deleted rows.
CREATE UNIQUE INDEX idx_enum_dictionary_unique
  ON enum_dictionary (factory_id, category, code)
  WHERE deleted_at IS NULL;

CREATE INDEX idx_enum_dictionary_factory_category
  ON enum_dictionary (factory_id, category)
  WHERE deleted_at IS NULL;

CREATE INDEX idx_enum_dictionary_category_code
  ON enum_dictionary (category, code)
  WHERE deleted_at IS NULL;

COMMENT ON TABLE enum_dictionary IS
  'Canvas-Phase C — 防呆 Rule 3 dropdown values; per-factory override + global ''*'' fallback.';
COMMENT ON COLUMN enum_dictionary.factory_id IS
  'Tenant scope; ''*'' = global fallback used when per-factory has no enabled rows.';
COMMENT ON COLUMN enum_dictionary.category IS
  'Enum 大类 (UPPER_SNAKE_CASE), e.g. CANCEL_REASON, APPROVAL_OPINION.';
COMMENT ON COLUMN enum_dictionary.code IS
  'Machine code (UPPER_SNAKE_CASE), e.g. CUSTOMER_CANCEL, QUALITY_ISSUE.';
COMMENT ON COLUMN enum_dictionary.label IS
  'Human-readable label for el-option (zh-CN by default).';
COMMENT ON COLUMN enum_dictionary.parent_code IS
  'Optional parent code for nested categories. Soft constraint (no FK).';

-- ==================== Seed: global defaults (factory_id = '*') ====================
-- 8 categories × 5-6 codes each = 41 baseline rows.

INSERT INTO enum_dictionary
  (factory_id, category, code, label, display_order, enabled, description)
VALUES
  -- 取消原因 (5 rows) — 适用 sales-order / production-order / requisition 取消
  ('*', 'CANCEL_REASON', 'CUSTOMER_CANCEL', '客户撤单', 10, TRUE, '客户主动取消订单'),
  ('*', 'CANCEL_REASON', 'MATERIAL_SHORTAGE', '原料缺货', 20, TRUE, '原料库存不足无法满足'),
  ('*', 'CANCEL_REASON', 'QUALITY_ISSUE', '质量问题', 30, TRUE, '质检不合格或客户投诉'),
  ('*', 'CANCEL_REASON', 'SCHEDULE_CONFLICT', '排程冲突', 40, TRUE, '生产排程与其他订单冲突'),
  ('*', 'CANCEL_REASON', 'OTHER', '其他', 99, TRUE, '其他原因 (需补充说明)'),

  -- 退货原因 (5 rows) — 适用 sales-return / supplier-return
  ('*', 'RETURN_REASON', 'QUALITY_ISSUE', '质量问题', 10, TRUE, '产品质量不达标'),
  ('*', 'RETURN_REASON', 'WRONG_ORDER', '客户错订', 20, TRUE, '客户下单错误'),
  ('*', 'RETURN_REASON', 'EXPIRED', '过期', 30, TRUE, '产品已过保质期'),
  ('*', 'RETURN_REASON', 'DAMAGED', '损坏', 40, TRUE, '运输或包装损坏'),
  ('*', 'RETURN_REASON', 'OTHER', '其他', 99, TRUE, '其他原因 (需补充说明)'),

  -- 审批意见 (5 rows) — 适用所有 approval workflow
  ('*', 'APPROVAL_OPINION', 'APPROVE', '同意', 10, TRUE, '同意当前申请'),
  ('*', 'APPROVAL_OPINION', 'REJECT', '拒绝', 20, TRUE, '拒绝当前申请'),
  ('*', 'APPROVAL_OPINION', 'RETURN_FOR_REVISION', '退回修改', 30, TRUE, '退回申请人修改后重新提交'),
  ('*', 'APPROVAL_OPINION', 'DELEGATE', '转他人审', 40, TRUE, '转给其他审批人处理'),
  ('*', 'APPROVAL_OPINION', 'DEFER', '暂缓', 50, TRUE, '暂缓审批, 待补充材料'),

  -- 缺陷严重度 (5 rows) — 适用 quality-inspection
  ('*', 'DEFECT_SEVERITY', 'CRITICAL', '致命', 10, TRUE, '直接危害食品安全或用户健康'),
  ('*', 'DEFECT_SEVERITY', 'MAJOR', '严重', 20, TRUE, '影响产品功能或显著外观'),
  ('*', 'DEFECT_SEVERITY', 'MINOR', '一般', 30, TRUE, '轻微瑕疵, 不影响使用'),
  ('*', 'DEFECT_SEVERITY', 'TRIVIAL', '轻微', 40, TRUE, '极轻微瑕疵, 仅记录'),
  ('*', 'DEFECT_SEVERITY', 'SUGGESTION', '建议', 50, TRUE, '改进建议, 非缺陷'),

  -- 不合格类型 (6 rows) — 适用 quality-inspection
  ('*', 'NONCONFORM_TYPE', 'APPEARANCE', '外观', 10, TRUE, '外观不合格 (颜色/形状/标识)'),
  ('*', 'NONCONFORM_TYPE', 'SPEC', '规格', 20, TRUE, '尺寸/重量/容量等规格偏差'),
  ('*', 'NONCONFORM_TYPE', 'PERFORMANCE', '性能', 30, TRUE, '功能性指标不达标'),
  ('*', 'NONCONFORM_TYPE', 'PACKAGING', '包装', 40, TRUE, '包装破损或不规范'),
  ('*', 'NONCONFORM_TYPE', 'LABEL', '标签', 50, TRUE, '标签信息错误或缺失'),
  ('*', 'NONCONFORM_TYPE', 'OTHER', '其他', 99, TRUE, '其他不合格类型'),

  -- 损耗原因 (6 rows) — 适用 wastage-record (库存损耗)
  ('*', 'WASTAGE_REASON', 'RODENT', '鼠害', 10, TRUE, '鼠类啃食或污染'),
  ('*', 'WASTAGE_REASON', 'PEST', '虫害', 20, TRUE, '虫蛀或虫卵污染'),
  ('*', 'WASTAGE_REASON', 'MOLD', '霉变', 30, TRUE, '储存环境潮湿导致霉变'),
  ('*', 'WASTAGE_REASON', 'LEAK', '泄漏', 40, TRUE, '包装泄漏或容器破损'),
  ('*', 'WASTAGE_REASON', 'OPERATION_ERROR', '操作失误', 50, TRUE, '员工操作不当导致损耗'),
  ('*', 'WASTAGE_REASON', 'OTHER', '其他', 99, TRUE, '其他损耗原因'),

  -- 召回等级 (3 rows) — 适用 product-recall
  ('*', 'RECALL_LEVEL', 'LEVEL_1_URGENT', '一级紧急', 10, TRUE, '严重危害健康, 立即停售召回'),
  ('*', 'RECALL_LEVEL', 'LEVEL_2_PRIORITY', '二级较急', 20, TRUE, '存在健康风险, 24h 内召回'),
  ('*', 'RECALL_LEVEL', 'LEVEL_3_NORMAL', '三级一般', 30, TRUE, '一般性问题, 计划性召回'),

  -- 紧急程度 (4 rows) — 适用通用任务/工单/通知
  ('*', 'URGENCY_LEVEL', 'URGENT', '紧急', 10, TRUE, '立即处理 (≤1h)'),
  ('*', 'URGENCY_LEVEL', 'HIGH', '高', 20, TRUE, '4h 内处理'),
  ('*', 'URGENCY_LEVEL', 'MEDIUM', '中', 30, TRUE, '24h 内处理'),
  ('*', 'URGENCY_LEVEL', 'LOW', '低', 40, TRUE, '一周内处理');
