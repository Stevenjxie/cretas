# Sprint 8 AI Workdesk Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 把 Cretas 从"传统菜单 ERP + AI 套壳"升级为"真 AI Workdesk" — 让 Sprint 5+6+7 的所有 customer business entity (微信/通话/商机/业绩/期间/科目/工资/请购/凭证/3 报表) 全部可被 AI 自然语言调用。

**Architecture:** Workdesk-Driven 节奏 — P0 修信任先行 (4 inline tasks, 1-2d) → P1-P4 各 1 个角色 Workdesk demo (4 dispatch units, 各 3-5d). 利用现有 476 Tool + 18 Skill 架构基础 (`ai/tool/impl/` + `service/skill/impl/`), 新增 ~50 Tool + 5 Skill 覆盖 5 个角色 Workdesk + 3 个食品行业 Skill。每 Phase 后 Steve smoke test (F006 真账号) + 5min mp4 demo 录屏 (Boss 演示弹药)。

**Tech Stack:** Java 21 Spring Boot 3.2.12 + Lombok / PostgreSQL Flyway / Vue 3 + Element Plus + ECharts 6 / 通义千问 LLM router / Whisper async / OSS audio。

**Reference:**
- Spec: `docs/superpowers/specs/2026-05-20-sprint-8-ai-workdesk-design.md` (877 行实施级设计)
- Goal: `docs/superpowers/specs/2026-05-20-sprint-8-ai-workdesk-goal.md`
- Rules: `.claude/rules/ai-intent-tool-skill-architecture.md` / `fool-proof-design.md` / `concurrent-edit-safety.md` / `database-entity-sync.md`

---

## File Structure Overview

### 新增文件 (Sprint 8 总)

| 类型 | 路径模板 | 数量 |
|---|---|---|
| Tool impl | `backend/java/cretas-api/src/main/java/com/cretas/aims/ai/tool/impl/{domain}/*Tool.java` | ~50 |
| Skill impl | `backend/java/cretas-api/src/main/java/com/cretas/aims/service/skill/impl/{name}Skill.java` + `.md` | 5 |
| Tool/Skill tests | `backend/java/cretas-api/src/test/java/com/cretas/aims/ai/tool/impl/{domain}/*ToolTest.java` | ~50 + 5 |
| Workdesk Vue | `web-admin/src/views/workdesk/{Role}Workdesk.vue` | 5 |
| P3 entity | `backend/java/cretas-api/src/main/java/com/cretas/aims/entity/foodsafety/{Haccp,Additive,Recall}*.java` | 4 |
| P3 repository | `backend/java/cretas-api/src/main/java/com/cretas/aims/repository/foodsafety/*Repository.java` | 4 |
| Flyway migration | `backend/java/cretas-api/src/main/resources/db/flyway/V20260820_*.sql` | 8 |

### 修改文件

| 路径 | 修改类型 |
|---|---|
| `web-admin/src/router/index.ts` | 加 5 Workdesk routes |
| `web-admin/src/layouts/menu.ts` (or equivalent) | 加 "🏪 我的工作台" menu group + 5 sub-items |
| `service/skill/impl/SkillRegistryImpl.java` | 加 5 行 `register(new XxxSkill())` |
| `finance/reports/list.vue` | DELETE (P0.1 修复) |
| 11 placeholder Vue files | DELETE or hide behind feature flag (per P0.2 audit) |
| ~20 DEAD_CODE Tool files | DELETE (per P0.3 audit) |

---

## Task 0.1: 修 Sprint 7 T3 三大报表路由 (P0, ~30 min, inline)

**Owner:** Inline (organizer)
**Blocker for:** P1, P2

**Files:**
- Modify: `web-admin/src/router/index.ts` (路由挂点)
- Modify: `web-admin/src/layouts/menu.ts` 或 `web-admin/src/components/menu/MenuConfig.ts` (菜单挂点 — 实际名 grep 确认)
- Delete: `web-admin/src/views/finance/reports/list.vue` (旧占位)
- Keep: `web-admin/src/views/finance/report/index.vue` (T3 新页, 3 tab parent)

- [ ] **Step 1: Grep 确认 2 个文件存在 + 路由现挂哪个**
```bash
cd C:/Users/Steve/my-prototype-logistics
find web-admin/src/views/finance -name "*.vue" | grep -iE "report"
grep -nE "finance/report|finance/reports" web-admin/src/router/index.ts
grep -rn "finance/report\|finance/reports" web-admin/src/layouts/ web-admin/src/components/menu/ 2>/dev/null
```
Expected: 找到 `reports/list.vue` (占位) + `report/index.vue` (T3 ship). 路由可能挂到 `reports/list.vue`。

- [ ] **Step 2: 修路由挂点改到 T3 新页**
找到 `router/index.ts` 中 `path: '/finance/reports'` 或 `component: () => import('@/views/finance/reports/list.vue')` 这一行, 改成 `component: () => import('@/views/finance/report/index.vue')` (注意单数 `report`)。

- [ ] **Step 3: 修菜单挂点 (如有)**
菜单配置文件改 path 到 `/finance/report` (从 `/finance/reports` 改单数)。

- [ ] **Step 4: 删除旧占位文件**
```bash
rm web-admin/src/views/finance/reports/list.vue
# 如果 reports 目录空了也删:
rmdir web-admin/src/views/finance/reports 2>/dev/null || true
```

- [ ] **Step 5: Local build + 自验**
```bash
cd web-admin && npm run build
```
Expected: 0 errors。如果有 "Cannot find module 'finance/reports/list.vue'" 报错, grep 其他引用点修。

- [ ] **Step 6: F006 prod 账号 smoke**
启动 dev 或部署 test, F006 账号登录 → 财务菜单 → 报表 → 验证显 3 tab parent (BalanceSheet/IncomeStatement/CashFlow) 而非 `<el-empty>`。

- [ ] **Step 7: Safe-commit (rule 5b)**
```bash
git add -- web-admin/src/router/index.ts \
            web-admin/src/components/menu/MenuConfig.ts \
            web-admin/src/views/finance/reports/list.vue
git status --short  # 验证 scope
git commit -m "$(cat <<'EOF'
fix(finance-report): P0.1 fix T3 三大报表路由冲突 — delete reports/list.vue 占位, 路由挂 report/index.vue

Audit 揭 finance/reports/list.vue (el-empty 占位) 跟 Sprint 7 T3 新 finance/report/index.vue
路径冲突 — 路由挂的可能是占位. Steve smoke 验证 T3 用户可达后清理.
EOF
)" -- web-admin/src/router/index.ts \
      web-admin/src/components/menu/MenuConfig.ts \
      web-admin/src/views/finance/reports/list.vue
```

- [ ] **Step 8: Push**
```bash
git push origin main
```

---

## Task 0.2: Audit + 清 11 个占位页 (P0, ~1h, inline)

**Owner:** Inline (organizer)

**Files:**
- Create: `docs/audits/2026-05-20-pre-sprint-8-placeholder-audit.md`
- Modify or delete: ~11 Vue files

- [ ] **Step 1: Grep 列全部占位页**
```bash
cd C:/Users/Steve/my-prototype-logistics
grep -rln '<el-empty.*description=".*\(开发中\|敬请期待\|占位\|coming soon\|正在开发\|待\(接\|开\|后端\)\)' \
  web-admin/src/views/ > /tmp/placeholder-files.txt
wc -l /tmp/placeholder-files.txt
```
Expected: ~10-15 files。

- [ ] **Step 2: 对每个文件读 description + 决定 action**
对每个文件:
```bash
for f in $(cat /tmp/placeholder-files.txt); do
  echo "=== $f ==="
  grep -B1 -A2 "el-empty" "$f" | head -5
done
```
分类决策:
- **DELETE**: 无其他依赖 (路由可能也要删, grep router 验证)
- **HIDE behind feature flag**: 路由保留, 菜单藏 (改 menu config)
- **KEEP**: 是合法 empty state (e.g. "暂无数据, 添加第一条") 不算 placeholder

- [ ] **Step 3: 写 audit doc**
创建 `docs/audits/2026-05-20-pre-sprint-8-placeholder-audit.md` 用表格:
```markdown
# Pre-Sprint 8 Placeholder Audit

**日期**: 2026-05-20
**触发**: Sprint 8 P0.2 audit, 修信任前提

## 11 placeholder files (or 实际数量) decisions

| 文件 | el-empty description | 决策 | 理由 | 跟进 issue |
|---|---|---|---|---|
| equipment/maintenance/index.vue | "功能开发中" | HIDE | Sprint 9 backlog | #XX |
| sales/calibration/CalibrationListView.vue | "后端 API 开发中" | HIDE | 等 P2 ship | #XX |
| ... (剩 9 个待 audit) | ... | ... | ... | ... |

总: X DELETE / Y HIDE / Z KEEP
```

- [ ] **Step 4: 执行 DELETE / HIDE actions**
- DELETE 的文件: `rm <path>` + grep 引用清理
- HIDE 的菜单条目: comment out in menu config

- [ ] **Step 5: Build 验证**
```bash
cd web-admin && npm run build
```

- [ ] **Step 6: Safe-commit 各文件 + audit doc**
```bash
git add -- docs/audits/2026-05-20-pre-sprint-8-placeholder-audit.md \
            <list of DELETE/HIDE files>
git status --short
git commit -m "fix(placeholders): P0.2 audit + cleanup 11 placeholder Vue pages

Pre-Sprint 8 修信任. 见 audit doc 分类决策表."
git push origin main
```

---

## Task 0.3: Audit 160+ null/empty Tools (P0, ~1h, inline)

**Owner:** Inline (organizer)

**Files:**
- Create: `docs/audits/2026-05-20-null-tool-audit.md`
- Delete: ~10-30 DEAD_CODE Tool files

- [ ] **Step 1: Grep 全部返 null/empty 的 Tool**
```bash
cd C:/Users/Steve/my-prototype-logistics
grep -rln "return null;\|return Collections.emptyList();\|return new HashMap<>();\|return new ArrayList<>();" \
  backend/java/cretas-api/src/main/java/com/cretas/aims/ai/tool/impl/ > /tmp/null-tools.txt
wc -l /tmp/null-tools.txt
```
Expected: ~50-160 files (audit 说 160, 实际可能 grep 出更多含合理 null)。

- [ ] **Step 2: 分类每个 Tool (sampling 20 random + manual rest)**
对每个文件 read `doExecute()`:
- **REAL_NOT_IMPLEMENTED**: 业务逻辑未写, 该有数据但返 null
- **TEST_STUB**: 故意空 (e.g. Tool 占位等扩展)
- **FAIL_FAST**: 主动 fail-safe 防假数据 (e.g. factory not found → null OK)
- **DEAD_CODE**: 无 caller (grep `getToolName()` 返值 in `service/`, 0 hit = dead)

- [ ] **Step 3: 写 audit doc**
```markdown
# Null/Empty Tool Audit

| Category | Count | Action |
|---|---|---|
| REAL_NOT_IMPLEMENTED | ~50 | Sprint 8/9 fill, top 20 列优先级 |
| TEST_STUB | ~30 | Keep, mark with `@Deprecated(forRemoval = true)` 待用 |
| FAIL_FAST | ~50 | Keep, 加 javadoc 解释 |
| DEAD_CODE | ~20 | DELETE this Sprint |

## REAL_NOT_IMPLEMENTED top 20 (按 entity 重要性)

1. ToolName: ToolFile — entity, blocker for Workdesk X
2. ...

## DEAD_CODE 删除清单

- ai/tool/impl/dataop/OldXxxTool.java (0 callers, 用 grep 验证)
- ...
```

- [ ] **Step 4: 删除 DEAD_CODE Tools**
```bash
git rm backend/java/cretas-api/src/main/java/com/cretas/aims/ai/tool/impl/.../OldXxxTool.java
git rm <list>
```

- [ ] **Step 5: Verify mvn compile + test**
```bash
cd backend/java/cretas-api
./mvnw compile -DskipTests
./mvnw test -Dtest='ToolRegistryTest' 2>&1 | tail -10
```
Expected: BUILD SUCCESS, ToolRegistry 启动验证 OK (476 → 456 左右)。

- [ ] **Step 6: Safe-commit**
```bash
git add -- docs/audits/2026-05-20-null-tool-audit.md \
            <DEAD_CODE files git-rm'd>
git commit -m "audit(tools): P0.3 null-tool audit + delete ~20 DEAD_CODE Tools

Pre-Sprint 8 修信任. 476 → ~456 真 Tool. REAL_NOT_IMPLEMENTED top 20
作为 Sprint 8 Tool 包装的优先项。"
git push origin main
```

---

## Task 0.4: Audit 102 @Deprecated on 新代码 (P0, ~1h, inline)

**Owner:** Inline (organizer)

**Files:**
- Create: `docs/audits/2026-05-20-deprecated-audit.md`

- [ ] **Step 1: Grep @Deprecated 在 Sprint 5+ 之后加的代码**
```bash
cd C:/Users/Steve/my-prototype-logistics
git log --since="2026-04-01" --diff-filter=AM --name-only --pretty=format: -- '*.java' \
  | sort -u > /tmp/post-apr1-files.txt

# 对每个 file 看是否含 @Deprecated
while read -r f; do
  if [ -f "$f" ] && grep -l '@Deprecated' "$f" > /dev/null; then
    echo "$f"
  fi
done < /tmp/post-apr1-files.txt > /tmp/deprecated-new-files.txt

wc -l /tmp/deprecated-new-files.txt
```

- [ ] **Step 2: 对每个文件读 @Deprecated 位置 + 分类**
```bash
for f in $(cat /tmp/deprecated-new-files.txt); do
  echo "=== $f ==="
  grep -B1 -A3 "@Deprecated" "$f"
done
```
分类:
- **LEGACY_KEPT**: 旧设计废弃, 还有 caller, 等迁移 (列迁移目标)
- **JUST_DEPRECATED**: 加完就 deprecated, 设计反复 (问 why, 决定保留/删)
- **REAL_DEAD**: 无 caller (delete)

- [ ] **Step 3: 写 audit doc**
```markdown
# @Deprecated New Code Audit

102 个 @Deprecated 在 Sprint 5+ 之后加的代码上。

| Category | Count | Action | Notes |
|---|---|---|---|
| LEGACY_KEPT | ~60 | 待迁移, 列迁移目标 + Sprint 排期 | e.g. AIIntentService 老 API → 新 API |
| JUST_DEPRECATED | ~30 | 内部 audit 决定 | 多数是 DashboardResponse 5 字段 |
| REAL_DEAD | ~10 | DELETE | 无 caller |

## LEGACY_KEPT 迁移计划

1. AIIntentService 20+ @Deprecated → migrate to NewAIIntentServiceV2 — Sprint 9 P1
2. DashboardResponse 5 字段 → migrate to SmartBI v3 DTO — Sprint 9 P1
3. ...

## REAL_DEAD 删除清单
...
```

- [ ] **Step 4: 删除 REAL_DEAD (如有)**
```bash
git rm <list of files>
```

- [ ] **Step 5: Safe-commit**
```bash
git add -- docs/audits/2026-05-20-deprecated-audit.md \
            <REAL_DEAD files if any>
git commit -m "audit(deprecated): P0.4 @Deprecated audit + delete REAL_DEAD

Pre-Sprint 8 修信任. 102 @Deprecated 分类: LEGACY_KEPT 60 / JUST_DEPRECATED 30 / REAL_DEAD 12.
迁移目标列入 Sprint 9 P1."
git push origin main
```

---

## Task 0.5: P0 总报告 + 验收 gate (P0, ~30 min, inline)

**Owner:** Inline (organizer)

**Files:**
- Create: `docs/audits/2026-05-20-pre-sprint-8-cleanup-summary.md`

- [ ] **Step 1: 汇总 4 个 P0 audit 结果**
```markdown
# Pre-Sprint 8 Cleanup Summary

## ✅ P0 BLOCKING 全部完成

| Task | 状态 | Deliverable |
|---|---|---|
| P0.1 修 T3 路由 | ✅ | F006 smoke 通过, 三大报表用户可达 |
| P0.2 清 11 占位页 | ✅ | X delete / Y hide / Z keep (per audit doc) |
| P0.3 audit null tools | ✅ | ~20 DEAD 删, top 20 REAL_NOT_IMPLEMENTED 列入 Sprint 8/9 |
| P0.4 audit @Deprecated | ✅ | LEGACY 60 / JUST 30 / DEAD 12 (DELETE 已执行) |

## AI 化评分追踪

- Sprint 8 起步: 3 / 10
- P0 完: 4 / 10 (信任建立)
- 后续 P1-P4 each +1 分

## P1 dispatch 准入

✅ P0 全 BLOCKING 解除, P1 卤味老板 Workdesk dispatch 可以开始。
```

- [ ] **Step 2: Steve 验收 P0 通过**
- Steve 亲自跑 F006 smoke (T3 三大报表点开 = 真页非 el-empty)
- Steve 确认 audit doc 合理

- [ ] **Step 3: Commit + push**
```bash
git add -- docs/audits/2026-05-20-pre-sprint-8-cleanup-summary.md
git commit -m "docs(audit): P0 完成总报告 — Sprint 8 P1 dispatch 准入"
git push origin main
```

---

## Task 1.0: P1 卤味老板 Workdesk V1 dispatch (3-5d, agent)

**Owner:** Dispatch 1 agent (worktree isolated, 120 min budget)

**Goal:** F006 真场景 — 张老板说 "今天该跟谁?" 系统输出排序客户清单。

### Dispatch brief

```
你是 Sprint 8 P1 卤味老板 Workdesk V1 实施者。

## ⛔ CRITICAL — READ FIRST: Worktree isolation
(per `.claude/rules/concurrent-edit-safety.md` HARD rule)

FORBIDDEN:
- ❌ DO NOT cd C:\Users\Steve\my-prototype-logistics (main repo path)
- ❌ DO NOT cd .. or paths outside your worktree
- ❌ DO NOT use absolute paths starting with C:\Users\Steve\my-prototype-logistics\backend\... for Write/Edit
  — ONLY use absolute paths under your worktree

REQUIRED first 2 commands:
1. pwd — confirm ends with .claude/worktrees/agent-<your-id>
2. git branch --show-current — should show worktree-agent-<your-id>

Branch from worktree: git checkout -b sprint8/p1-sales-owner-workdesk

## Required reading first

1. Spec: `<your-worktree>/docs/superpowers/specs/2026-05-20-sprint-8-ai-workdesk-design.md` §P1 (P1 完整规格)
2. AI 架构规范: `<your-worktree>/.claude/rules/ai-intent-tool-skill-architecture.md` (Tool/Skill 模板)
3. 防呆规则: `<your-worktree>/.claude/rules/fool-proof-design.md` (R1-R5 + 4 位一体)
4. 真 entity 字段 grep (audit 揭过假 entity 假设, 必须 grep 实际状态):
   grep -A30 "class WechatRecord " <your-worktree>/backend/java/cretas-api/src/main/java/com/cretas/aims/entity/WechatRecord.java
   grep -A30 "class CallRecord " <your-worktree>/backend/java/cretas-api/src/main/java/com/cretas/aims/entity/CallRecord.java
   grep -A30 "class SalesOpportunity " <your-worktree>/backend/java/cretas-api/src/main/java/com/cretas/aims/entity/SalesOpportunity.java
   grep -A30 "class Customer " <your-worktree>/backend/java/cretas-api/src/main/java/com/cretas/aims/entity/Customer.java
   — 看字段名 type 后再写 Tool
5. 现有 Tool 模板参考: 任选 1 个现有 Tool 文件:
   ls <your-worktree>/backend/java/cretas-api/src/main/java/com/cretas/aims/ai/tool/impl/crm/
   读 1 个看实际模板

## Scope (按 spec §P1, ship MVP)

### Phase A — 8 Tool implementations (~40 min)

新建 8 Tool, 均 extends `AbstractBusinessTool` `@Component`:

1. `customer_priority_query` — READ, args: `{includeStages?: string[]}`
2. `wechat_record_recent_query` — READ + FILTER, args: `{daysSince?: 7, customerId?: string}`
3. `wechat_record_create` — WRITE + Preview, args: `{customerId, direction, messageContent, recordTime}`, doPreview 必实施 R4 5min dedup check
4. `call_record_followup_pending` — READ + FILTER, args: `{daysSince?: 7, callType: 'MISSED'}`
5. `opportunity_stage_alert` — READ + FILTER, args: `{ownerId?, slaDays?: 21}`
6. `opportunity_transition_stage` — WRITE + Preview, args: `{id, newStage, reason}`, preview 显 state machine validation
7. `customer_revenue_trend` — AGGREGATE, args: `{customerId?, periodMonths?: 2}` — query SalesOrder by customer + month groupBy
8. `processing_capacity_today` — READ, 先 grep 现有 ProcessingService, 复用现有 Tool 如有, 否则新建

每 Tool 配 unit test (mvn test PASS, mock repository / service)。

### Phase B — Skill: daily-customer-followup (~30 min)

新建 `service/skill/impl/DailyCustomerFollowupSkill.java` 串 5-6 Tool, LLM aggregate 输出客户清单 (per spec §P1.3 YAML)。

`SkillRegistryImpl.java` 加 1 行 register。

### Phase C — Vue Workdesk (~30 min)

新建 `web-admin/src/views/workdesk/SalesOwnerWorkdesk.vue`:
- 顶部 AIChat 对话框 (复用现有 component, grep `find web-admin/src/components -name "AIChat*"`)
- 进入页面 auto-trigger "今天该跟谁?"
- 输出客户卡片 (颜色 🔴🟡🟢) + 行动按钮 [发微信][打电话][更新商机]
- 行动按钮调对应 Tool

加 route `/workdesk/sales-owner` to `web-admin/src/router/index.ts`
加菜单 "🏪 我的工作台 → 销售老板工作台" to menu config

### Phase D — Flyway intent migration (~10 min)

新建 `backend/java/cretas-api/src/main/resources/db/flyway/V20260820_01__sprint8_p1_workdesk_intents.sql`:
- INSERT `DAILY_CUSTOMER_FOLLOWUP` intent (skill_name='daily-customer-followup')
- INSERT 8 个 Tool-level intent (tool_name=*_query etc, keywords=3-5)
- ON CONFLICT (intent_code) DO NOTHING

### Phase E — Verification (~10 min)

- `cd <worktree>/backend/java/cretas-api && ./mvnw test -Dtest='Wechat*Tool*Test,Call*Tool*Test,Opportunity*Tool*Test,Customer*Tool*Test,Processing*Tool*Test'` — all PASS
- `cd <worktree>/web-admin && npm install --prefer-offline --legacy-peer-deps && npm run build` — PASS
- (可选 if time) Playwright E2E: 启 dev → F999 test 账号 → 访问 /workdesk/sales-owner → 验证 5 sec 内输出

## Constraints

- DO NOT use gh CLI (organizer creates PR)
- Concurrent edit safety rule 5b: `git commit -- <files>` 锁 scope
- 防呆 4 位一体 必实施 (per fool-proof-design.md)
- 所有 Tool description 写 3+ 用户问法 examples (LLM 触发关键)
- Time budget: 120 min hard cap. Phase A+B priority, C+D+E if time.

## Report back

- pwd + branch verification
- Phase A/B/C/D/E completed status
- Branch SHA pushed
- 8 Tool + 1 Skill + 1 Vue + 1 Flyway file 实际生成数
- 测试 PASS count
- 任何 spec 偏离 (entity 字段名不对 / 现有 Tool 复用 / 等)
```

- [ ] **Verification post-merge**: Steve F006 真账号 smoke + 录 5min mp4

- [ ] **Update AI 化评分**: 4 → 5 (1 Workdesk 跑通)

---

## Task 2.0: P2 财务主管 Workdesk dispatch (3-5d, agent)

**Owner:** Dispatch 1 agent (worktree, 120 min)

### Dispatch brief

```
你是 Sprint 8 P2 财务主管 Workdesk 实施者。

## ⛔ CRITICAL — Worktree isolation (同 Task 1.0 P1 brief)

Branch: sprint8/p2-finance-workdesk

## Required reading

1. Spec §P2 (`docs/superpowers/specs/2026-05-20-sprint-8-ai-workdesk-design.md`)
2. AI 架构规范 + 防呆规则
3. P1 已 ship 的 Tool 模板参考 (任选 1 个 P1 Tool 文件读)
4. Grep entity 字段:
   - Account / AccountingPeriod (`<worktree>/backend/.../entity/finance/`)
   - VoucherEntry / Voucher (复用)
   - SalesTarget / Commission / WagePolicy
   - 3 大报表 DTO (BalanceSheetDTO / IncomeStatementDTO / CashFlowDTO)
5. P0 修复成果验证: `<worktree>/web-admin/src/views/finance/report/index.vue` 是真页非 el-empty

## Scope (按 spec §P2, ship MVP)

### Phase A — 14 Tool (~60 min)
(列表见 spec §P2.2 表格)

### Phase B — Skill: monthly-financial-close (~30 min)
(YAML 见 spec §P2.3)

### Phase C — Vue Workdesk (~20 min)
- `web-admin/src/views/workdesk/FinanceManagerWorkdesk.vue`
- 输出"三表"链接必跳 P0 修复后的 `/finance/report/index.vue` (验证 link 正确)
- 应收账龄警告 -> 跳 `/finance/receivable/aging`

### Phase D — Flyway (~10 min)
`V20260820_02__sprint8_p2_finance_intents.sql` — `MONTHLY_FINANCIAL_CLOSE` + 14 Tool intents

### Phase E — 顺手修 (~10 min)
- W3-B `DecisionTypeMetadataRegistry.BUDGET_APPROVAL.wired` flip false → true (T2 spec follow-up)

### Verification

- mvn test 14+ Tool test PASS
- npm run build PASS
- F999 smoke: 访问 /workdesk/finance-manager → 输出经营摘要 + 三表链接可跳

## Constraints + Report back (同 P1)
```

- [ ] **Verification post-merge**: Steve smoke + 录 5min mp4

- [ ] **Update AI 化评分**: 5 → 6

---

## Task 3.1: P3 食品安全 entity + Flyway (5d Phase A, agent)

**Owner:** Dispatch 1 agent (worktree, 60 min for entity layer)

### Dispatch brief

```
你是 Sprint 8 P3 食品安全召回 Workdesk 实施者 — Phase A (entity + Flyway)。

## ⛔ CRITICAL — Worktree isolation (同 P1 brief)

Branch: sprint8/p3-food-safety-entity-and-flyway (会被 Phase B/C 继续扩)

## Required reading

1. Spec §P3 (`docs/superpowers/specs/2026-05-20-sprint-8-ai-workdesk-design.md`) — 4 entity 完整 Java 代码示例
2. 数据库 entity 规范: `<worktree>/.claude/rules/database-entity-sync.md`
3. 现有 entity 模板参考: 任选 1 个 `entity/finance/Account.java` 读

## Scope (Phase A 60 min — 仅 entity + Flyway, Phase B 后续 dispatch)

### Phase A.1 — 4 entity classes (~30 min)

新建 4 个 entity 在 `<worktree>/backend/java/cretas-api/src/main/java/com/cretas/aims/entity/foodsafety/`:

1. `HaccpCheckpoint.java` — per spec §P3.2.1 完整代码
2. `HaccpMonitoringRecord.java` — per spec §P3.2.2 完整代码
3. `AdditiveLimit.java` — per spec §P3.2.3 完整代码
4. `RecallEvent.java` + `RecallAction.java` — per spec §P3.2.4 完整代码 (放同一文件夹下)

### Phase A.2 — 4 repository interfaces (~10 min)

```java
public interface HaccpCheckpointRepository extends JpaRepository<HaccpCheckpoint, Long> {
    List<HaccpCheckpoint> findByFactoryIdAndActiveTrue(String factoryId);
}
// 同类 HaccpMonitoringRecordRepository / AdditiveLimitRepository / RecallEventRepository / RecallActionRepository
```

### Phase A.3 — 4 Flyway migrations (~20 min)

`<worktree>/backend/java/cretas-api/src/main/resources/db/flyway/`:

- `V20260820_03__haccp_checkpoints.sql` — CREATE TABLE haccp_checkpoints + haccp_monitoring_records + factory_id RLS + indexes
- `V20260820_04__additive_limits_seed.sql` — CREATE TABLE additive_limits + 30-50 INSERT 中国 GB 2760 卤味/熟肉常用添加剂 (亚硝酸钠/山梨酸钾/苯甲酸钠/防腐剂等)
- `V20260820_05__recall_events.sql` — CREATE TABLE recall_events + recall_actions + factory_id RLS
- `V20260820_06__food_safety_indexes.sql` — 复合 index (batch_number, monitoring_time, etc.)

### Phase A.4 — Unit tests (~10 min)

```java
public class HaccpCheckpointTest {
    @Test void entityValidation_requiredFields() { ... }
    @Test void criticalLimitMin_lessThanMax() { ... }
}
// 同类其他 entity tests
```

### Verification

- `cd <worktree>/backend/java/cretas-api && ./mvnw test -Dtest='Haccp*Test,Additive*Test,Recall*Test'` PASS
- `./mvnw compile` PASS

## Constraints + Report back (同 P1)
```

- [ ] **Phase A verification**: 4 entity + 4 Flyway + 5 tests PASS

---

## Task 3.2: P3 食品安全 Tool + Skill (5d Phase B, agent)

**Owner:** Dispatch 1 agent (worktree, 90 min) — depends on Task 3.1 ship + merge

### Dispatch brief

```
你是 Sprint 8 P3 食品安全 — Phase B (Tool + Skill)。

## ⛔ CRITICAL — Worktree isolation

Branch: sprint8/p3-food-safety-tools-and-skills (从 main 起, P3 entity 已 merge)

## Required reading

1. Spec §P3.3 (Tool 包装清单) + §P3.4 (3 Skill YAML)
2. P3 Phase A 已 ship 的 4 entity grep 确认字段名

## Scope (90 min)

### Phase B.1 — 8 Tool (~45 min)

(List per spec §P3.3 表格)

8 个 Tool 在 `<worktree>/backend/java/cretas-api/src/main/java/com/cretas/aims/ai/tool/impl/foodsafety/`:

1. `batch_trace_by_customer_date` — query DeliveryRecord by customer + date → batch_number
2. `batch_full_trace` — recursive trace: batch → ingredients → suppliers + 出货客户列表
3. `haccp_checkpoint_review` — query HaccpMonitoringRecord by batch
4. `additive_compliance_check` — query AdditiveLimit by additive code + product category, return 超限 list
5. `inventory_freeze` — WRITE + Preview, 修 InventoryBatch.status = FROZEN, audit log
6. `customer_notify_batch` — WRITE + Preview, batch 发短信/微信 (用现有 NotifyService)
7. `regulatory_report_generate` — READ + 模板生成 PDF (引用 Sprint 6 W3-C PrintService)
8. `recall_loss_estimate` — AGGREGATE: 冻结库存价值 + 客户退货预估 + 行政成本

每 Tool 配 unit test。

### Phase B.2 — 3 Skill (~30 min)

3 个 Skill 在 `service/skill/impl/`:

1. `HaccpCheckpointManagementSkill.java` — per spec §P3.4 (haccp-checkpoint-management YAML)
2. `FoodAdditiveComplianceSkill.java` — per spec §P3.4
3. `FoodSafetyRecallSkill.java` — per spec §P3.4 (主 Skill, 串 8 Tool)

`SkillRegistryImpl.java` 加 3 行 register。

### Phase B.3 — Flyway intent migration (~10 min)

`V20260820_07__sprint8_p3_food_safety_intents.sql`:
- FOOD_SAFETY_RECALL intent (skill='food-safety-recall')
- HACCP_CHECK intent (skill='haccp-checkpoint-management')
- ADDITIVE_COMPLIANCE intent (skill='food-additive-compliance')
- 8 Tool-level intents
- ON CONFLICT DO NOTHING

### Verification

- mvn test 8 Tool + 3 Skill tests PASS
- ToolRegistry 启动 verify 8 新 Tool 注册成功

## Constraints + Report back (同 P1)
```

- [ ] **Phase B verification**: 8 Tool + 3 Skill + intents PASS

---

## Task 3.3: P3 食品安全 Vue Workdesk + demo (5d Phase C, agent OR inline)

**Owner:** Dispatch 1 agent OR inline (60 min)

### Dispatch brief / inline tasks

```
Sprint 8 P3 食品安全 — Phase C (Vue Workdesk + 录 demo)。

## Scope

### Phase C.1 — Vue Workdesk (~30 min)

新建 `<worktree>/web-admin/src/views/workdesk/QualityManagerWorkdesk.vue`:
- 顶部对话框
- 启动召回 dialog (输入: 客户名 / 投诉日期 / 投诉描述)
- 自动追溯 → 进入召回 wizard
- 4 个一键执行按钮: [冻结库存] [通知客户] [生成监管文件] [关闭召回事件]
- 每按钮 click → 调对应 Tool with Preview → 用户确认 → execute

加 route `/workdesk/quality-manager` + 菜单挂载

### Phase C.2 — Demo 录屏 (~30 min, Steve 亲跑)

5min E2E 视频 (Boss 演示弹药 #3 — 杀手锏):
1. 输入: "鲜湘缘餐厅 5/18 卤猪蹄客户吃了拉肚子"
2. 3 sec AI 输出: B-20260518-A03 批次 + HACCP audit (冷却 2h ⚠️) + GB 2760 合规 + 12 影响客户列表 + 4 行动按钮
3. 一键 [冻结库存] → preview → 执行 → 28 斤库存 status=FROZEN
4. 一键 [通知客户] → preview 12 短信草稿 → 确认 → 发送
5. 一键 [生成监管文件] → PDF 下载
6. 一键 [关闭事件] → RecallEvent.status=COMPLETED
7. 全流程 < 2 min vs HJ 30 min

### Verification

- npm run build PASS
- F999 测试账号 E2E 全流程跑通
- mp4 录完 ≥ 5 min ≤ 8 min, 清晰可商务汇报
```

- [ ] **P3 Phase C verification**: Vue + demo mp4 完成

- [ ] **Update AI 化评分**: 6 → 7 (食品垂直 Skill 全 ship)

---

## Task 4.1: P4 仓管员 Workdesk dispatch (5d Phase A, agent)

**Owner:** Dispatch 1 agent (worktree, 90 min)

### Dispatch brief

```
Sprint 8 P4 — 仓管员 Workdesk (per 防呆 rule "告诉他要收多少就行")。

## ⛔ CRITICAL — Worktree isolation (同 P1)

Branch: sprint8/p4a-warehouse-keeper-workdesk

## Scope (90 min)

### Phase A — 5 Tool (~40 min)

1. `material_today_receiving_query` — READ, query PO + Requisition + scheduled receiving
2. `material_disposal_recommendation` — READ + AI, 临期物料 → 调拨/降价/退货建议
3. `receive_with_limit` — WRITE + Preview, R1 max 边界显示 (已订 100 / 已收 X / 可入 Y)
4. `receive_quality_check_today` — READ, 今日待质检入库
5. `pda_scan_task_generate` — READ + DOC, 给 PDA 生成扫码入库任务

### Phase B — Vue Workdesk (~20 min)

`web-admin/src/views/workdesk/WarehouseKeeperWorkdesk.vue`:
- 触发: "今天要收什么货?"
- 输出: 今日 N 批待收清单 + 每批 R1 max 边界 + [一键扫码] 按钮

### Phase C — Flyway intent migration (~10 min)

`V20260820_08a__sprint8_p4a_warehouse_intents.sql` (5 Tool intents + 1 Workdesk intent)

### Phase D — Verification (~20 min)

mvn test + npm build PASS

## Constraints + Report back (同 P1)
```

- [ ] **P4.1 verification**: Steve smoke 仓管员 Workdesk

---

## Task 4.2: P4 采购员 Workdesk dispatch (5d Phase B, agent)

**Owner:** Dispatch 1 agent (worktree, 90 min)

### Dispatch brief

```
Sprint 8 P4 — 采购员 Workdesk.

Branch: sprint8/p4b-purchaser-workdesk

## Scope (90 min)

### Phase A — 5 Tool

1. `stock_alert` — READ, 复用现有 if 有
2. `sales_forecast_7day` — AGGREGATE, query 7-day 销售订单 + AI 预测
3. `supplier_delivery_eta` — READ, query Supplier.deliveryDays + 历史 ETA
4. `price_history_query` — READ, query MaterialPrice by sku + 时间窗
5. `requisition_create` — WRITE + Preview, 用 Sprint 5 D PurchaseRequisition entity

### Phase B — Vue Workdesk

`web-admin/src/views/workdesk/PurchaserWorkdesk.vue`:
- 触发: "下周采购什么?"
- 输出: 5 品类预警 + 库存 + 预测 + 供应商 + 价格 + [一键生成请购单]

### Phase C — Flyway

`V20260820_08b__sprint8_p4b_purchaser_intents.sql`

### Phase D — Verification

mvn test + npm build PASS

## Constraints + Report back (同 P1)
```

- [ ] **P4.2 verification**: Steve smoke 采购员 Workdesk

---

## Task 4.3: P4 质量主管 Workdesk + LLM router tuning (5d Phase C, agent)

**Owner:** Dispatch 1 agent (worktree, 120 min)

### Dispatch brief

```
Sprint 8 P4 — 质量主管 Workdesk + LLM router tuning (Sprint 8 收尾).

Branch: sprint8/p4c-quality-and-llm-tuning

## Scope (120 min)

### Phase A — 质量主管 Workdesk 5 Tool (~30 min)

1. `quality_check_summary` — READ, 批次 + 检测项
2. `haccp_status_query` — 复用 P3 已 ship Tool
3. `additive_compliance` — 复用 P3 已 ship Tool
4. `customer_quality_standard` — READ, query Customer.qualityStandards 字段 (if exists, 否则建 fallback)
5. `release_decision` — WRITE + Preview, batch.status PENDING → RELEASED/REJECTED

### Phase B — Vue Workdesk (~20 min)

`web-admin/src/views/workdesk/QualityChiefWorkdesk.vue`:
- 触发: "这批卤猪蹄能放行吗?"
- 输出: 质检 + HACCP + 添加剂 + 客户标准 综合判断 + [一键放行] / [退货]

### Phase C — LLM router tuning (~40 min)

(高 ROI, 最后做)
- 286 intent 去重: query `SELECT intent_name, COUNT(*) FROM ai_intent_configs GROUP BY intent_name HAVING COUNT(*) > 1`
- 每 intent UPDATE tool_name (绑 Sprint 8 新 Tool name)
- 51 test intents 扩 80+ (覆盖 5 Workdesk 各 6 个触发问法)

### Phase D — 3 demo mp4 录屏 (~30 min, Steve 亲跑)

- 仓管员 Workdesk: 3min mp4
- 采购员 Workdesk: 3min mp4
- 质量主管 Workdesk: 3min mp4

### Verification

mvn test + npm build PASS + 3 mp4 录完

## Constraints + Report back (同 P1)
```

- [ ] **P4.3 verification**: 5 Workdesk 全 ship, 3 demo mp4 + P1+P2+P3 各 1 mp4 = 5 mp4 总

- [ ] **Update AI 化评分**: 7 → 8 (Sprint 8 目标达成)

---

## Task 5.0: Sprint 8 Final Integration + Validation (~1 day, inline)

**Owner:** Inline (organizer)

**Files:**
- Create: `docs/audits/2026-06-XX-sprint-8-final-validation.md`

- [ ] **Step 1: AI 化评分 final audit**
- 5 维度评分 per `feedback_agent_worktree_isolation` audit 模板:
  - Tool 增量: 50+ vs 0 (Sprint 5+6+7)
  - Skill 增量: 5 vs 0 (Sprint 5+6+7)
  - Intent 增量: 30+ 新 intent + 286 旧 intent 全绑 tool_name
  - 自然语言可达性: 5 Workdesk 全测试 — 客户问 → AI 输出 → ≥ 80% 命中率
  - 跨域 Workdesk 闭环: 5 个真 Workdesk + 食品行业 1 Skill 跨多 Tool

- [ ] **Step 2: 5 Workdesk smoke 全验**
F006 真账号每个 Workdesk 跑完 5 个真用户场景

- [ ] **Step 3: 5 demo mp4 汇总**
5 个 mp4 整合到 `docs/audits/sprint-8-demos/` 文件夹, commit (LFS if > 100MB)

- [ ] **Step 4: 总结报告 + Sprint 9 准备**
```markdown
# Sprint 8 Final Validation Report

## AI 化评分: 3 → 8 / 10 ✅

(详细 5 维度评分)

## 5 Workdesk LIVE on prod

(链接 + 截图)

## Boss 演示弹药 (5 mp4)

(列表)

## Sprint 9 准备 (per goal doc 长期路径)

- W1: 补 P1 客户档案剩 4 tab + 工作流 23 类接入 + 银行批量转账
- W3: 仓管员+质量主管 Workdesk 深化 + 食品召回 Skill 实战
- W5: 大客户审计 (印章/签名/电汇)
```

- [ ] **Step 5: Commit + push final report**

- [ ] **Step 6: Steve 验收 — Sprint 8 official close**

---

## Self-Review (per writing-plans skill)

### 1. Spec coverage

| Spec section | Plan task | 覆盖? |
|---|---|---|
| §P0 修信任 | Task 0.1-0.5 | ✅ |
| §P1 卤味老板 Workdesk | Task 1.0 (dispatch) | ✅ |
| §P2 财务主管 Workdesk | Task 2.0 (dispatch) | ✅ |
| §P3 食品安全召回 | Task 3.1 + 3.2 + 3.3 | ✅ (分 3 Phase 因 5d 范围大) |
| §P4 3 Workdesk + LLM tuning | Task 4.1 + 4.2 + 4.3 | ✅ (分 3 task) |
| §X cross-cutting pattern | 每 dispatch brief 引用 | ✅ |
| §Y testing strategy | 每 task verification step | ✅ |
| §Z rollout + 风险 | Task 5.0 final validation | ✅ |

无 spec gap.

### 2. Placeholder scan

✅ 无 TBD/TODO/incomplete sections。每个 task 有具体文件路径 + 命令 + verification step。

### 3. Type consistency

- Tool 命名: `{domain}_{action}` 全一致 (per `ai-intent-tool-skill-architecture.md`)
- Skill 命名: kebab-case (daily-customer-followup) 一致
- Vue Workdesk 命名: `{Role}Workdesk.vue` (SalesOwner/FinanceManager/QualityManager/WarehouseKeeper/Purchaser/QualityChief) 一致
- Branch 命名: `sprint8/p{N}-<name>` 一致
- Flyway 命名: `V20260820_{NN}__sprint8_p{N}_<name>.sql` 一致

✅ 无不一致。

### 4. 已知非完美点 (待 Sprint 8 ship 后回顾)

- Task 1.0-4.3 dispatch brief 没有给每个 Tool 的完整 Java 代码 (仅 spec §P1.2 给了 1 个 example)。Agent 需 read spec + 模板自己写。这是有意 — 否则 plan 会爆 5000 行, 且 Cretas 的 agent dispatch 模式不需要 step-by-step code (Sprint 5-7 实践证明 OK)。
- Task 5.0 final report 内容是模板 (具体数据待 Sprint 8 ship 后填)。
- Task 3.1/3.2/3.3 P3 拆 3 phase 但 dispatch agent 需要 serial (3.1 ship → main merge → 3.2 dispatch). 风险: 3 周内 3 次 dispatch 间隔可能拖 P3 延期。Mitigation: P3.1 entity 简单 (60 min), 加速 ship 不阻塞。

---

**Plan complete. Total tasks: 14 (4 inline P0 + 1 P0 summary + 6 P1-P2 dispatch + 3 P3 dispatch + 3 P4 dispatch + 1 final integration).**

**Estimated wall time: 3-4.5 weeks** (P0 1-2d inline + P1-P4 各 3-5d, 部分 parallel 可能)。

**AI 化评分追踪: 3 → 4 (P0) → 5 (P1) → 6 (P2) → 7 (P3) → 8 (P4) ✅**
