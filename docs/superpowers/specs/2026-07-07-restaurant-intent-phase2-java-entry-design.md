# 餐饮意图 Phase 2：QuerySpec + Answer Contract 提升到 Java 统一入口

**日期**: 2026-07-07（Phase 1 spec: `2026-07-07-restaurant-intent-tiered-routing-design.md`）
**触发**: Phase 1 线上验证发现 demo 主路由是 Java `executeIntent`（`/api/mobile/{factoryId}/ai-intents/execute`），其 Stage-8 LLM 以 0.95 置信度把"这两个月生意咋样，挣着钱没"直选为 `RESTAURANT_REVENUE_TREND` 并执行 Java Gold Tool——无槽位抽取、无契约校验，丢了 2 个月窗口和盈亏判断。Python 分层（Phase 1）只在 Java miss 时生效。
**范围**: 🔒 业态路由红线。Java 侧只改 `ai/tool/impl/restaurant/gold/` 包 + `GoldFinanceClient`；**不碰** `IntentExecutorServiceImpl`、不碰意图识别管线、不碰 ai_intent_config。

---

## 1. 方案选择

| 方案 | 结论 |
|---|---|
| A. Java Gold Tool 执行前带 guard 委托 Python 分层（本设计） | ✅ 复用 Phase 1 全部成果；判断集中在 Python；Java 只加一跳 |
| B. QuerySpec/契约 port 到 Java | ❌ 反向 parity 负担（python-java-port 12 条规则之痛反着来一遍） |
| C. 降 Stage-8 对餐饮意图的直选置信度 | ❌ 动共享匹配层，影响全部 504 意图，不可控 |

**核心不变量**：guard 保证「Java 现在答得好的查询继续由 Java 原样回答」——委托只发生在 Java 必然丢信息的查询上；任何异常 fail-open 回 Java 原流程。

## 2. 架构

```
executeIntent (Java, 不动)
  → 意图识别 8 层 (不动) → 命中 RESTAURANT_*_GOLD 意图 → Gold Tool 执行
      → GoldBackedRestaurantTool.doExecute (final 模板, 唯一挂钩点)
          ① NEW: userInput 非空 → GoldFinanceClient.fetchTieredIntentAnswer(factoryId, userInput, toolName)
              → Python POST /api/smartbi/gold/restaurant/tiered-answer
                  → parse_restaurant_query (Phase 1 T1→T2→T3)
                  → 委托判定 (§3)
                  → 委托: resolve_by_code + Answer Contract → {delegate:true, answer_text, charts, kpis, code, contract_pass}
                  → 不委托: {delegate:false}
          ② delegate:true → 映射为 Tool result（走 ensureActionableMessage 保持 followups/decisionBridge）→ 返回
          ③ delegate:false / 超时 / 异常 → 原 resolveWindow→queryGold→format 流程，一字不改
```

## 3. 委托判定（keystone，实现在 Python 端，判断集中一处）

输入：spec（parse 结果）+ `java_tool_name`。规则（按序）：

1. spec 为 None（三层全 miss / 非餐饮租户 / 异常）→ `{delegate:false}`
2. `spec.clarification_needed` → `{delegate:true, kind:"clarification", answer_text:澄清问题}`
3. `spec.asks_profitability` 或 `spec.wants_margin` → 委托（Java Gold Tool 家族不产盈亏判断/毛利）
4. `spec.intent == RESTAURANT_OPS_SALES_SUMMARY 且 spec.relative_window` → 委托（"最近N天/周/月"经营概览窗口只有 Python resolver honoring；Java 只解析绝对月/本月/上月）
5. 其余（纯趋势/排行/绝对月查询等）→ `{delegate:false}`（Java 现有回答质量好，保持 byte 级不变）

委托后执行 = Phase 1 `_try_tiered_restaurant_intent` 同一条链（含 Answer Contract 校验 + 缺失中文披露 + 飞轮 capture, tier/contract_pass 记入日志, `source:"java_entry_delegate"` 以便区分）。

## 4. 改动清单

### Python（复用 Phase 1，新增 ~150 行）
- **重构**: `chat.py::_try_tiered_restaurant_intent` 主体挪到新 `smartbi/gold/restaurant_intent_service.py`（`tiered_answer(query, pool, factory_id, role, *, java_tool_name=None) -> Optional[dict]`）；chat.py 留薄 wrapper 行为不变（现有 194 测试全绿）。
- **新端点**: 挂在 GoldFinanceClient 现用的同一 gold API router 下：`POST /api/smartbi/gold/restaurant/tiered-answer`，body `{factory_id, query, java_tool_name}`，读 `X-User-Role` header 作 role（镜像既有 gold 端点 RBAC 模式）。返回 §3 shape。**传原始 factoryId**（DEMO_REST 在 Python 有自己 seed，与 chat.py 路径一致；不做 RES_3101_009 映射）。
- 委托判定函数 `should_delegate(spec, java_tool_name) -> bool` 独立可测。

### Java（`restaurant/gold` 包内 + client，~120 行）
- `GoldFinanceClient.fetchTieredIntentAnswer(String factoryId, String userInput, String javaToolName)`：POST 上述端点，X-User-Role 转发（照抄既有方法），**timeout 10s**（T3 LLM 最多 5s + resolve），任何非 200/异常返回 null。
- `GoldBackedRestaurantTool.doExecute` 顶部插入委托 gate：
  - `userInput` 空 → 跳过（保持现状）
  - 委托结果映射：`message=answer_text`、`charts`（ops inline-ECharts shape，前端已有 normalize (b) 路径可渲）、`kpis`、`dataAvailable:true`、`tieredDelegate:true`（观测标记）→ 过 `ensureActionableMessage`
  - null / delegate:false / 异常 → 原流程。**catch Exception 必须 log.warn 后 fall through，绝不向上抛**
- 不改任何 subclass；不改 `IntentExecutorServiceImpl`；不加 Flyway migration。

### 测试
- Python: `tests/test_restaurant_intent_service.py` — should_delegate 判定矩阵（≥10 case：盈亏问/毛利问/SALES_SUMMARY+相对窗→委托；纯趋势/绝对月/排行→不委托；澄清；None spec）；端点 role 透传；fail-open。
- Java: `GoldBackedRestaurantToolDelegateTest`（mock GoldFinanceClient）— delegate:true 映射、delegate:false 走原流程、client 抛异常走原流程、userInput 缺失跳过。跑 `mvn test -Dtest=...` 相关模块。
- 现有 Python 194 测试全绿（chat.py wrapper 重构不得改行为）。

## 5. 验收（线上）
1. demo 餐饮问"这两个月生意咋样，挣着钱没" → 回答含 2 个月窗口 + 盈亏判断 + 毛利（此前答 17 个月趋势）。
2. "营收趋势怎么样" → 仍是 Java 趋势报表风格回答（不委托，零回归）。
3. Q1-Q4 验收基准问题复测不回归（owner-action 路径未动）。
4. prod 日志见 `java_entry_delegate` capture 记录。

## 6. 部署顺序（🔒 从 main）
1. 先部 Python（新端点向后兼容，Java 未升级时无人调用）
2. 后部 Java（blue-green `deploy-backend.sh --env prod`）
3. 若 Java 回滚，Python 端点闲置无害。

## 7. 明确不做
- 不动意图识别 8 层 / Stage-8 阈值 / ai_intent_config
- 不动 owner-action 桥（质量已好）
- 不给非 gold 家族的餐饮 Tool（economics 等）加 gate（后续按需）
- 不做 Java 端 QuerySpec 原生实现
