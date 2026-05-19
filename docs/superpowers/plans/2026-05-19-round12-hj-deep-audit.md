# R-HJ Round 12 Deep Re-Audit Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 完成 R-HJ Round 12 deep re-audit, 输出 32-doc 7 sections (≥4000 行) — HJ 帮助手册 + 5 大数据流 + 生产+BOM+ECN + RBAC+审批 + 5+ design pattern + 销售话术 + Cretas 改进 backlog.

**Architecture:** 2-phase pipeline (复用 Round 11 模式): Phase 1 organizer Playwright sequential capture (~5h, single browser MCP); Phase 2 5 parallel agents synthesis (~1.5h) + organizer 写 §F+§G (~1h). 总 ~10h 跨 1-2 工作日.

**Tech Stack:** Playwright MCP (browser_navigate/snapshot/click/take_screenshot) + Agent tool (subagent_type=general-purpose) + Bash (file ops + commits) + Read/Edit/Write (doc 操作) + Grep (Cretas main verification).

**Spec source:** `docs/superpowers/specs/2026-05-19-round12-hj-deep-audit-design.md`

---

## Task 0: Pre-flight (verify access + dir setup)

**Files:**
- Verify: `宏见竞品分析/04-最终决策/31-DEEP-RE-AUDIT.md` (exists, Round 11 baseline)
- Create: `宏见竞品分析/06-宏见测试账号深度审计/screenshots/round12/{help,chain,production,rbac,pattern}/`
- Create: `宏见竞品分析/06-宏见测试账号深度审计/round12-snapshots/`

- [ ] **Step 1: Verify Round 11 baseline + spec exist**

Run:
```bash
ls -la 宏见竞品分析/04-最终决策/31-DEEP-RE-AUDIT.md docs/superpowers/specs/2026-05-19-round12-hj-deep-audit-design.md
```
Expected: 两文件存在, 31-doc ~3517 行, spec ~277 行.

- [ ] **Step 2: Create round12 subdirectories**

Run:
```bash
mkdir -p 宏见竞品分析/06-宏见测试账号深度审计/screenshots/round12/{help,chain,production,rbac,pattern}
mkdir -p 宏见竞品分析/06-宏见测试账号深度审计/round12-snapshots
ls -la 宏见竞品分析/06-宏见测试账号深度审计/screenshots/round12/
```
Expected: 5 子目录 created.

- [ ] **Step 3: Verify Playwright MCP access + HJ login working**

Use `mcp__playwright-rn__browser_navigate` to `https://login.hongjian.com/login/login.jsp` + `browser_snapshot`. Expected: 登录页 form 显示.

如果 browser tools 未 loaded, 先用 `ToolSearch` query `select:mcp__playwright-rn__browser_navigate,mcp__playwright-rn__browser_snapshot,mcp__playwright-rn__browser_click,mcp__playwright-rn__browser_type,mcp__playwright-rn__browser_take_screenshot,mcp__playwright-rn__browser_evaluate` 加载.

- [ ] **Step 4: Login + verify session**

Use `browser_type` to fill `lyh01` (e29), `admin` (e34), `Aa123456` (e39). Click `登录` button (e42). Verify redirected to `main.hongjian.com/index.jsp` with title "宏见演示苏州李".

Note: refs may differ from Round 11 if HJ has changed UI. Use `browser_snapshot` first to verify ref ids.

---

## Task 1: Phase 1.1 — 帮助手册深读 (~1h)

**Files:**
- Create: `screenshots/round12/help/01-help-entry.png` ~ `XX-chapter-N.png`
- Create: `round12-snapshots/help-toc.md` (table of contents)
- Create: `round12-snapshots/help-chapter-XX.md` (per chapter)

- [ ] **Step 1: Locate 帮助手册 entry**

From dashboard, identify top-right header icons. Per Round 11 baseline, ref=e21 is `帮助手册` (between 消息提醒 e18 and 退出系统 e24).

Use `browser_snapshot` to get current refs (may shift across sessions). Find `generic "帮助手册"`.

- [ ] **Step 2: Click 帮助手册 + capture entry page**

`browser_click` target=帮助手册 ref. Then:
```
browser_take_screenshot filename="round12/help/01-help-entry.png" fullPage=true
browser_snapshot filename="round12-snapshots/help-entry.md" depth=8
```

Verify: 帮助手册 opens (could be new tab / iframe / modal / external link). Document what kind of UI.

- [ ] **Step 3: Capture 帮助手册 ToC (Table of Contents)**

Look for ToC sidebar / 章节列表. Use `browser_snapshot` to dump structure. Save to `round12-snapshots/help-toc.md`.

Document: 总章节数 / 每章标题 / 是否有搜索框 / PDF download link.

- [ ] **Step 4: Capture ≥5 关键章节** (priority: 销售 / 采购 / 财务 / 生产 / 工作流)

Per chapter:
```
browser_click target=<chapter link>
browser_take_screenshot filename="round12/help/0X-chapter-{module}.png" fullPage=true
browser_snapshot filename="round12-snapshots/help-chapter-{module}.md" depth=10
```

如章节超 ≥5 (HJ 通常 ≥12 module), 至少抓 销售/采购/财务/生产/工作流/客户/RBAC 7 章.

- [ ] **Step 5: Verify capture complete**

Run:
```bash
ls 宏见竞品分析/06-宏见测试账号深度审计/screenshots/round12/help/ | wc -l
ls 宏见竞品分析/06-宏见测试账号深度审计/round12-snapshots/help-*.md | wc -l
```
Expected: ≥5 PNG + ≥5 MD files.

- [ ] **Step 6: Commit Phase 1.1**

```bash
git add 宏见竞品分析/06-宏见测试账号深度审计/round12-snapshots/help-*.md
git commit -m "docs(audit): R-HJ Round 12 Phase 1.1 — 帮助手册 snapshots

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>" -- 宏见竞品分析/06-宏见测试账号深度审计/round12-snapshots/help-*.md
```

Note: screenshots .gitignored, snapshot.md NOT gitignored, will commit.

---

## Task 2: Phase 1.2 — 5 大 chain 端到端 walk (~2h)

**Files:**
- Create: `round12-snapshots/chain-01-sales-create.md` ~ `chain-08-voucher.md`
- Create: `screenshots/round12/chain/01-sales-create.png` ~ `08-voucher.png`

**Chain target**: 用 demo 订单 [00000060] 苏州远野 跟踪全链路 8 节点.

- [ ] **Step 1: Sales order CREATE flow**

Navigate 销售管理 → 销售订单 → 新建. Capture:
```
browser_take_screenshot filename="round12/chain/01a-sales-create-dialog.png" fullPage=true
browser_snapshot filename="round12-snapshots/chain-01-sales-create.md" depth=10
```

抓: dialog 全字段 list + validation hints + button 数量 + 必填 markers.

- [ ] **Step 2: Sales order DETAIL of [00000060]**

Navigate to `https://workflow.hongjian.com/workflow/workflowroute.jsp?workno=sale&primary=00000060&sale_type=sales` (Round 11 已验证 URL pattern).

```
browser_take_screenshot filename="round12/chain/01b-sales-detail-00000060.png" fullPage=true
browser_snapshot filename="round12-snapshots/chain-01b-sales-detail.md" depth=12
```

抓: 表头字段 / 行项目 / footer 汇总 / 关联 link counter (文件/图片/合同 N) / 跨子域 URL pattern.

- [ ] **Step 3: Sales 审核动作 (trigger downstream)**

如该订单已审核状态, snapshot 审批历史 modal (Round 11 已抓). 如未审核, snapshot 审核 dialog (不实际点 OK, 避免修改测试数据).

```
browser_snapshot filename="round12-snapshots/chain-01c-sales-approval.md" depth=8
```

抓: 审批 dialog 字段 / 触发的下游操作描述.

- [ ] **Step 4: MRP 缺料分析 page**

Navigate 采购管理 → 流程图 → 销售订单 node. 或直接 navigate `https://buy.hongjian.com/buy/mrp/...` if exists.

```
browser_take_screenshot filename="round12/chain/02-mrp-analysis.png" fullPage=true
browser_snapshot filename="round12-snapshots/chain-02-mrp.md" depth=10
```

抓: 缺料 list + 推荐采购 + 货源建议.

- [ ] **Step 5: Purchase order list + detail**

采购管理 → 采购订单. Pick 1 PO关联 sales [00000060] (look for linkno=00000060). Snapshot list + detail.

```
browser_take_screenshot filename="round12/chain/03a-po-list.png" fullPage=true
browser_take_screenshot filename="round12/chain/03b-po-detail.png" fullPage=true
browser_snapshot filename="round12-snapshots/chain-03-po.md" depth=12
```

抓: PO 字段 / 跟 sale 的 reverse link / 三价对比模块.

- [ ] **Step 6: 采购入库**

采购管理 → 采购入库 list. Snapshot 1 入库单 detail.

```
browser_take_screenshot filename="round12/chain/04-receive.png" fullPage=true
browser_snapshot filename="round12-snapshots/chain-04-receive.md" depth=10
```

抓: 收货数量 / 批次 / 跟 PO 关联.

- [ ] **Step 7: Production task (if 有) → 销售出库**

生产管理 → 生产任务. Snapshot 1 任务 detail. 然后 销售管理 → 销售出库列表. Snapshot 1 出库单.

```
browser_take_screenshot filename="round12/chain/05-production.png" fullPage=true
browser_take_screenshot filename="round12/chain/06-shipment.png" fullPage=true
browser_snapshot filename="round12-snapshots/chain-05-prod-06-ship.md" depth=10
```

- [ ] **Step 8: 开票 + 收款 + vflag 凭证**

财务管理 → 发票管理 → invoice detail (跟 sale [00000060] 关联). 然后 收款 list. 最后 财务流程图 → 应收应付单据生成凭证 → 实际生成的凭证 list.

```
browser_take_screenshot filename="round12/chain/07-invoice.png" fullPage=true
browser_take_screenshot filename="round12/chain/08-payment-voucher.png" fullPage=true
browser_snapshot filename="round12-snapshots/chain-07-invoice-08-voucher.md" depth=10
```

抓: vflag 4 状态实际显示 / 凭证 ↔ 业务单 反查 link.

- [ ] **Step 9: Verify Phase 1.2 complete**

Run:
```bash
ls 宏见竞品分析/06-宏见测试账号深度审计/round12-snapshots/chain-*.md | wc -l
```
Expected: ≥8 chain*.md files.

- [ ] **Step 10: Commit Phase 1.2**

```bash
git add 宏见竞品分析/06-宏见测试账号深度审计/round12-snapshots/chain-*.md
git commit -m "docs(audit): R-HJ Round 12 Phase 1.2 — 5 大 chain snapshots

8 节点 walk-through of demo order [00000060]: sales create →
detail → MRP → PO → receive → production → shipment → invoice
→ payment → vflag voucher.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>" -- 宏见竞品分析/06-宏见测试账号深度审计/round12-snapshots/chain-*.md
```

---

## Task 3: Phase 1.3 — 生产深入 (~1h)

**Files:**
- Create: `round12-snapshots/prod-{bom,ecn,wp,wip,tech}.md`
- Create: `screenshots/round12/production/*.png`

- [ ] **Step 1: BOM list + detail**

工程管理 → BOM列表 (or 生产管理 → BOM). Snapshot list + 1 BOM detail with full 工序 + 物料 + 替代物料 + 损耗率.

```
browser_take_screenshot filename="round12/production/01-bom-list.png" fullPage=true
browser_take_screenshot filename="round12/production/02-bom-detail.png" fullPage=true
browser_snapshot filename="round12-snapshots/prod-bom.md" depth=12
```

- [ ] **Step 2: ECN 变更明细 page**

工程管理 → BOM 反查 / 待审核BOM / ECN. Snapshot ECN list + 1 ECN detail.

```
browser_take_screenshot filename="round12/production/03-ecn.png" fullPage=true
browser_snapshot filename="round12-snapshots/prod-ecn.md" depth=10
```

抓: ECN reason enum 实际值 / 影响范围字段 / 审批链 UI.

- [ ] **Step 3: 工序管理 page (路由配置)**

生产管理 → 工序流转 + 工程管理 → 工序管理.

```
browser_take_screenshot filename="round12/production/04-workprocess.png" fullPage=true
browser_snapshot filename="round12-snapshots/prod-wp.md" depth=10
```

抓: 工序配置 UI + 条件路由 ("材质=不锈钢→工序A") 实际配置 dialog.

- [ ] **Step 4: WIP 在制品 list**

生产管理 → 在制品 (or wip.hongjian.com 子域).

```
browser_take_screenshot filename="round12/production/05-wip.png" fullPage=true
browser_snapshot filename="round12-snapshots/prod-wip.md" depth=8
```

抓: 5 列实际 schema (物料/批次/数量/工序/占用任务).

- [ ] **Step 5: 作业指导书 (technology)**

生产管理 → 作业指导书 / SOP 模板.

```
browser_take_screenshot filename="round12/production/06-tech-sop.png" fullPage=true
browser_snapshot filename="round12-snapshots/prod-tech.md" depth=8
```

如不存在该子菜单, 标 "NOT FOUND in HJ menus" + screenshot ToC.

- [ ] **Step 6: Verify + Commit Phase 1.3**

```bash
ls 宏见竞品分析/06-宏见测试账号深度审计/round12-snapshots/prod-*.md | wc -l
# Expected ≥4

git add 宏见竞品分析/06-宏见测试账号深度审计/round12-snapshots/prod-*.md
git commit -m "docs(audit): R-HJ Round 12 Phase 1.3 — 生产+BOM+ECN+WIP snapshots

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>" -- 宏见竞品分析/06-宏见测试账号深度审计/round12-snapshots/prod-*.md
```

---

## Task 4: Phase 1.4 — RBAC + 审批 (~1h)

**Files:**
- Create: `round12-snapshots/rbac-{roles,menus,workflow,spel,rules,opinion}.md`
- Create: `screenshots/round12/rbac/*.png`

- [ ] **Step 1: 角色管理 page (1591 f_no list)**

人力资源 → 员工角色管理 (or 系统管理 → 角色). Snapshot 角色 list + 1 角色 detail with f_no 权限点 tree.

```
browser_take_screenshot filename="round12/rbac/01-roles-list.png" fullPage=true
browser_take_screenshot filename="round12/rbac/02-role-permissions-tree.png" fullPage=true
browser_snapshot filename="round12-snapshots/rbac-roles.md" depth=12
```

抓: f_no 权限点 tree grouping (按模块 / 按 action / 按字段?) + 数量真实值 (1591 是估算?)

- [ ] **Step 2: 菜单可见性配置**

系统管理 → 菜单管理 (or 角色 detail 内的菜单 tab).

```
browser_take_screenshot filename="round12/rbac/03-menu-visibility.png" fullPage=true
browser_snapshot filename="round12-snapshots/rbac-menus.md" depth=10
```

抓: 菜单 tree + checkbox 形态 + 即时生效 vs 重启生效.

- [ ] **Step 3: 工作流编辑器 (重抓 Round 11 G3-05/06 update)**

系统管理 → 工作流设置. Pick 1 流程 → 编辑.

```
browser_take_screenshot filename="round12/rbac/04-workflow-editor.png" fullPage=true
browser_snapshot filename="round12-snapshots/rbac-workflow-editor.md" depth=15
```

抓: jsPlumb canvas / 节点 toolbox / 拖拽 affordance / 节点配置弹窗字段.

- [ ] **Step 4: 节点配置 dialog (SpEL / 条件)**

Double-click 1 节点 → 编辑节点 dialog.

```
browser_take_screenshot filename="round12/rbac/05-node-config.png" fullPage=true
browser_snapshot filename="round12-snapshots/rbac-node-config.md" depth=12
```

抓: 审批人 select / 条件 input / SpEL 表达式 (e.g. `${amount > 10000}`) / 跳转规则 / 会签 N-of-M 配置.

- [ ] **Step 5: 流转规则页 (C-WF-RULE-1 入口)**

系统管理 → 流转规则设置.

```
browser_take_screenshot filename="round12/rbac/06-flow-rules.png" fullPage=true
browser_snapshot filename="round12-snapshots/rbac-rules.md" depth=10
```

抓: 规则 list + 1 rule detail (金额 / 部门 / 角色阈值 input UI).

- [ ] **Step 6: 节点意见模板 (C-OPINION-1 入口)**

系统管理 → 工作流 → 意见模板 (or 类似子菜单).

```
browser_take_screenshot filename="round12/rbac/07-opinion-templates.png" fullPage=true
browser_snapshot filename="round12-snapshots/rbac-opinion.md" depth=8
```

抓: 模板 list + 1 模板 detail (常用语).

- [ ] **Step 7: Verify + Commit Phase 1.4**

```bash
ls 宏见竞品分析/06-宏见测试账号深度审计/round12-snapshots/rbac-*.md | wc -l
# Expected ≥6

git add 宏见竞品分析/06-宏见测试账号深度审计/round12-snapshots/rbac-*.md
git commit -m "docs(audit): R-HJ Round 12 Phase 1.4 — RBAC + 审批 snapshots

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>" -- 宏见竞品分析/06-宏见测试账号深度审计/round12-snapshots/rbac-*.md
```

---

## Task 5: Phase 1.5 — UI/UX pattern 抽样 (~30 min, NEW per spec §2.2)

**Files:**
- Create: `round12-snapshots/pattern-*.md`
- Create: `screenshots/round12/pattern/*.png`

Spec §2.2 列了 8 patterns. Round 11 + Phase 1.1-1.4 已 cover 大部分, 本 step 补抓 missing 的:

- [ ] **Step 1: iframe 6 层嵌套架构 (UX_PATTERNS 历史推测)**

Pick 1 复杂 page (e.g. 销售单 detail), 用 `browser_evaluate` 数 iframes:
```js
() => Array.from(document.querySelectorAll('iframe')).map(f => ({id:f.id, src:f.src.substring(0,80), nested:f.contentDocument?.querySelectorAll('iframe').length}))
```

抓: 实际 iframe count + 嵌套深度.

```
browser_snapshot filename="round12-snapshots/pattern-iframe.md" depth=4
```

- [ ] **Step 2: layui-layer 弹窗 4 操作 (U-DESKTOP-MODAL-1)**

Trigger 任意 modal (e.g. 销售单创建). Snapshot dialog header 4 button (最小化/最大化/拉伸/关闭).

```
browser_take_screenshot filename="round12/pattern/01-layui-layer.png"
browser_snapshot filename="round12-snapshots/pattern-modal.md" depth=8
```

- [ ] **Step 3: 列表 view 5 模式切换 (U-VIEW-1)**

进任一 list (e.g. 销售订单 list), 找 view-mode dropdown.

```
browser_take_screenshot filename="round12/pattern/02-view-modes.png"
browser_snapshot filename="round12-snapshots/pattern-view.md" depth=8
```

- [ ] **Step 4: 创建 4 模式 dropdown (U-NEW-1)**

任一 list → "+新建 ▼" dropdown. 抓 4 模式 (普通/一维/二维/BOM 展开).

```
browser_take_screenshot filename="round12/pattern/03-new-dropdown.png"
browser_snapshot filename="round12-snapshots/pattern-new.md" depth=8
```

- [ ] **Step 5: 行内 7 icon 工具集 (U-ICON-1)**

list row hover, 抓 7 icon 含义.

```
browser_take_screenshot filename="round12/pattern/04-row-icons.png"
```

- [ ] **Step 6: 订单标记 7 色 (U-MARKER-1)**

任一 list → 行 marker 颜色 dropdown.

```
browser_take_screenshot filename="round12/pattern/05-marker-colors.png"
browser_snapshot filename="round12-snapshots/pattern-marker.md" depth=8
```

- [ ] **Step 7: Verify + Commit Phase 1.5**

```bash
ls 宏见竞品分析/06-宏见测试账号深度审计/round12-snapshots/pattern-*.md | wc -l
# Expected ≥5

git add 宏见竞品分析/06-宏见测试账号深度审计/round12-snapshots/pattern-*.md
git commit -m "docs(audit): R-HJ Round 12 Phase 1.5 — UI/UX pattern snapshots

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>" -- 宏见竞品分析/06-宏见测试账号深度审计/round12-snapshots/pattern-*.md
```

---

## Task 6: Create 32-doc skeleton + dispatch 5 parallel agents

**Files:**
- Create: `宏见竞品分析/04-最终决策/32-DEEP-RE-AUDIT-V2.md` (skeleton with §A-§G section markers)

- [ ] **Step 1: Write 32-doc skeleton**

Use Write tool to create file with header + 章节地图 + section markers:

```markdown
# 32 — HJ Deep Re-Audit V2 (R-HJ Round 12)

> **Audit chat**: organizer (本 session, 2026-05-19/20)
> **Trigger**: Steve "用 superpowers 审计, 深入 HJ 帮助手册 + 跨模块数据流 + 全 UI/UX"
> **Spec**: `docs/superpowers/specs/2026-05-19-round12-hj-deep-audit-design.md`
> **Phase 1 captures**: `06-宏见测试账号深度审计/round12-snapshots/` (~30 .md) + `screenshots/round12/` (~30 .png)
> **前置**: Round 11 (`31-DEEP-RE-AUDIT.md`, 3517 行) reconcile 完成

## 章节地图

| § | 域 | Agent | 行号 |
|---|---|---|---|
| §A | HJ 帮助手册官方业务定义 | X1 | TBD |
| §B | 5 大 chain 端到端数据流 | X2 | TBD |
| §C | 生产 + BOM + ECN 数据流 | X3 | TBD |
| §D | RBAC + 审批 数据流 | X4 | TBD |
| §E | ≥5 大 design pattern 反向工程 | X5 | TBD |
| §F | 销售话术库 (HJ vs Cretas) | organizer | TBD |
| §G | Cretas 改进 backlog (新增 ↔ 31-doc §P 补充) | organizer | TBD |

---

<!-- Agents append below. Agent X1 → §A. X2 → §B. X3 → §C. X4 → §D. X5 → §E. organizer → §F+§G -->
```

- [ ] **Step 2: Commit skeleton**

```bash
git add 宏见竞品分析/04-最终决策/32-DEEP-RE-AUDIT-V2.md
git commit -m "docs(audit): R-HJ Round 12 32-doc skeleton

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>" -- 宏见竞品分析/04-最终决策/32-DEEP-RE-AUDIT-V2.md
```

- [ ] **Step 3: Dispatch 5 parallel agents (run_in_background=true, all in 1 message)**

每 agent brief 必含:
1. 本 agent 的 scope (§A/§B/§C/§D/§E)
2. snapshots/screenshots 路径 (`round12-snapshots/{prefix}-*.md` + `screenshots/round12/{prefix}/*.png`)
3. 32-doc target path + section marker
4. 输出 template (per spec §4.2)
5. multi-synonym grep rule per 31-doc §O.16 教训
6. ⚠️ 并发写: Read → Edit append, 不 overwrite, agent-X marker 隔离
7. 时间预算: ~45 min, 200-400 字/sub-section

**Agent X1** (帮助手册 → §A):
- Read: `round12-snapshots/help-*.md`, `screenshots/round12/help/*.png`
- Cretas main grep: 业务术语字典对比 (vflag/linkno/decisionType)
- 输出 §A: 12 模块官方定义 / 字段字典 / 状态机 / 触发规则

**Agent X2** (5 大 chain → §B):
- Read: `round12-snapshots/chain-*.md`, `screenshots/round12/chain/*.png`
- 输出 §B: 8 节点端到端数据流图 (mermaid) + 货币源 + 触发链 + linkno 反查

**Agent X3** (生产+BOM+ECN → §C):
- Read: `round12-snapshots/prod-*.md`, `screenshots/round12/production/*.png`
- Cretas grep: BomVersion / ECN / WIP entity + multi-synonym
- 输出 §C: BOM→MRP→WIP→ECN 子流 + 工序条件路由 + SOP 模板

**Agent X4** (RBAC + 审批 → §D):
- Read: `round12-snapshots/rbac-*.md`, `screenshots/round12/rbac/*.png`
- Cretas grep: ApprovalChain / WorkflowDefinition / SpEL / CheckPower
- 输出 §D: 1591 f_no 权限传递 / 126 工作流执行模型 / SpEL 表达式 / 流转规则引擎

**Agent X5** (UI/UX patterns → §E):
- Read: `round12-snapshots/pattern-*.md`, `screenshots/round12/pattern/*.png` + Round 11 §K (UX)
- Cretas grep: 跟 HJ pattern 对应的 Cretas 组件
- 输出 §E: 5+ 大 design pattern 反向工程 (jsPlumb / floating menu / sticky footer / cascading load / 4-chip stack 等) + Cretas gap 表

- [ ] **Step 4: Wait for 5 agents to complete**

Agents run in background (`run_in_background=true`). Receive notifications when each completes. Do NOT poll.

While waiting: 可以 prep §F + §G drafts in parallel (next Task 7).

---

## Task 7: organizer 写 §F 销售话术 + §G Cretas 改进 backlog (~1h)

**Files:**
- Modify: `宏见竞品分析/04-最终决策/32-DEEP-RE-AUDIT-V2.md` (append §F + §G)
- Modify: `宏见竞品分析/04-最终决策/31-DEEP-RE-AUDIT.md` (extend §P 补充 backlog 表 if §G adds items)

- [ ] **Step 1: 写 §F 销售话术库**

Per spec §4.3, 至少 5 个对比场景:
1. 创建销售订单 (HJ 40 字段 vs Cretas 4 + AI auto-fill)
2. 审批流配置 (HJ 复杂 SpEL vs Cretas VueFlow 拖拽)
3. 跨模块查找 (HJ linkno 反查 vs Cretas AIChat NL query)
4. 库存查询 (HJ multi-step navigation vs Cretas SmartBI 1 句话)
5. RBAC 配置 (HJ 1591 f_no 树 vs Cretas canViewPrice 等)

每场景: HJ 路径 + Cretas 路径 + 话术 + 配图引用.

- [ ] **Step 2: 写 §G Cretas 改进 backlog**

Per spec §4.4, ≥5 new backlog items 来自 §A-§E findings. 每 item:
- 来源 §X.Y
- Cretas 现状 (grep main verify)
- 优先级 + 工时
- 落地建议 (加 31-doc §P 补充表第 N 行)

- [ ] **Step 3: Extend 31-doc §P 补充表 (如 §G 加 new items)**

Read `宏见竞品分析/04-最终决策/31-DEEP-RE-AUDIT.md` (find §P section line ~3213-3517), append:

```markdown
## §P.12 Round 12 deep audit 新增 backlog items (本 doc §G 来源)

| # | Item | 来源 | 优先级 | 工时 | Sprint |
|---|---|---|---|---|---|
| ... | ... | 32-doc §G.X | ... | ... | ... |
```

- [ ] **Step 4: Verify 32-doc completeness**

Run:
```bash
grep -nE "^# §[A-G]" 宏见竞品分析/04-最终决策/32-DEEP-RE-AUDIT-V2.md
wc -l 宏见竞品分析/04-最终决策/32-DEEP-RE-AUDIT-V2.md
```
Expected: 7 sections §A-§G all present, total ≥4000 lines.

- [ ] **Step 5: Commit §F + §G + §P 补充**

```bash
git add 宏见竞品分析/04-最终决策/32-DEEP-RE-AUDIT-V2.md \
        宏见竞品分析/04-最终决策/31-DEEP-RE-AUDIT.md
git commit -m "docs(audit): R-HJ Round 12 §F 销售话术 + §G Cretas 改进 backlog

§F: ≥5 对比场景 (HJ 复杂 vs Cretas 简洁) — Boss 演示用话术库
§G: ≥5 新 backlog items (从 §A-§E findings 总结), 加入 31-doc §P
    补充表 (§P.12 Round 12 新增)

32-doc 完成 7 sections §A-§G, 总 N 行.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>" -- 宏见竞品分析/04-最终决策/32-DEEP-RE-AUDIT-V2.md \
                                                                       宏见竞品分析/04-最终决策/31-DEEP-RE-AUDIT.md
```

---

## Task 8: Acceptance + commit final

- [ ] **Step 1: Self-acceptance (per spec §7.1)**

```bash
# 5 大 chain 每节点 ≥ 1 screenshot
ls 宏见竞品分析/06-宏见测试账号深度审计/screenshots/round12/chain/ | wc -l
# Expected ≥8

# 帮助手册 ≥ 5 章
ls 宏见竞品分析/06-宏见测试账号深度审计/screenshots/round12/help/ | wc -l
# Expected ≥5 (1 entry + ≥5 chapters)

# 32-doc 7 sections all present
grep -cE "^# §[A-G]" 宏见竞品分析/04-最终决策/32-DEEP-RE-AUDIT-V2.md
# Expected =7

# §G 至少 5 新 backlog items
grep -cE "^## §G\.[0-9]+" 宏见竞品分析/04-最终决策/32-DEEP-RE-AUDIT-V2.md
# Expected ≥5
```

- [ ] **Step 2: Spot-check 准确性 (per spec §7.2)**

随机选 3 条 §A "业务定义" → 跟 baseline `02-XXX-deep-audit.md` 一致或显式标差异. 用 grep 验证.
随机选 3 条 §E "反向工程" → 跟 `04-UX-PATTERNS.md` 一致或显式 update.

- [ ] **Step 3: Update task #15 完成**

`TaskUpdate taskId=15 status=completed`

- [ ] **Step 4: Final summary report to user**

包含: 32-doc 路径 / 行数 / 7 sections 行号 / 关键 findings 前 5 / §G 新 backlog 列表 / 截图 count / 总耗时 / 后续 Round 13+ 推荐.

- [ ] **Step 5: Optional — update 28-Backlog or MUST_COPY**

如 §G 加了 new backlog items, 决定是否需要 update `28-CRETAS-PRIORITIZED-BACKLOG.md` 或 `MUST_COPY.md` (类似 Round 11 的整合 cleanup).

询问 user 后再做. Default: 标记到 31-doc §P.12 已足够, 28-doc 留给 Round 13+ 整合.

---

## Risks (per spec §6)

| 风险 | 缓解 | 触发处理 |
|---|---|---|
| HJ 帮助手册 entry 不存在 | 用 in-page tooltip 替代 | Task 1 Step 2 alt path |
| HJ live page 改版 vs Round 11 | 标 "2026-05-19 fresh" + cite baseline | Phase 1 各 step note |
| Playwright iframe 嵌套深 | snapshot to file + grep | Default 已 baked in |
| Agent 写 32-doc 冲突 | Read → Edit append + marker | Task 6 Step 3 brief 已含 |
| Cretas grep multi-synonym 漏 | 5 种 keyword (entity/enum/column/camelCase/lower) | Task 6 brief 已含 |
| 时间超 8h | 按优先级砍 1.3 (生产) | Phase 1 ordering 已 reflect |
| 测试账号 timeout | Re-login + restart current step | Task 0 Step 4 |

---

## Total estimate

| Task | 工作 | 时间 |
|---|---|---|
| 0 | Pre-flight | 0.25h |
| 1 | Phase 1.1 帮助手册 | 1h |
| 2 | Phase 1.2 5 chain | 2h |
| 3 | Phase 1.3 生产 | 1h |
| 4 | Phase 1.4 RBAC+审批 | 1h |
| 5 | Phase 1.5 UI/UX pattern | 0.5h |
| 6 | 32-doc skeleton + dispatch 5 agents | 0.25h + agents 跑 ~1.5h 并行 |
| 7 | §F + §G + §P 补充 | 1h (可 parallel with agents) |
| 8 | Acceptance + commit | 0.5h |
| Buffer | 25% | ~2h |
| **总** | | **~10h** |
