# 2026-05-19 — R-HJ Round 12 Deep Re-Audit (帮助手册 + 数据流 + UI/UX) — 设计

> **触发**: Steve "用 superpowers 审计, 再深入检查宏见的网页, 继续深入调研所有的使用逻辑, 查看右上角的操作说明手册, 结合操作说明去优化深入. 要求是更加了解每一个功能的交叉使用, 数据怎么流动的, 所有的按钮和 UI/UX 设计".
> **前置**: Round 11 完成 (31-doc 3517 行, 88 项 backlog reconcile, commit `1839a9d83`).
> **本轮关键差异**: Round 11 是"做什么 (88 项是否 ship)"维度; Round 12 是"HJ 怎么做 (UI/UX/数据流/帮助手册)"维度. 两轮互补, 不重复.

---

## 1. 目标 (Steve sign-off 4-in-1)

1. **Cretas 产品深化** — 看 HJ 怎么做, 标 Cretas UX gap 可优化
2. **销售/Boss 报告** — HJ 复杂 vs Cretas 简洁 话术库
3. **反向工程 design patterns** — 5 大高价值 UX 设计抽出实现细节
4. **全面文档** — 1 份 32-doc, ≥4000 行, 决策面 + 销售面 + 反工程面 全覆盖

## 2. 范围 (Steve sign-off 全量)

### 2.1 数据流 7 大 chain
- 销售→采购→入库→生产→出库→开票→收款→vflag 凭证 (主线)
- BOM→MRP→WIP→ECN (生产子流)
- RBAC: 角色→菜单→按钮→字段 (1591 f_no)
- 审批: 126 工作流 + SpEL 表达式 + 流转规则

### 2.2 UI/UX patterns
- jsPlumb 流程图 tab 自动生成
- 行末"操作 ▼" 浮动菜单 (14+ action)
- Sticky Footer 实时合计
- linkno 跨模块 link counter (8 类)
- 21-tab 客户档案 cascade load
- 4-chip 状态垂直堆
- iframe 6 层嵌套架构
- (其他 capture 时发现的)

### 2.3 帮助手册 (Round 12 独有)
- HJ 帮助 icon (e21 dashboard top-right)
- 12 模块 × 章 × 字段定义
- 业务术语字典 (vflag/linkno/decisionType etc 真实定义)
- 状态机 official 图
- 触发规则 official 描述

### 2.4 不在范围
- 重新审计 88 项 backlog ship 状态 (Round 11 已做)
- HJ 后端代码反编译 (不可能, 闭源)
- HJ 移动 APK 实测 (Round 9 已做 skeleton, Steve 可后续手动)
- 多公司账套 / 期间结账 / 复式记账 (P2 大企业, Cretas 不主推)

---

## 3. 架构 (2-phase pipeline, 复用 Round 11 模式)

```
┌──────────────────────────────────────────────────────────────┐
│ Phase 1 — Organizer Browser Capture (sequential, ~4-5h)      │
├──────────────────────────────────────────────────────────────┤
│ Playwright MCP (single browser, lyh01/admin/Aa123456)         │
│                                                                │
│ Step 1.1: 帮助手册深读 (~1h)                                  │
│   → snapshot 每章 + 截屏 → round12/help/*.md, /*.png         │
│                                                                │
│ Step 1.2: 5 大 chain 端到端 walk (~2h)                       │
│   → 跟 1 真实订单 [00000060] 走 8 节点 → round12/chain/*.md   │
│                                                                │
│ Step 1.3: 生产深入 (~1h)                                      │
│   → BOM/ECN/工序/WIP/作业指导书 → round12/production/*.md     │
│                                                                │
│ Step 1.4: RBAC + 审批 (~1h)                                   │
│   → 角色页/工作流编辑器/SpEL/流转规则 → round12/rbac/*.md     │
└──────────────────────────────────────────────────────────────┘
                            │
                            ▼
┌──────────────────────────────────────────────────────────────┐
│ Phase 2 — Parallel Agent Synthesis (5 agents, ~1h)            │
├──────────────────────────────────────────────────────────────┤
│ X1: 帮助手册 → 32-doc §A (业务定义/字典/状态机)              │
│ X2: 5 大 chain → 32-doc §B (端到端数据流图)                  │
│ X3: 生产+BOM+ECN → 32-doc §C (生产数据流)                    │
│ X4: RBAC + 审批 → 32-doc §D (权限传递+工作流执行)           │
│ X5: UI/UX patterns → 32-doc §E (反向工程 5 design patterns)  │
│                                                                │
│ + organizer 写 §F 销售话术 + §G Cretas 改进 backlog          │
└──────────────────────────────────────────────────────────────┘
                            │
                            ▼
                  32-DEEP-RE-AUDIT-V2.md
                  (≥4000 行, 7 sections §A-§G)
```

---

## 4. 输出 (32-doc 结构)

文件: `宏见竞品分析/04-最终决策/32-DEEP-RE-AUDIT-V2.md`

### 4.1 章节地图

| § | 域 | Agent | 预估行号 | 大致字数 |
|---|---|---|---|---|
| §A | HJ 帮助手册官方业务定义 | X1 | 200-500 | ~6000 |
| §B | 5 大 chain 端到端数据流 | X2 | 500-1200 | ~10000 |
| §C | 生产 + BOM + ECN 数据流 | X3 | 1200-1700 | ~7000 |
| §D | RBAC + 审批 数据流 | X4 | 1700-2300 | ~7000 |
| §E | ≥5 大 design pattern 反向工程 (open-ended, §2.2 列 8 项, agent 可扩) | X5 | 2300-3000 | ~8000 |
| §F | 销售话术库 (HJ vs Cretas) | organizer | 3000-3400 | ~4500 |
| §G | Cretas 改进 backlog (新增 ↔ 31-doc §P 补充) | organizer | 3400-4000+ | ~6000 |

**预计总长**: ≥4000 行 (跟 31-doc 3517 行同量级).

### 4.2 每 agent section template

```markdown
## §X.Y [chain/pattern 名] — [一句话总结]

### HJ 实测细节 (Round 12 fresh capture)
- 入口: 模块 → 子菜单 → 三级
- URL pattern: ...
- button list (含 icon + 跳转/dialog/inline edit + tooltip)
- 字段 list (含 placeholder + validation + 默认值 + 枚举值)
- 状态 + 触发 (谁触发谁改谁)
- 数据流图 (mermaid)

### 帮助手册 official 引用
- 章节 + 关键句 + screenshot path

### Cretas 对比 (grep main, multi-synonym per 31-doc §O.16 教训)
- 同 chain Cretas 现状: ✅/⚠️/❌ 字段对照
- 数据流 gap (HJ 有 Cretas 没的 / 反之)

### 反向工程 (本 chain/pattern 怎么实现)
- 技术栈推测 (jQuery+JSP+iframe+jsPlumb+layui-layer 等)
- 数据结构推测 (linkno=join key / vflag=enum column 等)
- API 形态推测 (workflowroute.jsp?primary=X / clientroute.jsp?id=Y)

### Cretas 改进建议 (新增 backlog item)
- 描述 + 工时估 + Sprint 优先级
- 写入 31-doc §P 补充表 (add 链接)
```

### 4.3 §F 销售话术库 模板 (organizer 写)

```markdown
## §F.X [对比场景: e.g. 创建销售订单]

### HJ 路径
- 步骤: 12 模块 → 销售管理 hover → 销售订单 → 列表 → 新建 button → 弹大 dialog (~40 字段必填)
- 截屏: round12/chain/sales-create.png
- 时间: ~3-5 min/单 (新手)

### Cretas 路径
- 步骤: BentoGrid 销售卡片 → 主屏 → +新建 → 4 字段必填 + AI auto-fill
- 时间: ~30-60s/单

### 销售话术 (Boss/客户演示用)
- "HJ 创建一个销售单要点 12 次 / 填 40 字段 / 3-5 分钟; Cretas 1 句话给 AI / 30 秒 / 自动填充"
- 配图: HJ 截屏 (复杂) vs Cretas 截屏 (简洁)
```

### 4.4 §G Cretas 改进 backlog 模板

```markdown
## §G.X [item: e.g. linkno 跨模块 link counter]

### 来源
- 数据流 §B.3 / pattern §E.2

### Cretas 现状
- ❌ 0 hits / ⚠️ partial / ✅ ship

### 建议优先级 + 工时
- P1, 4d

### 落地建议
- 加 31-doc §P 补充表第 N 行
- Sprint 6 W1 候选

### Sprint 7+ if 不优先
- 跳过
```

---

## 5. 元数据 conventions

### 5.1 screenshots 命名

`宏见竞品分析/06-宏见测试账号深度审计/screenshots/round12/`
- `help/<chapter>-<sub>.png` (帮助手册章节)
- `chain/<order>-<step>.png` (5 大 chain step 截屏)
- `production/<entity>.png` (生产域)
- `rbac/<page>.png` (RBAC 页)
- `pattern/<name>.png` (UI/UX pattern 抽样)

### 5.2 snapshot markdown files

`宏见竞品分析/06-宏见测试账号深度审计/round12-snapshots/`
- 每个 Playwright `browser_snapshot` 大 dump 存 `<step>-snapshot.md`
- 文件名跟 screenshot 一一对应
- 用于 agent Phase 2 读

### 5.3 status 标记 (同 31-doc conventions)
- ✅ SHIPPED · ⚠️ PARTIAL · ❌ NOT DONE · 🟡 IN-FLIGHT · 🔵 已有基础待优化
- ⭐ 高价值 pattern / 🚨 客户痛点 / 💡 反工程 finding

---

## 6. Error handling / fallbacks

| 风险 | 缓解 |
|---|---|
| HJ 帮助手册不存在 / 链接失效 | Phase 1.1 改为捕 in-page tooltip / sub-menu hover hint 替代 |
| HJ live page 改版 (HJ active 维护中) | 引用 Round 11 baseline + new finding 并列, 标 "2026-05-19 capture" |
| Playwright iframe 嵌套深 snapshot truncate | snapshot to file (`filename` param), grep 关键字 instead of inline parse |
| Agent 写 32-doc 冲突 (并发 append) | 同 Round 11: agent 先 Read 再 Edit append, agent-X marker 隔离 |
| Cretas main grep multi-synonym 还漏 | per 31-doc §O.16 教训: grep entity + enum + column + camelCase + lower 5 种 |
| 时间超 8h | Phase 1 优先级: 1.1 帮助手册 > 1.2 5 chain > 1.4 RBAC+审批 > 1.3 生产. 不够时砍 1.3 |
| 测试账号被 lock / session timeout | 重新 login, snapshots 已 cache 大部分, restart Phase 1.X 当前 step |

---

## 7. Testing / acceptance

### 7.1 自验
- [ ] 5 大 chain 每节点 ≥ 1 screenshot + 1 snapshot.md
- [ ] 帮助手册 ≥ 5 章 captured
- [ ] 32-doc 7 sections (§A-§G) 全 present
- [ ] §G 至少新增 5 项 Cretas backlog item, 加 31-doc §P 补充表

### 7.2 spot-check 准确性
- 随机选 3 条 §A "业务定义" → 跟 baseline 02-XXX-deep-audit.md 一致或显式标差异
- 随机选 3 条 §E "反向工程" → 跟 04-UX-PATTERNS.md 一致或显式 update

### 7.3 用户验收
- Steve 读 §F 销售话术: 真能用于 Boss 演示?
- Steve 读 §G 改进 backlog: 真能加入 Sprint 5+?

---

## 8. 时间预算

| Phase | 工作 | 预算 | 累计 |
|---|---|---|---|
| 1.1 | 帮助手册深读 | 1h | 1h |
| 1.2 | 5 大 chain walk | 2h | 3h |
| 1.3 | 生产深入 | 1h | 4h |
| 1.4 | RBAC + 审批 | 1h | 5h |
| 2.X | 5 agents parallel synthesis | 1.5h (各跑 1h, 并行) | 6.5h |
| Closer | §F + §G + 聚合 | 1h | 7.5h |
| Buffer | 25% | 2h | 9.5h |
| **总** | | | **~10h** |

跨 1-2 工作日完成. Steve sign-off 6-10h 范围内.

---

## 9. 后续 (Round 13+ optional)

Round 12 完成后:
- **Round 13**: HJ 移动 APK 实测 (Round 9 skeleton 已 ready, 待 Steve 装 APK)
- **Round 14**: 真去比对 Cretas 跟 HJ 同一 chain 端到端 (用户验收测试形态)
- **Round 15**: 整合 Round 11+12+13+14 写"Cretas vs HJ Final Benchmark Report" (Boss 决策面 1-2 页)

但 Round 12 完成后 audit 已经够 saturated, 后续 Round 13+ 由 Steve 触发.

---

## 10. 决策记录 (本 brainstorm session)

| 问题 | Steve 选择 |
|---|---|
| 末端目的 | ALL 4 (Cretas 改进 + Boss 报告 + 反工程 + 全面文档) |
| 数据流范围 | ALL (5 大 + 生产 + RBAC + 审批) |
| 执行模式 | A (organizer browser + N agents 并行, 同 Round 11 模式) |
| Output | 32-doc 单文件 (7 sections §A-§G) |
| Sprint 6+ 触发 | Round 13+ 不在本 spec 范围 |

---

**Spec written 2026-05-19 by organizer (本 session). Ready for review.**
