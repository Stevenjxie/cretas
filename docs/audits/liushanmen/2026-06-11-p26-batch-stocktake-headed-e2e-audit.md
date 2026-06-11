# P1 Audit #26 — Headed E2E: 批次详情双产出 + 盘点全链

**日期**: 2026-06-11
**审计任务**: P1 audit #26（两 web 链）
**执行者**: Sonnet subagent (in-harness)
**Prod URL**: `http://139.196.165.140:8086`
**测试账号**: f006_admin (工厂总监, F006 六扇门)

---

## 验证范围

| 链 | 内容 | 结论 |
|---|---|---|
| Chain-1 | 批次详情页 — SP1 双产出列渲染 (`outputKind`/`semiOutputQuantity`/`semiCode`) | ✅ 列头正确渲染 |
| Chain-1b | 批次 1924 (叮咚好食光猪舌) — 直接导航验双产出列头 | ✅ 两列头可见 |
| Chain-2 | 盘点全链 — 盘点任务管理页 + 发起 dialog (只读不提交) | ✅ 全链 UI 正常 |

---

## Chain-1: 批次详情双产出列渲染 (#698)

**PR #698** 在批次详情"生产批次列表"中新增了两列：

| 列名 | 字段 | 渲染逻辑 |
|---|---|---|
| 产出类型 | `outputKind` | `FINISHED`→"—"; `SEMI`→"纯半成品"tag; `BOTH`→"双产出"tag |
| 半成品产出 | `semiOutputQuantity` | 非 null 显示数量+semiCode; null→"—" |

**测试批次**: 自动选取最新 COMPLETED 批次（叮咚好食光椒麻掌中宝 120g, batch 1976）

**结果**:
- 批次列表页加载: ✅ (5 条批次可见)
- 批次详情页加载: ✅ (页面标题、基本信息正确)
- 双产出列现有数据: 全部显示"—"（SP1 新功能，prod 无 SEMI/BOTH 数据）— **符合预期**

**截图**:
- `01-batch-list.png` — 批次列表页 (5 条 COMPLETED 批次)
- `02-batch-detail-top.png` — 批次详情顶部
- `03-batch-detail-midscroll.png` — 批次详情中部滚动
- `04-batch-detail-yield-section.png` — 报工工序区域
- `06-batch-detail-fullpage.png` — 批次详情全页

---

## Chain-1b: 批次 1924 双产出列头直接验证

批次 1924（叮咚好食光猪舌, 忠实复刻批次, 10 道工序, 合计 998kg→540kg 出成率 54.1%）

**列头可见性验证**:
- `产出类型` 列头: **true** (visible)
- `半成品产出` 列头: **true** (visible)

这是本次验证的核心正向结论：SP1 双产出列已正确渲染到页面。

**截图**:
- `20-batch1924-detail-top.png` — 批次 1924 详情顶部 (叮咚好食光猪舌)
- `21-batch1924-midscroll.png` — 中部滚动 (合计 998→540kg, 出成率 54.1%, 总人工¥1,703)
- `22-batch1924-yield-area.png` — 报工工序区域 (半成品库存 WIP 10 道)
- `23-batch1924-fullpage.png` — 批次 1924 全页

---

## Chain-2: 盘点全链 UI 验证

**路由**: `/warehouse/stocktakes`

**验证内容**:
1. 页面导航: ✅ (仓储管理 → 盘点任务 路径正确)
2. 页面标题: ✅ "盘点任务管理"
3. 状态过滤器: ✅ "全部状态" 下拉可交互
4. 发起盘点 button: ✅ 可见
5. 发起 dialog: ✅ 打开后显示三字段（盘点仓库/盘点月份/备注）
6. Dialog 关闭: ✅ 点"取消"正常关闭
7. 提示横幅: ✅ "本月 (2026-06) 尚未创建盘点任务，建议每月发起一次盘点" (空状态引导正确)
8. 空状态: ✅ F006 当前 0 条盘点任务，空状态 UI 正常

**只读约束**: 打开 dialog 后点"取消"，**未提交**任何数据，prod 数据无污染。

**截图**:
- `10-stocktake-list.png` — 盘点列表初始化
- `11-stocktake-list-loaded.png` — 盘点列表加载完成 (0 条)
- `12-stocktake-initiate-dialog.png` — 发起盘点 dialog (只读截图)
- `14-stocktake-empty-state.png` — 空状态提示
- `15-stocktake-status-filter-open.png` — 状态过滤下拉展开
- `16-stocktake-final-fullpage.png` — 盘点页全页

---

## UI 问题发现

| # | 链 | 问题 | 严重度 | 状态 |
|---|---|---|---|---|
| — | — | 无 UI bug 发现 | — | — |

**特别说明**:
- SP1 双产出 `产出类型`/`半成品产出` 列头渲染正确，无样式异常
- 盘点页空状态横幅文案友好（防呆设计，提示每月发起）
- 无 JS 控制台错误，无 error overlay

---

## 技术备注

**Auth 机制**: 前端 `cretas_access_token` 存 localStorage（非 HttpOnly cookie）；`cretas_user` 同样 localStorage。使用 `context.addInitScript()` 在 SPA 启动前注入，避免跳转至登录页。

**路由模式**: Vue router 使用 `createWebHistory()` (HTML5 history mode)，路径无 `#/` 前缀。

**API 路径**: 所有 API 经由 nginx 网关 `http://139.196.165.140:8086/api/mobile`（后端 47.100.235.168:10010 在安全组保护下，不直连）。

---

## Headed Mode Verification

- headless: false ✓
- viewport: 1920×1080 ✓
- locale: zh-CN ✓
- chromium window 真弹 ✓ (Windows 桌面可见)
- 截图字体: 中文真显示（白垩纪AI Agent、生产管理、批次详情等中文字体正常，无方块 □）✓
- screenshot mode: fullPage ✓
- video: on ✓
- PLAYWRIGHT_PORT: 9223
- PLAYWRIGHT_CHAT_ID: p26
- Test run: 3/3 passed in 1.4 minutes

---

## 测试命令

```bash
cd C:\Users\Steve\cretas-p26\web-admin
$env:PLAYWRIGHT_PORT="9223"; npx playwright test --project=p26-headed --reporter=line
```

---

## 截图存档位置

`docs/audits/liushanmen/2026-06-11-p1-headed-e2e-screenshots/` (15 张)

| 文件 | 内容 |
|---|---|
| 01-batch-list.png | 批次列表 (5 条 COMPLETED) |
| 02-batch-detail-top.png | 批次详情顶部 |
| 03-batch-detail-midscroll.png | 批次详情中部 |
| 04-batch-detail-yield-section.png | 报工工序区域 |
| 06-batch-detail-fullpage.png | 批次详情全页 |
| 10-stocktake-list.png | 盘点列表初始 |
| 11-stocktake-list-loaded.png | 盘点列表加载完 |
| 12-stocktake-initiate-dialog.png | 发起盘点 dialog (只读) |
| 14-stocktake-empty-state.png | 盘点空状态 |
| 15-stocktake-status-filter-open.png | 状态过滤下拉 |
| 16-stocktake-final-fullpage.png | 盘点全页 |
| 20-batch1924-detail-top.png | 批次 1924 顶部 |
| 21-batch1924-midscroll.png | 批次 1924 中部 (998→540kg) |
| 22-batch1924-yield-area.png | 批次 1924 WIP 10 道工序 |
| 23-batch1924-fullpage.png | 批次 1924 全页 |
