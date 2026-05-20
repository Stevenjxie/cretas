-- ============================================================================
-- Sprint 6 W3-B (2026-05-19): Demo approval workflows — 2 个示例工作流
-- 配合 DecisionTypeMetadataRegistry 32 个 DecisionType + admin UI 全量 dropdown
-- 让客户首次访问 admin 编辑器时, 即可看到样例 workflow 直接 simulate / 学习模式
--
-- 示例 1: 出差申请 / 报销审批 (EXPENSE_APPROVAL)
--   start → 部门负责人审批 → 财务总监审批 (金额>10k条件) → end APPROVED
--
-- 示例 2: 库存调拨审批 (INVENTORY_TRANSFER_APPROVAL)
--   start → 仓库经理审批 → end APPROVED
--
-- 用 ON CONFLICT 防止重复 seed (Flyway 设计是 1-shot, 但如果 dev env Flyway clean
-- 重跑也安全).
--
-- F001 + F006 两个 sample factory 都 seed, 让多客户 demo 都可用.
-- ============================================================================

-- ============================================
-- F001 EXPENSE_APPROVAL demo
-- ============================================
INSERT INTO approval_workflows (
  id, factory_id, decision_type, name, description,
  nodes_json, edges_json, start_node_id,
  version, publish_status, enabled, priority,
  created_at, updated_at
)
VALUES (
  '11111111-1111-4111-1111-1111e1111001',
  'F001',
  'EXPENSE_APPROVAL',
  '[Sprint 6 W3-B 示例] 报销审批 — 双级',
  '示例工作流: 报销单 → 部门负责人 → (金额≥10000) 财务总监 → 完成. 自由编辑/复制为模板.',
  $json$
  [
    {"id": "start", "type": "start", "label": "提交报销", "position": {"x": 80, "y": 100}, "config": {}},
    {"id": "approve_dept", "type": "approval", "label": "部门负责人审批", "position": {"x": 280, "y": 100},
      "config": {"approverRoles": ["department_admin"], "requiredApprovers": 1, "timeoutMinutes": 1440}},
    {"id": "cond_amount", "type": "condition", "label": "金额≥10000?", "position": {"x": 500, "y": 100}, "config": {}},
    {"id": "approve_finance", "type": "approval", "label": "财务总监审批", "position": {"x": 700, "y": 50},
      "config": {"approverRoles": ["finance_manager"], "requiredApprovers": 1, "timeoutMinutes": 1440}},
    {"id": "end_approved", "type": "end", "label": "通过", "position": {"x": 920, "y": 100}, "config": {"outcome": "APPROVED"}}
  ]
  $json$::jsonb,
  $json$
  [
    {"id": "e1", "source": "start", "target": "approve_dept", "priority": 0},
    {"id": "e2", "source": "approve_dept", "target": "cond_amount", "priority": 0},
    {"id": "e3", "source": "cond_amount", "target": "approve_finance",
      "condition": "#amount >= 10000", "priority": 0, "label": "大金额"},
    {"id": "e4", "source": "cond_amount", "target": "end_approved", "priority": 10, "label": "DEFAULT"},
    {"id": "e5", "source": "approve_finance", "target": "end_approved", "priority": 0}
  ]
  $json$::jsonb,
  'start',
  1, 'draft', true, 0,
  NOW(), NOW()
)
ON CONFLICT (factory_id, decision_type, name) DO NOTHING;

-- ============================================
-- F001 INVENTORY_TRANSFER_APPROVAL demo
-- ============================================
INSERT INTO approval_workflows (
  id, factory_id, decision_type, name, description,
  nodes_json, edges_json, start_node_id,
  version, publish_status, enabled, priority,
  created_at, updated_at
)
VALUES (
  '11111111-1111-4111-1111-1111e1111002',
  'F001',
  'INVENTORY_TRANSFER_APPROVAL',
  '[Sprint 6 W3-B 示例] 库存调拨 — 单级',
  '示例工作流: 调拨申请 → 仓库经理 → 完成. 简单单级, 复制后可改为多级.',
  $json$
  [
    {"id": "start", "type": "start", "label": "调拨申请", "position": {"x": 80, "y": 100}, "config": {}},
    {"id": "approve_wh", "type": "approval", "label": "仓库经理审批", "position": {"x": 320, "y": 100},
      "config": {"approverRoles": ["warehouse_manager"], "requiredApprovers": 1, "timeoutMinutes": 720}},
    {"id": "end_approved", "type": "end", "label": "通过", "position": {"x": 560, "y": 100}, "config": {"outcome": "APPROVED"}}
  ]
  $json$::jsonb,
  $json$
  [
    {"id": "e1", "source": "start", "target": "approve_wh", "priority": 0},
    {"id": "e2", "source": "approve_wh", "target": "end_approved", "priority": 0}
  ]
  $json$::jsonb,
  'start',
  1, 'draft', true, 0,
  NOW(), NOW()
)
ON CONFLICT (factory_id, decision_type, name) DO NOTHING;

-- ============================================
-- F006 EXPENSE_APPROVAL demo (六腾门客户使用)
-- ============================================
INSERT INTO approval_workflows (
  id, factory_id, decision_type, name, description,
  nodes_json, edges_json, start_node_id,
  version, publish_status, enabled, priority,
  created_at, updated_at
)
VALUES (
  '11111111-1111-4111-1111-1111e1111003',
  'F006',
  'EXPENSE_APPROVAL',
  '[Sprint 6 W3-B 示例] 报销审批 — 双级',
  '示例工作流 (六腾门): 报销单 → 部门负责人 → (金额≥10000) 财务总监 → 完成.',
  $json$
  [
    {"id": "start", "type": "start", "label": "提交报销", "position": {"x": 80, "y": 100}, "config": {}},
    {"id": "approve_dept", "type": "approval", "label": "部门负责人审批", "position": {"x": 280, "y": 100},
      "config": {"approverRoles": ["department_admin"], "requiredApprovers": 1, "timeoutMinutes": 1440}},
    {"id": "cond_amount", "type": "condition", "label": "金额≥10000?", "position": {"x": 500, "y": 100}, "config": {}},
    {"id": "approve_finance", "type": "approval", "label": "财务总监审批", "position": {"x": 700, "y": 50},
      "config": {"approverRoles": ["finance_manager"], "requiredApprovers": 1, "timeoutMinutes": 1440}},
    {"id": "end_approved", "type": "end", "label": "通过", "position": {"x": 920, "y": 100}, "config": {"outcome": "APPROVED"}}
  ]
  $json$::jsonb,
  $json$
  [
    {"id": "e1", "source": "start", "target": "approve_dept", "priority": 0},
    {"id": "e2", "source": "approve_dept", "target": "cond_amount", "priority": 0},
    {"id": "e3", "source": "cond_amount", "target": "approve_finance",
      "condition": "#amount >= 10000", "priority": 0, "label": "大金额"},
    {"id": "e4", "source": "cond_amount", "target": "end_approved", "priority": 10, "label": "DEFAULT"},
    {"id": "e5", "source": "approve_finance", "target": "end_approved", "priority": 0}
  ]
  $json$::jsonb,
  'start',
  1, 'draft', true, 0,
  NOW(), NOW()
)
ON CONFLICT (factory_id, decision_type, name) DO NOTHING;
