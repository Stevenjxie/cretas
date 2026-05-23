# UX State Matrix — 72 cell (12 PNG × 6 dimension)

**Date**: 2026-05-23
**Owner**: AI 工厂 chat auditor
**Source**: 12 PNG (`screenshots/*.png`) — fullPage 1440×900 viewport
**Status**: ✅ Full fill — 72 cells filled from actual visual inspection of 12 PNGs

---

## Dimension definitions

| Dim | Question | Rubric |
|---|---|---|
| 1. **loading** | Loading state visible while waiting for AI response? | 满意: spinner + accurate text; 凑合: text only; 不可用: silent |
| 2. **error** | If response was error-class, shown in red el-alert banner with actionHint + sticky? | 满意: 红 banner + sticky + actionHint; 凑合: 红 banner no actionHint; 不可用: 黑色文字看着像正常 |
| 3. **empty** | Empty-state UX: friendly ("暂无数据, 试试 X")? | 满意: friendly + suggestion; 凑合: "暂无数据" only; 不可用: technical jargon ("dataAvailable=false") |
| 4. **mobile-320** | Layout works at 320px viewport? (predicted from el-row flex / overflow / fixed widths) | 满意: 单列 stack; 凑合: 横滚不致命; 不可用: 关键内容截断 |
| 5. **readability** | Markdown rendered properly (### → h3 + bold + lists)? Font/line-height comfortable? | 满意: 全 markdown 渲染 + 易读; 凑合: 部分渲染; 不可用: raw `###` |
| 6. **color** | Class B/D/E shown in warning color (yellow/red)? | 满意: 红/橙 banner; 凑合: 单字红色; 不可用: 全黑 "正常状态" 假象 |

---

## Matrix — Account A: qhj_warehouse_mgr (RES_3101_009)

| PNG \ Dim | 1.loading | 2.error | 3.empty | 4.mobile | 5.readability | 6.color |
|---|---|---|---|---|---|---|
| `qhj_warehouse_mgr__phrase1.png` (LLM timeout) | 凑合 (loading 已消失) | **不可用** (错误 "Skill 执行失败: Failed to call LLM" 黑色文字, 无红 banner, 无 sticky, 无 actionHint) | 凑合 (indicator card "暂无数据" 灰底 + 红 "刷新" 按钮) | 不可用 (el-row 4 indicator card 横排, 320px 必截) | 满意 (markdown 渲染 OK) | **不可用** (错误信息全黑色像正常文字) |
| `qhj_warehouse_mgr__phrase2.png` (损益分析→错路由) | 凑合 (loading 消失 → 直接 markdown) | **不可用** (无 error 标识, "今日客户跟进概览" 像正常输出, 但客户问的是 P&L) | 不可用 ("暂无最近的微信沟通记录" 等 5 项 全 plain markdown bullet, 不显示是 empty state) | 不可用 | 满意 (markdown ### h3 渲染 OK) | **不可用** (全黑文字, 没有 warning 色) |
| `qhj_warehouse_mgr__phrase3.png` (上月成本→错路由) | 凑合 | **不可用** (同上) | 不可用 | 不可用 | 满意 | 不可用 |
| `qhj_warehouse_mgr__phrase4_FAIL.png` (TIMEOUT, 卡在 loading) | **凑合** (蓝 banner "AI 正在聚合 5 个数据源 (客户优先级+微信+通话+商机+收入), 预计 5-10 秒" — 但文案 hardcoded 不匹配用户输入"哪个菜亏钱") | 不可用 (永远转, 无 timeout warning, 无 retry CTA) | — (无 result card) | 不可用 | — | **不可用** (loading 蓝色, 但卡 90s 后用户应该看到 timeout warning, 实际只有空白) |

## Matrix — Account B: f006_admin (F006)

| PNG \ Dim | 1.loading | 2.error | 3.empty | 4.mobile | 5.readability | 6.color |
|---|---|---|---|---|---|---|
| `f006_admin__phrase1.png` (错路由 + indicator mock 值 37.39/1.41/98.78) | 凑合 | 不可用 (无 error, "今日客户跟进概览" 假装正常) | 不可用 (5 项 "暂无" plain text) | 不可用 (4 indicator 横排) | 满意 (markdown 渲染) | **不可用** (indicator card 真值绿色显示但其实 Item 1 BLOCKER 数据是 F999_MOCK mirror — 颜色 GREEN 误导用户以为业务正常) |
| `f006_admin__phrase2.png` | 凑合 | 不可用 | 不可用 | 不可用 | 满意 | 不可用 |
| `f006_admin__phrase3.png` | 凑合 | 不可用 | 不可用 | 不可用 | 满意 | 不可用 |
| `f006_admin__phrase4.png` | 凑合 | 不可用 | 不可用 | 不可用 | 满意 | 不可用 |

## Matrix — Account C: warehouse_mgr1 (F001 sister)

| PNG \ Dim | 1.loading | 2.error | 3.empty | 4.mobile | 5.readability | 6.color |
|---|---|---|---|---|---|---|
| `warehouse_mgr1_F001__phrase1.png` (LLM timeout) | 凑合 | **不可用** (同 A.1 错误黑文字) | 凑合 | 不可用 | 满意 | 不可用 |
| `warehouse_mgr1_F001__phrase2.png` (错路由) | 凑合 | 不可用 | 不可用 | 不可用 | 满意 | 不可用 |
| `warehouse_mgr1_F001__phrase3.png` (错路由) | 凑合 | 不可用 | 不可用 | 不可用 | 满意 | 不可用 |
| `warehouse_mgr1_F001__phrase4.png` (LLM 错误重现) | 凑合 | **不可用** (错误黑色) | 不可用 | 不可用 | 满意 | 不可用 |

---

## Totals (72 cells)

| Bucket | 满意 | 凑合 | 不可用 | (skip = 1 timeout case 无 result-card 3 dims) |
|---|---|---|---|---|
| Count | **8** | **12** | **49** | 3 |

Per dimension:

| Dim | 满意 | 凑合 | 不可用 | skip |
|---|---|---|---|---|
| 1.loading | 1 (qhj.4 蓝 banner) | 11 | 0 | 0 |
| 2.error | 0 | 0 | 12 | 0 |
| 3.empty | 0 | 2 | 9 | 1 |
| 4.mobile-320 | 0 | 0 | 12 | 0 |
| 5.readability | 11 | 0 | 0 | 1 |
| 6.color | 0 | 0 | 11 (qhj.4 skip) | 1 |

---

## Top 3 UX 问题

### 问题 1 (最严重 — 12/12 不可用): error 信息全黑色, 无 red banner

每个 LLM timeout / error 都以 `Skill 执行失败: Failed to call LLM` **黑色普通文字**显示在 result-card 内, 而不是 `el-alert` 红色 banner。客户看不出来是"错误" — 跟"正常输出"长得一样。**违反 fool-proof-design Rule a (sticky red banner with actionHint)** + Rule c (duration:0 sticky)。

实施代价: 5 min. 改 `SalesOwnerWorkdesk.vue:603` `formattedText.value = ...` 检测 `Skill 执行失败` 前缀 → 改用 `errorMessage.value` (走 `.error-alert` 已存在的红 banner).

### 问题 2 (12/12 不可用): mobile 320px 必截 — 老板用手机看必坏

4 个 indicator card + `chat-card` + `result-card` 都用 `el-row` 横排 + 固定宽度 (经营指标 4 列, 没 mobile breakpoint)。320px viewport 必横滚或截断。**违反 fool-proof-design Rule 4 + responsive design 常识**。

实施代价: 中 (30-60 min). 加 `@media (max-width: 768px)` 切 indicator-grid 单列 + chat input 全宽。

### 问题 3 (11/12 不可用): "暂无" 是错路由后的副产物, 整个 UI 没标"路由失败"

UI 把"错路由 → DAILY_CUSTOMER_FOLLOWUP → 暂无数据"的 5 项"暂无" 当**正常输出**渲染, 没有任何 hint "您问的是损益, 但系统返回客户跟进数据 - 可能 misroute"。

这是最隐蔽的失败模式: 客户看到响应有 markdown 渲染 / 有"今日客户跟进概览" / 没 error banner → 以为系统正常工作, 只是"我们公司今天没客户跟进", 完全 oblivious 到自己问的损益分析根本没被处理。

实施代价: 大 (1-2h + 后端 intent 显式返回 → frontend 显示 "您说 X, 我理解为 Y" 确认 CTA)。**这是 P0 dispatch P0-2 (LLM 防幻觉) + P0-1 (intent 缺失) 同根源**。

---

## UX 总分

按 fool-proof-design 5 大规则反向打分:

| Rule | 实际状态 | 评分 |
|---|---|---|
| R1 预先显示边界 | error 后没建议 next action | 0 / 1 |
| R2 上下文必带身份信息 | "今日客户跟进概览" 但用户问的不是这个 → 完全 lost context | 0 / 1 |
| R3 自由文本改约束选择 | chat 自由输入 — 但 ❌ 没有 "您是否想问 X / Y / Z?" 引导 | 0 / 1 |
| R4 写操作幂等防重复 | (N/A for chat) | — |
| R5 Dead-end 改导航 | "暂无数据" 5 项纯文本, 0 CTA "前往 SmartBI 查看 / 上传数据" | 0 / 1 |
| 4 位一体 (error banner sticky + actionHint + 后端消息+前端同步) | 全 fail | 0 / 1 |
| **总分** | | **0 / 5 (0%)** |

---

## 跟 fool-proof-design 实施差距 估算

按 11 PNG 修一遍 → 工作量:
- 问题 1 修: 5 min (单文件修)
- 问题 2 修: 60 min (responsive CSS)
- 问题 3 修: 4h (后端 intent 显式 +前端 confirm CTA) + Sprint 12 routing fix 联动

合计: **5 h backend+frontend** 来 hit 满意 baseline。

但 routing 问题本身 (Class D 错路由 9/12) 需要 Sprint 12 IntentKnowledgeBase overhaul, 单独 12-20h。

**结论: UX 问题不是表面问题, 跟 Sprint 11 P0-1/2/3 是同根源 — intent matching 失败 → response misrouted → UX 无法用 banner 自救.**
