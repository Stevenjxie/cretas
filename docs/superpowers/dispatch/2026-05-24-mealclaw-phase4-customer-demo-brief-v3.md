# Phase 4 客户演示 Brief v3 (for Steve) — post Sprint 11.5 Phase F.1 + Q6 Option B.5

**日期**: 2026-05-24 (post Workdesk fix PR #254 merged + B.5 month-extract pending merge)
**前置**: B.5 subagent `acab1055a38979e75` 完成 + PR merged + prod 8086 redeploy 后此 brief 可发给客户

---

## ⚠️ Brief v3 vs v2 关键不同

- v2 让客户问 "**帮我看上月损溢异常**" → 实测 backend resolve "上月" = April 2026, 无数据, 返"未提供 financial_metrics"
- v3 让客户问 "**2025年12月哪个菜亏钱**" (或类似含明确 month phrase) → UI regex extract "2025-12" → backend fetch Dec 2025 → 返 **真实 ¥1,935,193 P&L**
- Phase F.1 backfill 只 Dec 2025 (POS data 只 2025 全年), 所以 demo 必须用 2025 月份

---

## 📱 客户微信一段话 (subagent acab1055a38979e75 ship 后可发)

```
X 总, 我们刚给 Cretas 加了一个 AI 餐厅经营分析助手. 想请您 5 分钟试用看好不好用.

操作:
1. 浏览器打开 http://139.196.165.140:8086
2. 用账号 qhj_warehouse_mgr 密码 123456 工厂 RES_3101_009 登录
3. 进 "仓管员工作台" → 在聊天框输入: **"2025年12月哪个菜亏钱"** (这个月份很关键, 我们示范数据是 2025 年 12 月那一段)
4. 30 秒内 AI 给您一份单店 P&L 一页纸 (营业收入, 毛利, 净利率, headline 等)
5. 截图发我看看好不好用就行

差异化: AI 会诚实告诉您哪些数据缺什么 (e.g. "本月成本数据未上传, 已用收入估算盈利"), 不胡说凑数. 这是我们跟客如云 / MealClaw 的核心区别 — 数据可信优先.
```

---

## Prod-Verified phrases (2026-05-24 21:10 curl + 待 B.5 deploy 后 Playwright PNG)

### ✅ Curl 已 verify (data 真返)

| 客户输入 (含明确月份) | API intentCode | dataAvailable | headline |
|---|---|---|---|
| `{userInput:"哪个菜亏钱", context.month:"2025-12"}` | RESTAURANT_ECONOMICS_ANALYSIS | true | "本店 current 盈利 ¥1,935,193 (100.00%)" ✅ |

### ⏳ 待 B.5 PR 部署后 UI verify

- "2025年12月哪个菜亏钱" — B.5 regex extract "2025-12" → 同上 ✅
- "去年12月损溢异常" — B.5 regex extract `(currentYear-1)-12` = "2025-12" → 同上 ✅
- "帮我看 2025年12月 损溢" — same path ✅

### ❌ 不可用 phrases (避免)

- "**上月损溢异常**" — backend resolve = April 2026, no data → "未提供 financial_metrics"
- "**本月成本**" — backend resolve = May 2026, no data → 同
- 无明确月份的 phrases — UI default 仍 "上月" → 同

**Demo brief 必须只让客户用 含明确 2025 月份的 phrase**.

---

## 2 Path Demo (各 2 min)

### Path A: AIChat — Cretas 数据哨兵 USP (post B.5 PR merge)
- URL: **http://139.196.165.140:8086** (非 47:10010, prod web-admin 是 139)
- Login: qhj_warehouse_mgr / 123456 / RES_3101_009
- 进 仓管员工作台
- Input: **"2025年12月哪个菜亏钱"** (明确月份, 不要 "上月")
- 期望 UI 显示: headline "本店 current 盈利 ¥1,935,193 (100.00%)" + pnlLines 4 行 (营业收入 ¥1,935,193 / 食材成本 ¥0 / 人力成本 ¥0 / 净利润 ¥1,935,193)
- **Demo 话术**: "数据是去年12月的真实 POS 餐厅数据. 31 天累计 ¥193万营业收入, AI 已按门店一页纸输出. 数据缺成本拆解 (因 ETL 只 backfill 收入), AI 老实告诉您, 不像 MealClaw 1122% 那种胡说."

### Path B: SmartBI composite endpoint — 真实 BI 365 天数据 (备用)
- URL: `http://47.100.235.168:8083/api/smartbi/restaurant/llm-composite?factory_id=RES_3101_009&month=2025-12`
- 头: Authorization Bearer <token from Path A login>
- 期望: topItems=10 含 **招牌青花椒味 ¥869,754** (646K POS 真数据, 365 天累计)
- **Demo 话术**: "BI 端跑 365 天历史数据, Top 10 菜品营收排名一目了然."

---

## 收反馈 (Steve 边演示边记)

```
客户名: ____________________________
日期: 2026-05-24
角色: 老板 / 店长 / 厨师长

Q1: 看 AI P&L 一页纸 (Path A ¥193万 + 4 行 pnl), 感觉?
Q2: 看 Path B Top 10 真菜品 (招牌青花椒味 ¥869K), 感觉?
Q3: 这个跟您原来看损益方式 (e.g. Excel + 计算器) 比, 哪个更方便?
Q4: 如果要付费, 您愿意加多少钱/月?
Q5: 还有什么改进建议?
Q6: 如果让您 type "上月损溢" 而非具体"2025年12月", 您会用吗? (我们正考虑后续做语义识别 "上月" → 最近有数据的月份)

Steve 主观判定: 好用 / 凑合 / 不好用
"好用" 引述: "____________________________"
```

---

## Demo 完后给 PM (餐饮 chat)

把以上反馈 + 客户原话 + 截图贴回 chat. PM 30 min 完成:
- 整理到 retrospective §3 + §8
- 决策书 §7 PART 2 evidence fill
- 决策书 §8 Steve 签字 prompt
- Sprint 11 close + Sprint 12 backlog (B.5 跟踪剩 5 个 Workdesk + 是否做"上月"语义识别)

---

## 已知限制 (客户可能问)

1. **数据只 2025 年 12 月** — Phase F.1 backfill 仅这 1 月, POS 数据源只有 2025 全年, 后续 Sprint 12 backfill 全年
2. **成本拆解空 (食材/人力/租金)** — POS 数据没成本字段, 当前只有收入. 后续接餐厨成本 API 后会齐
3. **必须明确月份 (e.g. "2025年12月")** — UI regex extract month; 输入 "上月" 当前对应 April 2026 (无数据). Sprint 12 加 latest-available-month fallback
4. **shrinkage / cost_rigidity 仍 "未提供"** — Sprint 11.5 Phase 1 只 wire store_pnl; Sprint 12 加 Phase 2/3 wire
5. **UI 卡片标题 "今日待收清单"** — WarehouseKeeperWorkdesk hardcoded; Sprint 12 改 dynamic intent name
6. **前端 UI 简陋** — Sprint 11 MVP API-only, 后续 polish

---

## 链接

- 决策书: `docs/superpowers/decisions/2026-05-22-mealclaw-sprint11-decision.md`
- Output quality audit Q1-Q6: PR #215 merged
- Q7/Q8 fresh evidence: PR #253 (worktree-mealclaw-pm-coord branch)
- Q6 Option B Workdesk fix: PR #254 merged 74accc37b
- Q6 Option B.5 month-extract fix: subagent acab1055a38979e75 (PR # 待)
- Round 3 verify: `docs/audits/sprint-11-mealclaw-q6-option-b-round3-result.md`
- Phase F.1 ETL resolved memory: `feedback_smartbi_repo_uses_primary_datasource.md`
- 服务器: 47 = Java + Python + DB; 139 = web-admin + nginx gateway
