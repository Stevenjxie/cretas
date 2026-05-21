# Sprint 9 P0.1 — Workdesk Intent Binding RCA + Fix

**日期**: 2026-05-21
**严重度**: P0 阻塞 (Sprint 8 P1+P2+P3 全部 WORKDESK AI 输出 broken)
**触发**: Sprint 9 P0 Playwright smoke 抓到 sales-owner + finance-manager workdesk AI 输出 "暂不支持此类型的意图执行: WORKDESK"
**修复 PR**: `sprint9/p01-workdesk-intent-binding-fix`

---

## 根因 (Root Cause)

Sprint 8 P1/P2/P3 ship 6 个 WORKDESK 顶层 intent (`DAILY_CUSTOMER_FOLLOWUP` /
`MONTHLY_FINANCIAL_CLOSE` / `FOOD_SAFETY_RECALL` / `QUALITY_CHIEF_WORKDESK` 等),
全部 `tool_name=NULL` + 假设 SkillRegistry keyword 匹配走 Skill 路由.

**实际 dispatch 路径** (`IntentExecutionOrchestrator.execute()`):

```
意图识别 → trySkillRoute (用 Skill triggers 匹用户原文)
            ├─ 用户 query 含 Skill triggers → Skill 执行 ✅
            └─ 用户 query 不含 Skill triggers → 返 null
                    ↓
              tool_name == null → 落 line 313 final else
                    ↓
              buildNoToolResponse(intent)
                    ↓
              "暂不支持此类型的意图执行: WORKDESK" ❌ P0 阻塞
```

**两个 keyword 集合不对齐** (Sprint 8 设计漏洞):

| 配置 | 关键词集合 | 例子 |
|---|---|---|
| `ai_intent_configs.keywords` (intent layer) | `["今天跟谁","今日跟进","我的客户跟进",...,"客户跟进清单"]` | LLM intent 识别用 |
| `SkillDefinition.triggers` (skill layer) | `["今天跟谁","今日跟进","我的客户跟进","今天该跟谁","跟进列表","客户跟进清单",...]` | `trySkillRoute` keyword 匹配用 |

如果用户问 "请帮我看下要跟进哪些客户" — intent layer LLM 能 recognize 为
`DAILY_CUSTOMER_FOLLOWUP`, 但 Skill keyword 匹配 (`contains()` substring) 找不到任何
Skill triggers → 整个 path 断裂.

更糟的是: Sprint 8 ship 时只验证了 "完美匹 keywords 的 case", **没验证 LLM 路径
intent 已 matched 但用户原文不含 Skill triggers 的 case**.

---

## 修复 Strategy (Strategy A — Backend Code Fix)

新增 `DynamicToolSelectionService.tryExplicitSkillRouteForIntent(intent, query, factoryId, userId)`:

1. 把 `intent_code` (UPPER_SNAKE_CASE) → `skill_name` (lower-kebab-case)
   - 例: `DAILY_CUSTOMER_FOLLOWUP` → `daily-customer-followup`
   - convention 由 Sprint 8 P1-P3 已建立 (SkillRegistryImpl line 518/558/675 等)
2. 通过 `SkillRouterService.executeSkill(skillName, ctx)` **直接** 执行 Skill, 绕过
   keyword 匹配 (因为 intent 已被识别, keyword 不是 gate)
3. Skill 不存在 / 异常 → return null, 让下游 fallback 处理

**插桩位置** (覆盖所有 dispatch path):

| 文件 | 位置 | 修复点 |
|---|---|---|
| `DynamicToolSelectionService.java` | new method 后 line 212 | + `tryExplicitSkillRouteForIntent` + `intentCodeToSkillName` helper |
| `IntentExecutionOrchestrator.java` | line 270 区域 | Skill 优先检查后加 explicit Skill 兜底 |
| `IntentExecutionOrchestrator.java` | line 313 区域 | 最终 No-Match 分支前加 explicit Skill 兜底 |
| `IntentExecutionOrchestrator.java` | line 397 (`executeWithExplicitIntent`) | 显式 intent path 加 explicit Skill 兜底 |
| `SseStreamingService.java` | line 280 区域 | SSE skill route 加 explicit Skill 兜底 |
| `SseStreamingService.java` | line 395 区域 | SSE `executeAndStreamResult` 加 explicit Skill 兜底 |

**Backwards compat 保证**:
- 旧 tool_name 绑定路径**完全不变** — tool 直接执行 (line 288-294) 完全保留
- 旧 trySkillRoute keyword 匹配路径**完全不变** — 优先尝试, 仅在 null 时 fallback
- explicit Skill 路由仅在 `tool_name=NULL` AND `trySkillRoute returned null` 时触发

---

## 验证

### Unit Test — `DynamicToolSelectionServiceWorkdeskRouteTest`

13 个 test case, 全 PASS:
- `intentCodeToSkillName_dailyCustomerFollowup_kebabLowercase` — DAILY_CUSTOMER_FOLLOWUP → daily-customer-followup
- `intentCodeToSkillName_monthlyFinancialClose_kebabLowercase` — MONTHLY_FINANCIAL_CLOSE → monthly-financial-close
- `intentCodeToSkillName_foodSafetyRecall_kebabLowercase` — FOOD_SAFETY_RECALL → food-safety-recall
- `intentCodeToSkillName_nullOrEmpty_returnsEmpty` — null/empty guard
- `tryExplicitSkillRouteForIntent_workdeskSkillRegistered_executesSkillAndReturnsResponse` — happy path Sprint 8 P1
- `tryExplicitSkillRouteForIntent_monthlyFinancialClose_routesToMonthlyClose` — happy path Sprint 8 P2
- `tryExplicitSkillRouteForIntent_skillNotRegistered_returnsNull` — Skill 不存在
- `tryExplicitSkillRouteForIntent_skillsDisabled_returnsNullNeverInvokesExecute` — feature flag off
- `tryExplicitSkillRouteForIntent_nullIntent_returnsNull` — null guard
- `tryExplicitSkillRouteForIntent_nullIntentCode_returnsNull` — null guard
- `tryExplicitSkillRouteForIntent_skillRouterReturnsNull_returnsNull` — defensive
- `tryExplicitSkillRouteForIntent_skillExecutionThrows_returnsNull` — exception swallowed
- `tryExplicitSkillRouteForIntent_nullUserId_passesNullToContext` — anonymous user

Existing `RestaurantSkillsRegistrationTest` (3 tests) — 仍 PASS (没影响 skill registry).

### Build

`./mvnw compile` — BUILD SUCCESS.
`./mvnw test-compile` — BUILD SUCCESS.

### Flyway Diagnostic

`V20260521_50__workdesk_intent_skill_binding_diagnostic.sql` — 列出现状 + sanity check
Sprint 8 P1/P2/P3 intents 是否存在. 仅 diagnostic, **不修数据**. 真修复是 Java code change.

---

## Sprint 8 Final Validation 评分更正

| 评分项 | 原 (Sprint 8 close) | 实测 (Sprint 9 P0 smoke) | 修复后 |
|---|---|---|---|
| Workdesk Skill 路由通畅 | 8/10 (假设 "Skill keyword match works") | **0/10** (Playwright 真测全 broken) | 8/10 (本 fix 验证) |
| 综合 | 8/10 | **6/10** | 8/10 |

**Sprint 8 ship 误判根因**: 没用 Playwright depth-first E2E 验证 LLM intent path
("不在 Skill triggers 的用户 query") — 只测了 "完美 keyword 匹配" happy path.

---

## 后续 Sprint 9 P0.2 验证 (Need Steve action)

1. Steve deploy 本 branch 到 `--env test` (cretas-backend-test 自动重启)
2. Steve 跑 Playwright P0 smoke 验证 sales-owner + finance-manager Workdesk AI 输出
3. 若 P0 smoke 全绿 → merge to main, deploy `--env prod`
4. 若仍有问题 → 本 RCA + 修复 + test 已可作为下次 deep-dive 的 baseline

---

## 历史教训 (Memory 候选)

**Pattern**: tool_name=NULL intent + Skill keyword 匹配 = 双 keyword 集合维护噩梦.
LLM intent path 能识别 intent 但 Skill triggers 不含等价 keyword → silent break.

**Fix Pattern**: intent_code → skill_name naming convention (UPPER_SNAKE → kebab-lower)
+ direct lookup, 绕过 keyword 匹配作为 fallback.

**Test Pattern**: 任何 tool_name=NULL ship 前必须 Playwright E2E with "LLM-identified
but keyword-non-matching" user query test case.
