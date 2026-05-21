-- V20260820_10__sprint8_p4c_quality_chief_intents.sql
--
-- Sprint 8 P4c — 质量主管 Workdesk 5 Tool intents + Workdesk-level intent (2026-05-20).
--
-- 5 Tool-level intents:
--   QUALITY_CHECK_SUMMARY              → quality_check_summary (READ)
--   HACCP_STATUS_QUERY                 → haccp_status_query (READ, P3 wrap)
--   ADDITIVE_COMPLIANCE_QUALITY        → additive_compliance_check_quality (READ, P3 wrap)
--   CUSTOMER_QUALITY_STANDARD          → customer_quality_standard (READ, R5 dead-end fallback)
--   RELEASE_DECISION                   → release_decision (WRITE + Preview BLOCKING)
--
-- 1 Workdesk-level intent (tool_name=NULL, 用 keywords 路由到首页):
--   QUALITY_CHIEF_WORKDESK             → 质量主管 Workdesk 入口 (今天哪些批次待放行)
--
-- Pattern mirrors V20260820_01..09 (P1/P2/P3/P4a/P4b proven). ON CONFLICT DO UPDATE.

-- ===== 1. Workdesk-level intent =====

INSERT INTO ai_intent_configs (
    id, intent_code, intent_name, intent_category, tool_name,
    sensitivity_level, keywords, description, priority, is_active,
    created_at, updated_at
)
VALUES
    (gen_random_uuid(), 'QUALITY_CHIEF_WORKDESK', '质量主管工作台', 'WORKDESK',
     NULL, 'LOW',
     '["今天哪些批次待放行","待放行","质量主管工作台","批次放行","放行 audit","质检综合","卤猪蹄能放行吗"]',
     'Sprint 8 P4c 质量主管 Workdesk 入口 — 综合 4 项 audit (质检 + HACCP + 添加剂 + 客户标准) ' ||
     '+ 一键放行/退货. Cretas vs HJ 差异化 — HJ 要点 4 菜单, Cretas 1 屏综合判断.',
     40, true, NOW(), NOW())

ON CONFLICT (intent_code) DO UPDATE SET
    tool_name = EXCLUDED.tool_name,
    intent_name = EXCLUDED.intent_name,
    intent_category = EXCLUDED.intent_category,
    sensitivity_level = EXCLUDED.sensitivity_level,
    keywords = EXCLUDED.keywords,
    description = EXCLUDED.description,
    priority = EXCLUDED.priority,
    is_active = EXCLUDED.is_active,
    updated_at = NOW();

-- ===== 2. 5 Tool-level intents =====

INSERT INTO ai_intent_configs (
    id, intent_code, intent_name, intent_category, tool_name,
    sensitivity_level, keywords, description, priority, is_active,
    created_at, updated_at
)
VALUES
    (gen_random_uuid(), 'QUALITY_CHECK_SUMMARY', '质检综合汇总', 'QUERY',
     'quality_check_summary', 'LOW',
     '["B-X 的质检记录","这批检测怎么样","卤猪蹄批次检测通过吗","质检合格率","批次质检汇总","质检结果"]',
     'Sprint 8 P4c 质量主管批次质检综合汇总 — 查 batch 的全部 QualityInspection 记录 + ' ||
     '通过率聚合 + result 分布. 用于放行决策综合判断. read-only.',
     80, true, NOW(), NOW()),

    (gen_random_uuid(), 'HACCP_STATUS_QUERY', '放行前 HACCP 状态查询', 'QUERY',
     'haccp_status_query', 'LOW',
     '["这批 HACCP 通过吗","卤猪蹄 HACCP 状态","B-X HACCP 检查","放行前 HACCP 验证","HACCP 简洁查询"]',
     'Sprint 8 P4c 质量主管放行前 HACCP 状态简洁查询 (P3 haccp_checkpoint_review wrapper). ' ||
     '输出: 整体通过状态 + 偏离监控点数 + releaseHint. read-only.',
     80, true, NOW(), NOW()),

    (gen_random_uuid(), 'ADDITIVE_COMPLIANCE_QUALITY', '放行前添加剂合规判定', 'QUERY',
     'additive_compliance_check_quality', 'LOW',
     '["B-X 添加剂合规吗","卤猪蹄添加剂超限了吗","放行前添加剂检查","质量 GB 2760 合规","批次添加剂判定"]',
     'Sprint 8 P4c 质量主管放行前添加剂合规判定 (P3 additive_compliance_check 质量语境 wrapper). ' ||
     '输出: compliant + 超限项 + releaseHint. R5 dead-end: 无 ingredients 时引导去 BOM 页面.',
     80, true, NOW(), NOW()),

    (gen_random_uuid(), 'CUSTOMER_QUALITY_STANDARD', '客户质量标准查询', 'QUERY',
     'customer_quality_standard', 'LOW',
     '["X 客户的质量标准是什么","鲜湘缘要什么 COA","客户接收检测项","客户质量要求","客户标准"]',
     'Sprint 8 P4c 客户质量标准查询 — 查指定 customer 的产品质量要求 / COA / 接收检测项. ' ||
     'R5 dead-end: 客户暂无登记标准则返工厂默认 + actionHint 引导去 CRM 配置. read-only.',
     80, true, NOW(), NOW()),

    (gen_random_uuid(), 'RELEASE_DECISION', '批次放行决策', 'DATA_OP',
     'release_decision', 'HIGH',
     '["放行 B-X","卤猪蹄批次放行","退货 B-X","质检通过准放","批次放行决策","拒收批次","放行/退货决策"]',
     'Sprint 8 P4c 质量主管批次放行决策 — WRITE + Preview BLOCKING. ' ||
     'RELEASED → AVAILABLE / REJECTED → DEFECTIVE. ' ||
     '防呆 R1+R2+R3+R4: preview 必显当前/新 status + R3 decision enum + R4 幂等防重复决策.',
     70, true, NOW(), NOW())

ON CONFLICT (intent_code) DO UPDATE SET
    tool_name = EXCLUDED.tool_name,
    intent_name = EXCLUDED.intent_name,
    intent_category = EXCLUDED.intent_category,
    sensitivity_level = EXCLUDED.sensitivity_level,
    keywords = EXCLUDED.keywords,
    description = EXCLUDED.description,
    priority = EXCLUDED.priority,
    is_active = EXCLUDED.is_active,
    updated_at = NOW();

-- ===== 3. 286 intent 去重 — UPDATE 合并明显的同义 intent (audit-suggested) =====
--
-- 鉴于本地无法直接 query 生产 ai_intent_configs 找 duplicates, 此段保留为 NO-OP placeholder.
-- 实际 dedup 应通过运维 audit 完成 (后续 Sprint 9 task):
--   SELECT intent_name, COUNT(*) FROM ai_intent_configs
--   GROUP BY intent_name HAVING COUNT(*) > 1 ORDER BY COUNT(*) DESC;
--
-- 已知潜在 duplicate hint (待 Sprint 9 confirm):
--   '质检结果' x N 个 (旧 placeholder vs P4c QUALITY_CHECK_SUMMARY)
--   '放行' x N 个 (旧 dataop vs P4c RELEASE_DECISION)
--   '添加剂' x N 个 (P3 ADDITIVE_CHECK vs P4c ADDITIVE_COMPLIANCE_QUALITY)
--
-- 此 migration 不强删 duplicates, 仅添加新 intent. 防止误删 prod 数据.
