# Sprint 11 BI Chat — Session Close

**日期**: 2026-05-23
**Chat**: BI chat (worktree `my-prototype-logistics-sprint11-d5`, branch `bi/tree-stats-honest-labels`)
**Close authority**: Organizer 2026-05-23 明示 — 40-50% demo-ready 收货 + Sprint 12 ownership 转给 sister AI 工厂 chat
**Status**: Sprint 11 BI scope 关闭. 无后续 Sprint 12 BI work in this chat.

---

## 1. Sprint 11 BI 完整时间线

| Phase | PR | Commit | What |
|---|---|---|---|
| D1 — SSH Layer 1 验证 + 数据源决定 | — | — | 47 ECS layer 1 ssh verified; F999_MOCK 选定为 mock factory id |
| D2 — Layer 4 mock generator | (in #192) | — | `scripts/mock/generate-f006-indicator-data.py` 落 210 indicator_versions snapshots in F999_MOCK |
| D3 — IndicatorQueryTool | (in #192) | — | Tool + 单测 + V20260823_04 intent registration |
| D4 — LineageQueryTool + IndicatorComparisonTool | (in #192) | — | 2 Tools + 单测 |
| D5 — IndicatorAlertTool + Alert hook | (in #192) | — | Tool + IndicatorRecomputeScheduler hook |
| D6 — smart-indicator-query Skill + UI v1 | #192 | `114c09522` | 4 Tools + Skill + IndicatorCenterDashboard v1 mock banner |
| D7-D8 — Monitor PR #192 CI + ship | #199 / #200 | — | Cherry-pick chain hotfixes for sister CI breakage |
| D9 Round 1 — UI 404 BUG fix | #155-ish | — | IndicatorController wire (collision with sister #205) |
| D9 Round 2 P1 — INDICATOR_ALERT priority bump | (V_23_12) | — | priority/keyword strength (collision with sister #203/#209) |
| BI P4 — Playwright prod 8086 prove | #217 | `1c16e9003` | `docs/audits/sprint-11-bi-prod-live.md` + 12 PNG screenshots |
| BI Deep Audit | #228 | `46646c44e` | `docs/audits/sprint-11-bi-deep-audit.md` — 3 findings (F1/F2/F3) |
| BI Finding-2 fix — mirror banner | #231 | `8429f0521` | small-print banner (later replaced by 4-B big-font) |
| BI Finding-3 fix — tree honest labels | #232 | `0169c0bbf` | "正常/关注/告警" → "已计算/待配置" |
| **BI 4-B band-aid** ⭐ | **#234** | **`5f3467ca2`** | **B2BRealDataSection.vue + filter 7 mirror + 大字 banner + Sprint 12 backlog 158 行** |

---

## 2. 撞车事故复盘 (sister chat collisions)

Sprint 11 中 BI chat 跟 sister AI 工厂 chat 撞车 **6 次**, 每次 sister 的 ship 让我的 cherry-pick 变 redundant:

| Collision # | My commit (废) | Sister PR (live) | Reason |
|---|---|---|---|
| 1 | cherry-pick #154 IndicatorQueryService | sister #205 `fb02e3d36` | Sister ship 同 service code; 我的 cherry-pick 变 dup |
| 2 | IndicatorThresholdRepository @Query | sister #208 `0268e984d` | Sister ship 同 repo method |
| 3 | V20260823_12 priority bump | sister #203 `19c2adf59` | Sister ship same migration |
| 4 | V_23_12 jsonb cast hotfix | sister #209 `8935284cb` | Sister ship migration sequencing fix |
| 5 | Round 3 priority+keyword bump | sister #204 `8b70f268c` | Sister ship 同 intent registration |
| 6 | IndicatorCard auth fix | sister #212 `000274146` | Sister ship 同 web-admin component |

**Root cause**: 我没在每个 PR 前跑 `gh pr list --state open --search indicator`. Sister chat 在并行 ship sprint 11 BI 主线 (Tool wire + Service + Repo + Migrations + UI), 我的 worktree 上下文 stale → 重复劳动 + 风险 sister ship 被我覆盖。

**Solution applied (本次 BI 4-B)**: 4-B band-aid 选定 **纯前端 compute** 路径, 完全跳过 backend 改动 + 跳过 indicator framework — 在 sister scope 边缘 work (web-admin static), 0 collision risk.

**Lesson** (saved to memory `feedback_anti_goal_short_term_vs_long_term.md`): Anti-goal "不准 backend 改动" 是 collision-avoidance short-term discipline, NOT long-term principle. 当 sister chat 在 active sprint shipping 主线时, 防撞 > 完整性。

---

## 3. 4-B band-aid 是临时方案, Sprint 12 必须 backend rewrite

**4-B 临时方案的 4 问题** (per `docs/sprint-12-backlog/indicator-service-rewrite.md`):

1. 7-code filter hardcoded in Vue → 新 indicator / 改名都漏
2. 9 个 null cards "未计算" → 老板看到 "—" 不 actionable (40-50% gap)
3. B2B size=200 limit → 客户 ≥200 单时 stats 不准
4. Indicator framework 分裂 truth → 3 个 data source (V_23_11 mirror DB + IndicatorComputation null + 前端 SQL-via-API), maintenance overhead 高

**Sprint 12 backend rewrite 必须** 删 V_23_11 + 加 7 个 F006 工厂业态 indicator (B2B_AVG_ORDER_VALUE / FACTORY_INVENTORY_VALUE / FACTORY_QUALITY_REJECT_RATE 等) + IndicatorComputationStrategy 框架。3-5 工作日 estimate。

---

## 4. 诚实学到的 (memory HARD rules)

### Rule (NEW HARD): Banner ≠ Fix

PR #231 small-print banner "示例数据警告" 不算 Finding-2 fix — 老板看 ¥37.39 客单价 cards 时, 小字 banner 在视线外, 仍误判 F006 业务规模为 ¥37 客单价的餐厅级。**Fix = remove false info OR provide true info OR force-block.** 4-B 做了 后两个 (B2B 真业务 cards 显示真数 + filter hide mirror).

### Rule (NEW HARD): 90% claim 错 — cross-verify required

我之前 claim "老板能用度 90%" 是 self-evidence, 没跟 AI 工厂 chat retro PR #220 cross-check (sister Item 1 BLOCKER: F006 100% mirror)。Cross-verify failure 立即 disqualify 收货。诚实评分 40-50% (3 真 cards + 9 待 Sprint 12 backend + 大字 banner) 而非 90%。Sprint N close 前 MUST cross-verify with sister retro / audit doc, 不接受 self-graded "evidence".

### Rule (NEW HARD): Worktree collision in active sprint — work at edge

Sprint 11 撞 sister 6 次 = high cost. 当 sister chat 在 active ship 时, 我应该:
- 在 sister scope 边缘 work (web-admin static / docs)
- 跑 `gh pr list --state open --search <keyword>` 每次 PR 前
- worktree 物理隔离 + 单独 branch
- 不动 sister "中心文件" (Service / Repo / Migration / shared intent config)

---

## 5. Ping AI 工厂 chat — Sprint 12 ownership handoff

> **TO**: Sister AI 工厂 chat (the one running Sprint 12 NL routing 主线)
>
> **FROM**: BI chat (sprint11-d5 worktree)
>
> **RE**: Sprint 12 indicator-service-rewrite ownership 转给你
>
> Per organizer 2026-05-23 决断, Sprint 12 BI backend rewrite 转 sister AI 工厂 chat handoff:
>
> **Spec doc**: `docs/sprint-12-backlog/indicator-service-rewrite.md` — Phase A-D, 158 行, 3-5d estimate, 含 V_24_01 migration + 7 F006 工厂业态 indicators + IndicatorComputationStrategy framework + 撤前端 compute + 撤 filter
>
> **前置阅读** (按顺序):
> 1. `web-admin/src/views/indicator-center/B2BRealDataSection.vue` — Sprint 11 临时方案前端 compute, Sprint 12 Phase D 要删
> 2. `backend/java/cretas-api/src/main/resources/db/flyway/V20260822_*__*.sql` 看 V_23_11 mirror migration full content (delete target)
> 3. `sales_orders` / `production_batches` / `quality_inspections` 真业务表 schema (sister Goal v5 audit 已查过 5 行/不足情况)
> 4. `docs/audits/sprint-11-bi-4b-real-data-fix.md` — 本 Sprint 11 4-B 落地 evidence + cross-verify failure
>
> **建议 sprint 内调度**:
> - Sprint 12 NL routing 修复 = 主线 (你已 own)
> - Indicator backend rewrite = 主线后做, worktree 隔离 (per `concurrent-edit-safety.md` Rule 2)
> - 不要并行 — V_24_01 migration 跟 sister chat 的 SmartIndicatorQuery intent SQL 不在同 file 但 Flyway slot 紧, 序列化执行更安全
>
> **DOD verification suggestion**: cross-verify SQL vs Tool ≤1% (per sister #220 Item 1 BLOCKER pattern). 写 audit doc 含 SQL + Tool output + diff.
>
> 谢谢. Sprint 11 BI chat close session, no follow-up here.

---

## 6. BI chat next state

- ✅ Sprint 11 BI scope 关闭. 4-B band-aid ship to prod 8086.
- ✅ Sprint 12 ownership 转 sister AI 工厂 chat (per `docs/sprint-12-backlog/indicator-service-rewrite.md`)
- ✅ Session close. 不接 Sprint 12 backend (concurrent-edit-safety, sister chat 已 own 主线)
- ⏸ Worktree `my-prototype-logistics-sprint11-d5` 保留直到 sister merge V_24_01 后, 由 Steve 手动 cleanup

**Sprint 11 BI 总分**: 40-50% demo-ready (3 真 B2B cards + filter mirror + 大字 banner + Sprint 12 backlog). 不到 80-90% 因 9 个 null indicators 等 Sprint 12 backend 真接.

**Co-Authored-By**: BI chat (Claude Opus 4.7 1M context)
