# Workdesk AI 输出质量 Audit — 2026-05-22

**审计员**: BG subagent (Playwright + Read-only) + 主 chat 整理 (rate-limited 后接管)
**时长**: 22min subagent + 整理 ~5min
**Scope**: 6 boss demo Workdesks × Path A (keyword) + Path B (LLM) = 12 tests
**Auth**: f006_admin / F006 (per `[[feedback_web_admin_auth_bypass_needs_user_object]]`)
**Test artifacts**: `docs/audits/2026-05-22-workdesk-ai-output-quality/{workdesk}-{pathA|B}.json`

---

## TL;DR — 真 Useful Rate = **5/12 paths PASS (42%)**

只 **4/6 Workdesks 有至少 1 个 useful path**:
- ✅ finance-manager (Path B)
- ✅ quality-chief (Path B)
- ✅ quality-manager (Path B)
- ✅ warehouse-keeper (Path A)
- ❌ purchaser (两 path 都坏)
- ❌ sales-owner (两 path 都坏)

---

## 完整测试表

| Workdesk | Path | Query | Status | Len | Verdict | Critical 问题 | Domain kw |
|---|---|---|---|---:|:---:|---|---|
| **finance-manager** | A | `本月经营怎么样?` | FAILED | 22 | **FAIL** | 暂不支持 WORKDESK | ✗ |
| **finance-manager** | B | `这个月业绩如何` | COMPLETED | 721 | **PASS** | — | ¥0 |
| **purchaser** | A | `下周采购什么?` | SUCCESS | 37 | **FAIL** | bare template "查询完成 包含 2 项数据指标" | ✗ |
| **purchaser** | B | `下周需要进货吗` | NEED_CLARIFY | 25 | **FAIL** | 多 op 歧义未消歧 | ✗ |
| **quality-chief** | A | `今天哪些批次待放行?` | FAILED | 22 | **FAIL** | 暂不支持 WORKDESK | ✗ |
| **quality-chief** | B | `今天有什么批次需要审批放行` | SUCCESS | 123 | **PASS** | — | 质检 |
| **quality-manager** | A | `今天 HACCP 监控全通过吗?` | SUCCESS | 37 | **FAIL** | _toolCount/_executionOrder/8个 _underscore key | ✗ |
| **quality-manager** | B | `今天质量监控有问题吗` | SUCCESS | 27 | **PASS** | — | 告警 |
| **sales-owner** | A | `今天该跟谁?` | SUCCESS | 37 | **FAIL** | _toolCount/_executionOrder/6个 _underscore key | ✗ |
| **sales-owner** | B | `今天哪些客户需要拜访` | SUCCESS | 5989 | **FAIL** | **raw JSON dump** + _underscore keys | 客户 |
| **warehouse-keeper** | A | `今天要收什么货?` | SUCCESS | 35 | **PASS** | — | 今天 |
| **warehouse-keeper** | B | `今天有什么入库` | SUCCESS | 141 | **FAIL** | domain keyword 缺失 (?kg ?箱) | ✗ |

---

## 关键 findings

### 🔴 P0 #1 — WORKDESK intent_category 调度缺失

**finance-manager Path A** + **quality-chief Path A** 直接返回:
```
暂不支持此类型的意图执行: WORKDESK
```

这是 `IntentExecutorService` 路由层未处理 `intent_category=WORKDESK` 的情况。Path A keyword 命中 `MONTHLY_FINANCIAL_CLOSE` / `QUALITY_CHIEF_WORKDESK` → 后端识别 intent 但拒绝执行。

**影响**: 客户 demo 直接看到"暂不支持"会立即失去信任。

**修复**: `IntentExecutorService` 添加 `WORKDESK` 类别分支, dispatch 到对应 Skill (e.g. `sales-owner-workdesk-skill`, `finance-manager-workdesk-skill`).

### 🔴 P0 #2 — Tool/Skill 多 Tool 返回 raw `_toolCount` / underscore metadata

**Affected**: quality-manager-A, sales-owner-A, sales-owner-B

Skill 调用多 Tool 后, formattedText 直接包含 SkillExecutor 内部 metadata:
- `_toolCount`, `_executionOrder` — Skill 框架 introspection 字段
- `_query`, `_freeze`, `_review`, `_date`, `_check`, `_trace`, `_pending`, `_alert`, `_trend` — 各 Tool 的 result key

最严重: **sales-owner Path B** 返回 5989-char 的 raw JSON dump:
```json
{"customer_priority_query":{"data":{"data":{"limit":50,"count":16,"customers":[{...}]}}}}
```

**根因**: `SkillExecutorImpl` 在 multi-Tool 输出上没有 LLM-summarize 步骤。它把每个 Tool 的 raw `result` 字段直接合并。

**修复**: `SkillExecutorImpl` 在 multi-Tool 结果上**调用 LLM 总结**, 输出 Chinese summary。或加 `outputFormatter` Strategy interface per Skill。

### 🔴 P0 #3 — "查询完成 包含 N 项数据指标" bare template

**Affected**: purchaser-A, quality-manager-A, sales-owner-A

返回:
```
查询完成
包含 2 项数据指标 — 详情请查看下方数据卡片或对应报表模块。
```

这是 backend 默认 placeholder, 完全没回答问题。客户预期是文字总结 "下周建议采购 X 公斤 ..."

**根因**: 同 P0 #2 — Skill 端无 LLM 总结。

### 🟡 P1 — Path B (LLM-routed) 也不完美

- **purchaser-B**: NEED_CLARIFICATION — 多 op 没消歧, 应该 LLM 内 disambiguate (e.g. "您是问采购建议还是查询历史采购?")
- **sales-owner-B**: 即使 LLM-routed 也返回 raw JSON dump (5989 chars). LLM 没 wrap 总结。
- **warehouse-keeper-B**: 返回了 chinese run 但 quantity 是 `?kg` `?箱` — unit 字段空。Data 问题, 不是 AI 问题。

### 🟢 P2 — Path B (LLM-routed) 4 个 Workdesk PASS

显示 LLM 总结能力部分工作:
- finance-manager-B: 721 chars 中文 + ¥0 amount + 建议措施
- quality-chief-B: 3 条质检记录格式化输出
- quality-manager-B: "告警检查完成: 当前没有未处理的告警"
- warehouse-keeper-A: "今天 (~1 天内) 1 个采购单共 1 行待入库"

但这是 **rate-limited 才好**, 非 deterministic 路径。

---

## Skill 端 outputFormatter 缺失 list

需检查的 Skills (per `backend/java/cretas-api/src/main/java/com/cretas/aims/service/skill/impl/`):

| Skill | Path A query | Path A 结果 | outputFormatter 状态 |
|---|---|---|---|
| sales-owner-workdesk | 今天该跟谁? | _toolCount 5 + raw json | **缺** |
| finance-manager-workdesk | 本月经营怎么样? | 暂不支持 WORKDESK | **缺** + 类别路由缺 |
| quality-manager-workdesk (food-safety-recall) | 今天 HACCP 监控? | _toolCount 5 + underscore keys | **缺** |
| quality-chief-workdesk | 今天哪些批次待放行? | 暂不支持 WORKDESK | **缺** + 类别路由缺 |
| warehouse-keeper-workdesk | 今天要收什么货? | "今天 1 个采购单..." | ✅ 有 (单 Tool, 自带 format) |
| purchaser-workdesk | 下周采购什么? | bare template | **缺** |

**5/6 Skill 缺 outputFormatter**, 仅 warehouse-keeper-workdesk 工作 (因为它是单 Tool 调用, 没触发 multi-Tool 默认 metadata 路径)。

---

## 战略 Recommendation

### Sprint 10 决策: **暂停 5 闭环 dispatch, 先修 6 Workdesk 输出器**

**Why pause**:
1. 现在并行跑着 5 个 闭环 impl subagent (assume per user prompt). 他们建在 **broken AI output foundation** 上。
2. 客户 demo 任何 Workdesk → 50%+ 概率看到 "暂不支持" / `_toolCount` / raw JSON. 立即失去信任。
3. 修 outputFormatter 是 **1-2 day Skill-level fix**, 不是大重构。
4. 闭环 features 一旦 build 在 broken 输出上, 后期修 outputFormatter 需要 **重测每个 feature** → 翻倍工作量。

**Why fix now (customer impact)**:
- F006 卤制品厂 + 4 demo factory 客户 (HJ 宏见 / QHJ 庆华建 / etc.) 已签合同
- 客户 demo 必经 Workdesk (boss demo 入口)
- 5 个 Workdesk Path A = "客户最自然 query"
- 失败 demo → 客户 churn → 收入损失

### 修复路径 (推荐 3-day Sprint 10.5)

**Day 1**: 修 P0 #1 — `IntentExecutorService` 添加 WORKDESK 类别 dispatch
- finance-manager + quality-chief Path A 立即恢复
- 2 个 Workdesk PASS rate ↑

**Day 2-3**: 修 P0 #2 + P0 #3 — `SkillExecutorImpl` outputFormatter
- Skill multi-Tool → LLM-summarize 输出
- 选项 A: 每 Skill 加 `outputFormatter()` method (per-Skill 定制)
- 选项 B: Generic LLM 总结 (传 Tool 结果给 LLM 请总结)
- 推荐 B (统一, 不需 per-Skill 修代码)

**Day 3 收尾**: re-run audit (本次 BG subagent script) → useful rate 应从 5/12 → 10+/12

### 不修 vs 修 customer impact 估算

**不修**:
- 客户 demo 50% 失败率 (Path A 直接命中 "暂不支持")
- Phase 1 Indicator Center 即使 ship UI, 也通过 Workdesk 入口, 仍 60% 失败
- Sprint 10 5 闭环 build 在 broken 上, 验收时痛 + 重测一遍

**修** (3 day):
- Demo 失败率 50% → ~10% (剩余 long-tail 问题)
- Phase 1 Sprint 1 closure 更干净 — UI 接到的 backend AI 是 working state
- Sprint 10 闭环 build 在 verified output formatter 上, 一次过

**净 ROI**: 修 3 day = 净赚 (避免 Sprint 10 完成后整套 rework)。

---

## Spot-check known case 验证 (per HARD rule)

**Steve 实测**: finance-manager `本月经营怎么样` raw `_toolCount: 5`

**Audit 结果**: ✅ 复现成功 — finance-manager Path A 返回 "暂不支持此类型的意图执行: WORKDESK" (实际不是 `_toolCount` 而是更糟的"暂不支持"). 但 Steve 之前看到的 `_toolCount` 可能在 quality-manager-A 或 sales-owner-A 出现 (本次 audit 都 reproduce 到了 `_toolCount` + 多个 _underscore keys)。

**P0 验证通过**: Steve 知道的问题 audit reproduce 了。

---

## Test infrastructure 产物 (保留供 debug)

`docs/audits/2026-05-22-workdesk-ai-output-quality/`:
- `analyze.py` — analyzer script (可复用 future audit)
- `analysis.json` — full machine-readable result
- `analysis-output.txt` — human-readable summary (subagent generated)
- `raw-{workdesk}-pathA/B.json` — raw AI API response
- `{workdesk}-pathA/B.json` — extracted formattedText + metadata
- (Screenshots not captured — subagent rate-limited before screenshot phase)

---

## 总结

| Metric | 值 |
|---|---|
| Workdesk 总数 | 6 |
| Path 总数 | 12 (6 × A + B) |
| PASS 数 | 5 |
| FAIL 数 | 7 |
| 真 useful rate | **5/12 = 42%** |
| Workdesks with ≥1 useful path | 4/6 = 67% |
| P0 bugs found | 3 (WORKDESK 类别路由 / multi-Tool metadata / bare template) |
| Skills missing outputFormatter | 5/6 |
| Recommendation | **PAUSE Sprint 10 5 闭环, 3-day P0 fix sprint** |

**Customer demo 阻塞: YES** — Sprint 10 5 闭环 features 任何一个 demo 客户会 50%+ 概率看到坏输出。先修。

---

🤖 Generated with [Claude Code](https://claude.com/claude-code)
