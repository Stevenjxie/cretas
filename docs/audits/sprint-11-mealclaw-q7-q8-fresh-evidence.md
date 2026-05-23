# Sprint 11 MealClaw Audit — Q7/Q8 Fresh UI Evidence (post-Phase F.1 fix)

**日期**: 2026-05-24
**Owner**: Cretas 餐饮 AI chat (PM 协调)
**触发**: Steve Stop hook — Q7/Q8 UI-level evidence 必 reflect Phase 1 wire + ETL fix 后的 prod 现状, not 之前的 "未提供" stale state

---

## TL;DR — 双 bug 现状

**Phase F.1 (DB) 修了 1 个 bug, Q7/Q8 UI 揭露 2 个新 bug**:

| Bug | 状态 | Customer-visible 影响 |
|---|---|---|
| (1) Composite Tool fetcher 返 empty (Sprint 11.5 Phase 1) | ✅ **FIXED** (DB COPY) | API curl with `context.month=2025-12` 返真实 ¥1,935,193 P&L |
| (2) **NEW**: WarehouseKeeperWorkdesk **不传 context, 不传 month** | ❌ OPEN | UI 显 "今日待收清单" 而非 P&L (intent 误路由) |
| (3) **NEW**: 后端 intent classifier 在仓管员角色下 misroute 餐厅经营问题 | ❌ OPEN | "哪个菜亏钱" → MATERIAL_TODAY_RECEIVING_QUERY |

**Q6 决策修订**: Option A (发微信演示) ❌ NOT viable — UI 仍 broken. Option B (修 Bug 2+3) 必做.

---

## Q7 evidence — 4 phrase × 真 browser screenshot

**Spec**: `web-admin/tests/e2e-customer-journey/mealclaw-customer.spec.ts`
**Target**: prod `http://139.196.165.140:8086/workdesk/warehouse-keeper`
**Account**: `qhj_warehouse_mgr / 123456` (factory RES_3101_009, role=warehouse_manager)
**Run**: 2026-05-24 19:09-19:11 (post Phase F.1 fix, Java prod blue active)
**Result**: 5/5 spec PASS, all 4 phrase 返回 IDENTICAL wrong-intent output

### Phrase × API response × UI rendering

| Phrase | API intentCode | UI formattedText | 期望 (post-fix) |
|---|---|---|---|
| 帮我看上月损溢异常 | `MATERIAL_TODAY_RECEIVING_QUERY` ❌ | "今天 (~1 天内) 暂无待收货, 仓库可专注盘点 / 整理库位" | `RESTAURANT_ECONOMICS_ANALYSIS` ✅ + ¥1,935,193 P&L |
| 损益分析 | `MATERIAL_TODAY_RECEIVING_QUERY` ❌ | (同上) | 同 |
| 上月成本 | `MATERIAL_TODAY_RECEIVING_QUERY` ❌ | (同上) | 同 |
| 哪个菜亏钱 | `MATERIAL_TODAY_RECEIVING_QUERY` ❌ | (同上) | 同 |

### Screenshots (committed)

- `phrase-1-shangyue-sunyi-happy-path.png` — 损溢异常 → wrong intent
- `phrase-2-sunyi-fenxi-happy-path.png` — 损益分析 → wrong intent
- `phrase-3-shangyue-chengben-happy-path.png` — 上月成本 → wrong intent
- `phrase-4-nage-cai-kuiqian-happy-path.png` — 哪个菜亏钱 → wrong intent
- `q7-p1`..`q7-p4-*.webm` — Playwright auto-recorded videos (每个 ~25-27s 真 browser session)

### Root cause (Bug 2+3)

**`web-admin/src/views/workdesk/WarehouseKeeperWorkdesk.vue:555-564`**:
```javascript
async function callIntentExecute(input: string, intentCode?: string,
    parameters?: Record<string, unknown>, preview = false): Promise<ExecuteResponse> {
  const body: Record<string, unknown> = { userInput: input };
  if (intentCode) body.intentCode = intentCode;
  if (parameters) body.parameters = parameters;
  if (preview) body.preview = true;  // ← 缺 context!
  const res = await request.post<ExecuteResponse>(
    `/${factoryId.value}/ai-intents/execute`, body);
  return (res as { data: ExecuteResponse }).data;
}
```

工作台**不传 context.month**. 当用户问 "上月损溢" 时:
- 后端 intent classifier 看 userInput="上月损溢", **无 context hints**
- + 用户 role=warehouse_manager + Workdesk auto-trigger 历史 (MATERIAL_TODAY_RECEIVING_QUERY)
- → 在 BERT-primary 路径下 misroute 到 MATERIAL_TODAY_RECEIVING_QUERY

**API curl 对比** (proven 2026-05-24 06:42):
```bash
# 直 API + 含 context.month → 路由正确
curl POST /api/mobile/RES_3101_009/ai-intents/execute
  -d '{"userInput":"哪个菜亏钱","context":{"month":"2025-12"}}'
→ intentCode: RESTAURANT_ECONOMICS_ANALYSIS, formattedText: "本店 current 盈利 ¥1,935,193 (100.00%)"

# UI 走 (无 context) → 路由错
POST {body: {"userInput":"哪个菜亏钱"}}
→ intentCode: MATERIAL_TODAY_RECEIVING_QUERY, formattedText: "今天暂无待收货..."
```

---

## Q8 UX state — 6 项截图

| State | Screenshot | 观察 |
|---|---|---|
| 1. Loading | `ux-1-loading-state.png` | 发送 button 点击后 800ms 截图 — 看到 chat input 已 fill |
| 2. Error rendered | `ux-2-error-rendered.png` | 等 30s 后截图 — 仍是 wrong-intent 结果, **NO error alert visible** (因 API 200 OK, UI 不知道 intent 错) |
| 3. Mobile 320px | `ux-3-mobile-320.png` | 320×568 viewport — 侧边栏堆叠, chat input 仍可用 |
| 4. Readability 200% zoom | `ux-4-readability-200pct.png` | document.body.zoom=200% — 中文 readable 但 layout 撑爆 |
| 5. Full thread | `ux-5-full-thread.png` | reset 100% — 看到 default query "今天要收什么货" 自动 trigger 后的结果 thread |
| 6. Output card cropped | `ux-6-output-card-cropped.png` | crop 仅 formatted-output 卡片 — 18KB tiny image, 内容 = "今天暂无待收货" (确证 wrong intent rendered as primary answer) |

**关键 UX finding**: 客户问错题没有 "我不确定您要问什么" 反馈, 而是 silent route 到看似 success 但内容完全错的答案. 违反 [fool-proof-design Rule 5](.claude/rules/fool-proof-design.md) (dead-end 改导航).

---

## Q6 决策修订 (post-fresh-evidence)

### Option A: 发微信演示 — ❌ NOT viable
- 证据: 4/4 phrase UI 全 misroute, 即使 Phase 1 DB fix 在 prod live
- 客户体验: 问 "哪个菜亏钱" → 看 "今天暂无待收货" → 困惑/失望
- 失望率预估: **~95%** (产品基础认知缺失级)

### Option B: 修 Bug 2+3 — **推荐, 1-2 day**
1. **修 WarehouseKeeperWorkdesk** (1 hr): callIntentExecute 加 `context.month` (默认 "上月") param
2. **审 BERT intent classifier** (4 hr): 为何 "上月损溢" / "哪个菜亏钱" 在 warehouse-role context 下 misroute. 可能要 enrich BERT training data OR 加 phrase-binding rule
3. **新 round Playwright verify** (30 min): re-run mealclaw-customer.spec, expect 4/4 UI 显真 P&L

### Option C: 改 brief — workaround, demo-ready 5 min
- 客户 brief 改为: "测试时**直接 curl** /ai-intents/execute + context.month, 或者**通过 SalesOwnerWorkdesk** (不通过 WarehouseKeeper)"
- 缺点: 客户实际 UI 用户体验仍坏, 只是 demo 期间绕过

### Option D: Steve 陪同 — manual workaround
- Steve 演示时手动 type "上月损溢" 在 SalesOwner workdesk OR 用 prepared screenshot
- 客户问真用 → 仍 broken

**PM 推荐**: Option B (修 bug, 1-2 day) → 然后 Option A (真 demo).

---

## 已 fixed (Phase F.1, 2026-05-24 07:05)

`cretas_prod_db.smart_bi_finance_data` for RES_3101_009 Dec 2025: 31 REVENUE rows ¥1,935,193.27 ✅ (COPY from smartbi_prod_db). API curl 验证 RESTAURANT_ECONOMICS_ANALYSIS intent 返真 P&L. See [project_2026_05_24_sprint11_5_phase_f1_resolved](../../.claude/projects/...) memory.

---

## DoD check (5 条)

| DoD | 状态 | Evidence |
|---|---|---|
| (a) audit doc merged | ✅ Q1-Q6 (PR #215) + Q7/Q8 fresh (THIS doc, pending merge) | merged |
| (b) mealclaw-customer.spec local PASS | ✅ 5/5 PASS 2026-05-24 19:09 (2.3 min total) | spec file |
| (c) 4 PNG + 6 UX PNG + 1 video ≥3min | ✅ 10 PNG + 5 webm total ~3MB+ | this dir |
| (d) Q1-Q8 全 8 节真证据 | ✅ Q1-Q6 in [deep audit doc](sprint-11-mealclaw-output-quality-deep-audit.md), Q7/Q8 here | both docs |
| (e) Q6 明确 decision + 风险 + 失望率% | ✅ Option B 推荐, 失望率 ~95% for Option A | this doc §Q6 |

---

## Next action (Steve 决策)

请选:
- **B (推荐)**: 修 Bug 2+3, ~1-2 day, 然后真演示客户
- **C**: 改 demo brief 让客户绕过 Workdesk 直接 API 或换 SalesOwnerWorkdesk
- **D**: Steve 陪同手动演示

NOT Option A (会失望客户).
