-- V20260820_02__sprint8_p2_finance_intents.sql
--
-- Sprint 8 P2 — 财务主管 Workdesk (2026-05-20)
-- 注册 1 Skill 顶层 intent + 14 Tool-level intents.
--
-- Skill 触发 (Workdesk auto-trigger entry point):
--   MONTHLY_FINANCIAL_CLOSE → monthly-financial-close Skill
--
-- 14 Tool intents (LLM 直接绑 tool_name 走 Tool 直接执行分支):
--   ACCOUNT_QUERY                → account_query
--   ACCOUNT_TREE_LOOKUP          → account_tree_lookup
--   PERIOD_STATUS_QUERY          → period_status_query
--   PERIOD_REQUEST_CLOSE         → period_request_close (WRITE + Preview)
--   PERIOD_CONFIRM_CLOSE         → period_confirm_close (WRITE + Preview, BLOCKING)
--   PERIOD_REOPEN                → period_reopen (WRITE + Preview, audit)
--   BALANCE_SHEET_QUERY          → balance_sheet_query
--   INCOME_STATEMENT_QUERY       → income_statement_query
--   CASHFLOW_STATEMENT_QUERY     → cashflow_statement_query
--   WAGE_COST_SUMMARY            → wage_cost_summary
--   WAGE_POLICY_QUERY            → wage_policy_query
--   OPPORTUNITY_FUNNEL_STATS     → opportunity_funnel_stats
--   COMMISSION_PENDING_TOTAL     → commission_pending_total
--   ACCOUNTS_RECEIVABLE_AGING    → accounts_receivable_aging
--
-- Pattern mirrors V20260820_01__sprint8_p1_workdesk_intents.sql (proven).
-- ON CONFLICT (intent_code) DO UPDATE — idempotent re-run safe.

-- ===== 1. Workdesk-level Skill intent (highest priority for auto-trigger) =====

INSERT INTO ai_intent_configs (
    id, intent_code, intent_name, intent_category, tool_name,
    sensitivity_level, keywords, description, priority, is_active,
    created_at, updated_at
)
VALUES (
    gen_random_uuid(),
    'MONTHLY_FINANCIAL_CLOSE',
    '月度财务复盘',
    'WORKDESK',
    NULL,  -- skill 模式, tool_name 留空, 走 Skill router (SkillRegistry keyword match)
    'LOW',
    '["本月经营","月度复盘","X 月经营","经营怎么样","本月财务","月底总结","月度经营摘要","财务主管工作台","经营摘要","月度报告","财务月报","5 月经营怎么样","本月经营总结"]',
    'Sprint 8 P2 财务主管 Workdesk 入口 — 聚合 8 Tool 输出月度经营摘要 (期间状态 + 三表 + 工资 + ' ||
    '漏斗 + 应付提成 + 应收账龄). 触发 monthly-financial-close Skill (定义于 SkillRegistryImpl.initializeDefaultSkills).',
    50,
    true,
    NOW(),
    NOW()
)
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

-- ===== 2. 14 Tool-level intents =====

INSERT INTO ai_intent_configs (
    id, intent_code, intent_name, intent_category, tool_name,
    sensitivity_level, keywords, description, priority, is_active,
    created_at, updated_at
)
VALUES
    (gen_random_uuid(), 'ACCOUNT_QUERY', '查询会计科目', 'QUERY',
     'account_query', 'LOW',
     '["会计科目","科目","账户","科目表","资产类科目","负债类科目","收入科目"]',
     '查 factory 可见会计科目 (含 GAAP 标准 + factory 自定义), 支持 category / balanceType 过滤. read-only.',
     80, true, NOW(), NOW()),

    (gen_random_uuid(), 'ACCOUNT_TREE_LOOKUP', '查询会计科目树', 'QUERY',
     'account_tree_lookup', 'LOW',
     '["科目树","科目层级","科目结构","会计科目目录","展开科目"]',
     '返回 factory 可见的完整会计科目树 (parent-child 4 级). read-only.',
     80, true, NOW(), NOW()),

    (gen_random_uuid(), 'PERIOD_STATUS_QUERY', '查询期间结账状态', 'QUERY',
     'period_status_query', 'LOW',
     '["关账状态","期间状态","本月关账了吗","财务期间","结账状态","period status"]',
     '查 factory 指定 (year, month) 期间结账状态 (OPEN/PENDING_CLOSE/CLOSED). 默认本月. read-only.',
     80, true, NOW(), NOW()),

    (gen_random_uuid(), 'PERIOD_REQUEST_CLOSE', '发起期间结账请求', 'DATA_OP',
     'period_request_close', 'MEDIUM',
     '["发起结账","申请关账","提交结账","结账请求","本月结账"]',
     '发起期间结账 (OPEN → PENDING_CLOSE), 触发 BUDGET_APPROVAL workflow. WRITE + Preview.',
     70, true, NOW(), NOW()),

    (gen_random_uuid(), 'PERIOD_CONFIRM_CLOSE', '确认期间关账', 'DATA_OP',
     'period_confirm_close', 'HIGH',
     '["确认关账","关账","财务关账","finalize 期间","完成关账"]',
     '确认期间关账 (PENDING_CLOSE → CLOSED). BLOCKING — voucher 写入立即锁. WRITE + Preview.',
     65, true, NOW(), NOW()),

    (gen_random_uuid(), 'PERIOD_REOPEN', '反结账', 'DATA_OP',
     'period_reopen', 'HIGH',
     '["反结账","重开期间","reopen","解锁期间","重新打开","补凭证"]',
     '反结账 (CLOSED → OPEN), 必填 reason audit log. WRITE + Preview.',
     65, true, NOW(), NOW()),

    (gen_random_uuid(), 'BALANCE_SHEET_QUERY', '资产负债表', 'ANALYSIS',
     'balance_sheet_query', 'LOW',
     '["资产负债表","总资产","总负债","所有者权益","balance sheet","BS","资产负债"]',
     '生成 factory 指定 (year, month) 期末资产负债表 (中国 GAAP). read-only.',
     85, true, NOW(), NOW()),

    (gen_random_uuid(), 'INCOME_STATEMENT_QUERY', '利润表', 'ANALYSIS',
     'income_statement_query', 'LOW',
     '["利润表","营业收入","净利润","毛利","毛利率","P&L","损益表","income statement"]',
     '生成 factory 期间内利润表 (4 层: 收入 → 成本 → 毛利 → 净利润). read-only.',
     85, true, NOW(), NOW()),

    (gen_random_uuid(), 'CASHFLOW_STATEMENT_QUERY', '现金流量表', 'ANALYSIS',
     'cashflow_statement_query', 'LOW',
     '["现金流","现金流量表","cash flow","经营活动现金流","投资活动","筹资活动","净现金增加"]',
     '生成 factory 期间内现金流量表 (直接法, 三类活动). read-only.',
     85, true, NOW(), NOW()),

    (gen_random_uuid(), 'WAGE_COST_SUMMARY', '工资成本汇总', 'ANALYSIS',
     'wage_cost_summary', 'LOW',
     '["工资成本","工资总额","本月工资","按件工资","按时工资","wage cost","薪资成本"]',
     '汇总 factory 指定月份工资成本, 按 PIECE_RATE/HOURLY/MIXED 三类拆分. read-only.',
     80, true, NOW(), NOW()),

    (gen_random_uuid(), 'WAGE_POLICY_QUERY', '工资策略', 'QUERY',
     'wage_policy_query', 'LOW',
     '["工资策略","工资计算 mode","WagePolicy","按件规则","wage policy","薪资策略"]',
     '查 factory / 员工的工资策略配置 (mode + mixed formula). read-only.',
     80, true, NOW(), NOW()),

    (gen_random_uuid(), 'OPPORTUNITY_FUNNEL_STATS', '商机漏斗统计', 'ANALYSIS',
     'opportunity_funnel_stats', 'LOW',
     '["商机漏斗","商机分布","pipeline","funnel","各阶段商机","商机统计"]',
     '聚合销售商机 8 阶段 funnel 统计, 每阶段 count + 总金额 + 加权预期金额. read-only.',
     80, true, NOW(), NOW()),

    (gen_random_uuid(), 'COMMISSION_PENDING_TOTAL', '待付提成汇总', 'ANALYSIS',
     'commission_pending_total', 'LOW',
     '["待付提成","应付提成","pending commission","提成账单","提成总额","下月提成"]',
     '汇总 factory 待付 (status=PENDING) 提成总金额 + 按销售员拆分. read-only.',
     80, true, NOW(), NOW()),

    (gen_random_uuid(), 'ACCOUNTS_RECEIVABLE_AGING', '应收账龄', 'ANALYSIS',
     'accounts_receivable_aging', 'LOW',
     '["应收账龄","账龄分析","60 天以上欠款","坏账风险","AR aging","应收老化","欠款分析"]',
     '应收账龄 6 桶分析, 60+ 天高风险标记. read-only.',
     85, true, NOW(), NOW())

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
