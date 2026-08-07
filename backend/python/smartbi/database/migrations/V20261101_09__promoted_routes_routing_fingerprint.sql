-- 2026-08-07 晋升表补「路由规则指纹」—— 消除与计划缓存之间的不对称。
--
-- 背景（`_routing_rules_fingerprint` 的 docstring 里记着这次事故）：
--   #2043 改了指标编译规则（「采购花了多少钱」→ requisition_cost），部署几小时后
--   RES_3101_009 上这句仍被路由成 RECIPE_COST，`source_tier=plan_cache` ——
--   **重放的是修复前编译的计划**。原因是 `_PLAN_VERSION` 要人记得 bump，而没人记得。
--   之后计划缓存的键里并进了从规则表本身算出来的指纹，**改规则即失效**。
--
-- 🔴 但 `ai_promoted_routes` 没有这个保护，只过滤「resolver 已退役」
--    （`_VALID_CODES`）。而晋升比缓存**更严重**：永久、跨重启、跨 worker。
--    规则改了，它照旧零 token 回放一个可能已经错的计划。
--
-- ⛔ 指纹不符时的处置：**跳过该行**（回落 planner，答案仍对、只是慢），不是
--    「标记待复审但继续服务」。理由：存的是**完整计划**，规则变了意味着这个计划
--    可能本来就是错的 —— 与缓存失效的理由完全相同。人审过不能保护这一点，
--    因为人是在**旧规则下**审的。跳过是安全的一侧，且代价有界（回落 LLM）。
--
-- ⚠️ 回填：现存行是在**当前规则下**由人审通过的，因此回填当前指纹是正确的，
--    不是「为了让它别失效而糊弄」。将来规则一变，它们会自然进入待复审。
--    回填值由应用侧在部署后写入（见 `backfill_promoted_route_fingerprints`），
--    因为指纹只能由 Python 侧的规则表算出来，SQL 里算不出。

ALTER TABLE ai_promoted_routes
    ADD COLUMN IF NOT EXISTS routing_fingerprint VARCHAR(16);

COMMENT ON COLUMN ai_promoted_routes.routing_fingerprint IS
    '写入时的 _routing_rules_fingerprint()。读取时不符即跳过该行(回落 planner)。'
    'NULL = 尚未回填的历史行, 同样跳过 —— 没有指纹就无法证明它是在当前规则下审的。';

-- 查询按 (domain, plan_version, scope) 走；指纹是行内比对，不需要索引。
