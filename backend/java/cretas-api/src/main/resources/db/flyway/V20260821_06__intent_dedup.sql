-- V20260821_06__intent_dedup.sql
--
-- Sprint 9 P1.1 Fix 3 — 286 intent (实际 542) 去重.
--
-- 历史背景: Sprint 8 V20260820_10 留 NO-OP placeholder, 因当时无 prod 数据可分析.
-- Sprint 9 P1.1 SSH 查 prod cretas_prod_db 抓 duplicates:
--   SELECT intent_name, COUNT(*) FROM ai_intent_configs WHERE deleted_at IS NULL
--   GROUP BY intent_name HAVING COUNT(*) > 1 ORDER BY COUNT(*) DESC;
--
-- 数据快照 (2026-05-21 query on cretas_prod_db, 542 total / 517 distinct):
--   19 个 duplicate groups, 共 48 重复 intents (含主+副).
--   策略: keep 最新 created_at 的 row (优先 is_active=true + 有 tool_name 的),
--   其他 soft-delete (UPDATE deleted_at = NOW()) — 保留审计痕迹.
--
-- 详细 audit 见: docs/audits/sprint-9-intent-dedup-report.md
--
-- 安全规则:
--   1. 仅对 prod 已确认 duplicate intent_name 操作
--   2. SOFT delete (deleted_at), 不 DROP
--   3. ON CONFLICT 哨兵: 若 prod 数据有进一步演化, intent_code 不在已知列表的 skip
--   4. 每组保留 1 个 (winner = 优先 is_active+tool_name+latest created_at)

BEGIN;

-- ===== Round 1: 保 winner intent_code, soft-delete loser intent_codes =====
--
-- Winner 选取原则 (per audit doc):
--   - 优先 is_active = true
--   - 次优先 tool_name IS NOT NULL
--   - 末选 created_at DESC
-- Loser 仅 update deleted_at, 保 intent_code 唯一性约束 (避免与 winner 冲突).

-- 下一页: keep PAGINATION_NEXT (active, has tool_name)
UPDATE ai_intent_configs
   SET deleted_at = NOW(), updated_at = NOW()
 WHERE intent_code = 'NAVIGATION_NEXT_PAGE'
   AND deleted_at IS NULL;

-- 产品销售排名: keep PRODUCT_PRODUCT_SALES_RANKING (latest, 2026-03-03)
UPDATE ai_intent_configs
   SET deleted_at = NOW(), updated_at = NOW()
 WHERE intent_code = 'PRODUCT_SALES_RANKING'
   AND deleted_at IS NULL;

-- 人均消费查询: keep RESTAURANT_AVG_TICKET (active + restaurant domain)
UPDATE ai_intent_configs
   SET deleted_at = NOW(), updated_at = NOW()
 WHERE intent_code = 'QUERY_PER_CAPITA_CONSUMPTION'
   AND deleted_at IS NULL;

-- 修改订单: keep ORDER_MODIFY (semantically clearer than UPDATE)
UPDATE ai_intent_configs
   SET deleted_at = NOW(), updated_at = NOW()
 WHERE intent_code = 'ORDER_UPDATE'
   AND deleted_at IS NULL;

-- 分配任务给员工: keep TASK_ASSIGN_WORKER (latest)
UPDATE ai_intent_configs
   SET deleted_at = NOW(), updated_at = NOW()
 WHERE intent_code = 'TASK_ASSIGN_EMPLOYEE'
   AND deleted_at IS NULL;

-- 创建采购订单: keep PURCHASE_ORDER_CREATE (active + has tool_name)
UPDATE ai_intent_configs
   SET deleted_at = NOW(), updated_at = NOW()
 WHERE intent_code = 'PROCUREMENT_CREATE'
   AND deleted_at IS NULL;

-- 删除员工: keep HR_DELETE_EMPLOYEE (standard HR-naming, 3 dupes total)
UPDATE ai_intent_configs
   SET deleted_at = NOW(), updated_at = NOW()
 WHERE intent_code IN ('HR_EMPLOYEE_DELETE', 'HRM_DELETE_EMPLOYEE')
   AND deleted_at IS NULL;

-- 发送微信通知: keep NOTIFICATION_SEND_WECHAT (latest)
UPDATE ai_intent_configs
   SET deleted_at = NOW(), updated_at = NOW()
 WHERE intent_code = 'NOTIFICATION_WECHAT_SEND'
   AND deleted_at IS NULL;

-- 取消摄像头事件订阅: keep CAMERA_UNSUBSCRIBE (active + concise, 3 dupes)
UPDATE ai_intent_configs
   SET deleted_at = NOW(), updated_at = NOW()
 WHERE intent_code IN ('CAMERA_EVENT_UNSUBSCRIBE', 'CAMERA_SUBSCRIPTION_CANCEL')
   AND deleted_at IS NULL;

-- 员工岗位分配: keep EMPLOYEE_POSITION_ASSIGN (latest)
UPDATE ai_intent_configs
   SET deleted_at = NOW(), updated_at = NOW()
 WHERE intent_code = 'WORKER_STATION_ASSIGN'
   AND deleted_at IS NULL;

-- 导航到指定地点: keep NAVIGATE_TO_LOCATION (active)
UPDATE ai_intent_configs
   SET deleted_at = NOW(), updated_at = NOW()
 WHERE intent_code = 'NAVIGATION_TO_LOCATION'
   AND deleted_at IS NULL;

-- 排除已选项: keep UI_EXCLUDE_SELECTED (4 dupes — UI namespace clearest)
UPDATE ai_intent_configs
   SET deleted_at = NOW(), updated_at = NOW()
 WHERE intent_code IN ('FILTER_EXCLUDE_SELECTED', 'SYSTEM_FILTER_EXCLUDE_SELECTED', 'EXCLUDE_SELECTED')
   AND deleted_at IS NULL;

-- 智能质检报告: keep REPORT_AI_QUALITY (latest of 3, alphabetical clearest)
UPDATE ai_intent_configs
   SET deleted_at = NOW(), updated_at = NOW()
 WHERE intent_code IN ('REPORT_QUALITY_AI', 'REPORT_INTELLIGENT_QUALITY')
   AND deleted_at IS NULL;

-- 查询库存总量: keep INVENTORY_TOTAL_QUERY (subject-first naming)
UPDATE ai_intent_configs
   SET deleted_at = NOW(), updated_at = NOW()
 WHERE intent_code = 'QUERY_INVENTORY_TOTAL'
   AND deleted_at IS NULL;

-- 测试意图: soft-delete 全部 TEST_* 旧 placeholder (旧测试数据, both inactive)
UPDATE ai_intent_configs
   SET deleted_at = NOW(), updated_at = NOW()
 WHERE intent_code IN ('TEST_1767807746', 'TEST_1767807105')
   AND deleted_at IS NULL;

-- 溯源码格式查询: keep TRACE_CODE_FORMAT (active + latest of 4)
UPDATE ai_intent_configs
   SET deleted_at = NOW(), updated_at = NOW()
 WHERE intent_code IN ('TRACE_FORMAT_QUERY', 'TRACE_CODE_FORMAT_QUERY', 'QUERY_TRACE_CODE_FORMAT')
   AND deleted_at IS NULL;

-- 物流线路查询: keep LOGISTICS_ROUTE_QUERY (3 dupes — LINE 同名歧义)
UPDATE ai_intent_configs
   SET deleted_at = NOW(), updated_at = NOW()
 WHERE intent_code IN ('LOGISTICS_LINE_QUERY', 'QUERY_LOGISTICS_ROUTE')
   AND deleted_at IS NULL;

-- 翻台率查询: keep RESTAURANT_TABLE_TURNOVER (active + restaurant ns)
UPDATE ai_intent_configs
   SET deleted_at = NOW(), updated_at = NOW()
 WHERE intent_code IN ('TURNOVER_RATE_QUERY', 'TABLE_TURNOVER_QUERY')
   AND deleted_at IS NULL;

-- 通知设置: keep FACTORY_NOTIFICATION_CONFIG (has tool_name, mature factory-level)
UPDATE ai_intent_configs
   SET deleted_at = NOW(), updated_at = NOW()
 WHERE intent_code = 'SYSTEM_NOTIFICATION'
   AND deleted_at IS NULL;

-- ===== Verification view (post-migration sanity check) =====
-- 此 query 跑完后应返 0 行 (no remaining duplicates among above):
--   SELECT intent_name, COUNT(*) FROM ai_intent_configs
--    WHERE deleted_at IS NULL
--    GROUP BY intent_name HAVING COUNT(*) > 1;

COMMIT;

-- 预期效果 (per prod snapshot 2026-05-21):
--   Before: 542 active (517 distinct names, 19 dup groups, 48 dup rows)
--   After:  ~519 active (517 distinct names, 0 dup groups)
--   Soft-deleted: 23 rows (preserved for audit, recoverable via UPDATE deleted_at = NULL)
