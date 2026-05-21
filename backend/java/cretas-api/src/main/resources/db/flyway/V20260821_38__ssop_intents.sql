-- Sprint 9 P2.E — SSOP 清洁消毒记录 AI 意图绑定
-- 1 Workdesk widget + 5 Tool intents

INSERT INTO ai_intent_configs (id, intent_code, intent_name, intent_category,
  tool_name, keywords, is_active, sensitivity_level, created_at, updated_at)
VALUES
  -- Workdesk widget (tool_name=NULL — UI widget 不走 tool, 由前端直接调 ssop_execution_today + ssop_production_gate_check)
  (gen_random_uuid(), 'SSOP_DAILY_SANITATION', 'SSOP 每日清洁 (Workdesk)', 'DATA_QUERY',
   NULL,
   '["SSOP","清洁","消毒","清洁制度","SSOP Workdesk","卫生制度","每日清洁","清洁工作台"]'::jsonb,
   true, 'LOW', NOW(), NOW()),

  -- Tool 1: 创建标准模板 (WRITE)
  (gen_random_uuid(), 'SSOP_PROCEDURE_CREATE', '创建 SSOP 清洁标准模板', 'DATA_OPERATION',
   'ssop_procedure_create',
   '["创建SSOP模板","添加清洁程序","录入清洁SOP","新建消毒程序","SSOP登记","创建清洁标准","录入清洁制度"]'::jsonb,
   true, 'MEDIUM', NOW(), NOW()),

  -- Tool 2: 今日清洁任务清单 (READ)
  (gen_random_uuid(), 'SSOP_EXECUTION_TODAY', '今日 SSOP 清洁任务清单', 'DATA_QUERY',
   'ssop_execution_today',
   '["今天有哪些清洁任务","今日SSOP清单","还有哪些没清洁","清洁完成率","SSOP今日","今天清洁","今日卫生任务"]'::jsonb,
   true, 'LOW', NOW(), NOW()),

  -- Tool 3: 清洁完成 + sign-off (WRITE)
  (gen_random_uuid(), 'SSOP_EXECUTE_COMPLETE', 'SSOP 清洁完成 + 检验员签字', 'DATA_OPERATION',
   'ssop_execute_complete',
   '["完成SSOP","标记清洁完成","清洁sign-off","完成清洁","清洁已完成","SSOP签字","清洁验收","完成消毒"]'::jsonb,
   true, 'MEDIUM', NOW(), NOW()),

  -- Tool 4: 生产开工 gate 检查 (READ + RULE)
  (gen_random_uuid(), 'SSOP_PRODUCTION_GATE_CHECK', 'SSOP 生产开工 gate 检查', 'DATA_QUERY',
   'ssop_production_gate_check',
   '["开工前检查","今天能开始生产吗","SSOP gate","清洁完了吗","生产前检查","gate状态","可以开工吗"]'::jsonb,
   true, 'LOW', NOW(), NOW()),

  -- Tool 5: 月度审计报告 (READ + AGGREGATE)
  (gen_random_uuid(), 'SSOP_MONTHLY_AUDIT_REPORT', 'SSOP 月度审计报告', 'DATA_QUERY',
   'ssop_monthly_audit_report',
   '["SSOP月度报告","清洁月报","HACCP audit报告","上个月清洁完成率","清洁审计","FSSC22000报告","清洁月度审计"]'::jsonb,
   true, 'LOW', NOW(), NOW())

ON CONFLICT (intent_code) DO NOTHING;
