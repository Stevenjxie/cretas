-- 店长经营 KPI 看板 (single-store 直营 MVP) — RESTAURANT_STORE_KPI_DASHBOARD 意图
-- → restaurant_store_kpi_dashboard 工具 (2026-06-04)
--
-- 背景: 店长/老板想一屏看门店核心经营 6 指标 (日营收/客单价/订单数/毛利率/食材成本率/
--   目标完成率), 每项带健康灯。绑定关键词命中 StoreKpiDashboardTool (调 Python section
--   store_kpi_dashboard self-query gold)。单店 = 工厂级聚合, 不做门店范围网关。
--
-- flyway 版本 20260927.01: origin/main Java flyway dir frontier = V20260926_01
--   (out-of-order=false → 必须更大)。实施前查重:
--     git ls-tree -r origin/main --name-only backend/java/cretas-api/src/main/resources/db/flyway \
--       | grep -oE 'V[0-9]{8}_[0-9]{2}' | sort -u | tail  → max V20260926_01, 无 927。
--   合并前再 `git ls-tree origin/main db/flyway | grep -oE 'V[0-9]{8}_[0-9]{2}' | sort | uniq -d`
--   防 sister 抢号 (Flyway 同号静默跳过 → 启动报 more than one migration)。
--
-- 幂等: ON CONFLICT (intent_code) DO UPDATE, 可重复执行。
-- business_type='RESTAURANT' 让业态门控放行 (制造业态工厂不进此意图)。
-- keywords ::jsonb cast 与既有迁移一致 (keywords 列跨环境 text/json/jsonb 不一致)。
-- priority 115: 略高于 value_summary(110), 因 "经营看板/我的看板" 是店长高频入口短语。
-- sensitivity_level LOW: 只读经营汇总 (金额由 Python RBAC 按角色剥零, 非写操作)。
--
-- ⚠️ 新意图需 embedding backfill 才能走语义 (SEMANTIC) 层; keyword/phrase 匹配立即可用。
--   (backfill 由 embedding-service 重建意图向量, 见 Phase 2B embedding 迁移流程。)
-- ⚠️ 关键词避开过度泛化的 "怎么样" 形式 (已知歧义查询局限)。

INSERT INTO ai_intent_configs (id, intent_code, intent_name, intent_category, tool_name, sensitivity_level, keywords, description, priority, is_active, business_type, created_at, updated_at)
VALUES (gen_random_uuid(), 'RESTAURANT_STORE_KPI_DASHBOARD', '店长经营KPI看板', 'RESTAURANT', 'restaurant_store_kpi_dashboard', 'LOW',
        '["经营看板","我的看板","店长KPI","店长看板","门店经营情况","经营KPI","经营指标","门店KPI","经营健康度","门店经营看板"]'::jsonb,
        '店长经营 KPI 看板 (单店直营): 一屏概览门店核心经营 6 指标 — 日营收、客单价、订单数、毛利率、食材成本率、目标完成率, 每项带健康灯 (绿/黄/红) + 整体经营健康度。复用 gold (agg_daily / 配方成本 / 领料成本 / 目标达成)。金额按角色 RBAC 剥零, 比率/计数照常。适用 "经营看板 / 我的看板 / 店长KPI / 门店经营情况" 类问题。',
        115, true, 'RESTAURANT', NOW(), NOW())
ON CONFLICT (intent_code) DO UPDATE SET
    tool_name = 'restaurant_store_kpi_dashboard',
    keywords = EXCLUDED.keywords,
    description = EXCLUDED.description,
    priority = 115,
    is_active = true,
    business_type = 'RESTAURANT',
    updated_at = NOW();
