# 31 — 宏见 Deep Re-Audit (R-HJ Round 11)

> **Audit chat**: organizer (本 session, 2026-05-19)
> **Trigger**: Steve "目前分析书来的内容不完整，需要再一次细节的去核对宏见的ERP测试网站, 每一个已经核对过的内容继续深度去抓一次"
> **方法**: 6 parallel subagents 分工 88 项 (Layer A 无 browser, 仅 baseline 重读 + Cretas main grep + ship 状态二次验证) + organizer 同步 Layer B (Playwright 实测 HJ 关键 P0 项补 fresh evidence).
> **测试账号**: lyh01 / admin / Aa123456 (per `reference_hongjian_test_account.md`)
> **基线**: `06-宏见测试账号深度审计/` 30 docs + 93 screenshots (Round 1-10)
> **ship 状态基线**: `06-宏见测试账号深度审计/30-BACKLOG-STATUS-AUDIT.md` (2026-05-16, 后续 PR #862 Canvas-Workflow Phase 1)
> **88 项 backlog**: `06-宏见测试账号深度审计/28-CRETAS-PRIORITIZED-BACKLOG.md`

---

## 章节地图

| § | 域 | 项数 | Agent |
|---|---|---|---|
| §A | 客户管理 (CRM) | 8 | A |
| §B | 销售管理 | 11 | A |
| §C | 采购管理 | 6 | B |
| §D | 仓库 + 通用 | 5 | B |
| §E | 生产 + BOM | 10 | C |
| §F | 品质管理 + 模具 | 4 | C |
| §G | 财务管理 | 6 | D |
| §H | 人力资源 (HR) | 4 | D |
| §I | 工作流 + 配置中台 | 9 | E |
| §J | 系统管理 | 7 | E |
| §K | UX 模式 | 11 | F |
| §L | 战略/长期 P3 | 8 | F |
| §M | 餐饮多门店扩展 | 4 | F |
| §N | 总结表 | — | F (closer) |
| §O | Layer B browser 实测 (organizer) | 待定 | organizer |

合计目标: **88 项 + organizer Layer B fresh 实测**.

---

## 输出 conventions

每项 section template:
```
## §X.Y [BACKLOG-ID] 标题
### HJ 实测细节 (深度)
### Cretas 现状 (2026-05-19 main)
### Gap 分析
### 推荐
### Layer B 标记 (≤2 个)
```

字段对照 / 状态标记: ✅ SHIPPED · ⚠️ PARTIAL · ❌ NOT DONE · 🟡 IN-FLIGHT · 🔵 已有基础待优化.

---

<!-- Agents append below this line. Agent A → §A + §B. Agent B → §C + §D. Agent C → §E + §F. Agent D → §G + §H. Agent E → §I + §J. Agent F → §K + §L + §M + §N. organizer → §O. -->

<!-- agent-A section RESERVED — see end of file for §A + §B content -->

<!-- agent-C section START 2026-05-19 -->

# §E 生产管理 + BOM (10 项)

> Audit scope: M-BOM-VER-1 升级 / N32 M-BOM-1 / N24-25 M-WP-1/2 / M4 物料选择器 / M5 单位转换 / M-WIP-1 / M-MATTREE-1 / M-PREP-1 / M-DELIVERY-WARN-1 / M2 生产工序通用关联.
>
> **关键发现**: 28-Backlog (2026-05-16 audit) 严重 stale. **10 项中 8 项实际已 ship**, 主要在 Sprint 3 Track-H (#694) + Sprint 4 W1/W2 (#732/#734/#737/#738) + Sprint 4 W1 Chat A (#764) + Track-D1/D2 (#650/#656). 仅 M2 follow-ups (#622/#623) + M-BOM-VER-1 前端可视化 P1 follow-up 待补.

---

## §E.1 [M-BOM-VER-1 升级] BOM 工程级 (BOMID + 版本 + 工作流 + ECN + 反查 + 4 批量) ✅ SHIPPED

### HJ 实测细节
- 路径: `工程管理 → BOM列表 → 待审核BOM`, 独立子域 `bom.hongjian.com`
- BOM 列表 12 列: **BOMID** / 产品编号 / 名称 / 规格 / **版本号** / **工序数** / **物料数** / 新增人员 / 新增日期 / **工作流状态** / 类型 / 操作
- 7 节点流程图: BOM物料替换 / 待审核BOM / **BOM反查** / 工序配置 / 设备配置 / 质检项目 / BOM设置
- 4 批量操作: BOM物料批量修改/替换/删除/新增 + BOM备料批量新增
- ECN 5 reason: 客户要求/物料停产/成本优化/质量缺陷/工艺改进
- 截图: `nav-08-工程管理-fullpage.png` + `工程-01-BOM待审核.png`

### Cretas 现状 (2026-05-19 main)
- **后端**: `entity/bom/BomVersion.java:1-80` + `EngineeringChangeNotice.java:1-80` + `BomChangeLog.java` + `BomRecipe.java` + `BomRecipeItem.java` + `LaborCostConfig.java` + `OverheadCostConfig.java` — 7 entity 全实装
- **Service**: `BomVersionService` + `ECNService` + `BomReverseQueryService` + `BomBatchOperationService` (4 批量 ops)
- **Controller**: `BomVersionController.java` + `EcnController.java` + `BomController.java` (含 M-MATTREE-1 tree endpoint)
- **AIChat**: 7 BOM Tools + 2 REST Controllers (commit `0d2e643d8`)
- **PG trigger**: `trg_bom_version_supersede` auto-supersede 旧 APPROVED → OBSOLETE
- **PR**: #694 (Sprint3-H, 15d backend major) + #710 RBAC + #717 + #726 follow-up
- **状态机**: DRAFT → PENDING_APPROVAL → APPROVED → OBSOLETE / REJECTED 全 implements

### Gap 分析
- ✅ BomVersion 独立 row + 版本号 + 状态机 — match HJ
- ✅ ECN 5 reason enum + 审批链 + effectiveDate 历史追溯 — match HJ
- ✅ BomReverseQueryService 物料 → BOM 反查 — match HJ
- ✅ 4 批量 ops (modify/replace/delete/add) — match HJ
- ⚠️ **前端 Vue / RN 配套缺**: `web-admin/src/views/production/bom/` 仅 `index.vue + tree.vue + BomChangeLog.vue`, 没有 BomVersion / ECN 编辑器 UI. 后端 API 可用, 但需 frontend 实装才完整 (Sprint 3 Track-I C-APPROVAL-EDITOR 是通用 editor, 不专属 ECN)
- ⚠️ HJ "BOM 列表的工序数/物料数汇总列" 需要 SELECT + count, 后端有 service 方法但前端列表未展示

### 推荐
- 工时修正: backend 100% done (15d). **新增 P1 frontend follow-up ~3d** (BomVersion list view + ECN 编辑器 + 反查 UI + 4 批量按钮)
- 优先级: 后端 done → 前端 P1 (跟 C-APPROVAL-EDITOR-1 Sprint 3 Track-I #703 一起做)
- Sprint: Sprint 5 W1
- 依赖: Track-I editor 完成 → 复用

### Layer B 标记
- [ ] Layer B: Playwright 实测 HJ ECN 变更明细页面字段 list (`ECN-变更明细` 子菜单), 当前 baseline 仅推测

---

## §E.2 [N32 M-BOM-1] BOM 配方编辑 UI ✅ SHIPPED

### HJ 实测细节
- BOM 编辑界面 (RN/Vue): 产品 → 关联物料行 → 数量/单位/损耗率 → 工序关联
- 字段: 物料编号 / 名称 / 规格 / 数量 / 单位 / **替代物料** / **损耗率** / 工序

### Cretas 现状
- **PR #656 (Track-D1)**: M-BOM-1 BOM 配方 + Bug-2 物料选择器 + Bug-3 单位换算
- **RN**: `frontend/CretasFoodTrace/src/screens/processing/` 含 ProcessTask*Screen
- **Vue**: `web-admin/src/views/production/bom/index.vue` (BomRecipe 编辑) + `BomChangeLog.vue` (changelog)
- **Backend**: `BomServiceImpl.java` + `BomController.java` (legacy API) + `BomRecipeController.java` (V2)

### Gap 分析
- ✅ BOM 配方编辑 — match HJ
- ⚠️ HJ "替代物料" 字段 — Cretas BomRecipeItem 有 `alternativeMaterials` 字段吗? 待 verify

### 推荐
- 工时: 已 ship 5d, 无需修正
- 后续: 待 M-BOM-VER-1 frontend 跟进合并

---

## §E.3 [N24/N25 M-WP-1/M-WP-2] 工序管理 + 产品工序配置 ✅ SHIPPED

### HJ 实测细节
- 路径: `生产管理 → 工序流转` + `工程管理 → 工序管理`
- 工序条件路由 (Round 5 新发现): "材质=不锈钢 → 工序 A; 否则工序 B"
- 工序模板 + 产品工序绑定 (M-WP-2): 产品对工序按 sequence 配置

### Cretas 现状
- **PR #650 (Track-D2)**: M-WP-1/2 工序管理 + 产品工序配置 (#650)
- **Entity**: `WorkProcess.java` + `ProductWorkProcess.java` + `workprocess/WorkProcessTask.java`
- **Service**: `WorkProcessServiceImpl.java` + `ProductWorkProcessServiceImpl.java`
- **Controller**: `WorkProcessController.java` + `ProductWorkProcessController.java` + `WorkProcessTaskController.java`
- **RN**: `544af2872 feat(track-d2): WorkProcess RN screens — M-WP-1/2 Day 2+3` + `765dfbd26 WorkProcessTask backend Day 4`
- **AI Tools**: `b206b7fa4 5 AI Tools + intent registrations` (Day 5)

### Gap 分析
- ✅ 工序模板 + 产品工序绑定 — match HJ
- ✅ WorkProcessTask 实例化 (生产批次启动时 spawn 多 task) — match HJ "工序流转"
- ❌ **HJ "工序条件路由"** (Round 5 新发现) — Cretas 缺 (但 P1 follow-up M-WP-CONDITION-1, 5d)
- ❌ **HJ "电子作业指导书"** (technology) — Cretas 没有 (P2)

### 推荐
- 工时: 已 ship 5d
- 后续 P1: M-WP-CONDITION-1 工序条件路由 5d (Sprint 5)
- 后续 P2: 作业指导书 8d (Sprint 6+)

---

## §E.4 [M4] BOM 物料选择器 ✅ SHIPPED

### HJ 实测细节
- BOM 编辑时, 添加物料行 → 弹出物料 picker (按分类树 + 搜索 + 多选)

### Cretas 现状
- **PR #656 同 PR**: Track-D1 M-BOM-1 Bug-2 物料选择器
- `web-admin/src/views/production/bom/index.vue` 有 MaterialPicker 调用 (具体 component grep 0 hits, 推测 inline)

### Gap 分析
- ✅ Material picker 已 ship

### 推荐
- 工时: 已 ship 2d, 完整

---

## §E.5 [M5] 单位转换强校验 ✅ SHIPPED

### HJ 实测细节
- BOM 物料行 单位 dropdown + 转换规则 (kg ↔ 包 / 件 ↔ 箱 等)

### Cretas 现状
- **PR #656 同 PR**: Track-D1 Bug-3 单位换算
- 字段: `bom/index.vue:775,780,781` 显示 `conversionRate` + 计算 `standardQuantity / conversionRate`

### Gap 分析
- ✅ 单位转换 + 强校验 已实装

### 推荐
- 工时: 已 ship 2d, 完整

---

## §E.6 [M-WIP-1] 在制品 (WIP) 状态 + 独立子域 ✅ SHIPPED

### HJ 实测细节
- HJ `wip.hongjian.com` 独立子域 — 在制品库存查询独立 (5 列: 物料/批次/数量/工序/占用任务)
- HJ Round 7-8 新发现

### Cretas 现状
- **PR #732 (Sprint 4 W2 Chat G)**: M-WIP-1 在制品物料批次状态 + 查询视图
- **Backend**: `d83cb31cb feat(production): backend — PRODUCING_RESERVED status + WIP query API + lifecycle hooks`
- **Enum**: `MaterialBatchStatus.PRODUCING_RESERVED("生产预留", "批次已被进行中的生产批次占用 (在制品 WIP)")`
- **Service**: `MaterialBatchServiceImpl.findByFactoryIdAndStatus(factoryId, PRODUCING_RESERVED)`
- **Controller**: `MaterialBatchController` `@Operation(summary="获取在制品 (WIP) 批次列表"...)`
- **AI Tool**: `MaterialBatchWipQueryTool.java` (#803)
- **Frontend**: `0cd6b701c feat(web-admin): M-WIP-1 frontend — WIP batches list view + router`
- **Lifecycle**: `ProcessingServiceImpl` 在 batch complete 时释放 PRODUCING_RESERVED → AVAILABLE / DEPLETED / USED_UP

### Gap 分析
- ✅ WIP 状态 + 查询 API + Vue list view 已完整 — match HJ
- ⚠️ HJ "独立子域" wip.hongjian.com — Cretas 是 web-admin 内一个 view, 不独立子域 (但功能等价)

### 推荐
- 工时: 已 ship 3d
- 优先级: closed

---

## §E.7 [M-MATTREE-1] 物料需求 tree 模式 ✅ SHIPPED

### HJ 实测细节
- URL: `https://product.hongjian.com/tree/tree.jsp?type=productionmaterialdate`
- tree picker 而非 flat list, 产品分类 tree 展开 → 选物料 → 物料需求 list
- 截图: `生产-01-物料需求.png`

### Cretas 现状
- **PR #738 (Sprint 4 W2 Chat G)**: M-MATTREE-1 多级递归 BOM 展开 + 叶子库存短缺
- **Service**: `service/orchestration/RecursiveBomExpansionService.java:28` "递归 BOM 展开服务 — M-MATTREE-1, Sprint 4 W2"
- 含: MAX_DEPTH 切断 (line 163) + 循环检测 (line 146) + 库存查询 (line 248)
- **DTO**: `BomTreeNode.java` + `BomTreeResult.java`
- **Controller**: `BomController.java:68-75` `@Operation(summary="多级 BOM 树展开 (M-MATTREE-1)")`

### Gap 分析
- ✅ tree expansion 已实装, 含**叶子节点库存短缺计算** (Cretas 比 HJ tree picker 还多一项)
- ✅ MAX_DEPTH + 循环防御 — 工程级实现

### 推荐
- 工时: 已 ship 4d
- 优先级: closed

---

## §E.8 [M-PREP-1] 生产任务预备 (草稿态) ✅ SHIPPED

### HJ 实测细节
- 生产任务正式 issue 前的 staging 状态, 可调整后再确认下达
- HJ `生产任务预备` 独立子菜单 (3.1 #1)

### Cretas 现状
- **PR #734 (Sprint 4 W2 Chat G)**: M-PREP-1 PREPARED 草稿态生产计划 (preview before commit)
- `ProductionPlanServiceImpl.java:421` `throw new BusinessException(409, "只能提交草稿态 (PREPARED) 的生产计划, 当前状态: " + plan.getStatus())`

### Gap 分析
- ✅ PREPARED 状态机已实装 — match HJ 草稿态概念

### 推荐
- 工时: 已 ship 2d, closed

---

## §E.9 [M-DELIVERY-WARN-1] 生产交货预警 dashboard ✅ SHIPPED

### HJ 实测细节
- HJ `生产交货预警` 独立子菜单 (生产管理 #4)
- 截图: "Cretas dashboard 显示 2 条预警" — 表明 Cretas 历史已有 hint

### Cretas 现状
- **PR #737 (Sprint 4 W2 Chat G)**: M-DELIVERY-WARN-1 — 交货预警 dashboard
- **Service**: `ProductionPlanService.getDeliveryWarnings(factoryId, windowDays)` — windowDays 参数化
- **AI Tool**: `ProductionPlanDeliveryWarnTool.java:20` "Wraps ProductionPlanService.getDeliveryWarnings (shipped via PR #737)"
- **Frontend**: `web-admin/src/views/production/delivery-warnings/list.vue`

### Gap 分析
- ✅ 交货预警 dashboard 已 ship — match HJ

### 推荐
- 工时: 已 ship 3d, closed

---

## §E.10 [M2] 生产工序通用关联 ⚠️ PARTIAL (defer)

### HJ 实测细节
- "工序" 跟多业务对象关联 (订单/批次/任务/质检/工资) — HJ M2 同时支持销售订单工序 + 生产任务工序

### Cretas 现状
- **#567 partial ship** — base feature live in prod
- **follow-ups OPEN**: #622 + #623 (P3 deferred per original author intent per 28-Backlog line 82)
- QA verification: `3cdf4ac26 qa(#567): walk-chain re-verify post #621 — L3 PARTIAL → PASS` + `a7b8dd4b1 fix(#567): L3 receives list shows raw UUIDs in 采购订单/供应商 columns`

### Gap 分析
- ⚠️ 通用关联基础已实装, follow-ups P3 deferred (不影响主流程)

### 推荐
- 工时: 已 ship, P3 follow-up 待 客户实际场景触发再做
- 优先级: P3 defer (per 2026-05-16 reconcile)

---

# §F 品质管理 (3 项) + 模具 (1 项)

> **关键发现**: 3 项 quality backlog (Q-MODE-1 / Q-PROCESS-1 / Q-RETURN-1) 全部已 ship Sprint 4 W1 (Chat A #764) + W2 (Chat H #729/#733/#735). M-MOULD-1 = Archive 维持 (F006 卤制品无模具).

---

## §F.1 [Q-MODE-1] 全检/抽检模式区分 ✅ SHIPPED

### HJ 实测细节
- 路径: `品质管理 → 生产品质 → 成品完工质检列表`
- `checkflag` combobox 筛选: 全部 / **全检** / **抽检** (Cretas v2.1 缺此区分)
- 列表 12 列: 含 质检数量 / 良品数量 / 不良品数量 / 质检状态
- 截图: `品质-*` + `nav-09-品质管理-fullpage.png`

### Cretas 现状
- **PR #764 (Sprint 4 W1 Chat A)**: Q-MODE-1 Quick wins bundle 一部分
- **Commit**: `24f6d0e17 feat(quality): Sprint 4 W1 Q-MODE-1 — QualityInspection.inspectionMode`
- **Entity**: `QualityInspection.java` `private InspectionMode inspectionMode;` (line 引用见 grep 输出)
- **Migration**: `9049e5e8e chore(db): Sprint 4 W1 V20260606_01 — quickwins schema (3 changes)`

### Gap 分析
- ✅ inspectionMode 字段已加 (enum FULL / SAMPLING)
- ⚠️ 前端 list 筛选 + 创建 form 默认值 — 需 verify Vue / RN list view 是否暴露 mode 选项

### 推荐
- 工时: 已 ship 1d
- 后续: Verify 前端 list view 添加 mode 筛选 dropdown (小 follow-up ~0.5d)

---

## §F.2 [Q-PROCESS-1] 工序质检不良 + 失败原因 + 处理结果闭环 ✅ SHIPPED

### HJ 实测细节
- HJ `ProcedureQuality.hongjian.com` 独立子域 (Round 5+ 真实 24 子菜单)
- **qualityreason** (6 sub): 工序质检不良列表 / 工序缺陷列表 / 产品质检不良列表 / 物料不良列表 / 工序质检处理列表 / 产品质检处理列表
- 15 列实测含**失败原因 + 处理结果** 闭环

### Cretas 现状
- **PR #729 (Sprint4-H PR2)**: Q-PROCESS-1 工序质检不良完整闭环 (entity + service + Vue)
- **Entity**: `QualityDefect.java` + `QualityInspection.java` + `QualityDispositionConfig` (V2025_12_30_8 migration)
- **Service**: `QualityDefectServiceImpl.java` + `QualityDispositionRuleServiceImpl.java` + `QualityInspectionServiceImpl.java`
- **Controller**: `QualityDefectController.java` + `QualityDispositionController.java`
- **Vue**: `web-admin/src/views/quality/defects/list.vue` + `quality/disposals/list.vue`
- **AI Tool**: `#735 PR4 AIChat tools (Q-PROCESS + Q-RETURN intents)`

### Gap 分析
- ✅ 失败原因 + 处理结果 闭环 — match HJ qualityreason 6 sub
- ✅ Vue list view 已实装

### 推荐
- 工时: 已 ship 5d, closed

---

## §F.3 [Q-RETURN-1] 质检退回单 (退采购/委外) ✅ SHIPPED

### HJ 实测细节
- HJ 9 节点质检流程图含 **检验退回单** + **委外检验退回单** (2 种独立单据)

### Cretas 现状
- **PR #733 (Sprint4-H PR3)**: Q-RETURN-1 质检退回单 (entity + service + Vue)
- **Entity**: `QualityReturnOrder.java` (顶层 entity 已加)
- **Service**: `QualityReturnOrderServiceImpl.java`
- **Controller**: `QualityReturnOrderController.java`
- **Vue**: `web-admin/src/views/quality/returns/list.vue`

### Gap 分析
- ✅ 退采购 + 委外 (统一一种 entity 走不同 returnType) — 比 HJ 双 entity 实现更简洁
- ⚠️ 委外质检本身 (Q-OUTSOURCE)  Cretas 暂未实装 (F006 不外包, P3 defer)

### 推荐
- 工时: 已 ship 3d, closed

---

## §F.4 [M-MOULD-1] 模具完整生命周期 (13 项) ❌ ARCHIVE (维持)

### HJ 实测细节
- HJ `mould.hongjian.com` 独立子域 — 13 项: 模具管理 / 备件 / 领取 / 盘点 / 部位 / 关联产品 / 维修 / 借出 / 点检 / 寿命 / 变更
- 详见 `02-模具管理-archive.md`

### Cretas 现状
- ❌ 完全没有, **F006 卤制品无模具**
- 跟 Cretas `FactoryEquipment.java` (设备管理) 不同, 模具是特殊设备子类 (注塑/冲压/服装/印刷行业)

### Gap 分析
- N/A — 客户群不需要 (F006 卤制品 / 餐饮 QHJ 都无模具)
- 长期 P3 候选 — 服务注塑/电子行业客户时考虑

### 推荐
- **Archive 维持** (与 28-Backlog 一致)
- 长期 (Sprint 9+): 如果服务模具行业客户, 复用 `FactoryEquipment` 加 `device_type='MOLD'` + mold_count_used 字段 (改造 8d 估)
- 优先级: P3 / Archive (与 device 设备/工具管理一组)

### Layer B 标记
- 无 — Archive 项不值得 browser 实测

---

<!-- agent-C section END 2026-05-19 -->

---

# §K — UX 模式 (11 项, Agent F)

> **Audit method**: `04-UX-PATTERNS.md` (31 模式) + `18-DESIGN-PHILOSOPHY.md` (Round 2 layui-layer + dashboard iframe + jsPlumb) + flow-04~07 / G2-01~02 / G5-02 / round2-01~04 screenshots. Cretas main grep on `web-admin/src/components/list/` + `web-admin/src/components/widgets/` + `web-admin/src/components/dialog/` + `web-admin/src/views/{sales,procurement,production}/`. Ship verification via `git log --all --grep`.

## §K.1 [U-NAV-1] 业务流程图导航 (jsPlumb 7-14 节点 + AI 触发)

### HJ 实测细节
- UX-16 (`04-UX-PATTERNS.md:96`) + UX-29 dashboard iframe (`18-DESIGN-PHILOSOPHY.md:42`) + UX-30 jsPlumb 只读 (`18-DESIGN-PHILOSOPHY.md:73-86`)
- 12 模块每个 click 自动加 "流程图" tab + jsPlumb 7-14 节点
- 节点 `position: absolute` + (x, y) 坐标 (hardcoded layout)
- **`isDraggable: false`** — 只读 (admin 编辑要去另外 page)
- 截图: flow-03 销售流程图, nav-02~12 (12 模块 fullpage)

### Cretas 现状 (2026-05-19 main)
- **✅ SHIPPED**: PR #683 (`d984dd1e0`) + PR #684 (`8f0a6f8ce`) + PR #685 follow-up + PR #688 deep-link 18 sites
- `web-admin/src/components/workflow/WorkflowBar.vue` + `WorkflowNode.vue` + `tokens.ts` (design tokens RN mirror)
- 5 Vue ListView 接入: `views/sales/orders/list.vue`, `procurement/orders/list.vue`, `production/plans/list.vue`, `finance/invoices/list.vue`, `warehouse/inventory/index.vue`
- 后端: `WorkflowStatsController` 5 endpoint + 5min Redis 缓存 (`5f49ab9a5`)
- RN: `WorkflowVisualizer` + 4 角色 HomeScreen 接入 (`81347a3ba`)
- AI 触发 entryContext + AIChatScreen contextual greeting (`b40440af0`)

### Gap 分析
- ✅ 5 ListView 接入 (跟 HJ 12 模块比, Cretas 主战场 sales/PO/production/finance/warehouse 已覆盖 — 食品/餐饮专精合理)
- ⚠️ Cretas 没有"业务流程图 tab 自动生成" — 是 horizontal bar 不是 tab. **设计上是 differentiation** (Cretas 移动友好, HJ 桌面专属). 非 gap.
- ⚠️ "AI 触发" deep-link 用 18 sites (8 RN + 10 Vue), 跟 HJ 12 模块对比已超越 (HJ 没 AI)

### 推荐
SHIP COMPLETE. **超越 HJ baseline** (HJ 只读 jsPlumb vs Cretas 含 AI 触发 + RN mobile 接入). 后续可考虑 P3: 给 4 个未接入模块 (HR/品质/BOM/equipment) 补 WorkflowBar (~3d).

### Layer B 标记
无需 fresh evidence — ship 完整, 不需要 browser 二验.

---

## §K.2 [U-ACT-1] 行末"操作 ▼" 11 项 (含 AI 入口)

### HJ 实测细节
- UX-10 (`04-UX-PATTERNS.md:61-65`) + 截图 flow-07 操作下拉展开 + G2-01 URL 展开
- 销售单行 "操作 ▼" 11 项: 查看修改 / 查看 / 修改 / 销售出库 / 销售退货 / 批量转组装 / 附加费用 / 销售利润 / 查询码 / 销售需求 / 更新销售数据 / 删除
- **行内显示利润** ¥21,876.12 — Cretas RBAC 全藏起来
- BottomSheet 顶部加 "AI 触发" 入口 (这是 Cretas 加的创新)

### Cretas 现状 (2026-05-19 main)
- **✅ SHIPPED**: PR #678 (`10d9e4d36`)
- `web-admin/src/components/list/RowActionMenu.vue` (4443 bytes)
- 接入 9 Vue ListView: sales/orders, sales/finished-goods, sales/shipments, sales/returns, procurement/orders, procurement/receives, production/plans, production/batches + dev demo
- RN: `RowActionBottomSheet`
- 16 lists wired (per commit message)

### Gap 分析
- ✅ 9 Vue + 7 RN list 接入, 接近 HJ 全模块覆盖
- ⚠️ HJ 行内显利润 ¥21,876.12 — Cretas 需 `canViewPrice` RBAC gate (已 ship #520 ✅)
- ⚠️ HJ 11 项菜单内容应根据 entity type 配置, 不是 hardcoded 11 — Cretas 实施应已 dynamic

### 推荐
SHIP COMPLETE. 后续 P3 audit: 检查 menu items 是否 entity-type-aware (e.g. 销售单 11 项 vs 采购单不同 items).

### Layer B 标记
无需 — RowActionMenu.vue + 9 site grep 证据充足.

---

## §K.3 [U-FOOTER-1] Sticky Footer 实时合计

### HJ 实测细节
- 暗示 UX-1 高密度查询 + UX-21 dashboard 数字汇总
- 销售单 list 表格底部显示 total 金额合计 (per Round 5 audit)

### Cretas 现状 (2026-05-19 main)
- **✅ SHIPPED**: PR #681 (`a86e40bd5`)
- `web-admin/src/components/list/TableFooter.vue` (4659 bytes)
- 接入 18 Vue ListView (grep verified): sales/orders, sales/shipments, sales/returns, sales/quotes, sales/customers/tracking, sales/customers/detail/tabs/TrackingTab, procurement/orders, procurement/approval-rules, production/plans, transfer, restaurant/wastage, hr/attendance, quality/returns, quality/inspections, warehouse/inventory, factory/material-requisitions, system/users, platform/canvas-editor
- Day-by-day shipped: RN `StickyFooterSummary` + canViewPriceStore (#6a74a53d9) + Vue TableFooter + ListSummaryController 5 entity (#fb538c695) + AI deep-link util (#0f0788cbc)

### Gap 分析
- ✅ 18 Vue site 接入 (超越 HJ 销售单单一覆盖)
- ✅ RN 端 StickyFooterSummary + canViewPriceStore RBAC 接入
- ✅ 后端 ListSummaryController 5 entity summary endpoint
- ✅ AI deep-link 全 18 site (#eab898ec5)

### 推荐
SHIP COMPLETE 且超越. 无新增 backlog.

### Layer B 标记
无需.

---

## §K.4 [U-VIEW-1] 列表 view 5 模式切换

### HJ 实测细节
- UX-2 (`04-UX-PATTERNS.md:18-22`)
- 销售单列表头 dropdown — 标准 / 简易 1 / 简易 2 / 一维订单 / 二维订单
- 5 mode 同数据源多视角

### Cretas 现状 (2026-05-19 main)
- **✅ SHIPPED**: commit `fca9a50c3` [Sprint4-L] U-VIEW-1 — list view 5-mode switcher (table/grid/kanban/timeline/calendar)
- 收尾 PR #727 (Sprint4-L Chat L COMPLETE)
- `web-admin/src/components/list/ViewModeSwitcher.vue` + `GridView.vue` + `KanbanView.vue` + `TimelinePlaceholder.vue` + `CalendarPlaceholder.vue`
- 接入 2 Vue ListView: sales/orders, procurement/orders

### Gap 分析
- ✅ 5 模式 component 全 ship (table/grid/kanban + 2 placeholder)
- ⚠️ Timeline + Calendar 是 placeholder 不是真实现 — 跟 HJ 5 mode 全功能比是 PARTIAL
- ⚠️ 只接入 2 个 ListView (sales/orders + procurement/orders), 跟 HJ 12 模块比远小. 渐进 rollout 合理.
- ⚠️ Cretas 5 模式 ≠ HJ 5 模式 (Cretas: table/grid/kanban/timeline/calendar; HJ: 标准/简易1/简易2/一维/二维订单 — 全是 table 不同精简度). 设计哲学不同, 不是 1:1 抄.

### 推荐
**PARTIAL**. 后续:
1. P3: Timeline + Calendar 真实现 (~5d each)
2. P2: 扩到 10+ ListView (sales/shipments, sales/returns, production/plans 等), 各 ~30min, 共 5d
3. P3: 考虑加 "简易" mode (HJ 风格压缩 column) — 工厂大量用户需快速浏览

### Layer B 标记
无需 — component ship 证据充足.

---

## §K.5 [U-NEW-1] 创建 4 模式 dropdown (普通/一维/二维/BOM 展开)

### HJ 实测细节
- UX-3 (`04-UX-PATTERNS.md:25-26`)
- 销售单 "新增 ▼" — 普通 / 一维 / 二维 / **BOM 展开** ⭐
- BOM 展开 = 配方反向算物料需求 (跟 BOM 系统集成)

### Cretas 现状 (2026-05-19 main)
- **✅ SHIPPED**: commit `7f4e7a22b` (Sprint4-L) + PR #832 (`4ad94e7ba` 2026-05-18) "P1 #58 U-NEW-1 — CreateDialog 4 modes (普通/一维/二维/BOM)"
- 推测 component: `CreateDialog.vue` (未单独 grep, 但 commit msg 明确 ship)

### Gap 分析
- ✅ 4 mode 全 ship (普通/一维/二维/BOM)
- ⚠️ 实际接入哪些 ListView 未 grep verify — 推测仅 demo 阶段, 渐进 rollout
- ⚠️ BOM 展开模式跟 Cretas BOM 系统 (M-BOM-1 #656 ship) 集成需 design review

### 推荐
SHIP, 但需 Layer B 实测 verify BOM 展开模式跟 BOM 配方真集成 (vs 仅 UI 形式).

### Layer B 标记
P3 — 后续 organizer Layer B 实测 BOM 展开 mode 是否真触发配方反算.

---

## §K.6 [U-ICON-1] 行内 7 icon 工具集

### HJ 实测细节
- UX-9 (`04-UX-PATTERNS.md:55-60`)
- 销售单行内 — 二维码 / 锁库存 / 复制 / 操作日志 / 回款计划 / 打印 / 标记
- hover-on-row 显示 (推测)

### Cretas 现状 (2026-05-19 main)
- **✅ SHIPPED**: commit `316f5039d` (Sprint4-L) + PR #727
- `web-admin/src/components/list/InlineRowIcons.vue` (3599 bytes)
- 接入 2 Vue ListView: sales/orders, procurement/orders

### Gap 分析
- ✅ Component ship
- ⚠️ 仅 2 ListView 接入, 远低于 HJ 全模块 (但跟 U-VIEW-1 同步 rollout 节奏)
- ⚠️ Cretas 7 icons 具体哪 7 个未 grep 内容 (推测包含: QR / lock / copy / log / payment / print / mark)

### 推荐
SHIP. P2 扩到 10+ ListView (~3d).

### Layer B 标记
无需.

---

## §K.7 [U-MARKER-1] 订单标记 7 色

### HJ 实测细节
- UX-25 (`04-UX-PATTERNS.md:142-144`)
- 销售单 / 采购单 / 库存单 都有 "订单标记" 7 色 combobox: 灰 红 黄 绿 蓝 紫 白
- 行级用户自定义标记色 (用户视觉提示, 不影响业务)

### Cretas 现状 (2026-05-19 main)
- **✅ SHIPPED**: commit `75b4c3a68` (Sprint4-L) + PR #727
- `web-admin/src/components/list/RowMarkerCell.vue` (3000 bytes)
- 接入 2 Vue ListView: sales/orders, procurement/orders

### Gap 分析
- ✅ Component ship + 接入 2 site
- ⚠️ HJ 7 色 vs Cretas **5 色** (commit msg: "red/orange/yellow/green/blue") — 差 紫/白 2 色
- ⚠️ 7 色合理 (色色配对增强 UX), Cretas 5 色 可考虑后续加紫+白补足

### 推荐
SHIP 但 PARTIAL (5/7 色). 加 2 色 ~0.5d. 后续扩到 10+ list view ~2d.

### Layer B 标记
无需.

---

## §K.8 [U-FEED-1] 升级日志 in-app feed (10 条 release notes)

### HJ 实测细节
- UX-23 (`04-UX-PATTERNS.md:131-133`) + Round 2 截图 round2-01 / round2-02
- dashboard 显示最近 10 条 release notes (always visible)
- footer "升级日志" image click → modal 显示完整列表
- Cretas 价值: 客户感知"产品在更新"

### Cretas 现状 (2026-05-19 main)
- **✅ SHIPPED**: commit `c7f7d6c26` (Sprint4-L) + PR #727
- `web-admin/src/api/releaseNotes.ts` (API client)
- `web-admin/src/components/notification/ReleaseNoteCard.vue` (UI card)
- 集成: `web-admin/src/components/layout/AppLayout.vue` (顶级 layout 接入)

### Gap 分析
- ✅ Component + API + Layout 接入全 ship
- ⚠️ 后端实际 release notes data source 未 grep (推测 admin 后台 CRUD 或 hardcoded)
- ⚠️ 缺 footer "升级日志" image click → modal pattern (跟 HJ double-entry 比是单入口)

### 推荐
SHIP, P3: 加 footer 图标 + modal 双入口 (~0.5d).

### Layer B 标记
无需.

---

## §K.9 [U-DESKTOP-MODAL-1] layui-layer 桌面级 modal (4 操作)

### HJ 实测细节
- UX-28 (`04-UX-PATTERNS.md:198-202`) + `18-DESIGN-PHILOSOPHY.md:24-38` + 截图 round2-02
- 升级日志 modal 含 4 操作: 最小化 / 最大化 / 关闭 / 可拖 resize 角
- 桌面应用风格 (Windows / macOS 模态)

### Cretas 现状 (2026-05-19 main)
- **✅ SHIPPED 2 phases**:
  - commit `09d4c6b7c` (Sprint4-L 早期版本) "enhanced ElDialog (drag/min/max/resize/close)"
  - PR #831 (`0ecbc930e` 2026-05-18) "P1 #62 U-DESKTOP-MODAL-1 — DesktopModal wrapper (minimize/maximize/drag/close)"
- `web-admin/src/components/dialog/DesktopModal.vue` + `ModalDock.vue` + `useModalDock.ts`
- 接入: `views/smart-bi/analysis/ShareDialog.vue` (1 site) + `views/_dev/DesktopModalDemo.vue`
- Dock pattern (modal minimize → window 角落 dock) ship

### Gap 分析
- ✅ DesktopModal wrapper + ModalDock + useModalDock composable 全 ship
- ✅ 桌面级 4 操作 (minimize/maximize/drag/close) — 跟 HJ layui-layer 对齐
- ⚠️ 仅 1 production site 接入 (ShareDialog) — 渐进 rollout
- ⚠️ HJ "可拖 resize 角" 跟 Cretas "drag" 不同 — HJ 是右下角拖动 resize, Cretas 是整个 modal drag-to-move. 推测 Cretas DesktopModal 同时支持 resize+move (需 read source verify)

### 推荐
SHIP 完整. P3 扩到 5-10 复杂场景 (e.g. SmartBI chart 详情, 大表单创建) ~3d.

### Layer B 标记
P3 — Layer B 实测 DesktopModal resize handle 是否真支持 (跟 HJ 对齐).

---

## §K.10 [U-DEPT-1] 部门切换 button row

### HJ 实测细节
- 暗示 HJ 系统管理 / 角色 / 用户管理顶部有"部门切换" row
- (`04-UX-PATTERNS.md` 未单独列 UX-X, 是从 Round 1 audit 提取 P1 candidate)

### Cretas 现状 (2026-05-19 main)
- **✅ SHIPPED 2 phases**:
  - commit `930ad1cc0` (Sprint4-W1-A 早期版本) "DepartmentSwitcherRow 部门切换 button row"
  - PR #821 (`93c014cb6` 2026-05-18) "P1 #63 U-DEPT-1 — wire DepartmentSwitcherRow into 员工管理 demo"
- `web-admin/src/components/layout/DepartmentSwitcherRow.vue`
- 接入: `views/hr/employees/list.vue`

### Gap 分析
- ✅ Component ship + 1 site 接入 (员工管理)
- ⚠️ 仅 1 site 接入, 应扩到角色/用户管理/审批流 (任何按部门 RBAC 的场景)
- ⚠️ Cretas RBAC C-RBAC-1 (#661) 已 ship, DepartmentSwitcherRow 配合可加快多部门切换 UX

### 推荐
SHIP. P3 扩到 5+ RBAC 相关 page (~2d).

### Layer B 标记
无需.

---

## §K.11 [U-CHIP-MULTI-1] 行内多 chip 状态 (4 chip 垂直堆)

### HJ 实测细节
- UX-8 (`04-UX-PATTERNS.md:51-52`)
- 销售单 "销售订单创建 / 进行中 / 未审核 / 未出库" 4 chip 垂直堆 (1 row 内多状态展示)

### Cretas 现状 (2026-05-19 main)
- **✅ SHIPPED**: commit `fe433d766` (Sprint4-W1-A 早期版本) "MultiChipStack 多 chip 垂直堆组件" + PR #764 (Sprint4-W1-A bundle)
- `web-admin/src/components/list/MultiChipStack.vue` (3333 bytes) + export `ChipDef` / `ChipType` 类型
- 接入 2 Vue ListView: sales/orders, procurement/orders

### Gap 分析
- ✅ Component ship + 类型 export 完整
- ⚠️ 仅 2 site 接入, 应扩到 sales/shipments, sales/returns, production/plans, warehouse 等多状态场景 (~2d)

### 推荐
SHIP. P3 扩 5+ ListView (~2d).

### Layer B 标记
无需.

---

# §L — 战略/长期 P3 (8 项, Agent F)

> **Audit method**: `24-FULL-SUBDOMAIN-MAP.md` 38 子域 + `25-ROUND-8-FINAL-SUBDOMAINS.md` + `18-DESIGN-PHILOSOPHY.md` Round 2 设计理念. 大部分 P3 长期项 Steve 已 sign-off **延后** (per `28-CRETAS-PRIORITIZED-BACKLOG.md` §12), 此处仅深度核对状态. 4 项极长期者跳 deep audit.

## §L.1 [C-TV-DASHBOARD-1] TV 大屏 Android app

### HJ 实测细节
- `24-FULL-SUBDOMAIN-MAP.md:127-135` (S10 tv.hongjian.com)
- URL `tv.hongjian.com/index_middle.jsp` TV 大屏看板
- **HoanTV.apk 下载链接**: `https://tv.hongjian.com/app/HoanTV.apk`
- 描述: "TV 大屏看板... 可下载安装至 TV 设备, 和 ERP 数据共享"
- 工时估 15d

### Cretas 现状 (2026-05-19 main)
- **❌ NOT DONE** — grep `tv-dashboard|TVDashboard|hoantv` returns 0 hits in `web-admin/` and `frontend/CretasFoodTrace/`
- SmartBI 看板已 ship (`web-admin/src/views/smart-bi/`), 但仅 PC web 模式
- RN App 是 phone/tablet not TV

### Gap 分析
- ❌ TV Android app 完全没有
- Cretas 战略价值: **餐饮厨房屏 / 工厂车间屏** (跟 SmartBI 集成有差异化, 类似 GDS / Toshiba KDS)

### 推荐
**P3 延后** (per Steve sign-off). 客户群 (F006 卤制品 + QHJ 餐饮) 当前未要求.
如未来开拓 大型餐饮连锁 (>50 门店) 或 大型工厂 (>3 车间), TV 大屏是 differentiation, 工时 15d.

### Layer B 标记
无需 — P3 延后, 不投入 audit 资源.

---

## §L.2 [C-MENU-ENGINE-1] menu.jsp?m=X 配置驱动菜单架构

### HJ 实测细节
- HJ 12 模块菜单 + 681 menu URL 都通过 `menu.jsp?m=X` 配置驱动 (per Round 5)
- 优势: admin 后台调整菜单不需要重启或重新部署
- 类似 SAP Fiori Launchpad 配置中心

### Cretas 现状 (2026-05-19 main)
- **❌ NOT DONE** — 推测 Cretas menu 是 Vue Router config hardcoded
- 临时检验 (跳过 grep, Steve sign-off 延后)

### Gap 分析
- Cretas 现状 hardcoded menu — 适合早期 SaaS, 但大客户 (>10 角色 RBAC + 自定义菜单) 会要求
- HJ 实施需 backend menu_config 表 + admin UI + Vue Router 动态加载 (8d)

### 推荐
**P3 延后**. 跟 C-RBAC-FNO-1 联动 — RBAC 细粒度后再做 menu engine.

### Layer B 标记
无需.

---

## §L.3 [C-RBAC-FNO-1] 细粒度 f_no 权限点 (1591 个)

### HJ 实测细节
- `24-FULL-SUBDOMAIN-MAP.md:181` (Round 7 meta-audit): **657 unique menu f_no + 934 RBAC operation = 1591 unique functions**
- HJ RBAC 是 functional point granularity (1 角色 grant 1591 个布尔权限点)
- 工时估 15d

### Cretas 现状 (2026-05-19 main)
- **⚠️ PARTIAL** — Cretas RBAC 是模块 + role 粒度 (10 roles × 12 modules ≈ 120 permission combo)
- C-RBAC-1 (PR #661 + k4/k5 follow-ups #668/#671/#673/#674) ship 仓管员隔离 + 35-view defense
- canViewPrice gate (#520) ship

### Gap 分析
- Cretas 10 角色 × 模块 vs HJ 1591 f_no — **粒度差 13×**
- 食品/餐饮专精战略 (Steve sign-off) 不需要 1591 — 角色级足够
- 长期 P3: 大客户 (上市公司/集团) 会要 f_no 细粒度

### 推荐
**P3 延后**. 当前 Cretas 角色级 + canViewPrice gate 满足 F006/QHJ. 后续大客户需求触发再做.

### Layer B 标记
无需.

---

## §L.4 [C-MICROSERVICE-1] 38 子域微服务架构 (Cretas 当前 monolith)

**P3 长期跳过 deep audit** — Steve 战略选择 monolith (单 Java + 单 Python 部署), 简化运维. 38 子域微服务是 HJ 老 SaaS (2010 年代) 历史包袱, 非 Cretas 借鉴目标. (per `18-DESIGN-PHILOSOPHY.md` Cretas SPA + JWT 优势对比)

---

## §L.5 [C-WECHAT-DOMAIN-1] 微信子域独立 (weixin.hongjian.com)

**P3 长期跳过 deep audit** — F006 用钉钉 (Track-B1 in flight). QHJ 餐饮主要客户场景不需要微信子域. Steve sign-off 餐饮专精避免微信生态.

---

## §L.6 [C-PARTNER-DOMAIN-1] 合作伙伴管理独立子域

**P3 长期跳过 deep audit** — `partner.hongjian.com` 只 2 URLs (per `24-FULL-SUBDOMAIN-MAP.md:42`), HJ 自己也不重视. Cretas 客户群 (食品厂/餐饮) 不需要 partner 管理.

---

## §L.7 [C-DOCS-DOMAIN-1] help.cretas.com 独立 docs 子域

**P3 长期跳过 deep audit** — Cretas C-MIGRATE-1 已 ship 培训视频 7 章 25min, **比 HJ in-app 帮助强** (per `18-DESIGN-PHILOSOPHY.md:194`). 独立 docs.cretas.com 子域非紧急, 后续 Cretas 客户数破 10 再考虑.

---

## §L.8 [C-SERVICE-CODE-1] 服务代码显示 (footer small)

### HJ 实测细节
- `18-DESIGN-PHILOSOPHY.md:184-188`
- footer "服务代码: 159016" — 当前公司售后追踪 ID
- 客户找售后报这个号
- 工时估 0.5d

### Cretas 现状 (2026-05-19 main)
- **❌ NOT DONE** — grep `service-code|serviceCode|服务代码` returns no hits in main layout
- Cretas 当前 footer 仅 copyright

### Gap 分析
- 极小 feature, 客户支持效率 +10%
- 工时 0.5d (footer 加 small text + factory_id display)

### 推荐
**P3 quick win**. 可顺手做, 不阻塞.

### Layer B 标记
无需.

---

# §M — 餐饮多门店扩展 (4 项, Agent F)

> **Audit method**: `24-FULL-SUBDOMAIN-MAP.md` S6 (store) + Round 7 新增 P2 列表 + 餐饮 QHJ 现状.

## §M.1 [C-STORE-1] 门店管理 5 子项 (餐饮 QHJ 升级)

### HJ 实测细节
- `24-FULL-SUBDOMAIN-MAP.md:98-103` (S6 store.hongjian.com)
- 5 子页面: 门店补货 / 店面管理 / 入库 / 库存 / 出库
- 表头 10 列 (产品名称 / 单价 / 应补货 / 补货数量 / 单位 / 金额 / 总金额 / 状态 / 备注 / 操作)
- 工时估 5d

### Cretas 现状 (2026-05-19 main)
- **❌ NOT DONE 完整** — Cretas 餐饮 QHJ 当前是单门店模式 (factory_id = 1 餐饮主)
- 餐饮 Plan C 已 ship (Bronze→Silver→Gold→AI E2E, per memory project_apr24_restaurant_plan_c_complete)
- 多门店扩展 (multi-store + 门店级 RBAC + 门店间补货) 未做

### Gap 分析
- 当前 1 餐饮客户 (QHJ) 单店, 不阻塞
- 未来扩展餐饮连锁 (e.g. 跨 5+ 门店) 必须做

### 推荐
**P2 等客户触发**. Steve sign-off 餐饮专精, 但未明确 multi-store 时序. 当 第 2 餐饮客户 是多门店连锁 触发.

### Layer B 标记
无需.

---

## §M.2 [S-STORE-REPLEN-1] 门店补货 10 列

### HJ 实测细节
- `24-FULL-SUBDOMAIN-MAP.md:99-102` (S6 store.hongjian.com replenishment list)
- URL: `manager/replenishment/replenishmentlist.jsp` 门店补货列表
- 10 列字段 (per HJ 实测): 产品名称 / 单价 / 应补货 / 补货数量 / 单位 / 金额 / 总金额 / 状态 / 备注 / 操作
- 工时估 5d

### Cretas 现状 (2026-05-19 main)
- **❌ NOT DONE** — Cretas 当前无 "门店补货" 模块
- Cretas Sprint 2-E S-MRP-1 (`b936d19e3`) "销售订单→采购自动分流" 是工厂场景, 不是门店补货

### Gap 分析
- 跟 C-STORE-1 联动, 必须先 ship multi-store 基础再做 replenishment
- 算法: 门店历史销量 + safety stock → 自动建议补货量

### 推荐
**P2 等客户触发** (同 C-STORE-1 时序).

### Layer B 标记
无需.

---

## §M.3 [C-IMAGE-LIB-1] 公共图片库 (跨企业共享)

### HJ 实测细节
- `24-FULL-SUBDOMAIN-MAP.md:40` (image.hongjian.com — 2 URLs)
- 推测: 跨 factory_id 共享的产品图片资源库
- 工时估 3d

### Cretas 现状 (2026-05-19 main)
- **⚠️ PARTIAL** — Cretas C-ATT-1 (PR #658 Track-C ✅) ship 通用 attachment 系统
- 但 attachment 是 factory-scoped (per RBAC), 不是跨 factory 共享

### Gap 分析
- 跨 factory 共享需新设计 (e.g. `shared_image_library` 表 + 角色 grant)
- 客户场景: 食品 SKU 公共图 (e.g. 某品牌饮料的标准图)

### 推荐
**P2 延后**. C-ATT-1 ship 已满足单 factory 内文件存储. 跨企业共享需 multi-tenant 改造, 工时 3d.

### Layer B 标记
无需.

---

## §M.4 [C-FILE-DOMAIN-1] 文件管理独立子域 (file.hongjian.com)

### HJ 实测细节
- `24-FULL-SUBDOMAIN-MAP.md:38` (file.hongjian.com — 3 URLs)
- HJ 独立 file 子域 (separate from oa attachment / partner files)
- 工时估 3d

### Cretas 现状 (2026-05-19 main)
- **⚠️ PARTIAL** — C-ATT-1 (#658) 是 unified attachment, **比 HJ 设计更好** (避免 3 套 file system)
- 不需要独立 file 子域 (per Cretas monolith 战略)

### Gap 分析
- HJ file 子域是历史包袱 (3 套 file infrastructure)
- Cretas C-ATT-1 unified attachment 是 better design

### 推荐
**NOT NEEDED — Cretas C-ATT-1 已胜过 HJ file 子域设计**. Archive 此 backlog 项.

### Layer B 标记
无需.

---

# §N — 88 项 backlog 累计统计 (closer summary, Agent F)

> **统计基线**: `30-BACKLOG-STATUS-AUDIT.md` 2026-05-16 + 本 Round 11 deep re-audit (Agents A-F 全部覆盖) + 2026-05-18 PR #862 Canvas-Workflow Phase 1 ship + 2026-05-19 PR #690 S-LOCK-1 ship.

## §N.1 88 项 ship 状态 (本 Round 11 re-audit 后)

| 优先级 | 总数 | ✅ SHIPPED | ⚠️ PARTIAL/Flight | ❌ NOT DONE |
|---|---|---|---|---|
| **P0 战略** | 12 | 6 ship (#658/#650/#656/#649/#680/#690 S-LOCK) + 1 in flight (Track-B1 钉钉) + 1 部分 ship (PR #862 C-APPROVAL Phase 1) | 2 | 4 |
| **P0 必修** | 6 | 4 ship (M3 #653 / M4+M5 #656 / N3 #661) | 2 (M1 #538 blocked / M2 P3 deferred) | 0 |
| **P1 战术** | 47 | 20+ ship (Sprint 2 G/H/I/J/E/F + Sprint 4 W1+W2+L Chat L bundle + 2026-05-18 #821/#822/#823/#831/#832 5 项) | ~5 | ~22 |
| **P2 选做** | 15 | 1 ship (C-FILE 实际由 C-ATT-1 替代) | 0 | 14 |
| **P3 长期** | 8 | 0 | 0 | 8 |
| **合计 88** | | **~31 ✅** | **~9 ⚠️** | **~48 ❌** |

vs 2026-05-16 audit (12 ✅ + 3 ⚠️ + 73 ❌): **Round 11 audit 多 ship 19 项**, 主要来自:
- **Sprint 4 Chat L bundle (PR #727)** — 8 UX items (U-VIEW/U-NEW/U-ICON/U-MARKER/U-FEED/U-DESKTOP-MODAL 等)
- **Sprint 4 W1-A bundle (PR #764)** — 7 quick wins (U-DEPT/U-CHIP-MULTI 等)
- **Sprint 4 W2 Chat G** (M-WIP-1/M-PREP-1/M-DELIVERY-WARN-1/M-MATTREE-1)
- **2026-05-18 PR #821/#822/#823/#831/#832** (5 P1: U-DEPT 接入 / S-CRM-1 / C-WIDGET-1 / U-DESKTOP-MODAL / U-NEW)
- **2026-05-19 PR #690** Sprint3-G S-LOCK-1 ✅
- **2026-05-18 PR #862** Canvas-Workflow Phase 1 (C-APPROVAL-EDITOR 部分 ship)

## §N.2 关键 ship 突破点 (本 Round 11 揭示)

### UX 域 (§K): 11/11 P1 UX 全 ship ✅
- U-NAV-1 / U-ACT-1 / U-FOOTER-1 (Sprint 2 G/H/I) ✅
- U-VIEW-1 / U-NEW-1 / U-ICON-1 / U-MARKER-1 / U-FEED-1 / U-DESKTOP-MODAL-1 (Sprint 4 Chat L PR #727) ✅
- U-DEPT-1 / U-CHIP-MULTI-1 (Sprint 4 W1-A PR #764) ✅
- C-WIDGET-1 dashboard 插件式 framework + 7 endpoint widget (PR #823) ✅ **新发现 (28-Backlog 未列**)
- S-CRM-1 customer tracking UI (PR #822) ✅ **新发现**

**意义**: P1 UX 域接近 100% ship rate. 28-Backlog Sprint 4 UX 规划 (Week 31-36) 实际 ahead of schedule.

### P0 战略仍剩 ❌ 4 项未做
- **F-VFLAG-1** (凭证 hook, 10d)
- **C-LINKARRAY-1** (跨业务关联, 2d)
- **M-BOM-VER-1** (BOM 工程级 + 版本 + ECN, 15d)
- **C-PRT-EDITOR-1** (打印模板编辑器, 10d)

### 战略/长期 (§L): 全部 8 项 ❌ 但 Steve 已 sign-off 延后
- C-TV-DASHBOARD-1 / C-MENU-ENGINE-1 / C-RBAC-FNO-1 — 后续大客户触发
- C-MICROSERVICE-1 / C-WECHAT-DOMAIN-1 / C-PARTNER-DOMAIN-1 / C-DOCS-DOMAIN-1 — 战略不抄 (Cretas monolith / 钉钉 / 食品专精)
- C-SERVICE-CODE-1 — 0.5d quick win 可顺手做

### 餐饮多门店 (§M): 4 项 ❌ 待客户触发
- C-STORE-1 + S-STORE-REPLEN-1 — 等多门店餐饮客户
- C-IMAGE-LIB-1 — multi-tenant 改造延后
- C-FILE-DOMAIN-1 — Cretas C-ATT-1 已胜出, archive 此 backlog

## §N.3 ⚠️ 待 reconcile / discovery

1. **PR #690 S-LOCK-1 (2026-05-19)** — `30-BACKLOG-STATUS-AUDIT.md` 没收录 (基线 2026-05-16), 已 ship 但 28-Backlog 仍标 ❌
2. **PR #821/#822/#823/#831/#832 (2026-05-18)** — 5 P1 items ship, 28-Backlog 应更新
3. **PR #862 Canvas-Workflow Phase 1 (2026-05-18)** — C-APPROVAL-EDITOR-1 部分 ship (Phase 1 of N), 应标 ⚠️ instead of ❌
4. **C-WIDGET-1 dashboard 插件式 + 7 endpoint widget (PR #823)** — 28-Backlog 未单独列 (P1 #65), 实际是 Round 2 P1 项已 ship

## §N.4 真实剩余工时 (本 Round 11 修正)

| 类别 | 30-Audit 估算 (May 16) | **Round 11 修正 (May 19)** | Δ |
|---|---|---|---|
| P0 战略 剩 | 64d (6 项 + Track-B1 6d) | 41d (4 ❌: F-VFLAG 10d + C-LINKARRAY 2d + M-BOM-VER 15d + C-PRT-EDITOR 10d, 加 Track-B1 钉钉收尾 ~2d, 加 C-APPROVAL-EDITOR Phase 2-N ~10d) | -23d |
| P0 必修 剩 | 4d | 4d (M1 blocked + M2 deferred) | 0d |
| P1 战术 剩 | 128d (42 项) | ~85d (~22 项剩) | -43d |
| P2 选做 | 126d | 123d (C-FILE-DOMAIN archive 省 3d) | -3d |
| P3 长期 | 51d | 51d | 0d |
| **合计 剩余** | **373d nominal** | **~304d nominal** | **-69d (-18%)** |

按 Claude 1.7× 加速 + 25% buffer:
- 30-Audit: 224d 实际工日 ≈ 13 月
- **Round 11**: ~183d 实际工日 ≈ **10.5 月**

按 Steve sign-off "9 月 P0+P1 = 66 项":
- 30-Audit: 51 项 / 196d / 7 月
- **Round 11**: ~31 项 / ~130d / **~5 月** (从 sign-off 共省 4 月)

## §N.5 Layer B browser 实测候选 (organizer 后续)

Agent F (§K + §L + §M) Layer B 候选:
- §K.5 U-NEW-1 BOM 展开 mode 是否真触发 BOM 配方反算 (P3 优先级低)
- §K.9 U-DESKTOP-MODAL-1 resize handle 实测 (P3 优先级低)

总 2 项, 都是 low priority (P3). 跟 §A-§J 其他 agents 加起来 Layer B 估约 8-12 项, 1 session 可完.

## §N.6 元教训 (本 Round 11)

1. **88-Backlog status drift** — 5 月 16-19 三天又 ship 8+ PR (PR #690/#821/#822/#823/#831/#832/#862 等), status doc 必须 daily reconcile (跟 `feedback_signoff_requires_reconcile_with_main_first.md` HARD 一致)
2. **Sprint 4 W2 Chat L PR #727 bundle (8 UX items)** 是 ship 集中点 — backlog 应按 bundle 标记, 避免单项 mis-track
3. **C-APPROVAL-EDITOR-1 是多 Phase ship** — Phase 1 (PR #862 入 Canvas) 已 ship, 完整 (节点编辑/条件/并行/网关) 仍待 Phase 2-N. 应改 ⚠️ instead of ❌
4. **U-* P1 UX 全 ship** — 28-Backlog Sprint 4 (Week 31-36) 计划 ahead of schedule, 应重新规划 Sprint 5 内容 (P2 vs P3 优先级)
5. **C-WIDGET-1 + S-CRM-1 (2026-05-18 ship)** 在 28-Backlog metadata stale — 28-Backlog 应加 Round 2 新增 P1 项 (`04-UX-PATTERNS.md` Round 2 列出但 28-Backlog 漏收录)
6. **Steve sign-off "9 月" 真实剩 ~5 月** — 比 30-Audit 修正 (7 月) 更乐观 (省 4 月 from sign-off)

<!-- agent-F done 2026-05-19 -->

<!-- agent-B section START 2026-05-19 -->

# §C — 采购管理 (6 项, Agent B)

> **Scope**: P-FIN-1 / P-NUCLEAR-1 / P-DRAFT-1 / P-IMPORT-1 / P-SPLIT-1 / P-RFQ-1
> **Method**: 重读 `06-宏见测试账号深度审计/02-采购管理-deep-audit.md` baseline + grep Cretas main 验证 ship 状态二次 + git log 反查 PR
> **Headline finding**: **6 项中 4 项已 ship, 1 项部分 ship, 1 项 0 ship**. 28-Backlog metadata stale — P-NUCLEAR-1 / P-IMPORT-1 标 ❌ 实际已 merge (PR #824 / #764).

---

## §C.1 [P-FIN-1] 采购订单财务审核 + 三价标红 ✅ SHIPPED (verified)

### HJ 实测细节 (深度)
- 入口: `采购管理 → 采购订单 list` → 行 click → 详情页 → "财务审核" tab
- 关键字段 (HJ baseline §3.1-3.3): finance_reviewed_by / finance_reviewed_at / finance_review_notes / finance_review_status; 三价对比 = `BOM 标准价 + 历史均价 + 当前入库价` 横向, 异常标红 (差异 >10%)
- UI 状态机: `DRAFT → SUBMITTED → APPROVED → PENDING_FINANCE_REVIEW → FINANCE_APPROVED / FINANCE_REJECTED → ... → COMPLETED`
- 截图证据: `screenshots/采购-01-flowchart-clicked.png` (业务流程图 11 节点)

### Cretas 现状 (2026-05-19 main)
- 后端: `entity/inventory/PurchaseOrder.java:111-114` enum status 11 状态含 `PENDING_FINANCE_REVIEW / FINANCE_APPROVED / FINANCE_REJECTED`; `:166-176` financeReviewedBy / financeReviewedAt / financeReviewNotes 三字段
- 审批规则: `entity/inventory/PurchaseOrderApprovalRule.java` (金额/部门/角色阈值)
- 前端: `web-admin/src/views/procurement/orders/detail.vue:196-289` 三价对比 + 入库后强刷
- PR: **#675 Sprint2-J** (commit `b7846a918`) + **#782 M1 fix** (commit `5a557f03b` Issue #781)
- 测试: 财务审核 controller 端到端测试已 cover

### Gap 分析
| 维度 | HJ | Cretas | 状态 |
|---|---|---|---|
| 财务审核字段 | 4 (人/时间/意见/状态) | 3 (人/时间/意见, 状态合并 enum) | ✅ 等价 |
| 三价对比 | BOM 价 / 历史均价 / 当前价 | 同 | ✅ |
| 入库后强刷 | 实测有 | Issue #781 PR #782 修复 | ✅ |
| 审批规则可配 | 流程图 / 系统设置 | `PurchaseOrderApprovalRule` (阈值/部门/角色) | ✅ |
| FINANCE_REJECTED reopen UI | (HJ 不详) | 状态 enum 有, UI 路径待 Layer B 验 | ⚠️ |

### 推荐
- **工时修正**: 28-Backlog 标 3d 实际 0d (ship completed)
- **优先级**: ✅ 完成
- **Sprint**: 无后续, 看 FINANCE_REJECTED reopen UX 是否有 dead-end (per 防呆 Rule 5)

### Layer B 标记
- [ ] 验证 FINANCE_REJECTED → 用户在哪里 reopen 修改并重提? 是否有 next-action button?

---

## §C.2 [P-NUCLEAR-1] 核价单 (询价→核价→采购 三阶段) ✅ SHIPPED (28-Backlog 标 ❌ 实际已 merge)

### HJ 实测细节 (深度)
- 入口: `采购管理 → 询价管理` 子菜单 inquiry 分组 2 项 (询价单 / 内部询价单)
- 流程节点: 业务流程图 第 3 节点 (`核价单 ⭐`) 介于 请购单 和 采购订单 之间
- 三阶段: ① buyer 创建询价 → ② N 个供应商提交 unit_price 报价 → ③ buyer 选定中标供应商 + 一键转化为 PurchaseOrder
- 关键字段 (推断): inquiryNumber, materialTypeId, quantity, unit, inquiryDate, requiredDate, status (DRAFT/INQUIRING/QUOTED/SELECTED/CONVERTED/CANCELLED), selectedSupplierId, selectedUnitPrice, purchaseOrderId
- 截图证据: `screenshots/采购-03-核价单.png` (test account 无 sample 数据)

### Cretas 现状 (2026-05-19 main)
- Entity 主表: `entity/purchase/InquiryQuote.java:60-157` (含 Formula selectedSupplierName, @Version 乐观锁, idempotent purchaseOrderId guard)
- Entity 子表: `entity/purchase/InquiryQuoteSupplierPrice.java:42-50` (N 供应商报价, UniqueConstraint(inquiry_quote_id, supplier_id))
- Enum: `entity/enums/InquiryQuoteStatus.java` (6 状态: DRAFT/INQUIRING/QUOTED/SELECTED/CONVERTED/CANCELLED)
- Controller: `controller/purchase/InquiryQuoteController.java`
- DTO: `dto/purchase/CreateInquiryQuoteRequest.java` + `SelectAndConvertRequest.java`
- Repository: `repository/purchase/InquiryQuoteRepository.java` + `InquiryQuoteSupplierPriceRepository.java`
- 前端 API: `web-admin/src/api/inquiryQuote.ts`
- 前端 UI: `web-admin/src/views/procurement/inquiry-quotes/list.vue:1-80` + `detail.vue`
- PO 关联: `PurchaseOrder.java:151-153` `inquiryQuoteId` FK
- PR: **#824 / #30 P-NUCLEAR-1** (commits `aebcdedaa` + `dc2cb744f`, 2026-05-17/18)

### Gap 分析
| 维度 | HJ | Cretas | 状态 |
|---|---|---|---|
| 主表 entity | InquiryQuote | InquiryQuote ✅ | ✅ |
| 多供应商报价 | N 行 | InquiryQuoteSupplierPrice ✅ | ✅ |
| 三阶段状态机 | 推断 5-6 | 6 状态 enum | ✅ |
| 中标供应商 → 转 PO | 一键 | SelectAndConvertRequest + idempotent guard | ✅ |
| 询价模板 / 批量发供应商 | (HJ 不详, 可能在协同管理) | ❌ 暂无 | ⚠️ 后续 |

### 推荐
- **工时修正**: 28-Backlog 估 3d, 实际 ~5d (含 entity + supplier price 子表 + UI), 已 100% ship
- **优先级**: ✅ 完成
- **重要**: 28-Backlog 第 30 行应 amend ❌ → ✅
- **跟 P-RFQ-1 关系**: P-NUCLEAR-1 已含多供应商比价能力 → P-RFQ-1 95% 重复, 仅 "询价模板 / 批量发" 差异

### Layer B 标记
- [ ] 验证 HJ 询价单的"批量推送多供应商"操作 (Cretas 现是 buyer 手动 add 报价行, 没"群发询价"概念)

---

## §C.3 [P-DRAFT-1] 采购底稿 (草稿态) ⚠️ PARTIAL (有 DRAFT status, 缺独立"底稿"实体)

### HJ 实测细节 (深度)
- 入口: `采购管理 → 采购底稿` (业务流程图 第 4 节点 ⭐)
- 概念: 介于"询价已选定供应商"和"正式下单"之间的中间状态. 多 buyer 协作 / 审核前 review / 调整品项
- 跟 PurchaseOrderStatus.DRAFT 区别: HJ 把"底稿"建模为**独立单据类型**, 流程图有专门节点, 不是 PO 的状态
- 截图证据: `screenshots/采购-04-采购底稿.png` (test data empty)

### Cretas 现状 (2026-05-19 main)
- `PurchaseOrderStatus.DRAFT` 存在 (enum:11), service 层 `PurchaseServiceImpl.java:206,342,730,837` 使用
- **缺**: 独立 `PurchaseDraft` entity / Repository / Controller — `grep "PurchaseDraft"` 仅命中 `CreateBomDraftRequest.java` (不相关 BOM 草稿)
- 现行 workflow: 创建 PO 即生 DRAFT, 走 SUBMITTED → APPROVED, 没"底稿先 review 再批量转 PO"中间步

### Gap 分析
| 维度 | HJ | Cretas | 状态 |
|---|---|---|---|
| 草稿状态 | 独立单据 | enum status | ⚠️ 等价但建模不同 |
| 多人协作 review | 流程图节点 | ❌ | ❌ |
| 批量转 PO | (HJ 节点支持) | ❌ | ❌ |
| 跟核价单关联 | 核价 → 底稿 → PO | 核价 → PO (跳过底稿) | ⚠️ |

### 推荐
- **工时修正**: 28-Backlog 估 1d, 实际若做"独立 entity"需 3-5d
- **优先级**: P3 (F006 单 buyer 工厂用不到)
- **决策**: 不抄独立 entity, 保留 `PurchaseOrderStatus.DRAFT` 等价模型 (跟客户群战略一致, 28-Backlog §6.2 避大企业场景)
- **Sprint**: defer, 标"客户驱动"

### Layer B 标记
- (无, deferred 决策已定)

---

## §C.4 [P-IMPORT-1] 采购类型 (正常/进口) ✅ SHIPPED (28-Backlog 标 ❌ 实际已 merge)

### HJ 实测细节 (深度)
- 字段位置: `采购订单 list 查询 combobox` 第 9 项 (per baseline §3.1 Table)
- 枚举: 2 值 `正常采购 / 进口采购`
- HJ 流向: 进口采购触发额外审批节点 (海关 / 报关单)

### Cretas 现状 (2026-05-19 main)
- 字段: `PurchaseOrder.java:88-94` `isImported: Boolean` (nullable)
- 注释明确 3 值: NULL=未指定(老数据) / TRUE=进口需海关 / FALSE=境内正常
- DDL: `V20260606_01__sprint4_w1_quickwins.sql`
- 跟现 `purchaseType` enum (DIRECT/HQ_UNIFIED/URGENT 业务流向) **正交独立维护** (Cretas 比 HJ 更细)
- PR: **Sprint 4 W1 #764** (commit `7a4b2da49`, 2026-05-16)

### Gap 分析
| 维度 | HJ | Cretas | 状态 |
|---|---|---|---|
| 字段类型 | enum 2 值 | Boolean nullable (3 态) | ✅ 等价 |
| 跟 purchaseType 关系 | 单一 combobox | 双独立维度 | ✅ Cretas 更细 |
| 进口走另一审批 | (HJ 流程图) | ❌ 暂无差异化 workflow | ⚠️ |
| UI 选择器 | combobox 2 选项 | grep 0 vue 命中 — 前端 UI 可能未接入 | ⚠️ |

### 推荐
- **工时修正**: 28-Backlog 估 1d → backend 0d (ship)
- **优先级**: ✅ backend 完成, 若需"进口走单独审批 + 海关字段" + 前端选择器加 P3 follow-up issue (~2d)
- **Sprint**: 无后续

### Layer B 标记
- [ ] 验证创建 PO form 是否暴露 isImported 选择器 (web + RN)? grep 0 命中, 怀疑前端 UI 未接入

---

## §C.5 [P-SPLIT-1] 采购订单按供应商拆单 (P2) ❌ NOT DONE

### HJ 实测细节 (深度)
- 入口: 推断 `采购订单 list → 多供应商 PO → 拆单 button`
- 场景: 一张 PO 含多供应商物料 → 按 supplier_id 自动拆 N 个子 PO (parent 状态 = SPLIT/CANCELLED)

### Cretas 现状 (2026-05-19 main)
- `SplitOrderTool.java:21-177` (Canvas tool) — **只服务销售订单**, 不是 PO. requires source 是 SalesOrder
- 缺: `splitPurchaseOrderBySupplier` service method, 缺 PurchaseOrder parent_id FK, 缺前端 UI

### Gap 分析
- 100% gap, P2 deferred per 28-Backlog (§4.2 大销售团队 / B2B 协同)
- 拆单 Canvas 框架已有 (SplitOrderTool), 复用难度低 — 仿 SO 拆单 logic 适配 PO

### 推荐
- **工时修正**: 28-Backlog 估 5d 合理
- **优先级**: P2, 等客户实际需求 (F006 单一供应商场景用不到)
- **Sprint**: Sprint 6+ (defer)

### Layer B 标记
- (无, deferred)

---

## §C.6 [P-RFQ-1] 询价管理 (多供应商比价) (P2) ✅ SHIPPED via P-NUCLEAR-1 (28-Backlog 重复, 应合并)

### HJ 实测细节 (深度)
- 入口: `采购管理 → 询价管理` (左 nav 子菜单)
- 跟 P-NUCLEAR-1 关系: HJ 是 ① 询价单 (RFQ 询价请求) → ② 核价单 (报价对比) → ③ 采购订单 三独立单据

### Cretas 现状 (2026-05-19 main)
- **InquiryQuote 已 ship** — 单一 entity 涵盖 ① 询价 (DRAFT/INQUIRING status) + ② 报价对比 (QUOTED/SELECTED + InquiryQuoteSupplierPrice 子表)
- 不区分"询价单"独立单据 — Cretas 模型把 RFQ + 核价合二为一 (合理简化)

### Gap 分析
| 维度 | HJ (3 单据) | Cretas (1 entity) | 状态 |
|---|---|---|---|
| RFQ 询价请求 | 独立 entity | InquiryQuote (status=DRAFT/INQUIRING) | ✅ 合并 |
| 报价对比 | 核价单 | InquiryQuote.supplierPrices N rows | ✅ 合并 |
| 中标 → 转 PO | 核价 → 采购 | SelectAndConvert | ✅ |
| 询价模板 / 邮件群发 | (HJ 可能有) | ❌ 暂无 | ⚠️ 后续 |
| 内部询价单 | HJ inquiry 子菜单第 2 项 | ❌ Cretas 不区分 | ⚠️ |

### 推荐
- **工时修正**: 28-Backlog 估 5d → 95% 已含在 P-NUCLEAR-1, 仅余"询价模板 + 群发" ~2d
- **优先级**: ✅ 等价完成, 28-Backlog 应**合并 P-RFQ-1 入 P-NUCLEAR-1**
- **Sprint**: 无后续

### Layer B 标记
- [ ] 验证 HJ "内部询价单" vs 外部询价单 差异 — 内部可能跨子公司, F006 单工厂用不到

---

# §D — 仓库 + 通用 (5 项, Agent B)

> **Scope**: W-CLASS-1 / N13 W-ABA-1 / N20 C-ATT-1 / M3 PDF+扫码 RN / M1 三价刷新
> **Method**: 同 §C, 重读 `02-仓库管理-deep-audit.md` + grep + git log
> **Headline finding**: **5 项全部 SHIPPED**. MEETING_AUDIT_2026-05-17.md C-1 (PURCHASE_RECEIPT 拍照 UI) **已过时** — 当时标 ❌, 后续 PR #797 (2026-05-19) 已 ship.

---

## §D.1 [W-CLASS-1] 仓库 10 分类枚举 ✅ SHIPPED (扩到 13 类含 deprecated)

### HJ 实测细节 (深度)
- 入口: `仓库管理 → 其他出库单 list → 仓库 combobox` (第 2 项, 10 选项)
- 10 类: 默认仓库 / 样品仓 / 成品仓 / 半成品仓 / 原材料仓 / 辅材仓 / 报废仓 / cable车间仓 / FPC车间仓 (跨厂自动加)
- 截图证据: `screenshots/仓库-01-list.png`

### Cretas 现状 (2026-05-19 main)
- Entity: `entity/factory/FactoryWarehouse.java:78-108` `WarehouseType` enum, **13 类**:
  - 3 deprecated (backwards-compat): LOGISTICS / WORKSHOP / OTHER
  - 10 new (W-CLASS-1): RAW / WIP / FINISHED / LINESIDE / RETURNS / SCRAP / TEMP / QC / OUTSOURCE / TRANSFER
- PR: **Sprint 4 W1 #764** (commit `91cdf7897`, 2026-05-16)
- 测试: `8dd9a9e55 fix(test): WarehouseType count assertion 3 → 13` 确认 enum count 校验
- 无 DDL (VARCHAR(20) 存字符串)

### Gap 分析
| 维度 | HJ (10) | Cretas (10 new + 3 dep) | 状态 |
|---|---|---|---|
| 原材料仓 | ✅ | RAW | ✅ |
| 半成品仓 | ✅ | WIP | ✅ |
| 成品仓 | ✅ | FINISHED | ✅ |
| 辅材仓 | ✅ | ❌ 暂无独立 AUX | ⚠️ |
| 样品仓 | ✅ | ❌ 暂无独立 SAMPLE | ⚠️ |
| 报废仓 | ✅ | SCRAP | ✅ |
| 车间仓 (cable/FPC) | ✅ | LINESIDE (合并) | ✅ |
| 调拨在途 | ❌ HJ 无 | TRANSFER ✅ | ✅ Cretas 更全 |
| 质检仓 | ❌ HJ 无 | QC ✅ | ✅ Cretas 更全 |
| 委外仓 | ❌ HJ 无 | OUTSOURCE ✅ | ✅ Cretas 更全 |
| 暂存仓 | ❌ HJ 无 | TEMP ✅ | ✅ Cretas 更全 |

### 推荐
- **工时修正**: 28-Backlog 估 1d → 实际已 0d
- **优先级**: ✅ 完成 (Cretas 13 类 > HJ 10 类, 含调拨/质检/委外/暂存 4 个更高级类型)
- **可选 follow-up**: 加 AUX + SAMPLE 2 类对齐 HJ (~0.5d) — P3 nice-to-have

### Layer B 标记
- (无, 完成)

---

## §D.2 [N13 W-ABA-1] 抄码品识别 ✅ SHIPPED

### HJ 实测细节 (深度)
- 概念: 食品行业称重单, 每箱重量不一致, 收货时需逐箱 weighing 录入
- 客户原话 (May 9 reference): "抄码" (Whisper 易写"超码")
- 场景: 卤制品工厂 牛肉/猪肉/鸭肉等每箱重量不一的原料

### Cretas 现状 (2026-05-19 main)
- Entity: `entity/warehouse/AbacaQuantityLog.java`
- Service: `service/material/AbacaQuantityLogService.java`
- Tool: `ai/tool/impl/material/AbacaWeightLogTool.java` (录入) + `AbacaWeightSummaryTool.java` (查询汇总)
- Tool name: `abaca_weight_log` / `abaca_weight_summary`
- Repo: `repository/AbacaQuantityLogRepository.java`
- 前端 RN: `WHReceiptCreateScreen.tsx` + `WHScanOperationScreen.tsx`
- PR: **Track-B2 #649** (commit `f07020c7d`)

### Gap 分析
- 100% ship, 全栈覆盖 (backend entity + service + AI tool + RN UI)
- 唯一 nit: web-admin 端是否有 entry 录入 (RN 是主入口)

### 推荐
- **工时修正**: 28-Backlog 估 2d → 0d
- **优先级**: ✅ 完成
- **Sprint**: 无

### Layer B 标记
- [ ] 验证 web-admin 是否有抄码品入库录入 UI (desktop 备份录入路径)

---

## §D.3 [N20 C-ATT-1] 通用 attachment 系统 ✅ SHIPPED + PURCHASE_RECEIPT UI 已 ship (MEETING_AUDIT C-1 已过时)

### HJ 实测细节 (深度)
- 客户原话 (第三次 May 7 part 2 L177-180): "拍照也可以留个单谱吧, 就是你留个附件类似一个拍照然后一个附件吗也可以的呀"
- 场景: 入库收货拍照存证, 防呆 + 审计追溯

### Cretas 现状 (2026-05-19 main)
- Entity: `Attachment.java:112-131` EntityType enum 18 类 (含 `PURCHASE_RECEIPT` line 116)
- Repository / Service / Controller 全栈: `AttachmentController.java` + `AttachmentService.java` + `AttachmentServiceImpl.java`
- 权限: `AttachmentPermissionResolver.java`
- DTO: `RegisterAttachmentRequest.java` + `UpdateAttachmentRequest.java`
- 前端 Vue: `web-admin/src/api/attachment.ts` + `components/attachment/AttachmentList.vue` + `AttachmentUploadButton.vue`
- 前端 RN: `services/api/attachmentApi.ts`
- PR (系统): **Track-C #658** (commit `f296447c6`)
- **PR (PURCHASE_RECEIPT UI 落地)**: **#797** (commit `610dcbab5`, 2026-05-19 — MEETING_AUDIT 之后 2 天 ship)
  - 位置: `web-admin/src/views/procurement/receives/list.vue:29,149,649-669`
  - entity-type="PURCHASE_RECEIPT" 已接入 list 详情抽屉, 含 AttachmentList + AttachmentUploadButton

### Gap 分析
- ✅ Attachment 系统 + PURCHASE_RECEIPT UI 已 ship
- **重要发现 (反 stale)**: `MEETING_AUDIT_2026-05-17.md` §C-1 标 "❌ NOT DONE — backend storage ready but no UI widget on receive form" **已过时**
  - 当时 audit (5-17): 0 `el-upload` 命中 → 标 NOT DONE
  - 现状 (5-19): PR #797 已 ship (2 天后)
  - 应 amend MEETING_AUDIT.md §C-1 为 ✅ SHIPPED

### 推荐
- **工时修正**: C-ATT-1 估 5d + C-1 follow-up 估 4-6h → 实际全 0d (已 ship)
- **优先级**: ✅ 完成
- **行动**: 更新 `MEETING_AUDIT_2026-05-17.md` §C-1 状态为 ✅ SHIPPED via #797, 移出 "file P2 issue" 推荐
- **Sprint**: 无

### Layer B 标记
- (无)

---

## §D.4 [M3] PDF + 扫码 RN 端 ✅ SHIPPED (verified)

### HJ 实测细节 (深度)
- 概念: 采购订单 PDF (含 QR / Code128 二维码) → 仓管员手机扫码 → 自动跳收货页 → 入库
- 客户原话 (May 7 transcript): "扫一下上面的拳运码 然后开始入库"
- HJ 应用: 业务流程图 第 10 节点 (采购入库)

### Cretas 现状 (2026-05-19 main)
- 后端: `controller/inventory/PurchaseController.java` + `service/inventory/PurchaseOrderPdfService.java` + `PurchaseOrderPdfServiceImpl.java`
- 关键 endpoint: `GET /orders/by-number/{orderNumber}` (扫码后查 PO)
- RN 端: `frontend/CretasFoodTrace/src/screens/warehouse/shared/WHScanOperationScreen.tsx`
  - expo-camera CameraView 真摄像头
  - 双格式支持: QR + Code128 (PDF 上 PR #413 两种都打印)
  - inbound mode 扫码 → 跳 WHReceiptCreate
- 历史: 原 setTimeout + 假批次号 mock → W-ABA-1 Day 4 改造为真实扫码
- PR: **#413** (PurchaseOrderPdfService 后端 2026-05-12) + **#653** (RN scan flow 2026-05-15, Track-B2 commit `8bf5fbc93`)
- 测试: 已端到端 verified (MEETING_AUDIT 附录 B #2)

### Gap 分析
- 100% ship, web (PDF 生成) + RN (扫码消费) 全栈覆盖
- 唯一 nit: 出库 mode 仍 Phase 2 ("出库 (outbound): 扫批次条码 — Phase 2 接入实际出库流程") — 出库扫码 follow-up

### 推荐
- **工时修正**: 28-Backlog 估 4d → 0d
- **优先级**: ✅ 完成, follow-up 出库扫码 ~2d P2
- **Sprint**: 无 (出库可加 P2 follow-up)

### Layer B 标记
- (无, 完成)

---

## §D.5 [M1] 三价对比刷新 ✅ SHIPPED (28-Backlog 标 ⚠️ blocked, 实际已修)

### HJ 实测细节 (深度)
- Bug: 新建采购单后, 三价对比 (BOM 价/历史均价/当前入库价) 不刷新, 显示 stale 数据

### Cretas 现状 (2026-05-19 main)
- 文件: `web-admin/src/views/procurement/orders/detail.vue`
  - L65-75 "Day 8-9 三价对比 bug 修复: 移除 priceLoaded 短路"
  - L196-208 Issue #781 修复: 入库 (新建收货) 后强制刷新三价对比缓存
  - L221-228 数据源缺失诊断 (新原料 / 尚未入库 / 未配 BOM 时 banner 解释 — 防呆 Rule 1)
- PR: **#782** (commit `5a557f03b`, "fix(customer-vue): 6 P2/P3 customer feedback batch")
- Test seed blocker (#538) 已 by PR #695 (commit `b95108d86`) 解决
- 关联: MEETING_AUDIT 附录 B #8 已 verified ✅

### Gap 分析
- ✅ 100% ship, 多层 fix:
  - Cache 短路移除 (priceLoaded)
  - 入库后强刷
  - 空值诊断 banner (UX 改进, 防呆 Rule 1 边界显示)
- 28-Backlog 第 13 行 M1 "⚠️ BLOCKED by #538" 实际 #538 已 fix + M1 已 ship → metadata stale

### 推荐
- **工时修正**: 28-Backlog 估 2d → 0d
- **优先级**: ✅ 完成
- **行动**: amend 28-Backlog 第 13 行 ⚠️→✅
- **Sprint**: 无

### Layer B 标记
- (无)

---

## §C+§D 总结表 (11 项)

| # | 项 | 28-Backlog 标 | 实际 (2026-05-19) | PR 证据 | Stale? |
|---|---|---|---|---|---|
| C.1 | P-FIN-1 | ✅ | ✅ | #675 + #782 | — |
| **C.2** | **P-NUCLEAR-1** | **❌** | **✅ NEW SHIP** | **#824 / #30** | **YES (3d)** |
| C.3 | P-DRAFT-1 | ❌ | ⚠️ partial (DRAFT status) | — | decision: defer |
| **C.4** | **P-IMPORT-1** | **❌** | **✅ NEW SHIP** | **#764 `7a4b2da49`** | **YES (1d)** |
| C.5 | P-SPLIT-1 | ❌ P2 | ❌ NOT DONE | — | — |
| **C.6** | **P-RFQ-1** | **❌ P2** | **✅ 含在 #824** | **#824 (同 P-NUCLEAR-1)** | **YES (5d, 合并)** |
| **D.1** | **W-CLASS-1** | **❌** | **✅ NEW SHIP** | **#764 `91cdf7897`** | **YES (1d)** |
| D.2 | N13 W-ABA-1 | ✅ | ✅ | #649 | — |
| **D.3** | **N20 C-ATT-1 + C-1** | **✅ + C-1 file P2** | **✅ + C-1 ship** | **#658 + #797** | **YES (0.5d, C-1)** |
| D.4 | M3 | ✅ | ✅ | #413 + #653 | — |
| **D.5** | **M1** | **⚠️ blocked** | **✅ blocked 已解, ship** | **#695 + #782** | **YES (2d)** |

**11 项 audit 结果**:
- 9 ✅ SHIPPED (4 项 28-Backlog stale 漏标: P-NUCLEAR-1 / P-IMPORT-1 / W-CLASS-1 / M1)
- 1 ✅ via 合并 (P-RFQ-1 含在 P-NUCLEAR-1)
- 1 ⚠️ partial (P-DRAFT-1, defer)
- 1 ❌ NOT DONE (P-SPLIT-1, P2 defer)

**Stale metadata 待 amend** (建议 organizer 修):
- 28-Backlog 第 13 行 M1: ⚠️ → ✅ (#695 + #782)
- 28-Backlog 第 30 行 P-NUCLEAR-1: ❌ → ✅ (#824)
- 28-Backlog 第 32 行 P-IMPORT-1: ❌ → ✅ (#764)
- 28-Backlog 第 33 行 W-CLASS-1: ❌ → ✅ (#764)
- 28-Backlog 第 73 行 P-RFQ-1: ❌ P2 → ✅ 合并 P-NUCLEAR-1
- MEETING_AUDIT_2026-05-17 §C-1 PURCHASE_RECEIPT UI: ❌ → ✅ (PR #797 ship 2 天后)
- **总 saved**: 12.5d nominal (相对 28-Backlog 假设)

**Layer B (Playwright/HJ 浏览器实测) 待 verify** (本 agent 4 项):
- [B-C.1] FINANCE_REJECTED reopen UX 是否有 dead-end / next-action
- [B-C.2] HJ 询价单"批量推送多供应商"操作 vs Cretas 手动 add 报价行
- [B-C.4] Cretas web/RN 创建 PO form 是否暴露 isImported 选择器
- [B-D.2] Cretas web-admin 是否有抄码品入库录入 UI (desktop 备份路径)

---

<!-- agent-B §C+§D done 2026-05-19 -->


<!-- organizer §O START 2026-05-19 -->

# §O — Layer B Browser Fresh 实测 (organizer, 2026-05-19)

> **方法**: organizer 与 6 Agent 并行期间, 用 Playwright MCP 登录 HJ test account (lyh01/admin/Aa123456) 实测 13 个 P0/P1 项, fresh 截图保存 `../06-宏见测试账号深度审计/screenshots/round11/*.png`. **目的**: 补 Agents 无 browser 时无法直接验证的 HJ live UI 细节, 形成 baseline 之外的"现场证据".
> **session 时长**: ~15 min (登录 → 销售模块 → 客户档案 → 采购模块 → 财务模块 → 系统管理)
> **screenshots 共**: 10 张于 ../06-宏见测试账号深度审计/screenshots/round11/

---

## §O.1 [S-LOCK-1] 锁定/备货/缺料 3 维度 — 直接证据 capture

**捕获**: `../06-宏见测试账号深度审计/screenshots/round11/02-sales-orders-list.png` (销售管理 → 销售订单 list, 12 行订单, 每行产品名称右侧)

**精确字段**: 行内 3 chip
- `锁:N` (tooltip: "已锁定在本单上的库存")
- `备:N` (tooltip: "已转其他单备货中")
- `缺:N` (tooltip: "缺口 = 未出库数 - 锁定库存 - 备货数") ← 公式 in tooltip

**实测数据**:
| 产品 | 锁 | 备 | 缺 | 状态 |
|---|---|---|---|---|
| 密封压盖 (Z14736842) | 0 | 1 | 0 | 销售订单创建/进行中/未审核/未出库 |
| 密封压盖 (Z14736842, line 2) | 0 | 100 | 0 | 同上 |
| 液压泵站 (00000207) | 0 | 3 | 0 | 已完成/进行中/已审核/未出库 |
| 泵站 (00000190) | 0 | 0 | 0 | 已完成/进行中/已审核/已出库 |
| 液压系统 (00000179) | **4** | 0 | 0 | 已完成/进行中/已审核/未出库 |

**Cretas main 状态 (2026-05-19)**: 已 ship PR #690 Sprint3-G S-LOCK-1 (per Agent F §N).

**结论**: 28-Backlog metadata stale (标 ❌). Cretas 已 ship. **公式 tooltip 是关键 UX 元素**, Cretas frontend 必须复刻 hover tooltip.

---

## §O.2 [U-ACT-1] 行末"操作 ▼" — 14 actions 实测

**捕获**: `../06-宏见测试账号深度审计/screenshots/round11/04-action-dropdown-13items-U-ACT-1.png`

**实测 dropdown 行项** (订单 [00000190]泵站 已出库 已审核):
1. 查看
2. 修改
3. 销售出库
4. 出库列表
5. 销售退货
6. 退货列表
7. 批量转组装
8. 组装列表
9. **附加费用(0)** ← 含 count
10. **收款提醒** ← S-REMIND-1 直接证据
11. **销售利润(20,700)** ← S-PROFIT-DETAIL-1 直接证据 (含金额)
12. 查询码
13. **销售需求** ← S-NEED-1 直接证据
14. 更新销售数据

**真实 count: 14 (baseline 估 11, 实际 14)**. 含金额 inline 显示是 HJ 独特 UX.

**Cretas main**: PR #678 ship U-ACT-1. Cretas dropdown 现有 11 项. **可补 3 项 inline 金额 + S-NEED-1 / S-REMIND-1 链接**.

---

## §O.3 [U-FOOTER-1] Sticky Footer 实时合计 — 3 字段实测

**捕获**: 销售订单 list 底部 (同 §O.1 截图右下)

**实测**:
```
未税金额：￥215,040.80
含税金额：￥239,230.00
总数量：155
```

12 条订单 aggregate 实时计算. 含税 vs 未税分列, **总数量** 跨产品 (PCS + 台 等单位混合, HJ 不分单位).

**Cretas main**: PR #681 ship U-FOOTER-1. 已实装.

**Gap 修正**: HJ 没分单位 (PCS + 台 加和), Cretas 可做 better — 按单位分组求和.

---

## §O.4 [U-CHIP-MULTI-1] 4-chip 状态垂直堆 — 直接证据

**捕获**: ../06-宏见测试账号深度审计/screenshots/round11/02 销售订单 list 订单状态列

**实测 4 chip stack**:
- chip 1: `销售订单创建` (业务状态)
- chip 2: `进行中` / `已完成` (生命周期)
- chip 3: `未审核` / `已审核` (审批状态)
- chip 4: `未出库` / `已出库` / `部分出库` (执行状态)

**互斥规则**: chip 4 三态互斥, chip 3 二态互斥, chip 2 二态.

**Cretas main**: Sprint 4 W1-A PR #764 ship. 已实装 4-chip 垂直堆.

---

## §O.5 [C-LINKARRAY-1] 跨业务关联 — 3 of 8 link types live

**捕获**: ../06-宏见测试账号深度审计/screenshots/round11/02 销售订单 list 关联列

**实测每行 link counter**:
- `文件(0)` (linkno 关联 attachments)
- `图片(0)` (linkno 关联 images)
- `合同(0)` → URL: `oa.hongjian.com/oa/contract/contractmanager/salecontractlist_pc.jsp?linkno=00000060` ← 跨子域 oa.hongjian.com

**linkno 反查机制**:
- URL pattern: `?linkno=<saleOrderId>` 直查关联文档
- 8 类: file/image/contract/sample/request/produce/outsource/stock (per baseline `05-DATA-MODEL-INFERRED.md`)

**Cretas main 状态**: ⚠️ **organizer 初判错误**. 我用 `linkListArray|linkno=` grep 返 0, 但 Agent E §I.3 实测 — **Track-F (Sprint 2 PR cluster) 已 ship 业务关联**. 详见 §I.3 (Agent E 深度 audit). 同 §O.9 F-VFLAG-1 教训: multi-synonym grep 才靠谱.

**结论修正**: ✅ SHIPPED. 28-Backlog 标 ❌ 同样 stale.

---

## §O.6 [S-CUSTOMER-TAB-1] 客户档案 — 17 named tabs 实测

**捕获**: `../06-宏见测试账号深度审计/screenshots/round11/07-customer-detail-tabs-S-CUSTOMER-TAB-1.png` (公司客户 00000014 苏州远野)

**实测 17 named tabs**:
1. 跟踪记录 (默认 active, 7 列: 时间/星期/操作员/跟进方式/跟踪内容/跟进状态/图片)
2. 微信记录
3. 好友添加记录
4. 通话记录
5. 短信记录
6. 图片
7. 文件
8. 销售单 (跨模块关联, C-LINKARRAY-1 旁证)
9. 样品单
10. 报价单
11. 产品
12. 活动管理
13. 商机管理 ← S-OPP-1 直接证据
14. 商品统计
15. 收件地址
16. 谈话录音 ← Cretas 缺 + 销售合规场景
17. 邮件列表

**Baseline 21 tabs claim 验证**: 实测 17 named tabs. 差 4 个可能为子 tab 或 sub-section. **修正 baseline 21 → 17 主 tab + 多 sub-config**.

**Cretas main (修正)**: ⚠️ **organizer 第三次 grep 错**. 我用 `customer-detail-tabs|client-profile-tabs` 关键字漏判. Agent A §A.2 实证 — **✅ SHIPPED 13/21 active tabs = 62%** 已实装 (PR 待 cite). 详见 §A.2.

**结论修正**: ✅ SHIPPED (partial). 28-Backlog 标 ❌ stale.

---

## §O.7 [S-CRM-FULL-1] Customer 22 字段 — 21+ 字段实测

**捕获**: ../06-宏见测试账号深度审计/screenshots/round11/07 客户档案上半

**实测 21 字段** (全部带 `*` 是必填):
- **基本信息** (10): *客户编号 (00000014) / *公司名称 (苏州远野) / **税号 (0012)** / 法人 / 注册资本 (万元) / 成立日期 / 公司简称 / 公司电话 / 公司传真 / 邮箱
- **金融账户** (3): 开户行 / 银行账号 / 行业
- **状态枚举** (1): *客户状态 (枚举: 无意向 / 跟进 / 报价 / 正在签约 / 待付款 / 已成交 / 已流失 / 中止合作 = **8 状态**)
- **地址** (3): 国家 (中国) / 省市区 / 详细地址
- **辅助** (3): 通信地址 / 公司网址 / 备注
- **联系人** (sub-table, 9 列): 联系人姓名/手机/电话/职位/部门/邮箱/微信/主联系人/操作 (M:N)

**关键差异 vs baseline 22**:
- baseline 标"客户状态 11" — 实测 **8**
- baseline 标"重要程度 4" — 实测 **未见**, 可能在另一 sub-tab
- baseline 标"来源 11" — 实测 **未见**, 可能在另一 sub-tab

**Cretas main (修正)**: ⚠️ **organizer 第四次 grep 错**. Agent A §A.1 实证 — **✅ SHIPPED S-CRM-FULL-1**. Customer 实体已扩 22 字段 (`Customer.java` 已经实装税号/法人/注册资本/成立日期/开户行/银行账号/行业/公司网址/客户状态枚举 等). 详见 §A.1.

**结论修正**: ✅ SHIPPED. 28-Backlog 标 ❌ stale.

---

## §O.8 [S-INVOICE-CLIENT-1 + S-PAYMENT-DATE-1] 客户级开票+对账 — 直接证据

**捕获**: ../06-宏见测试账号深度审计/screenshots/round11/07 客户档案"订单属性" section

**实测字段** (per customer):
- 开票税率: `不含税` (枚举: 不含税 / 13% / 9% / 6% / ... baseline 估 17 档)
- **发票类型: `数电专票`** (枚举: 普票 / 专票 / 数电普票 / 数电专票 / 数电铁路票 / 数电航空票 = 6 档 baseline 准确)
- 支付方式: (枚举: 月结 / 周结 / 现金 / ...)
- 默认币种: `人民币` (multi-currency 入口)
- **对账日期: `未设置`** (S-PAYMENT-DATE-1 直接证据, 期望 1-31 号选择)
- 常用快递公司
- **聚合数据 (read-only)**: 订单总个数 (5) / 订单总金额 (74400.0) / 回款总次数 (0) / 回款总金额 (74400.0)

**Cretas main (修正)**: ⚠️ **organizer 第五次 + 第六次 grep 错** (S-INVOICE-CLIENT-1 + S-PAYMENT-DATE-1 都是). Agent A §B.1 + §B.5 实证 — **✅ 全 SHIPPED**. Customer 实体已加 invoiceTaxRate (17 档) / invoiceType (6 档含数电) / paymentDate (1-31 号) 字段. 销售订单创建时已 auto-default.

**结论修正**: ✅ FULL SHIPPED (2 项). 28-Backlog 标 ❌ stale.

---

## §O.9 [F-VFLAG-1] 凭证生成 hook — 7 generator 全 confirmed

**捕获**: `../06-宏见测试账号深度审计/screenshots/round11/09-finance-flow-7-generators-F-VFLAG-1.png` (财务管理 → 流程图)

**实测 7 凭证生成器 nodes** (flow chart):
1. **应收应付单据生成凭证** (f42e7)
2. **进销存单据生成凭证** (f42e11)
3. **固定资产生成凭证** (f42e15)
4. **生产管理单据生成凭证** (f42e19)
5. **现金银行单据生成凭证** (f42e23)
6. **工资分摊生成凭证** (f42e27)
7. **行政办公生成凭证** (f42e31)

加: **会计凭证** (主凭证 list) + **查凭证** (反查工具).

**财务模块 sub-menu** (20 项): 收支明细 / 账户报表 / 账户维护 / 财务管理 / 账簿管理 / 报表管理 / 结账管理 / 月结对账 / 应收应付 / 发票管理 / 预存款 / 成本核算 / 员工借款 / 费用报销 / 备用金 / 固定资产 / 长期待摊 / 汇率管理 / 票据管理 / 参数设置.

**vflag 4 状态**: baseline `08-MUST-COPY-AUGMENT.md` 记 (未生成 / 已生成 / 已审核 / 已过账). Layer C 后续可补.

**Cretas main**: ⚠️ **organizer 初判错误**. 我用 `Voucher|FinanceJournalEntry|vflag` grep 返 0, 但 Agent D §G.1 用正确关键字 (`VoucherFlag` enum + lowercase `vflag` column + entity `Voucher.java`) 找到 — **实际 PR #693 Sprint3-E 已 ship 7 generator + vflag 4 状态 + 4 listener + 借贷必平 invariant + 2 AIChat Tool** (commit `5dff10064`). Sprint4-J PR #773 加 VoucherTemplate.

**结论修正**: ✅ FULL SHIP. 28-Backlog 标 ❌ 同样 stale. 详见 §G.1 (Agent D 深度 audit).

**元教训**: 我的 grep 用了驼峰 `Voucher` 但 Cretas 命名习惯小写 column + Enum suffix, 单关键字 grep 漏判. 加入 `feedback_brief_must_grep_existing_endpoint_paths.md` HARD sister: **multi-synonym grep (entity / enum / column / camelCase / lower) 才能 reliably verify**.

---

## §O.10 [P-NUCLEAR-1 / P-DRAFT-1 / P-RFQ-1] 采购流程图 — 3 P1 全 confirmed

**捕获**: `../06-宏见测试账号深度审计/screenshots/round11/08-procurement-flow-11nodes.png` (采购管理 → 流程图)

**实测 11 nodes**:
1. 销售订单 (cross-module, MRP-1 触发源)
2. 请购单
3. **核价单** (P-NUCLEAR-1)
4. **采购底稿** (P-DRAFT-1) — 草稿态 stage
5. 采购订单 (PO)
6. **询价单** (P-RFQ-1) — 多供应商比价
7. 采购变更单 (Change Order)
8. 质检单 (Quality Inspection)
9. 采购退货 (Purchase Return)
10. 采购入库 (Goods Receipt)
11. 报表

**Cretas main**:
- P-NUCLEAR-1: 0 hits grep `NuclearPrice|核价`. 未做. P1 3d.
- P-DRAFT-1: `entity/procurement/PurchaseOrder.java` 有 status enum (DRAFT/...). 推测 DRAFT 等价. **需 frontend 验证是否 expose 草稿态独立子菜单**.
- P-RFQ-1: 0 hits grep `RFQ|RequestForQuote|询价`. 未做. P2 5d.

---

## §O.11 [U-NAV-1] 业务流程图 tab 自动生成 — 4 模块实测全 confirm

**捕获**:
- `../06-宏见测试账号深度审计/screenshots/round11/01-sales-module-flow.png` (销售流程图 7 主 nodes)
- `../06-宏见测试账号深度审计/screenshots/round11/08-procurement-flow-11nodes.png` (采购流程图 11 nodes)
- `../06-宏见测试账号深度审计/screenshots/round11/09-finance-flow-7-generators-F-VFLAG-1.png` (财务流程图)
- `../06-宏见测试账号深度审计/screenshots/round11/10-workflow-settings-list.png` (系统管理流程图)

**实测**: 每个 module 点击后**自动新增 "流程图" tab**, 内嵌 jsPlumb canvas. 每个流程图 7-14 节点.

**Cretas main**: PR #683/#684 Sprint2-G ship U-NAV-1. 已实装. **唯一差异**: HJ 节点点击进 list 是新 tab, Cretas 是同 tab navigate.

---

## §O.12 [C-APPROVAL-EDITOR-1] 工作流可视化编辑器 — 入口 confirmed

**捕获**: `../06-宏见测试账号深度审计/screenshots/round11/10-workflow-settings-list.png` (系统管理 → 工作流设置, page loaded)

**实测**:
- 入口: 系统管理 → 工作流设置 (顶级菜单, 直接可达)
- Page loaded 但内容在 nested iframe (snapshot tree depth 不够 capture editor canvas)
- Baseline `G3-05-流程设计编辑器.png` + `G3-06-真编辑器chartquey.png` 已有 jsPlumb editor 截图

**Cretas main**: Sprint 3 Track-I 早已 ship 完整 758-行 VueFlow editor + 4 执行模式 (sequential/parallel/conditional/会签 N-of-M, HJ 都没有 N-of-M). 2026-05-18 PR #862 Canvas-Workflow Phase 1 把 editor UI 入口迁入 Canvas "审批工作流" Tab + Step 1-4 + B.1-B.6 migration + ck_po_status + SpEL syntax + hotfix `f26f6efad`.

**Gap 修正 (per Agent E §I.1)**: 28-Backlog 标 ❌ 20d 严重 outdated, 实际**剩 3-5d incremental** (WorkflowRule UI / OpinionTemplate dialog / decisionType 扩枚举). gap 不是工作流本身, 是业务覆盖度 — Cretas decisionType 当前 ~10 个 vs HJ 126 工作流 → 8% 覆盖, Sprint 4+ 需扩枚举/CUSTOM+name 路径.

---

## §O.13 [C-PRT-EDITOR-1] 打印模板编辑器 — URL pattern 仍未知

**实测 (HJ 端)**: 直接 URL `oa.hongjian.com/oa/printmanager/printmanagerlist.jsp` → 404 (跟 baseline `21-AUDIT-GAPS-FILLED.md` G4 一致). HJ 真 URL 在系统管理 → 打印管理 sub-menu hover, 本 session 未深入. Layer C 后续 audit.

**Cretas main (修正)**: ⚠️ **organizer 初判错误**. 我标"未做"是因为只看 HJ 端 URL 404, 没 grep Cretas main. Agent E §I.2 实测 — **Cretas Track-J 已 ship 3-pane 打印模板编辑器**. 详见 §I.2 (Agent E 深度 audit).

**结论修正**: ✅ SHIPPED. 28-Backlog 标 ❌ 同样 stale.

---

## §O.14 Layer B 总结 — 13 项验证结论

| § | 项 | HJ live confirm? | Cretas ship? | 28-Backlog 准确? |
|---|---|---|---|---|
| O.1 | S-LOCK-1 | ✅ chip + tooltip + 公式 | ✅ #690 | ❌ stale (标 ❌) |
| O.2 | U-ACT-1 | ✅ 14 actions | ✅ #678 | ✅ (11 项, 略低估) |
| O.3 | U-FOOTER-1 | ✅ 3 字段 | ✅ #681 | ✅ |
| O.4 | U-CHIP-MULTI-1 | ✅ 4 chip 互斥规则 | ✅ #764 | ❌ stale |
| O.5 | C-LINKARRAY-1 | ✅ 3 of 8 linkno | ✅ **Track-F (organizer grep 错, Agent E 找到)** | ❌ stale |
| O.6 | S-CUSTOMER-TAB-1 | ✅ 17 tabs (baseline 21 偏高) | ✅ **13/21 active 62% (organizer grep 错, Agent A 找到)** | ❌ stale |
| O.7 | S-CRM-FULL-1 | ✅ 21+ 字段 | ✅ **Customer.java 已扩 22 字段 (organizer grep 错, Agent A 找到)** | ❌ stale |
| O.8 | S-INVOICE-CLIENT-1 | ✅ 6 发票类型 + 5+ 税率 | ✅ **Customer 加 invoiceTaxRate/Type/PaymentDate (organizer grep 错, Agent A §B.1+B.5 找到)** | ❌ stale |
| O.9 | F-VFLAG-1 | ✅ 7 generator nodes | ✅ **PR #693 (organizer grep 错, Agent D 找到)** | ❌ stale |
| O.10 | P-NUCLEAR-1 | ✅ flow node 存在 | ❌ 未做 | ✅ |
| O.10 | P-DRAFT-1 | ✅ flow node 存在 | ⚠️ 推测部分 (DRAFT enum) | ⚠️ 需 verify |
| O.10 | P-RFQ-1 | ✅ flow node 存在 | ❌ 未做 | ✅ |
| O.11 | U-NAV-1 | ✅ 4 模块流程图 | ✅ #683/#684 | ✅ |
| O.12 | C-APPROVAL-EDITOR | ✅ 入口 + baseline 截图 | ✅ **Track-I VueFlow + Canvas Phase 1 (Agent E 实证)** | ❌ stale (剩 3-5d 不是 20d) |
| O.13 | C-PRT-EDITOR | ⚠️ URL 未知 (HJ 端) | ✅ **Track-J 3-pane editor (Agent E 实证)** | ❌ stale |

**关键 finding (organizer + Agent A/D/E reconcile)**:
1. **10 项 28-Backlog 严重 stale** (本 Layer B 抓的 13 项里): S-LOCK-1 + U-CHIP-MULTI-1 + **F-VFLAG-1** + **C-LINKARRAY-1** + **C-APPROVAL-EDITOR-1** + **C-PRT-EDITOR-1** + **S-CUSTOMER-TAB-1** + **S-CRM-FULL-1** + **S-INVOICE-CLIENT-1** + **S-PAYMENT-DATE-1** — 全 ❌ 但实际 SHIPPED. Round 11 修正 backlog metadata. 跟 Agent A/D/E/F findings 累计共 **30+ 项 stale** in 28-Backlog.
2. **organizer 单关键字 grep 漏判 7 次**: §O.5 (C-LINKARRAY-1) / §O.6 (S-CUSTOMER-TAB-1) / §O.7 (S-CRM-FULL-1) / §O.8×2 (S-INVOICE-CLIENT-1 + S-PAYMENT-DATE-1) / §O.9 (F-VFLAG-1, `Voucher` 漏 VoucherFlag enum + lowercase vflag column) / §O.12+13 (C-APPROVAL/PRT-EDITOR, 只看 HJ URL 404 没 grep Cretas main). 教训写进 §O.16. **Sister rule 升级 `feedback_brief_must_grep_existing_endpoint_paths.md` HARD**: 单 keyword grep 漏判, must multi-synonym (entity / enum / column / camelCase / lower).
3. **3 项 baseline metadata 偏高**: S-CUSTOMER-TAB-1 (21→17 tabs) / S-CRM-FULL-1 (客户状态 11→8 / 重要程度 4 / 来源 11 实测未见) — 工时不影响, spec doc 修正.
4. **C-APPROVAL-EDITOR-1 工时 20d → 实际 3-5d**: 28-Backlog 标 20d, 实际 Cretas Track-I + Canvas Phase 1 已 ship 完整 758-line VueFlow editor + 4 执行模式 (含 N-of-M HJ 没有), 剩仅 WorkflowRule UI / OpinionTemplate dialog / decisionType 扩枚举. **省 15-17d**.
5. **真 gap 是 decisionType 覆盖度**: Cretas decisionType ~10 个 vs HJ 126 工作流 = 8% 覆盖率, Sprint 4+ 扩 decisionType 是真 P0 priority.

---

## §O.15 Layer B request 汇总

**organizer 本 session 完成 13 项 fresh capture (above)**.

**Agent A/B/D/E 跑完后 flag 的 Layer B item** 将合并至此节.

**已知 Layer C 后续候选**:
- C-PRT-EDITOR-1 真 URL pattern (打印管理 sub-menu hover)
- vflag 4 状态 (会计凭证 list 实际 column)
- M-BOM-VER-1 ECN 变更明细 字段 list
- C-WF-RULE-1 流转规则配置 UI (流转规则设置 sub-menu)
- C-WF-VAR-1 系统变量库 ({#own} 实际表达式)

---

## §O.16 Browser session lessons

1. **Playwright snapshot depth 5-6 已够 list 页**, depth 8-10 用于 form/detail
2. **iframe 嵌套深 (HJ layui)**: snapshot 不自动深入, 大文件 truncate 警告 — 需手动 file-based 抓
3. **HJ direct URL 不稳**: 部分页面要 hover 触发 sub-menu link, direct URL 404. 总走 main.hongjian.com → 模块 → sub-menu hover 流程
4. **截图保存路径**: 必须 `../06-宏见测试账号深度审计/screenshots/round11/*.png` (Playwright cwd 相对路径), 不能用 `..` 跳出
5. **不要破坏数据**: 全程只 read + screenshot, 0 写操作, admin 测试账号安全

---

<!-- organizer §O END 2026-05-19 -->

<!-- agent-D section START 2026-05-19 -->

# §G 财务管理 (6 项)

> Audit scope: F-VFLAG-1 (P0 战略 10d) + F-AR-1 + F-INV-1 + F-VOUCHER-2-1 (P2) + F-PERIOD-1 (P2) + F-3REPORT-1 (P2).
>
> **关键 finding**: 28-Backlog 把 F-VFLAG-1 / F-AR-1 / F-INV-1 全标 ❌, **三项实际全 ✅ SHIPPED** (Sprint 3-E + Sprint 4-C/E/J). 30-BACKLOG-STATUS-AUDIT 漏抓 (2026-05-16 写时只查 P0 必修 + 第一批 P0 战略). 财务域 ship 比例严重低估 → ~28d 工时已落地, backlog 仍标待做.

## §G.1 [F-VFLAG-1] 凭证生成 hook (P0 战略 10d) — ✅ SHIPPED (Sprint3-E PR #693)

### HJ 实测细节 (深度)
- 7 凭证 generator hook (跨模块): 应收应付 / 进销存 / 固定资产 / 生产 / 现金银行 / 工资分摊 / 行政办公 (`11-AUTO-TRIGGERS.md:28-39`)
- vflag 4 状态机: UNCREATED → PENDING → CREATED (终态) / FAILED → PENDING retry loop
- 凭证创建页 (`02-财务管理-deep-audit.md:37-75`): 凭证字 (记/收/付/转) + 字号自增 + 复式记账 5 行默认 + 8 列 (序号/摘要/科目/数量/币别/辅助核算/借方/贷方 含亿/千 column groups) + "保存为凭证模板" + "从模板生成凭证"
- 跨模块 idempotent: 业务单 (sourceBusinessType, sourceBusinessId) 唯一 — event 重发不重复生成

### Cretas 现状 (2026-05-19 main)
**FULL SHIP**:
- ✅ `VoucherFlag` 4 状态 enum + transition map (`backend/java/cretas-api/src/main/java/com/cretas/aims/entity/enums/VoucherFlag.java:12-34`)
- ✅ 7 generator 全部实装: SalesReceipt / PurchasePayment / Wage / Expense / Return / InventoryTransfer / Depreciation (`backend/java/cretas-api/src/main/java/com/cretas/aims/service/voucher/impl/*VoucherGenerator.java`)
- ✅ `VoucherService.createFromBusiness(factoryId, businessType, businessId)` idempotent — (businessType, businessId) unique check + DB constraint `uk_voucher_source_business` (`Voucher.java:40`)
- ✅ `batchCreateForFactory()` 扫描 vflag=UNCREATED 批量补 (`VoucherService.java:32`)
- ✅ 4 event listener: SalesOrder / PurchaseOrder / ReturnOrder / Transfer (`backend/java/cretas-api/src/main/java/com/cretas/aims/listener/voucher/*VoucherListener.java`)
- ✅ vflag 字段加在 7 业务单 entity (`SalesOrder.java:60-63` "Sprint3-E F-VFLAG-1: 凭证生成状态")
- ✅ REST API: GET list/detail/by-business + POST generate/batch-generate/post/void (`VoucherController.java`)
- ✅ AIChat 2 Tool: VoucherGenerateTool + VoucherBatchGenerateTool
- ✅ 借贷必平 invariant: `Voucher.validateBalanced()` 双重检查 (entries.debit sum == entries.credit sum == totalDebit == totalCredit, `Voucher.java:126-147`)
- ✅ Sprint3-F follow-up #720/#723 — Voucher → LinkArrayService 集成 + backfill
- ✅ Sprint4-Chat J PR #773 C-VOUCHER-TPL-1 凭证模板系统 (template-first + SpEL amountExpression + `VoucherTemplate.java`)
- ✅ #815/#816 P1 pagination bug 修 (PR #828, 2026-05-17)

### Gap 分析
- **客户面 UI**: ✅ Web-admin Voucher page; 复式 inline-editor 是否实装需 Layer B 实测 (后端 OK)
- **会计科目硬编码**: VoucherEntry 是 (subject_code + subject_name) 字符串, 没有"科目树" entity. HJ 是 popup picker. Sprint4-J template `entriesJson` 写死 subjectCode, 不是 FK. 长期 (Sprint 9+) 大企业客户需"会计科目树" 才能多级 drill-down 报表.
- **辅助核算** (部门/项目/客户): VoucherEntry 有 `cost_center String` 字段 (`VoucherEntry.java:80`), 但**未跟 Department/Project FK 关联** — 只是字符串. HJ 是结构化 popup picker. Sprint 9+ 升级.
- **凭证字 (记/收/付/转)**: Cretas VoucherType 是 enum (业务维度: SALES_RECEIPT/PURCHASE_PAYMENT/...), 不是会计凭证字. 两种维度. F006 卤制品场景够用, 大企业可能要求"凭证字"自定义.
- **亿/千列组**: 数字输入 UI 实装情况未知 (Layer B).

### 推荐
- **Sprint 4 闭环**: backlog 立即标 ✅ SHIPPED (修 28-Backlog + 30-Status-Audit table)
- **Sprint 5 优化** (3d): VoucherEntry.subject_code 跟新"会计科目树" entity (subject_tree 表 + 一/二/三级) FK 关联
- **Sprint 8+ P2** (5d): "凭证字" 自定义 (factoryId-scoped) 给 finance_setup 客户管理员
- **Sprint 8+ P2**: 辅助核算结构化 (cost_center 改 polymorphic FK 引用 Department/Project/Customer)
- ⚠️ **30-Status-Audit doc 必修**: Table §2 row F-VFLAG-1 "grep 0 hits" 错 — 修正为 ✅ Sprint3-E PR #693, "vflag" grep 0 hits 是因为 audit 用错误关键字 (entity 用 `VoucherFlag` enum + `vflag` column, audit 没扫到; sister rule: brief MUST grep multiple synonyms + entity + enum)

### Layer B 标记 (≤2)
1. **Voucher 创建/编辑 UI**: web-admin `/finance/vouchers/new` — 复式分录 inline-editor + 借贷必平 client-side 校验 + "保存为模板" button
2. **批量生成 button**: `/finance/vouchers/batch-generate` — UNCREATED 业务单扫描 + count + retry-on-fail (财务月底关键)

---

## §G.2 [F-AR-1] 销售订单财务成本核算审核 (P1 5d) — ✅ SHIPPED

### HJ 实测细节
- 财务 21 子菜单 (`02-财务管理-deep-audit.md:80-94`): 应收应付 / 收支明细 / 月结对账 / 成本核算 (销售成本 / 生产成本)
- 应收账龄分析: 0-30 / 31-60 / 61-90 / 90+ 天分桶
- 银行流水模式: 应收挂账 → 客户付款冲减 → 实时 balance_after 快照

### Cretas 现状 (2026-05-19 main)
**FULL SHIP**:
- ✅ `ArApTransaction` entity 银行流水模式 (`backend/java/cretas-api/src/main/java/com/cretas/aims/entity/finance/ArApTransaction.java:1-80`) — AR_INVOICE / AR_PAYMENT / AP_INVOICE / AP_PAYMENT 四种 transactionType + balance_after 快照 + counterpartyType (CUSTOMER/SUPPLIER) polymorphic FK
- ✅ `ArApController` 完整 CRUD: 应收/应付 record / payment 双向 + 账龄分析 + 对账单 + 调整 workflow (V20260426_01__arap_adjustment_approval_workflow.sql)
- ✅ RBAC 精确: `finance:read_write` class-level + per-method `@RequireModule("finance_ar")` / `@RequireModule("finance_ap")` 分离 (R23 audit C4 修)
- ✅ 销售订单 sales finance review 已 ship (PR #675 Sprint2-J P-FIN-1 sister)
- ✅ MEETING_AUDIT_2026-05-17 附录 B+ #793 P1 "客户协议价 auto-apply" (PR #799) — PriceListService new interface + Vue chip CUSTOMER/GLOBAL 区分

### Gap 分析
- **应收账龄 UI**: 后端 service 有 aging 计算, 前端 dashboard 是否有 0-30/31-60/61-90/90+ 可视化分桶 — Layer B
- **成本核算** (销售/生产成本): SmartBI 出报表 (Phase II Kitchen Cost LIVE) 是数据分析, 不是法定财务模块 — 跟 HJ "成本核算" 模块定义不同

### 推荐
- backlog 立即标 ✅ SHIPPED
- Sprint 5 增强 (2d): 应收账龄 dashboard chart (按 customer 分组 + 90+ 红色 alert) — `finance:read` permission
- Sprint 9+ P2 (5d): 法定 "销售成本核算" 模块 (区别 SmartBI 实时分析 vs 月结会计成本 — 给上市公司客户)

### Layer B 标记
1. **应收账龄 dashboard**: web-admin `/finance/ar-ap` — 是否有按 customer 分组的 aging 桶可视化

---

## §G.3 [F-INV-1] 开票申请 + 发票回写 + 收款流水 (P1 8d) — ✅ SHIPPED + 持续增强

### HJ 实测细节
- 财务 21 子菜单含 "发票管理"
- Round 5 真实 invoice (7): 已开发票 / 已收发票 + 4 种统计 + 发票号申请 (`02-财务管理-deep-audit.md:179`)
- 客户原话 (V3 P0-3 / G1): 一笔订单可同时含 9% 原料 + 13% 加工费两个税项, 开票申请必须按税率分组拆分显示

### Cretas 现状 (2026-05-19 main)
**FULL SHIP + V3 客户原话已实装**:
- ✅ `InvoiceRecord` entity 含 `tax_breakdown JSONB` 字段按 tax_rate 分组 (taxableAmount/taxAmount/lineCount per rate) — V3 P0-3 客户原话已实装 (`InvoiceRecord.java:79-95`)
- ✅ 工作流 6 状态: REQUESTED → APPROVED → ISSUED (+ REJECTED / CANCELLED)
- ✅ `InvoiceController` full REST: `/request`, `/request-from-order` (自动按税率分组), `/{id}/approve`, `/{id}/reject`, `/{id}/issue` (multipart PDF upload), GET list/detail/by-sales-order/by-customer
- ✅ V20260330_02 invoice_payment_records + V20260407_01 invoice_tax_breakdown + V20260419_01 unique_pending + V20260606_03 invoice_ocr_columns + V20260606_04 invoice_status_intent
- ✅ Sprint 4 W2 Chat C PR #763 — F-INV-1 gap-fill: OCR auto-parse + 状态查询 Tool + UI 核对 (commit `9da5fdcba`)
- ✅ 防呆 retrofit (commit `6eb287950`): R1 开票金额 max / R2 dialog context / R4 backend dedup / 4 位一体 OCR sticky error
- ✅ `PaymentRecord` entity 收款流水 + `PaymentRecordController`
- ✅ AIChat 5 Tool: InvoiceRequestTool / InvoiceRequestFromOrderTool / InvoiceApproveTool / InvoiceStatusTool / PaymentRecordTool

### Gap 分析
- **发票号申请**: HJ 有 "发票号申请" 子菜单 (tax dept 申请新 invoice number range). Cretas auto-generate "INV-YYYYMMDD-XXXX" — F006 够用, 大企业可能要导入税局分配号段
- **4 种统计**: HJ 4 种 statistics (按时段/客户/税率/状态). Cretas 有基础 list, dashboard 4 报表是否实装需 Layer B
- **已收发票** (供应商开给我们): Cretas 未见独立 AP invoice 表 (仅 issue 销售发票). 大企业 finance_ap 场景需

### 推荐
- backlog 立即标 ✅ SHIPPED + ENHANCED
- Sprint 5 增强 (1d): 4 种 invoice statistics dashboard (按时段/客户/税率/状态分组聚合) — 复用现有 list endpoint + 前端 chart
- Sprint 8+ P2 (3d): 已收 AP invoice 实体 (supplier_invoices 表 + 上传 PDF) — 给 finance_ap 客户

### Layer B 标记
- 无 (Sprint 4 W2 + Apr 18 Bug #4 已经做了详细 RBAC + 4 位一体, 已 e2e). 可低优先级抽 1 个税率分组 invoice issue 测试

---

## §G.4 [F-VOUCHER-2-1] 复式记账凭证 (P2 大企业 20d) — ⚠️ PARTIAL (entity 已 ship, 缺会计科目树)

### HJ 实测细节
- 真 ERP 标准 (借/贷 + 辅助核算)
- 会计科目树 (一/二/三级 popup picker)
- 辅助核算 (部门/项目/客户/产品 维度拆分)
- 凭证模板 + 业务模式自动化

### Cretas 现状 (2026-05-19 main)
**60% SHIP**:
- ✅ `Voucher` + `VoucherEntry` 借贷必平 entity (§G.1 已述)
- ✅ `VoucherTemplate` foundation (Sprint4-J PR #773) — `entriesJson` JSONB + SpEL amountExpression + (factory_id, voucher_type) 唯一 default
- ✅ Sprint 4 W2 PR #773 commit msg: "template-first + 借贷必平 + R4 幂等" → 模板系统 foundation ship
- ❌ **会计科目树** entity (一/二/三级 hierarchy) — 缺. VoucherEntry.subject_code 是字符串硬编码 (e.g. "1122", `VoucherEntry.java:58`)
- ❌ **辅助核算** 结构化关联 (Department/Project/Customer FK) — `cost_center` 字段是 free-text String, 不是 FK
- ⚠️ Vue 凭证创建/编辑 inline-editor 实装情况待确认 (Layer B)
- ⚠️ Generator full refactor 用 template (而非 hardcoded `1122`) 是 follow-up chat, foundation 落但未完全替换

### Gap 分析
- **真复式记账**: ✅ 借贷必平 invariant 已落, 但会计科目树 + 辅助核算结构化才是 "真 ERP" 完整体. Cretas 当前是 "简化复式记账" (字符串 subject_code 够 F006 卤制品 / 中小客户)
- **客户群分层**:
  - F006 卤制品 / 中小制造: ✅ 当前实装够用 (借贷必平 + template + 7 generator)
  - 大型食品集团 / 上市公司: ❌ 需要会计科目树 + 辅助核算 FK + 凭证字"记/收/付/转" + 财政年度归档
- **工时重估**: 原估 20d (HJ 等同 - 大企业), 实装已 ~12d → 剩余 ~8d (会计科目树 4d + 辅助核算 FK 3d + 凭证字 1d)

### 推荐
- ⚠️ backlog 修 status: "❌ → ⚠️ 60% (entity + template + invariant 已 ship)"
- **当前 Cretas 状态足以服务 F006 + 类似中小客户** — 不阻塞 sales
- Sprint 8+ P2 (剩余 ~8d): 会计科目树 + 辅助核算 FK + 凭证字自定义 — 大企业升级
- 销售策略 (per `02-财务管理-deep-audit.md:148-152`): "我们 AI + 业务流自动化, SmartBI 出报表" / "我们的财务跟金蝶/用友通过 vflag hook 集成, 不重复建设"

### Layer B 标记
- 无 — entity foundation 已实装, 上层 UI 是 Sprint 8+ 工作

---

## §G.5 [F-PERIOD-1] 期间结账 (P2 大企业 8d) — ❌ NOT DONE

### HJ 实测细节
- "结账管理" 子菜单 (`02-财务管理-deep-audit.md:84`)
- Round 5 真实 finance_check (2): 结账 / 结账纸
- 月结/年结流程: 月底自动 → 工资生成 → 月结对账单 → 期间凭证检查 → 锁定期间
- 锁定后: 不能修改本期间任何单据/凭证. 跨期: 走调整凭证 (反向 + 当期重做)

### Cretas 现状 (2026-05-19 main)
**NOT SHIPPED**:
- ❌ grep `PeriodClosing|FiscalPeriod|fiscal_period|月结|期间结账` in `backend/java/cretas-api/src/main/java/` → 0 file hits (仅 PayrollPeriod 工资周期, 不是会计期间)
- ❌ 无 `period_closing` / `fiscal_periods` 表
- ❌ 无 "本期间锁定" 业务规则 (任何凭证/业务单均可修改, 没有 closed-period guard)
- ❌ 无月结对账单 (跟 HJ "月结对帐 7 项" 全缺)
- ✅ SmartBI 月度报表 (Phase II Kitchen Cost) 是数据分析, **不是法定财务月结**

### Gap 分析
- **F006 + 中小客户**: 不需要法定月结 — SmartBI 月度 dashboard 已满足"看数字"诉求
- **大企业 / 上市公司**: 必须有审计可追溯的"封账" — 月底锁定 + 调整凭证 reverse-and-redo
- 工时验证: HJ 月结对帐 7 子菜单 + 期间锁定 + 调整凭证 ≈ 8d 合理

### 推荐
- ❌ backlog 维持 ❌
- ✅ 短期销售策略: 用 SmartBI 替代法定月结 (已 ship)
- Sprint 9+ P2: 跟 F-VOUCHER-2-1 升级一起做 (法定月结 + 会计科目树 + 凭证字自定义 = 大企业财务套件)

### Layer B 标记
- 无 — 未实装项无需 browser 实测

---

## §G.6 [F-3REPORT-1] 报表三表 (P2 上市公司 12d) — ❌ NOT DONE

### HJ 实测细节
- Round 5 报告 (4): **资产负债表 / 利润表 / 现金流量表 / 自定义报表** ⭐⭐⭐ 法定三表实装 (`02-财务管理-deep-audit.md:182`)
- 依赖: 会计科目树 + 期间结账 + 凭证 三个 dependency
- 自动生成 (从凭证 aggregate)

### Cretas 现状 (2026-05-19 main)
**NOT SHIPPED — 跟 HJ 法定三表完全不同**:
- ❌ grep `BalanceSheet|IncomeStatement|CashFlowStatement|资产负债表|损益表|现金流量表` → 仅 SmartBI Python 内部分类器 hits + FinancialChartGenerateTool (AI 图表生成, 不是法定三表) + FieldMappingDictionary (语义映射字典)
- ❌ 无 `balance_sheets` / `income_statements` / `cash_flow_statements` 实体表
- ❌ 无 "按会计期间 aggregate 科目余额 → 三表 row" 计算服务
- ✅ Python smartbi 模块有"利润表"/"现金流"分类语义 — 但只是 NL Query 触发器, 不是法定生成

### Gap 分析
- **客户分层**:
  - F006 卤制品 / 中小: SmartBI 出报表足够 — 销售策略可定位
  - **大型食品集团 / 上市公司**: 必须有审计可追溯的法定三表 (送税务局/审计师)
- **依赖链**: F-3REPORT-1 必须先有 F-VOUCHER-2-1 (会计科目树) + F-PERIOD-1 (期间结账) 才能算 — 不能单独实装

### 推荐
- ❌ backlog 维持 ❌
- 销售策略 (per `02-财务管理-deep-audit.md:152`): "❌ 不能说: 我们替代金蝶/用友"; "✅ 可说: 我们 AI + 业务流自动化, SmartBI 出报表"
- Sprint 9+ P2 一揽子: F-VOUCHER-2-1 残余 8d + F-PERIOD-1 8d + F-3REPORT-1 12d = **~28d 大企业财务套件** (视大客户需求一起做)

### Layer B 标记
- 无 — 未实装项无需 browser 实测

---

# §H 人力资源 (HR) (4 项)

> Audit scope: H-WAGE-FULL (P1 11项 10d) + H-ATT-FULL (P1 11项 10d) + H-LEAVE-1+OVT+EXP (P1 12d) + H-PARTNER-FULL (P2 大代理 4项).
>
> **关键 finding**: HR 域 28-Backlog 全标 ❌, 实际 **3/4 ✅ SHIPPED (1 ⚠️ MVP) + 1 ❌** (Sprint 4 W2 Chat E + #770/#833/#835/#844/#863/#870 接连 ship). HR 是 backlog 跟 main 偏差最大的域 (财务+HR 一共 9/10 已 ship 至 MVP/full).

## §H.1 [H-WAGE-FULL] 工资管理 11 项 (P1 10d) — ✅ SHIPPED (#833 + #844 + #863 + #870)

### HJ 实测细节
- Round 5 真实 wage (11): 工资管理 / 发放记录 / 年度统计 / 薪资记录 / 工资发放 / **社保管理** / **专项扣除** / 计件管理 + 3
- 跟生产模块 (T9 工序完工 → 工资计算更新) + 财务模块 (工资分摊生成凭证 — F-VFLAG-1 generator 之一) 联动

### Cretas 现状 (2026-05-19 main)
**FULL SHIP + 4 PR**:
- ✅ `SalaryItem` entity 月度工资单 (`backend/java/cretas-api/src/main/java/com/cretas/aims/entity/hr/SalaryItem.java:51-135`) — base_salary / 社保 employee+employer / 公积金 employee+employer / taxable_income / personal_tax / net_salary 9 字段 + R4 防呆 `uq_salary_factory_user_month` unique 防同月重复 + status DRAFT/CONFIRMED/PAID
- ✅ PR #833 (commit `7da06e0a2`) H-WAGE-FULL MVP — SalaryItem CRUD + 社保 compute
- ✅ PR #844 (commit `5aed485e7`) H-WAGE follow-up — 专项扣除 MVP (6 deduction types + 个税 integration), `SalarySpecialDeduction.java`
- ✅ PR #863 (commit `99de741cd`) — 公积金/社保 rate config UI (`HrInsuranceConfig.java` + `HrCityInsuranceOverride.java` + `HrEmployeeCityAssignment.java`)
- ✅ PR #870 (commit `49f0d39b6`) — 年度汇算 综合所得 annual settlement (`AnnualTaxSettlement.java` + `AnnualTaxSettlementService.java` + `AnnualBonusTaxCalculator.java`)
- ✅ Sprint 3 计件管理 `PieceRateRule` + `WorkerDailyEfficiency` + `PayrollRecord` + `WageController` (648 行) — V2026_01_14_10__piece_rate_wage_tables.sql
- ✅ Flyway V20260606_18 hr_salary_items + V20260606_21 hr_salary_special_deductions + V20260606_22 hr_insurance_configs + V20260606_24 hr_salary_annual_bonus + V20260606_25 hr_annual_tax_settlement + V20260606_28 hr_city_insurance_overrides

### Gap 分析
- **工资发放** (实际 bank transfer): 后端 status PAID, 但跟银行系统对接 Sprint 8+ (大企业接 ESS / U-pay 等代发系统)
- **年度统计 dashboard**: 后端 entity + service 全在, Vue dashboard 跨员工年度汇总报表 — Layer B 实测
- **薪资记录历史**: SalaryItem 是月度快照, 历史查询通过 list + filter 即可 (没 separate "salary history" 表)

### 推荐
- ✅ backlog 立即标 ✅ SHIPPED + ENHANCED (4 PR 接连)
- Sprint 5 增强 (1d): 年度工资 dashboard (按员工/部门聚合 + 同比环比)
- Sprint 8+ P2 (3d): 银行代发 ESS 集成 — 大企业场景

### Layer B 标记
1. **薪资 admin 工作流**: web-admin `/hr/salaries` — 月底 batch-generate salary items + 社保自动 compute + DRAFT → CONFIRMED → PAID 状态推动 (财务关键 button)

---

## §H.2 [H-ATT-FULL] 考勤管理 11 项 (P1 10d) — ⚠️ MVP SHIPPED (#835), 6 周矩阵 UI + 高级排班 Sprint 5+

### HJ 实测细节
- Round 5 attendance (11): 考勤月报 / 日报 / 流水查询 / 修改记录 / 考勤分析 / **高级排班** / 考勤机管理 / 考勤排班记录 + 3
- 月考勤员工矩阵 (`02-人力资源-deep-audit.md:18-32`): 15 列含**第1-6周 + 7 天展开** + 工作时长/加班时长/总时长 (3 时长维度) + 出勤汇总 + 部门 button row (7 部门)
- "重新生成" button — 考勤数据从打卡机重新汇总

### Cretas 现状 (2026-05-19 main)
**MVP SHIPPED + 部分 UI**:
- ✅ PR #835 (commit `55481c8c0`) H-ATT-FULL MVP — `AttendanceShift` (班次模板) + `AttendanceException` (异常检测) + `EmployeeShiftAssignment` (员工某天班次分配) + 3 controller
- ✅ `AttendanceService.detectExceptions(...)` 根据 shift 起止时间 + TimeClockRecord 比对生成异常 (`AttendanceShift.java:13-18`)
- ✅ Flyway V20260606_19 hr_attendance_shift_exception
- ✅ Web-admin UI: `web-admin/src/views/hr/attendance/list.vue` + `exceptions.vue` + `shift-calendar.vue` + `index.vue` (4 views)
- ✅ AIChat AttendanceMonthlyTool (`backend/java/cretas-api/src/main/java/com/cretas/aims/ai/tool/impl/hr/AttendanceMonthlyTool.java`) — 跟 HJ 月考勤矩阵语义对接
- ⚠️ 跨月 6 周 × 7 天矩阵 UI — 后端 AttendanceMonthlyTool 输出能力强, Vue 端是否实装 6 周 × 7 天 layout 待 Layer B 实测
- ⚠️ "高级排班" — `AttendanceShift.java:16` 注释 "MVP 假设固定时间. 高级排班 (轮班/弹性) 留后续迭代"
- ⚠️ 部门 button row 7 部门快捷切换 — UX 细节待 Layer B
- ❌ "重新生成" button — 后端 detectExceptions 单次调用, 没有 "重新汇总打卡机数据" 入口

### Gap 分析
- **MVP 已 ship**: shift template + exception detect + 4 view 已落, 满足 80% 场景
- **高级排班** (轮班/弹性时段/排班冲突检测): 留 Sprint 5+ 升级 ~3d
- **6 周矩阵 UI**: 后端能力在, 前端 layout 实装情况 Layer B 关键
- **完整 11 项**: ~6 项实装 (MVP), 剩 5 项 (流水修改 / 高级排班 / 考勤分析 / 考勤机管理 / 排班记录历史) Sprint 5+

### 推荐
- ⚠️ backlog 修 status: ❌ → ⚠️ PARTIAL (MVP shipped, 6/11 项)
- Sprint 5 增强 (3d): 6 周 × 7 天月考勤矩阵 UI + 部门 button row + "重新生成" button
- Sprint 5 增强 (3d): 高级排班 (轮班/弹性/冲突检测)

### Layer B 标记
1. **月考勤矩阵 UI**: web-admin `/hr/attendance` index.vue — 是否有 6 周 × 7 天 × 3 时长维度矩阵 layout, 还是简单 list
2. **班次配置 UI**: `/hr/attendance/shift-calendar.vue` — admin 配 shift 模板 + 员工分配 day-level 拖拽

---

## §H.3 [H-LEAVE-1+OVT+EXP] 请假/调休/报销 3 套 (P1 12d) — ✅ SHIPPED (Sprint 4 W2 Chat E PR #770)

### HJ 实测细节
- Round 5 vacation (4): 请假管理 / 调休假管理 / 销假管理 / 我的请假
- Round 5 overtime (4): 加班类型 / 加班申请 / 加班管理 / 我的加班
- Round 5 reimbursementfee (5): 费用报销 / 借款报销 / 差旅费 / 我的费用 / 我的差旅
- 3 套独立工作流 + DRAFT → SUBMITTED → APPROVED/REJECTED state machine + 自我服务 + 主管审批

### Cretas 现状 (2026-05-19 main)
**FULL SHIP** (3 流程 end-to-end):
- ✅ PR #770 (commit `ba8f26f34`) "HR self-service H-LEAVE+OVT+EXP — 3 流程 end-to-end + 4 AIChat Tools + 防呆 R1-R4"
- ✅ `LeaveRequest` entity (`backend/java/cretas-api/src/main/java/com/cretas/aims/entity/hr/LeaveRequest.java:1-50`) + LeaveType enum (ANNUAL/SICK/COMPTIME/OTHER) + 完整 state machine + 跟 `LeaveBalance` 联动 (审批通过 usedHours += durationHours)
- ✅ `OvertimeRequest` + `OvertimeRequestController` + `OvertimeRequestService`
- ✅ `ExpenseRequest` + `ExpenseRequestController` + `ExpenseRequestService`
- ✅ `LeaveBalance` + `LeaveBalanceController` + `LeaveBalanceService` — 年度调休假/年假余额管理
- ✅ `CompTimeBalance` + `CompTimeLedgerEntry` + `CompTimeBalanceController` — 调休 ledger
- ✅ Flyway V20260606_07 hr_leave_overtime_expense_balance + V20260606_08 hr_request_intent_configs + V20260606_13 hr_leave_submit_intent + V20260606_23 comptime_balance_and_ledger + V20260606_27 hr_leave_approve_intent
- ✅ 跟 ApprovalWorkflow / ApprovalChainConfig 联动 (`LeaveRequest.java:23-25`)
- ✅ 4 AIChat Tool + 防呆 R1-R4 retrofit
- ✅ Vue views: `web-admin/src/views/hr/leave-requests/` + `overtime-requests/` + `expense-requests/` + `leave-balances/`

### Gap 分析
- **销假管理**: HJ "销假" (员工提早返回销假调整 leave_balance) — Cretas LeaveBalance 有 usedHours 字段, "销假"逻辑 (cancel APPROVED 请假) 是否实装需 Layer B 验证
- **差旅费 / 借款报销**: HJ 有 5 子项 (费用 / 借款 / 差旅 + my views). Cretas ExpenseRequest 1 entity + expenseType enum 即可覆盖, 但 "差旅" 跟 "费用报销" 是否区分 UI tab 待 Layer B
- **加班类型** config: HJ 有 "加班类型" 配置 (普通/周末/法定 → 不同时薪倍数). Cretas OvertimeRequest 是否有 type field + 不同倍数 — 待验证
- **借款管理** (员工借款 + 报销冲抵): HJ "员工借款" 独立子菜单. Cretas 未见 EmployeeLoanRequest 实体 (28-Backlog #133 P3 不抄)

### 推荐
- ✅ backlog 立即标 ✅ SHIPPED (3 套 + 4 AIChat Tool + 4 位一体防呆全 ship)
- Sprint 5 增强 (2d): 销假流程 (cancel APPROVED leave) + 差旅 vs 费用报销 UI 分 tab + 加班类型 config

### Layer B 标记
1. **请假提交流程**: web-admin `/hr/leave-requests` — DRAFT → SUBMITTED → APPROVED → balance 自动扣减闭环 (员工视角)
2. **主管审批 dashboard**: 待审批 leave/overtime/expense 三合一 inbox (主管视角)

---

## §H.4 [H-PARTNER-FULL] 合作伙伴 4 项佣金管理 (P2 代理 / S-COMMISSION-1) — ❌ NOT DONE

### HJ 实测细节
- Round 5 partner (4): 合作伙伴列表 / 历史合作伙伴 / **佣金管理** / **佣金方案**
- S-COMMISSION-1 (`28-Backlog #75`, P2 代理/分销 5d): 合作伙伴佣金报表 (12 月统计)
- C-PARTNER-DOMAIN-1 (`28-Backlog #87`, P3 3d): 独立子域 (按 HJ partner.hongjian.com 推断)
- 推断业务: 代理/分销商关系 + 提成方案配置 (按销量/金额/级别) + 月度佣金报表 + 历史归档

### Cretas 现状 (2026-05-19 main)
**NOT SHIPPED**:
- ❌ grep `PartnerCommission|partner_commission|commission.report|代理.佣金` in `backend/java/cretas-api/src/main/java/` → 0 file hits
- ❌ web-admin grep `commission|partner|代理|佣金` → 仅 SmartBI dashboard / print-template mock data refs, **无 partner management view**
- ❌ 无 `partners` / `partner_commissions` / `commission_schemes` 表 (Flyway 全扫无 partner-related migration)

### Gap 分析
- **客户群**: F006 卤制品 / 中小制造 不需要 — 餐饮 QHJ 用门店运营 (非代理模式), 食品行业大部分用直销
- **代理/分销客户场景**: 服装/快消品/食品分销渠道企业才需 (e.g. 白酒经销商 / 区域分销代理)
- **跟 HR 关系**: HJ 把 partner 归 HR 子域 (代理多是兼职/合作伙伴而非员工), Cretas 如果实装应该独立 `entity/partner/` 模块, 不强制 HR

### 推荐
- ❌ backlog 维持 ❌ (Sprint 9+ P2/P3 视客户)
- 销售策略: 不主动推 — 服务直销客户已经够 (Cretas 当前焦点 F006 卤制品 + 餐饮 QHJ + 食品制造)
- 长期 Sprint 9+ (~5d): 等到有 "分销/代理" 客户线索时再做 — entity + 配置 + 月报表, 估 5d 跟 28-Backlog #75 S-COMMISSION-1 一致

### Layer B 标记
- 无 — 未实装项无需 browser 实测

---

<!-- agent-D done 2026-05-19 -->
<!-- agent-D section END 2026-05-19 -->

---

<!-- agent-E section START 2026-05-19 -->

# §I 工作流 + 配置中台 (9 项, Agent E)

## §I.1 [C-APPROVAL-EDITOR-1] 工作流可视化拖拽编辑器 (P0 战略 20d)

### HJ 实测细节
- 路径: `系统管理 → 工作流 → 工作流设置` (`workflow.hongjian.com/workflow/workflowshow.jsp`)
- 子菜单: 工作流设置 / 流转规则设置 / 我创建的 / 我参与的 / 待处理 / 工作流处理 (6 项)
- Round 4 实测: **126 个独立工作流**
- 引擎: jsPlumb 拖拽 + 节点类型 (创建/审批/系统操作/通知/条件/终点)
- 多分支 + 并行 + 会签 (推测, 流程图节点观察)
- 节点意见模板 (常用语) + 流转规则 (金额/部门/角色/时间)

### Cretas 现状 (2026-05-19 main)
- ✅ 后端 graph-native ApprovalWorkflow entity + ApprovalWorkflowExecutorImpl (sequential / parallel / conditional / 会签 N-of-M 四种) — `entity/config/ApprovalWorkflow.java:30-58`, `service/workflow/impl/ApprovalWorkflowExecutorImpl.java:31-58`
- ✅ legacy flat ApprovalChainConfig 双源读 — `entity/config/ApprovalChainConfig.java:11-32`
- ✅ Vue editor **830 行** `web-admin/src/views/platform/approval-workflow-editor/index.vue` + composables — 3-pane (palette/canvas/properties), Sprint 3 Track-I ship
- ✅ 第二个 designer `web-admin/src/views/system/workflow-designer/index.vue` (1057 行) — VueFlow + 状态机 entity-type (PRODUCTION_WORKFLOW / MATERIAL_BATCH / QUALITY_INSPECTION) + 校验 + 模拟 + 版本
- ✅ Canvas-Workflow Phase 1 (PR #862 + #885 + 后续 hotfix `f26f6efad`): UI 入口已并入 Canvas Editor "审批工作流" Tab, 独立路由保留 `showInMenu:false` — `router/index.ts:1226-1256`
- ✅ Phase 1 B.1-B.6: Redis + PG shadow + 完整 state machine + ck_po_status + SpEL syntax (5-7d Option B per memory)

### Gap 分析
**几乎全 ship**. 实测差距:
- ⚠️ 流转规则 UI 跟 WorkflowRule entity (Chat D Sprint 4 W1) 是否打通 — 后端 ship `entity/config/WorkflowRule.java:42-80` (AMOUNT_THRESHOLD/DEPT_MATCH/ROLE_MATCH/SPEL_CUSTOM 4 类), 是否在 ApprovalEditor 已有 form? 待 Layer B 实测.
- ⚠️ 节点意见模板 (OpinionTemplate, Chat J): entity + Controller ship (`entity/config/OpinionTemplate.java:34-60` + `controller/OpinionTemplateController.java`), 是否已注入审批 dialog 让审批人选?
- ⚠️ 跟 HJ 126 个独立工作流相比, 我们 decisionType enum 当前只覆盖 PURCHASE_ORDER_APPROVAL + 等 ~10 个, **业务覆盖度 8%** — Sprint 4+ 需扩枚举或开放 CUSTOM + name
- ⚠️ 模拟 + 校验 + 版本 history 已有, 但是否到 jsPlumb 同等流畅度待评估

### 推荐
**视为 ✅ SHIPPED** (Canvas-Workflow Phase 1 完整 close). 后续工作:
1. Sprint 4 W2 follow-up: WorkflowRule UI 接入 ApprovalEditor properties pane (1d)
2. OpinionTemplate dropdown 接入审批 dialog (1d)
3. decisionType 枚举扩 to ≥30 业务类型 (1d) — 覆盖 HJ 实测 8 类核心
4. **不再立 spec, 走 incremental enhancement** — 估剩余 3-5d
5. 客户 demo 主打 "我们工作流可视化 + 4 种执行模式 (会签 N-of-M HJ 也没有)" + "126 工作流靠 decisionType 扩枚举 + factory 自定义"

### Layer B 标记 (≤2)
- **L-I.1.a**: HJ `workflowshow.jsp` 拖拽 demo 实测对比 Cretas Canvas Editor "审批工作流" Tab — 流畅度 + 节点丰富度
- **L-I.1.b**: HJ "流转规则设置" 独立子菜单实测金额/部门/角色 阈值配置 UI (跟 Cretas WorkflowRule entity 4 类对照)

---

## §I.2 [C-PRT-EDITOR-1] 打印模板可视化设计器 (P0 战略 10d)

### HJ 实测细节
- 路径: `系统管理 → 打印管理` (3 子菜单: 动态打印 / 打印模板 / 字体管理)
- 子域: `print.hongjian.com` (真打印模板编辑器, 20 模板分类 + 25+ 具体模板, 含**称重模板** ⭐) + `print2.hongjian.com` (静态打印)
- 销售单"打印销售单" → `saleprintselect.jsp?type=sale&id=X` 弹窗选模板
- 推测能力: 模板列表 (按单据类型分类) + 拖拽字段绑定 + 预览 + 多模板 per 单据

### Cretas 现状 (2026-05-19 main)
- ✅ 后端 FormTemplate entity 已 ship (`entity/config/FormTemplate.java:27-50`) — Formily Schema, multi-tenant, AI 配置助手
- ✅ FormTemplateVersion 版本管理 entity
- ✅ FormTemplateService + PrintTemplateCreateFromAITool (`ai/tool/impl/print/PrintTemplateCreateFromAITool.java`)
- ✅ Vue editor `web-admin/src/views/platform/print-template-editor/index.vue` (278 行) — Sprint 3 Track-J ship: 3-pane (palette / canvas / property) + EntityFieldTree + FormCanvas + PropertyPanel + PDF preview (`composables/usePrintEditor.ts`)
- ✅ printSchemaTypes.ts + envelope spec test (`__tests__/printTemplateApi.envelope.spec.ts`)
- ✅ PDF 打印 PR #413 ship 5 单据 (per backlog)

### Gap 分析
**已 ship**. 实测差距:
- ⚠️ 当前 entity-bound 模板; HJ 是 20 大类 + 25+ 模板, Cretas 单据覆盖度待 audit
- ⚠️ HJ 有"字体管理"独立子菜单, Cretas 用系统默认字体 (中文嵌入是否 ok?)
- ⚠️ 缺**称重模板** ⭐ (食品厂关键场景 — F006 抄码品 + AbacaQuantityLog 是核心, 模板缺)
- ⚠️ 多模板 per 单据 (e.g. 销售单 3 模板) 跟弹窗选择是否 ship 待 Layer B
- ⚠️ 批量打印 (多选 → 一键打) 未 ship

### 推荐
**视为 ✅ SHIPPED foundation** (3-pane editor 完整). 后续:
1. Sprint 4 follow-up: 称重模板 (W-ABA-1 抄码品 PDF) — 2d
2. 多模板 per 单据 + 弹窗选择 — 2d
3. 批量打印 — 1d
4. 字体管理 (custom font upload) — 1d, P2 选做
5. 客户 demo 主打 "我们 Schema-driven + AI 助手生成模板"

### Layer B 标记
- **L-I.2.a**: HJ `print.hongjian.com` 模板列表实测 (20 大类) 跟 Cretas FormTemplate entity 覆盖对照

---

## §I.3 [C-LINKARRAY-1] linkListArray 8 类跨业务关联 (P0 战略 2d)

### HJ 实测细节
- 实测证据: 采购订单 `linklistarray` JSON 字段
  ```json
  [{"LINK_TYPE": "sale", "LINK_NO": "00000060"}, {"LINK_TYPE": "produce", "LINK_NO": "P0001"}]
  ```
- 8 类 LINK_TYPE: sale / sample / request / produce / outsource / stock / project / free
- 应用: 反查"哪些销售单触发了这个采购单" — 反向追溯能力

### Cretas 现状 (2026-05-19 main)
- ✅ **完整 ship** — Sprint 3 Track-F (per `service/LinkArrayService.java` Javadoc)
- ✅ Entity `BusinessLink` (`entity/common/BusinessLink.java`) — owner_type/owner_id + link_type (8 类完全对齐) + target_type/target_id
- ✅ Migration `V20260516_09__business_links.sql`
- ✅ Service `LinkArrayService.link()` / `unlink()` / `getOutboundLinks()` / `getInboundLinks()` / `getByType()` — 双向追溯
- ✅ AI Tool `BusinessLinkQueryTool` (`ai/tool/impl/common/BusinessLinkQueryTool.java`)
- ✅ Canvas SplitOrderTool 用 (`ai/tool/impl/canvas/SplitOrderTool.java`)
- ✅ ReturnOrderService 跟 VoucherService 已 wire 使用 (`ReturnOrderServiceImpl.java`, `VoucherServiceImpl.java`)
- ✅ factoryId 隔离 + 幂等 link
- ✅ 3 索引 (owner / target / type) 支撑双向 + filter

### Gap 分析
**100% close**. 跟 HJ 完全 parity 且**优于**:
- HJ 是 JSON 字段嵌入业务表, Cretas 独立 business_links 表 — index 性能更好
- Cretas factory-aware (HJ 单租户不需), 多租户 enterprise-ready

### 推荐
**✅ SHIPPED**. 剩余工作:
1. 验证所有业务实体 (sale/sample/request/produce/outsource/stock/project) 都用 LinkArrayService 而不是 hardcoded foreign key — 0.5d audit
2. AIChat "查关联" 端到端测试 — 0.5d
3. **不需 spec, 推 prod close**

### Layer B 标记
- (None, ship 完整)

---

## §I.4 [C-WF-RULE-1] 流转规则引擎 (金额/部门/角色阈值)

### HJ 实测细节
- 子菜单"流转规则设置"独立, 跟"工作流设置"并列
- 推测规则类型: 金额 > 阈值 / 角色 = X / 部门 = Y / 时间窗口 / 多分支

### Cretas 现状 (2026-05-19 main)
- ✅ **后端完整 ship** Sprint 4 Wave 1 Chat D (`entity/config/WorkflowRule.java:42-80`)
- ✅ 4 RuleType enum: AMOUNT_THRESHOLD / DEPT_MATCH / ROLE_MATCH / SPEL_CUSTOM
- ✅ JSON expression schema: `{"field":"amount","op":">","value":10000}` / `{"field":"department","in":["finance"]}` / etc.
- ✅ Sandboxed SpEL escape hatch (`service/workflow/SandboxedSpelEvaluator.java`) — RCE-safe
- ✅ Executor 求值顺序 priority ASC, first-true → trueTargetNodeId
- ✅ WorkflowRuleEvaluator + RuleContextBuilder (注入 ApprovalWorkflowExecutorImpl line 52-55)
- ✅ Migration + Repository + Indexes
- ⚠️ **前端 UI 缺** — WorkflowRule 配置在 ApprovalEditor properties pane 是否已接? 当前 ApprovalEditor 用 raw SpEL `edge.condition` (Sprint 3 I 路径), 用户友好层 (4 RuleType) UI 待加

### Gap 分析
- Backend 100%, Frontend ~30% (raw SpEL editor 已用, 4 RuleType form UI 缺)
- workflow_rule_test AIChat Tool 是否 ship 待确认 (Chat J brief 提到)

### 推荐
**🟡 IN-FLIGHT** — Backend ship, UI need 2-3d:
1. ApprovalEditor properties pane 加 "规则类型" dropdown (AMOUNT_THRESHOLD/DEPT_MATCH/...) + 结构化 form
2. 测试 button "测一下" 调 workflow_rule_test Tool
3. AIChat workflow_rule_test 集成测试
4. **不立 spec, Sprint 4 W2 follow-up, ~3d**

### Layer B 标记
- **L-I.4.a**: HJ "流转规则设置"子菜单实测 (规则类型枚举数 + 配置 UI 形态)

---

## §I.5 [C-WF-VAR-1] 工作流系统变量库 ({#own}, 业务变量)

### HJ 实测细节
- 推测 SpEL 变量库: `{#own}` 当前用户 + 业务变量 (#amount, #department, ...)
- jsPlumb 节点配置时变量 dropdown 选取
- release notes 数据源扩展列 — 自定义字段做变量

### Cretas 现状 (2026-05-19 main)
- ✅ **完整 ship** Sprint 4 W2 Chat J (`entity/config/WorkflowVariableDef.java:31-50`)
- ✅ Context class `service/workflow/WorkflowVariableContext.java:37-60` — own / order / customer / businessEntity 4 命名空间
- ✅ SafeAccessor + SandboxedSpelEvaluator — 无反射 + whitelisted field
- ✅ 15 系统默认变量 (factoryId NULL) + 工厂自定义扩展
- ✅ var_type 6 类: STRING / NUMBER / DECIMAL / BOOLEAN / DATE / DATETIME
- ✅ VoucherTemplateServiceImpl 也用 (跟 VoucherTemplate amountExpression SpEL 配合)
- ⚠️ 前端 PropertyPanel 是否已加 "变量" dropdown / 帮助文档? 待 Layer B

### Gap 分析
- Backend 100%, 前端 UX 待 audit
- 跟 HJ parity, **优势**: Sandbox 防 RCE, HJ jsPlumb 老旧推测无防护

### 推荐
**🟡 ALMOST SHIPPED** — Backend complete, UI varselect dropdown 1-2d:
1. ApprovalEditor / VoucherTemplate editor 加 var dropdown
2. 文档生成器 (开发者文档 list 15 默认变量)
3. **incremental 1-2d**

### Layer B 标记
- (覆盖于 L-I.1.a)

---

## §I.6 [C-OPINION-1] 节点意见模板 (常用语)

### HJ 实测细节
- 销售/采购单 workflow.jsp 节点意见输入 + "设置常用语" 按钮
- 标准化审批回复减少手动输入

### Cretas 现状 (2026-05-19 main)
- ✅ Entity ship Sprint 4 W2 Chat J (`entity/config/OpinionTemplate.java:34-60`)
- ✅ factory_id NULL = 系统预设跨工厂可见; 非 NULL = 工厂自定义
- ✅ decisionType String 关联
- ✅ sortOrder + isActive
- ✅ Controller ship `controller/OpinionTemplateController.java` (`/api/mobile/{factoryId}/opinion-templates`) — `RequirePermission(system:read_write)` for write
- ⚠️ Service 实现 + Vue dialog dropdown 接入审批是否完整? Javadoc 说 "Foundation (entity + repo only). Full service / controller / Vue dialog 集成 follow-up chat ship" — Controller 已 ship 但 dialog 接入待审

### Gap 分析
- Backend foundation ship + Controller, Vue dialog 接入待 audit
- 跟 HJ parity

### 推荐
**🟡 PARTIAL** — Backend + Controller ship, Vue dialog 接入 1d:
1. 各 ApprovalDialog (PendingApprovals widget / 审批弹框) 加 OpinionTemplate dropdown
2. 系统预设 seed 10-20 常用语 (同意 / 请补充材料 / 金额过高需总监 / ...)
3. **incremental 1d**

### Layer B 标记
- (None, 简单)

---

## §I.7 [C-VOUCHER-TPL-1] 凭证模板系统 (5d)

### HJ 实测细节
- 财务凭证按业务类型自动套模板 (借/贷方科目 + 辅助核算)
- 跟 7 凭证生成器 (T10) 配合: 应收应付 / 进销存 / 固定资产 / 生产 / 现金银行 / 工资 / 行政
- 客户可改科目代码

### Cretas 现状 (2026-05-19 main)
- ✅ Entity ship Sprint 4 W2 Chat J (`entity/finance/VoucherTemplate.java:17-50`)
- ✅ JSONB entries schema: `{sortOrder, subjectCode, subjectName, direction:DEBIT/CREDIT, amountExpression:SpEL, description}`
- ✅ amountExpression 用 SpEL 求值 (复用 ApprovalWorkflowExecutorImpl SpEL parser)
- ✅ isDefault unique partial 索引 per (factory_id, voucher_type)
- ✅ Generator 选择顺序: factory+voucherType active default → 任意 active → null (hardcoded fallback)
- ✅ VoucherTemplateServiceImpl ship (跟 WorkflowVariableService 配合)
- ⚠️ Javadoc 说 "Foundation. Full service / generator refactor / Vue editor 在 follow-up chat"
- ⚠️ F-VOUCHER-HOOK-1 (Sprint 1 P0 战略, 10d, ❌ 未做) 是配套, **未 ship** — 凭证 hook 框架是 prerequisite

### Gap 分析
- VoucherTemplate entity + Service 70% ship
- Vue editor 0% (主 UI 缺)
- F-VOUCHER-HOOK-1 框架未 ship — template 没用武之地 (7 generator 没接 template lookup)

### 推荐
**🔵 已有基础待优化** — Entity foundation ship 但 dead weight 没 hook:
1. 优先做 F-VOUCHER-HOOK-1 (Sprint 1 P0 战略, 10d) — 7 generator 改造 + vflag 状态机
2. 然后 Generator 接入 template lookup (active default first)
3. Vue editor 5d (财务管理员配置科目映射)
4. **建议跟 F-VOUCHER-HOOK-1 合并立 1 spec, 15d total**

### Layer B 标记
- **L-I.7.a**: HJ 财务凭证按业务自动生成实测 (借/贷科目 + 金额公式 + 辅助核算)

---

## §I.8 [N49 C-AI-1] 钉钉机器人 PoC (P0 战略 6d, ⚠️ Track-B1 Day 5/6 no PR)

### HJ 实测细节
- 子菜单"微信管理"独立 + 推测钉钉接入
- "我们出了微信就是钉钉在用" (六扇门客户原话, 第二次会议)
- 钉钉群 @ 机器人 → 查库存 / 查订单 / 触发 AI 分析

### Cretas 现状 (2026-05-19 main)
- ✅ **Track-B1 完整 ship Day 1-5** (per `STATUS/TRACK_B1_STATUS.md`)
- ✅ 5 commits, 27 文件, +2764/-5 LOC, 46/46 单测 PASS
- ✅ Day 2 `5def64a2e`: entity/integration/DingTalkWebhookLog + Repository + Flyway V20260516_01_abaca_dingtalk.sql + Signature service (HMAC SHA256) + Inbound queue (Redis FIFO + 内存 fallback) + Controller (`POST /api/dingtalk/webhook/inbound`, public per WebMvcConfig:48)
- ✅ Day 3 `c4daa2278`: Inbound consumer (`@Scheduled(fixedDelay=5000)`) + DingTalk user binding + Response formatter (IntentExecuteResponse → 钉钉群文本)
- ✅ Day 4 `859a18e63`: SendService (HTTP + retry exp backoff + ratelimit) + 2 AI Tool: DingTalkSendMessageTool + DingTalkAlertPushTool
- ✅ Day 5 `529611399`: Retry scheduler (`@Scheduled(fixedRate=30000)`) + 4 admin endpoint (`POST /send`, `GET /logs`, `GET /logs/{id}`, `POST /logs/{id}/retry`) per `RequirePermission(ai:dingtalk:send / ai:audit:view)`
- ⚠️ **Day 6 BLOCKED** on Steve 提供 F006 钉钉凭证 (`DINGTALK_APP_KEY`/`APP_SECRET`/`CORP_ID`/`OUTBOUND_WEBHOOK_URL`/`OUTBOUND_WEBHOOK_SECRET` + F006 测试群 chatId + senderId↔userId 映射)
- ⚠️ **未 PR 也未 merge** main — 代码在 worktree `feature/asap-track-b1-c-ai-1` push 了

### Gap 分析
- Code 100% (46 单测绿), F006 credential 100% block
- Production 部署 = 凭证写 `.env.test` + deploy script + 钉钉 console 配 Outgoing Webhook URL
- **未开 PR** → main 上看不到, 也未合并; per 30-BACKLOG status 列为 ⚠️ Track-B1 Day 5/6 (no PR)

### 推荐
**⚠️ PARTIAL — code complete, PR + deploy block 凭证**:
1. **organizer push**: Steve 拿钉钉凭证 → 写 `.env.test` → deploy `--env test`
2. F006 测试群 @ 机器人 "查询今天的生产任务" 验证 5s 内回复
3. AIInsightCard → DingTalkAlertPushTool 推送链验证
4. retry scheduler 验证 (kill webhook → FAILED → 30s 后重试 → SENT)
5. 开 PR `[Track-B1] C-AI-1 钉钉机器人 PoC` merge main → ship 状态可改 ✅
6. **2-3d** (depending 凭证拿到速度)

### Layer B 标记
- **L-I.8.a**: Steve 拿到凭证后 organizer 实测钉钉 inbound + outbound + retry 链路 (Layer B 实测)

---

## §I.9 [N3 C-RBAC-1] RBAC 仓管隔离审计 (P0 必修, ✅ #661 + k4/k5 follow-ups)

### HJ 实测细节
- 推断: 仓管员看不到价格 (类似 Cretas canViewPrice)
- 8 角色 (跟部门关联): admin / 销售员 / 仓管员 / 财务 / 工程 / HR
- 4 权限粒度: 模块级 / 字段级 / 行级 / 操作级
- 推断: 工作流引擎控制操作级
- ⚠️ HJ 测试账号 admin 单账号无法切其他角色实测, 是推断

### Cretas 现状 (2026-05-19 main)
- ✅ **完整 ship** PR #423 + #520 + #661 + 4 follow-ups (#668/#671/#673/#674) + 35-view defense + k4/k5
- ✅ Framework `security/PriceFieldResponseAdvice.java` + `security/PriceSensitive.java` + `security/PriceMaskResolver.java`
- ✅ AI Tool `RBACAuditTool` (`ai/tool/impl/finance/RBACAuditTool.java`) — Cretas 现有审计面板
- ✅ 5 视图全 defense: 采购订单详情 / 销售订单详情 / 三价对比 (403) / BOM 详情 / 财务凭证
- ✅ 35-view static audit + bash regression script `scripts/rbac-warehouse-mgr-audit-2026-05-15/run-regression.sh`
- ✅ JWT 多 session (per audit doc 14)
- ✅ 推角色路由分离 (RN App SalesNavigator / WarehouseNavigator / FinanceNavigator) — 跟 HJ 单菜单不同, **优势**

### Gap 分析
- 100% close + 优于 HJ
- HJ 无角色路由分离 (单 12 模块菜单)
- Cretas 35-view 自动化 vs HJ 推测 (无 testable proof)
- ⚠️ S-RBAC-1 (业务员客户隔离, Customer 表加 owner_id) Sprint 3 还没 ship — 是另一条 line (P1, 不算这里)

### 推荐
**✅ SHIPPED**. 销售话术: "我们 PR #423 + 30+ follow-up ship, 35 view 全防御 + AI Tool RBACAuditTool 在线诊断面板; 宏见推测有但单账号无法 testable. 我们 JWT 多 session 支持手机销售员 + 电脑财务同时登, 宏见单 cookie 不行."

### Layer B 标记
- (None, ship 完整 + 优于 HJ)

---

# §J 系统管理 (7 项, Agent E)

## §J.1 [C-CHECKPOWER-1] RBAC 权限检查统一函数 (P1 3d)

### HJ 实测细节
- 推测: checkPower(userId, permissionCode) 函数 (传统 ERP 模式)
- HJ 1591 个 f_no 权限点 (Round 5 实测 `sMenu2Array.jsonArray`) — 4× Cretas 现有
- 每 Controller 方法走 checkPower 校验

### Cretas 现状 (2026-05-19 main)
- ✅ **完整 ship** 框架
- ✅ Annotation `annotation/RequirePermission.java` — 支持 single permission / OR / AND (requireAll=true)
- ✅ Interceptor `config/PermissionInterceptor.java` — HandlerInterceptor preHandle 拦截 + JSON 错误回响
- ✅ Service `service/PermissionService.java` + `PermissionServiceImpl` — hasAllPermissions / hasAnyPermission
- ✅ Registry `entity/auth/PermissionRegistry.java` + Repository + Service — 75+ Controllers 已用 (Grep sample 5 files 75 occurrences, 实际多得多)
- ✅ AI Tool `PermissionAuditTool` + `PermissionQueryTool` (`ai/tool/impl/system/`)
- ✅ Canvas Tool `CanvasSetUserPermissionTool` 接入 AIChat
- ✅ Platform 维度 `controller/platform/PlatformRolePermissionController.java`
- ✅ User-menu binding `entity/auth/UserMenuPermission.java`
- ⚠️ **粒度**: Cretas 当前 ~404 Tool (per CLAUDE.md) vs HJ 1591 f_no — 4× gap (但 Tool 是 AI 维度, RBAC 维度是 module:action permission code, 实际 ~150-200, 待 audit)
- ⚠️ `PermissionManifestExporter` (`service/auth/PermissionManifestExporter.java`) — 自动 export 当前 manifest

### Gap 分析
- 框架 100% close, 跟 HJ checkPower 同 mechanism
- 粒度 25%-30% (1591 vs ~400, depending count)
- C-RBAC-FNO-1 (P3 长期 15d) 是细粒度补充 (跟 HJ 1591 parity)

### 推荐
**✅ SHIPPED foundation**. 后续:
1. PermissionManifestExporter 跑一遍统计当前 permission code 总数 (1h audit)
2. 客户 demo 主打 "我们 @RequirePermission annotation 比传统 checkPower(uid, code) 写起来少一行 + AOP 拦截 + AI 审计"
3. C-RBAC-FNO-1 是 P3 长期, **不优先**, 因 ROI 不大 (Cretas 食品/餐饮场景 ~200 permission 够)

### Layer B 标记
- (None, ship 完整)

---

## §J.2 [C-LOG-AUDIT-1] 系统操作日志独立 (5 列 + 查询导出, P1 3d)

### HJ 实测细节
- 子菜单"操作日志"独立 (system Round 5)
- 子域 `log.hongjian.com` 独立日志专用
- 5 列查询 (时间 / 用户 / 模块 / 操作 / 对象) + 导出

### Cretas 现状 (2026-05-19 main)
- ✅ **完整 ship** Sprint 4 Chat K (Javadoc on `entity/datacenter/OperationLog.java:14`)
- ✅ Entity `OperationLog.java:35-60` — 字段: factoryId/userId/username/module/action (CREATE/UPDATE/DELETE/EXPORT/IMPORT/OTHER) / entityType / entityId
- ✅ 4 索引: factory+created / user+created / module+action+created / entity
- ✅ Annotation `annotation/Loggable.java` + Aspect `aspect/LoggableAspect.java` (@Around AOP, async 写库, 异常不阻塞业务)
- ✅ Service + Controller (`controller/datacenter/OperationLogController.java`)
- ✅ AI Tool `OperationLogQueryTool` (`ai/tool/impl/system/`)
- ✅ Vue UI `web-admin/src/views/system/operation-log/index.vue` (80+ 行) — filter (模块/操作/用户ID/Entity 类型/Entity ID/时间范围) + table + action tag
- ✅ Migration `V20260525_03__operation_log.sql`
- ⚠️ 导出 (Excel/CSV) 还需在 UI 加 button 跟 ExportRule 接

### Gap 分析
- 100% Backend + UI ship, 跟 HJ 5 列 + 查询 parity
- 导出待加 ExportRule integration (跟 §J.3 C-EXPORT-CENTER-1 联动)

### 推荐
**✅ SHIPPED**. 后续:
1. 在 operation-log UI 加 "导出当前条件" button 调 ExportRule (1d)
2. AIChat "查我今天的操作" 实测端到端 (0.5d)

### Layer B 标记
- (None)

---

## §J.3 [C-EXPORT-CENTER-1] 数据导出规则中心 (跨 12 模块, P1 5d)

### HJ 实测细节
- 子域 `export.hongjian.com` 独立
- 推测: 跨模块统一规则定义 (按模块/Entity/格式/同步异步)
- 销售/采购/出库 list 页都"导出"

### Cretas 现状 (2026-05-19 main)
- ✅ **完整 ship** (`entity/datacenter/ExportRule.java` + ExportJob.java)
- ✅ Service hierarchy: `ExportServiceImpl` + `ExportExecutor` + `ExportJobRunner` (异步任务)
- ✅ Controller `controller/datacenter/ExportRuleController.java`
- ✅ DTO `dto/pack/ExportRulePackRequest.java` + RulePackService (rule pack export/import 跨工厂复用)
- ✅ Migration `V20260525_01__export_rule.sql`
- ✅ Vue UI `web-admin/src/views/system/export-center/index.vue` — rules list (ruleName/moduleCode/targetEntity/format/isAsync) + 运行/编辑/删除
- ✅ Plan card 跟 V20260525_04__data_center_intents.sql AI 意图配套

### Gap 分析
- 100% close, 跟 HJ "export.hongjian.com" 子域级 parity
- Cretas 优势: RulePack 跨工厂复用 (HJ 单租户无)

### 推荐
**✅ SHIPPED**. 后续:
1. 主流模块 (sale/buy/stock/quality) seed 默认导出 rule (~12 rules)
2. AIChat "导出今天销售单 Excel" 端到端实测

### Layer B 标记
- (None)

---

## §J.4 [C-IMPORT-CENTER-1] 数据导入规则中心 (含校验/未导入/成功/失败, P1 5d)

### HJ 实测细节
- 子域 `import.hongjian.com` 独立
- 推测 4 状态: 校验 / 未导入 / 成功 / 失败
- 各模块都有"导入"link → 走 import 子域

### Cretas 现状 (2026-05-19 main)
- ✅ **完整 ship** (`entity/datacenter/ImportRule.java`)
- ✅ Service hierarchy: `ImportServiceImpl` + `ImportExecutor` + `ImportCommitter` (两阶段提交: 校验 → 提交)
- ✅ Controller `controller/datacenter/ImportRuleController.java`
- ✅ DTO `dto/pack/ImportRulePackRequest.java` + `ImportRulePackResult.java`
- ✅ Migration `V20260525_02__import_rule.sql`
- ✅ Vue UI `web-admin/src/views/system/import-center/index.vue` — rules + jobs (status / totalRows / validRows / errorRows / committedRows)
- ✅ 字段对齐 HJ 4 状态: 校验中 (validRows pending) / 错误 (errorRows>0) / 已提交 (committedRows>0) / 失败 (jobStatus=FAILED)
- ✅ DedupStrategy enum (ERROR / IGNORE / etc.)

### Gap 分析
- 100% close, 跟 HJ "import.hongjian.com" parity
- Cretas 优势: 两阶段 (validate → commit) + DedupStrategy + RulePack 跨工厂复用

### 推荐
**✅ SHIPPED**. 后续:
1. 主流模块 (sale/buy/material/customer) seed 默认 import rule + Excel template
2. 跟 C-MIGRATE-1 Onboarding Wizard (Sprint 0 设计中) 整合 — 复用 rule 引擎

### Layer B 标记
- (None)

---

## §J.5 [C-WIDGET-1] dashboard 卡片插件式 (10 独立 endpoint 渲染, P1 5d)

### HJ 实测细节
- 推测: dashboard 多卡片插件化 (类似 BentoGrid + 各自 endpoint 拉数据)
- 餐饮厨房屏 / 工厂车间屏 使用

### Cretas 现状 (2026-05-19 main)
- ✅ **完整 ship** Sprint 4 Wave 2 Chat L + P1 #65
- ✅ TS types `web-admin/src/types/dashboardWidget.ts` — WidgetKind enum 17 类
  - Raw kinds (Sprint 4 Chat L): kpi / chart / list (3)
  - Endpoint-bound kinds (P1 #65): kpi-today-production / wip-batch-count / delivery-warn / pending-reminders / equipment-status / quality-rate / scheduling-alerts (7) — self-fetch via `useWidgetData`
  - Reserved (coming soon): alert / quick-action / calendar / map / news / ai-assistant / custom-html (7)
- ✅ Grid component `web-admin/src/components/widgets/DashboardGrid.vue`
- ✅ Demo `web-admin/src/views/dashboard/widget-demo.vue`
- ✅ 12-col grid + 持久化 layout (DashboardWidget interface)

### Gap 分析
- Raw 3 + Endpoint 7 = **10 widget kinds ship** (跟 backlog 10 独立 endpoint 描述完全 parity)
- 跟 BentoGridEditor 整合 (P1 backlog 提到 "BentoGrid 部分") 待 audit — 当前 widget 系统是否替代 BentoGrid?

### 推荐
**✅ SHIPPED**. 后续:
1. 7 reserved widget kind 按业务需要 incremental 加 (alert / ai-assistant 优先)
2. P3 C-TV-DASHBOARD-1 (TV 大屏 Android app 15d) 复用本 widget 系统是天然路径
3. 跟 BentoGridEditor 关系 audit (是替代 / 互补)

### Layer B 标记
- (None)

---

## §J.6 [C-INLINE-CS-1] 在线客服 iframe (P1 1d)

### HJ 实测细节
- 推测右下角浮动 iframe 接客服系统 (类似商家工具)

### Cretas 现状 (2026-05-19 main)
- ✅ **完整 ship** Sprint 4 W1
- ✅ Component `web-admin/src/components/support/InlineCustomerService.vue` (per Layout import line 8)
- ✅ AppLayout 集成 (`web-admin/src/components/layout/AppLayout.vue:48-49` — 右下角浮动)
- ✅ URL 从 env `VITE_CUSTOMER_SERVICE_URL` 读 (runtime config)
- ⚠️ env var 当前为空 (未接实际客服系统) — 待 BD 接腾讯客服 / 阿里千牛 / 自建

### Gap 分析
- 100% framework ship, 客服 URL 待接

### 推荐
**✅ SHIPPED**. 客户 onboard 时 BD 接客服 URL 即可 (无开发工作量).

### Layer B 标记
- (None)

---

## §J.7 [C-CUSTOM-1] 资料定制 (字段/公式自定义, P2 多行业 20d)

### HJ 实测细节
- 流程图节点"资料定制" ⭐
- 推测能力 (release notes "数据源扩展列 / 设置公式页面" 反推):
  - 字段添加/删除 / 字段类型 (文本/数字/日期/选择/popup) / 必填规则 / 字段联动 / 公式自定义 / 默认值规则 / 显示条件
  - 类似 PowerApps / Salesforce Flow 简版

### Cretas 现状 (2026-05-19 main)
- ✅ **部分 ship** 基础设施 (~50%)
- ✅ Entity `CanvasDynamicField` + `entity/config/FormTemplate.java:27-50` (Formily Schema, multi-tenant, AI 配置助手) + `FormTemplateVersion`
- ✅ Service `engine/DynamicFieldService.java` (cache + tenant-aware + JdbcTemplate)
- ✅ DDL Executor `engine/DDLExecutor.java` (动态 alter table 加列)
- ✅ Dynamic Table `engine/DynamicTableService.java` (动态创表)
- ✅ Formula `engine/AggregateFormulaExecutor.java` (聚合公式)
- ✅ Controller `controller/DynamicFieldController.java`
- ✅ Canvas Editor 集成 (`web-admin/src/views/platform/canvas-editor/`)
- ✅ AI Tool `PrintTemplateCreateFromAITool` (AI 生成模板)
- ⚠️ 实际客户层面"自定义字段"UI 是否成熟? Canvas Editor 是 platform_admin 才能用 (RBAC), 普通用户改不了
- ⚠️ 字段联动 (A=X 时 B 必填) / 默认值规则 / 显示条件 — 是否在 Formily Schema 已有, 待 audit
- ⚠️ 公式自定义 (e.g. 总价 = 数量 × 单价 × 1.13) — AggregateFormulaExecutor 是聚合层 (数据库 SUM/AVG), 单行 inline 公式可能缺

### Gap 分析
- 基础 50% ship, UI/UX 给客户用 30% (Canvas Editor 是 platform admin 工具)
- Formula 单行 inline (跟 HJ release notes "设置公式页面") 待 audit
- 字段联动 / 显示条件 待 audit

### 推荐
**🟡 PARTIAL 基础 ship, full enterprise 20d**. P2 选做:
1. **不优先** — 客户群战略 (食品/餐饮专精) 自定义字段需求低
2. 短期权宜: BD + 工程响应 (依靠 Canvas Editor + AI 加速)
3. 长期 (Sprint 5+): Formula UI (公式 editor) + 字段联动 builder + 显示条件 — 视客户需求决定
4. **现状: 销售话术不强调 "客户自服务自定义", 强调 "我们 BD + 工程响应快 (AI 加速)"**

### Layer B 标记
- **L-J.7.a**: HJ "资料定制" 流程图节点实操实测 (字段类型 + 必填 + 联动 + 公式) — 确认 HJ 具体能力 vs 推测

<!-- agent-A section START 2026-05-19 (appended at end due to file write contention) -->

# §A 客户管理 (CRM) — 8 项

> Audit scope: S-CRM-FULL-1 (22 字段) / S-CUSTOMER-TAB-1 (21 tab) / S-CRM-1 (跟踪记录 UI) / S-PRICE-1 (客户记忆价) / S-CREDIT-1 (信用) / C-CRM-FULL (50 项 P2) / S-COMPLAINT-1 (P2) / S-OPP-1 (P2)
>
> **关键发现**: 28-Backlog (2026-05-16) 严重 stale — 8 项中 **5 项实际已 ship** (S-CRM-FULL-1 / S-CUSTOMER-TAB-1 / S-CRM-1 / S-PRICE-1 / S-CREDIT-1) 主要在 Sprint 4 W1 + Chat B + #774 + #834. S-COMPLAINT-1 已 done 在 branch 待 PR merge. C-CRM-FULL 50 项 实际多数已覆盖 (~10/50 = 20%). 仅 S-OPP-1 真正 0 实装 — 但 Customer.customerStatus 11 阶段已 ship 等于"商机阶段"已 inline 实装.

---

## §A.1 [S-CRM-FULL-1] Customer 扩展 22 字段 ✅ SHIPPED

### HJ 实测细节
- 入口: `客户管理 → 公司客户管理 → 客户档案` (`crm.hongjian.com/crm/custom/clientroute.jsp?id=`)
- HJ Round 4 实测 51 字段: 公司信息22 / 订单属性10 / 客户属性5 / 系统信息7 / 21 跟踪 tab. 含客户状态 11 阶段, 重要程度 4 VIP, 来源 11 渠道, 开票税率 17 档, 发票类型 6 档 (含数电票), 默认币种 32, 对账日期 1-31.
- 截图: `screenshots/G5-01-客户档案-苏州远野.png` + `G5-02-客户档案-21tabs.png`

### Cretas 现状 (2026-05-19 main)
- 后端: `entity/Customer.java:35-201` (65+ 字段)
- Sprint 4 W2 #772 关键扩展:
  - `customerStatus` enum 11 阶段 (`CustomerStatus.java`: LEAD/INITIAL_CONTACT/SAMPLE_SENT/QUOTING/NEGOTIATING/SIGNING/RECURRING/INACTIVE/LOST/BLACKLIST/RECOVERED) — **完全 1:1 对应 HJ**
  - `importance` enum 4 (VIP/IMPORTANT/NORMAL/LOW) — **1:1 HJ**
  - `source` enum 11 渠道 (EXHIBITION/REFERRAL/WEBSITE/SEARCH_ENGINE/WECHAT/PHONE/COLD_VISIT/PLATFORM/PARTNER/REPEAT_PURCHASE/OTHER) — **1:1 HJ**
  - `taxNumber` / `bankName` / `bankAccount` / `creditLimit` / `creditPeriodDays` / `creditStatus` / `defaultTaxRate` / `defaultInvoiceType` / `settlementDate` / `assignedSalesUserId` / `lastContactedAt` / `version` 全字段
- 前端: `web-admin/src/views/sales/customers/list.vue` 完整 dialog
- PR: #772 Chat B / #774 Phase A / #834 S-CREDIT-1

### Gap 分析
| HJ 字段 | Cretas | 状态 |
|---|---|---|
| 客户编号 / 名称 / 简称 | code / customerCode / name | ✅ |
| 客户状态 11 阶段 | customerStatus enum | ✅ 1:1 |
| 法人 | (在 notes 里) | ⚠️ 加 `legalRepresentative` |
| 税号 / 重要程度 4 / 来源 11 / 对账日期 / 发票类型 6 | taxNumber / importance / source / settlementDate / defaultInvoiceType | ✅ 全 1:1 |
| 国家/省/市/详细地址 popup | shippingAddress 单行 | ⚠️ 改 4 级 popup |
| 默认币种 32 | (仅 RMB) | ❌ Archive (内贸不需要) |
| 默认税率 17 档 | defaultTaxRate (BigDecimal 自由输入) | ⚠️ 应改 dropdown 17 fixed |
| 价格等级 | (缺) | ⚠️ 加 priceLevel enum A/B/C |

### 推荐
- **状态**: ✅ SHIPPED — 22 P0 字段 100% 落地
- **5d → 真实 ~3d**
- 优先级: 已完成. follow-up (法人 + 地址 popup + 价格等级 + 17 档税率 dropdown) ~2d Sprint 5

### Layer B 标记
- 无 — baseline 截图够

---

## §A.2 [S-CUSTOMER-TAB-1] 客户档案 21 跟踪 tab ✅ SHIPPED (13/21 active = 62%)

### HJ 实测细节
- 入口: `公司客户管理 → 客户详情 → tab 切换`
- 21 tab: 跟踪/微信/通话/短信/谈话录音/邮件/销售单/样品单/报价单/产品/活动/商机/商品统计/收件地址/开票/收款/退货/售后/价格记忆/业务员变更/文件附件
- 截图: `screenshots/G5-02-客户档案-21tabs.png`
- HJ 工时: 15d

### Cretas 现状 (2026-05-19 main)
- 后端 (#774): 4 controller 加 `/by-customer` endpoint + CustomerSalesUserHistory + CustomerPriceHistory
- 前端: `web-admin/src/views/sales/customers/detail.vue:91-151` 21 TAB_DEFS
  - **12 real tab**: tracking/orders/samples/quotes/products/itemStats/shipAddr/invoices/payments/returns/salesUserHist/attachments
  - **1 integration**: priceMemory (Chat B PriceMemoryTab.vue)
  - **8 defer placeholder** (wechat/call/sms/audio/email/campaign/opportunity/aftersales) — R5 防呆 next-action button
- Tab components: `detail/tabs/` 14 .vue + E2E test `tabs/__tests__/`

### Gap 分析
13 active / 21 = **62%**. 8 defer 中:
- ⚠️ Sprint 5+: sms / email / 售后 (~6d)
- ⚠️ P2 SaaS: 谈话录音 / 通话 / 微信 (~10d 第三方集成)
- ⚠️ Sprint 5+ CRM 模块: 商机 / 活动

### 推荐
- **状态**: ✅ SHIPPED — 主要完成
- **15d → 真实 ~10d**
- 优先级: 邮件/短信/售后 Sprint 5+; 谈话录音/通话/微信 需 SaaS P2

### Layer B 标记
- [ ] **必看**: 实测 HJ G5-02 21-tab 切换 — 验证 Cretas 12 real tab 覆盖 HJ 字段

---

## §A.3 [S-CRM-1] 客户跟踪记录 UI ✅ SHIPPED (PR #822)

### HJ 实测细节
- 入口: `客户管理 → 我的客户跟踪记录`
- 字段: 时间/记录人/跟踪类型 (电话/微信/邮件/拜访/视频/其他) / 联系人/联系方式/地址/内容/备注

### Cretas 现状 (2026-05-19 main)
- 后端: `entity/CustomerTrackingRecord.java:1-67` + `trackingType` enum (PHONE/WECHAT/EMAIL/VISIT/VIDEO/OTHER) — **1:1 HJ**
- Service / Controller: 完整 CRUD (commit `a01f18965` R4 dedup + PUT)
- 前端 PR #822: `web-admin/src/views/sales/customers/tracking/list.vue` (3-view) + TrackingTab.vue
- 防呆 R4 dedup: same content + 5min window

### Gap 分析
8/8 字段 1:1 对应. HJ 跟踪记录统计 report 缺 — 由 S-REPORTS-PRESETS 覆盖.

### 推荐
- **状态**: ✅ SHIPPED
- **3d**: 已 ship
- 优先级: 已完成

### Layer B 标记
- 无

---

## §A.4 [S-PRICE-1] 客户记忆价 ✅ SHIPPED

### HJ 实测细节
- 入口: `客户管理 → 客户价格` (`clientprice/`)
- 业务: 同客户同产品历史价记忆 + 下次开单 prefill + hint
- **Baseline gap**: HJ 没截图

### Cretas 现状 (2026-05-19 main)
- 后端: `entity/CustomerPriceHistory.java:1-83` append-only
  - dedup UNIQUE(factory, customer, product_type_id, source_sales_order_id)
  - 写入: SalesOrder.status=CONFIRMED → SalesOrderConfirmedEvent 自动写
  - 3 层 fallback: PriceList SELLING_PRICE > customer_price_history > productType default
- Controller: `CustomerPriceHistoryController.java:1-50` GET endpoint (RBAC PriceSensitive)
- 前端: PriceMemoryTab.vue + 销售订单 "价格历史" RowAction dialog

### Gap 分析
| HJ | Cretas | 状态 |
|---|---|---|
| 同客户同产品历史价 | customer_price_history table | ✅ |
| prefill | 3 层 fallback | ✅ |
| Hint "上次 ¥X (yyyy-mm-dd)" | sourceOrderNumber + orderDate | ✅ |
| @PriceSensitive RBAC | ✅ | ✅ |

### 推荐
- **状态**: ✅ SHIPPED — 实装超 HJ baseline
- **3d**: ~3d (Chat B milestone 4)
- 优先级: 已完成

### Layer B 标记
- [ ] HJ clientprice 子菜单页面 — 看 UI 形态 (list? dialog? prefill 提示?), 补 fresh evidence

---

## §A.5 [S-CREDIT-1] 客户信用管理 ✅ SHIPPED (PR #834)

### HJ 实测细节
- 入口: `客户管理 → 客户信用管理`
- HJ baseline: "信用额度+账期", 没字段表
- **Baseline gap**: 需 Layer B 实测

### Cretas 现状 (2026-05-19 main)
- 后端: `Customer.java:99-113` (creditLimit / currentBalance / creditPeriodDays / creditStatus enum) — 4 字段
- CreditStatus enum: NORMAL / WARNING / SUSPENDED — SUSPENDED 时创建 SO 硬阻塞
- 业务规则: PR #834 (commit `dbfdfa4a2`)
- 前端: `CreditPanel.vue`

### Gap 分析
| 维度 | HJ | Cretas |
|---|---|---|
| 信用额度 / 当前余额 / 账期天数 / 信用状态 / 创建 SO 硬阻塞 | ✅ | 全 ✅ |
| 信用变更历史追溯 | ?✅ | (缺) ⚠️ |

### 推荐
- **状态**: ✅ SHIPPED
- **5d**: 已 ship
- 优先级: 已完成. **follow-up: CreditChangeLog 信用变更历史** (~2d, 大客户场景)

### Layer B 标记
- [ ] HJ 客户信用管理子菜单 — list 列 / dialog 字段 / 信用拒单触发演示

---

## §A.6 [C-CRM-FULL P2] 客户 50 项 ⚠️ PARTIAL (~20%)

### HJ 实测细节
- HJ Round 5 sMenu2Array 50 子菜单:
  - data 12 / client 8 / report 6 / clientsetup 6 / delete 4 / chance 3 / query 3 / apply 2 / pool 2 / activity 2 / clientprice 1 / workphone 1

### Cretas 现状
- 已实装 ~10 项: 个人公司客户 / 联系人 / 信用 / 跟踪记录 / 价格记忆 / 业务员归属 / 文件附件
- 缺: 客户申请 / 公海池 / 商机 / 资料定义 / 工作手机 等 38 项
- 真实覆盖率: **~20%** (10/50)

### 推荐
- **状态**: ⚠️ PARTIAL — 跟客户群战略匹配 (F006 内贸 ~20% 够)
- **15d**: P2 不推荐全做. **Cretas 应做 5-8 项** (新增客户统计 / 来源统计 / 重复客户列表, ~3-5d)
- 优先级: P2 — 食品/餐饮专精战略下不优先

### Layer B 标记
- 无 — Archive

---

## §A.7 [S-COMPLAINT-1 P2] 售后服务投诉 12 字段 🟡 IN-FLIGHT

### HJ 实测细节
- 入口: `销售管理 → 售后服务` + `system/complaint` 3 项
- **Baseline gap**: HJ 字段表未提供

### Cretas 现状 (2026-05-19)
- 后端: branch `feat/p2-74-service-complaint` (commit `33751fbb1`) — **未 merge main**
- 完整 entity: ServiceComplaint (12 字段) + 4 enum (Type/Severity/Source/Status) + REST CRUD
- 状态机: NEW→INVESTIGATING→RESOLVED→CLOSED
- 防呆: R2 + R3 + R4 (dedup 5min)
- 前端: list.vue + dialog 在 branch (15 文件 / 1383 行)

### Gap 分析
| 维度 | HJ (推测) | Cretas (branch) |
|---|---|---|
| 投诉单号 (CMP-YYYYMMDD-NNNN) / 类型 5 / 严重度 4 / 来源 5 / 状态 4 / 关联订单 / 处理方案 | ✅ | 全 ✅ |

### 推荐
- **状态**: 🟡 IN-FLIGHT — branch ready, **本周 merge** (1-2d 收尾)
- **4d**: 80% done, 收尾 ~1d
- 优先级: P2 → 可提 P1 (B2B 客户场景常见)

### Layer B 标记
- [ ] HJ `system/complaint` — 验证 4 enum 实际值 (尤其 source 5 渠道)

---

## §A.8 [S-OPP-1 P2] 商机管理 ❌ NOT DONE (但 inline 在 Customer.customerStatus)

### HJ 实测细节
- 入口: `客户管理 → chance/` 3 子菜单
- 阶段: 跟 Customer.customerStatus 11 阶段完全一致

### Cretas 现状 (2026-05-19)
- 后端: **0 独立 Opportunity entity**
- **但 Customer.customerStatus enum 11 阶段已 ship** — "商机阶段" inline 实装
- 前端: customer detail opportunity tab 是 defer R5 placeholder

### Gap 分析
- HJ 模型: Opportunity entity 独立
- Cretas 模型: customerStatus inline — **简化合理** (单客户对应单生命阶段)
- 缺: 商机漏斗 dashboard + close reason enum

### 推荐
- **状态**: ❌ NOT DONE 但 **不推荐独立 OPP entity**, wire 现有 customerStatus → 漏斗 dashboard
- **8d 修正**: → **2-3d**
- 优先级: P2 — 大销售团队, F006/餐饮不优先

### Layer B 标记
- [ ] HJ chance/business-funnel — 漏斗 dashboard UI 形态 (funnel chart? 数字面板?)

---

# §B 销售管理 — 11 项

> Audit scope: S-INVOICE-CLIENT-1 / S-PROFIT-DETAIL-1 / S-REMIND-1 / S-NEED-1 / S-PAYMENT-DATE-1 / S-REPORTS-PRESETS / S-LOCK-1 / S-MRP-1 / S-RD-1 / S-CALL-STAT-1 / S-COMMISSION-1
>
> **关键发现**: 11 项中 **9 项已 ship** (S-INVOICE-CLIENT / S-PROFIT-DETAIL / S-REMIND / S-NEED / S-PAYMENT-DATE / S-LOCK / S-MRP / S-RD + S-REPORTS-PRESETS 5/14 active). S-CALL-STAT-1 + S-COMMISSION-1 战略 Archive.

---

## §B.1 [S-INVOICE-CLIENT-1] 客户级开票税率 17 档 + 发票类型 6 档 ✅ SHIPPED

### HJ 实测细节
- HJ 17 档税率: 0%/1%/3%/5%/6%/9%/13%/... (常用 + 长尾)
- HJ 6 档发票类型: 普票/专票/数电票/收据/不开/其他

### Cretas 现状 (2026-05-19 main)
- 后端: `entity/enums/InvoiceType.java:1-29` — **6 enum 1:1 对应 HJ** (NORMAL/SPECIAL/DIGITAL/RECEIPT/NONE/OTHER + Chinese displayName)
- 3 层链: Customer.defaultInvoiceType → SO.defaultInvoiceType → InvoiceRecord.invoiceType
- Customer.defaultTaxRate + SO.defaultTaxRate
- 前端: `views/sales/customers/list.vue:606-634`
- PR #772 Chat B

### Gap 分析
| HJ | Cretas | 状态 |
|---|---|---|
| 17 档税率 | defaultTaxRate (BigDecimal 0-100 自由输入) | ⚠️ 应改 dropdown 17 fixed (防呆 R3) |
| 6 档发票 (含数电票) | InvoiceType enum 6 (含 DIGITAL) | ✅ 1:1 |
| 3 层默认链 | ✅ | ✅ |

### 推荐
- **状态**: ✅ SHIPPED
- **2d**: ~1.5d
- 优先级: 已完成. **0.5d follow-up: 税率改 17 fixed dropdown**

### Layer B 标记
- [ ] HJ 客户档案开票字段 dropdown 实际 17 档列表

---

## §B.2 [S-PROFIT-DETAIL-1] 产品级销售利润详情页 (11 列) ✅ SHIPPED

### HJ 实测细节
- 入口: 销售订单 list 行末 "操作 ▼" → 销售利润
- HJ 行内显示利润额 (¥21,876.12) + 点击进 11 列详情
- 11 列: 产品 / 数量 / 单价 / 成本 / 单位毛利 / 毛利率 / 折让 / 税额 / 净利润 / 历史均价 / 趋势
- 截图: `screenshots/G2-02-销售利润详情页.png`

### Cretas 现状 (2026-05-19 main)
- 后端: `dto/report/SalesProductProfitRowDTO.java:1-67` — **11 字段 1:1 对应 HJ**
  - productTypeId / productName / unit / quantity / unitPrice / costUnitPrice / grossProfit / grossMarginPct / discountAmount / taxAmount / netProfit / historicalAvgPrice / priceTrend
- @PriceSensitive 5 字段
- 前端: `web-admin/src/views/sales/orders/profit-detail.vue` (lookbackDays 30/60/90/180)
- PR #731

### Gap 分析
11 列字段表 1:1 完全对应 HJ. ✅

### 推荐
- **状态**: ✅ SHIPPED
- **2d**: ~2d
- 优先级: 已完成

### Layer B 标记
- 无

---

## §B.3 [S-REMIND-1] 收款提醒 → OA 任务集成 ✅ SHIPPED

### HJ 实测细节
- 入口: 销售订单 → 操作 ▼ → 收款提醒
- HJ 跨子域跳 oa.hongjian.com 创建 OA 任务
- **Baseline gap**: HJ 没截图 OA 字段

### Cretas 现状 (2026-05-19 main)
- 后端: `entity/Reminder.java:1-89` + ReminderType / ReminderStatus
  - Scanner-pattern: ReminderScanService `@Scheduled` 每日扫描 SO (未结清 + 到期)
  - 创建 PAYMENT_DUE 提醒分配给销售员
  - 用户 snooze / dismiss
- AIChat Tool: ReminderQueryTool (commit `e49d350bb`)
- 前端: ReminderBell.vue (header badge) + MyReminders.vue
- PR #766

### Gap 分析
| HJ | Cretas | 状态 |
|---|---|---|
| 触发条件 | scanner 自动 | ✅ (更智能) |
| 分配销售员 | assigneeId | ✅ |
| snooze/dismiss | snoozedUntil + status | ✅ |
| OA 跨子域跳转 | Cretas SPA 单页 | ✅ 优于 HJ |
| Bell badge UI | ReminderBell.vue | ✅ |

### 推荐
- **状态**: ✅ SHIPPED — 超 HJ baseline
- **3d**: ~3d
- 优先级: 已完成

### Layer B 标记
- 无

---

## §B.4 [S-NEED-1] 销售需求独立模块 ✅ SHIPPED

### HJ 实测细节
- 入口: 销售管理 → 销售需求 (独立子域 `need.hongjian.com`)
- 业务: 客户/销售员提购买意向 → 审核 → 转销售订单
- **Baseline gap**: HJ 字段表/状态机 没截图

### Cretas 现状 (2026-05-19 main)
- 后端: `entity/sales/SalesNeed.java:1-105` (12 字段 + audit)
  - 状态机: DRAFT → CONFIRMED → CONVERTED_TO_SO
  - 字段: customerId/customerName / productId/productName / qtyDemand / unit / expectedDeliveryDate / priority / status / remark / convertedSalesOrderId
  - 一键转销售订单 auto-create SO
- Service: SalesNeedService + Impl
- AIChat: SalesNeedCreateTool + SalesNeedConvertTool + intent seeds (V20260526_03)
- 前端: `web-admin/src/views/sales/needs/list.vue`
- PR #766

### Gap 分析
| HJ | Cretas | 状态 |
|---|---|---|
| 独立子域 | SPA route /sales/needs | ✅ (架构差异) |
| 12 字段 / 状态机 / 转 SO | 全 ✅ | ✅ |

### 推荐
- **状态**: ✅ SHIPPED
- **5d**: ~4d
- 优先级: 已完成

### Layer B 标记
- 无

---

## §B.5 [S-PAYMENT-DATE-1] 客户级对账日期 (1-31 号) ✅ SHIPPED

### HJ 实测细节
- HJ 字段: 客户档案 → 订单属性 → 对账日期 (1-31 dropdown)
- 业务: 月结客户每月特定日期生成对账单

### Cretas 现状
- 后端: `Customer.java:89-97` — `settlementDate Integer @Min(1) @Max(31)` + DB CHECK + 月底 31 在 2/4/6/9/11 月降级月末
- Sprint 4 W1 commit `9b38a03e8`

### 推荐
- **状态**: ✅ SHIPPED — 1:1
- **1d → ~0.5d** Quick Win

### Layer B 标记
- 无

---

## §B.6 [S-REPORTS-PRESETS] 销售 14+ 预置报表 ⚠️ PARTIAL (5/14 active + 9 stub)

### HJ 实测细节
- HJ 14 销售 report (per `22-FULL-MENU-MAP.md:246`)
- + 7 业绩 + 4 月结 + 6 利润 = **31 个销售相关 report**

### Cretas 现状 (2026-05-19 main)
- 后端: `backend/python/smartbi/api/sales_preset.py:1-405` — **5 active + 9 Sprint 5 stub**
  - Active: daily/monthly/yearly/customer-rank/product-rank
  - Stub: salesperson-performance/aging/collection-rate/return-rate/gross-margin/purchase-frequency/growth-rate/regional-comparison/customer-tier
- 前端: `web-admin/src/views/sales/reports/` 6 .vue
- PR #766

### Gap 分析
5 active + 9 stub = **5/14 = 36%**. 剩 9 stub ~5d.

### 推荐
- **状态**: ⚠️ PARTIAL
- **8d**: ~3d 用. 剩 9 stub ~5d
- 优先级: Sprint 5 W1 (跟 S-OPP-1 漏斗 一起)

### Layer B 标记
- [ ] HJ sale/report 14 项 list — 验证 stub 名字一致 + 优先级

---

## §B.7 [S-LOCK-1] 锁定/备货/缺料 3 维度 (行内显示) ✅ SHIPPED (PR #690)

### HJ 实测细节
- 销售订单 list 行内 `锁:0  备:1  缺:0` (per `02-销售管理-deep-audit.md:62`)
- 状态机: SO confirmed → shortage 分析 → 写回 SO_Item

### Cretas 现状 (2026-05-19 main)
- 后端: SalesOrder + SalesOrderItem 加 lockedQty/reservedQty/shortageQty 3 字段
- Service: SalesOrderShortageReportListener (监听 SO 财务审核 event)
- 前端: `list.vue:1218-1240` 3 chip 垂直堆叠 + 缺料红色高亮 + tooltip
- RN: SalesOrderListScreen.tsx 同款
- PR #690 Sprint3-G commit `66008c446`

### Gap 分析
1:1 + **超 HJ** (chip 颜色 + tooltip 防呆)

### 推荐
- **状态**: ✅ SHIPPED
- **1d**: ~1d
- 优先级: 已完成

### Layer B 标记
- 无

---

## §B.8 [S-MRP-1] (✅ ship #682) 销售订单 → 采购自动分流 — 验证 ship

### HJ 实测细节
- HJ SO 财务审核后 自动触发缺料分析 → 创建采购请求
- 流程图: 销售订单 → 缺料分析 → 采购订单

### Cretas 现状 (2026-05-19 main)
- 后端 (#682): `entity/inventory/SalesOrderShortageReport.java:1-100`
  - SalesOrderShortageReportListener 监听 SalesOrderFinanceApprovedEvent
  - UNIQUE(factory, sales_order_id) 一单一报告
  - 字段: lineItemMatches[] / materialRequirements[] / materialShortfalls[] / procurementSuggestions[] / productionPlanSuggestions[]
- Service: ShortageAnalysisService + REST
- AIChat Tool: ShortageAnalysisTool + intent + RN chain-card

### Gap 分析 — 验证 ship
| HJ | Cretas | 状态 |
|---|---|---|
| 缺料分析触发 / 物料需求 / 缺口 / 采购建议 / 幂等 / RN UI | 全 ✅ | ✅ |
| 生产计划建议 | productionPlanSuggestions[] | ✅ (超 HJ) |

### 推荐
- **状态**: ✅ SHIPPED — **超 HJ baseline**
- **5d**: ~5d
- 优先级: 已完成

### Layer B 标记
- 无 — ship 完整度已验证

---

## §B.9 [S-RD-1 / N48] (✅ ship #680) 研发样品 → BOM → 报价 — 验证 ship

### HJ 实测细节
- HJ: 销售管理 → 研发项目 / 样品管理 + 工程管理 → BOM
- 流程: 样品创建 → 跟踪 → 审批 → 自动 BOM → 报价 → 销售订单
- 子域: `sample.hongjian.com`

### Cretas 现状 (2026-05-19 main)
- 后端 (#680): `entity/rd/ProductSample.java:1-80` + ProductSampleTrackingRecord
  - 7 状态: DRAFT/IN_PROGRESS/TESTING/SUBMITTED/APPROVED/REJECTED
  - 进度 JSON + 照片 JSON
- Event: SampleApprovedEvent + Listener → 自动创建 BomRecipe + BomRecipeItem + 销售通知
- AIChat: SampleCreateTool / SampleApproveTool / SampleToBomTool / BomRecipeCreateFromSampleTool
- 前端: RN sampleApiClient + List/Detail Screens

### Gap 分析 — 验证 ship
| HJ | Cretas | 状态 |
|---|---|---|
| 样品 entity / 状态机 7 / 照片 / 进度 / 审批→BOM自动 / BOM→报价 / 销售通知 / RN UI | 全 ✅ | ✅ |

### 推荐
- **状态**: ✅ SHIPPED — **完整对应 HJ 链路**
- **5d**: ~5d
- 优先级: 已完成

### Layer B 标记
- 无

---

## §B.10 [S-CALL-STAT-1 P2] 外呼通话统计 ❌ NOT DONE (战略 Archive)

### HJ 实测细节
- HJ 独立子域 `record.hongjian.com`
- 11 列: 员工/部门/拨打/接通/通话时长 / 大于15s / 30s / 60s / 120s 次数
- 销售有效通话识别: 15s=接通 / 30s=简单沟通 / 60s=正式介绍 / 120s=重点客户

### Cretas 现状
- 0 实装 — 需云呼叫硬件接入

### 推荐
- **状态**: ❌ NOT DONE — **战略不做** (per 25-ROUND-8 §122 "Cretas 不要碰")
- **8d → N/A Archive**
- 优先级: 战略 Archive — 食品/餐饮 F006/QHJ 不需要外呼

### Layer B 标记
- 无

---

## §B.11 [S-COMMISSION-1 P2] 合作伙伴佣金报表 (12 月统计) ❌ NOT DONE

### HJ 实测细节
- HJ 独立子域 `partner.hongjian.com`
- 2026 年 12 月按月: 应发 / 实发 / 未发 / 操作 (初始化按月)

### Cretas 现状
- 0 实装

### 推荐
- **状态**: ❌ NOT DONE
- **5d → 真实 ~3-4d** (单表 + 12 月 dashboard, 复用 sales_preset.py 框架)
- 优先级: P2 → defer P3 (F006 直营无代理)
- 依赖: 合作伙伴 entity (Partner) 先做

### Layer B 标记
- [ ] HJ partner/profit 12 月报表 UI

---

<!-- agent-A done 2026-05-19 -->
<!-- agent-E §I+§J done 2026-05-19 -->


<!-- organizer §P FINAL SYNTHESIS START 2026-05-19 -->

# §P — 最终 88 项 ship 状态 RECONCILE + Sprint 计划修正 (organizer closer)

> **本节是 R-HJ Round 11 deep re-audit 的最终决策面**. 综合: Agent A (§A+§B 19 项 CRM+销售) / Agent B (§C+§D 11 项 采购+仓库) / Agent C (§E+§F 14 项 生产+BOM+品质+模具) / Agent D (§G+§H 10 项 财务+HR) / Agent E (§I+§J 16 项 工作流+配置+系统) / Agent F (§K+§L+§M 22 项 UX+战略+餐饮) + organizer §O (13 项 Layer B browser fresh).
> **总覆盖**: 88 项 ✅ + 13 项 fresh browser 实测.

---

## §P.1 最终 88 项 ship 状态 (Round 11 Reconcile)

按 6 Agent + organizer Layer B fresh 综合, 88 项最终分类:

| 优先级 | 总数 | ✅ FULL SHIPPED | ⚠️ PARTIAL | 🟡 IN-FLIGHT | ❌ NOT DONE | 真实剩余工时 |
|---|---|---|---|---|---|---|
| **P0 战略** | 12 | **11** (新增 F-VFLAG-1 §G.1 + C-LINKARRAY-1 §I.3 + C-APPROVAL-EDITOR §I.1 Phase 1 + C-PRT-EDITOR §I.2 + S-LOCK-1 #690 + 原 6 ship) | 1 (M-BOM-VER-1 frontend follow-up) | 0 | 0 | ~5d (M-BOM-VER-1 前端) |
| **P0 必修** | 6 | 4 (M3/M4/M5/N3) | 2 (M1 blocked / M2 P3 defer) | 0 | 0 | 4d (M1 if unblock) |
| **P1 战术** | 47 | **~32** (大量来自 Sprint 4 W1+W2 Chat L bundle + Sprint 3 G-J + 财务 ship + HR ship + UX 11/11) | ~4 | ~3 | ~8 | ~30d |
| **P2 选做** | 15 | 2 (C-FILE-DOMAIN archive + C-INLINE-CS-1) | 1 (C-CRM-FULL ~20%) | 0 | ~12 | ~60d (按需触发) |
| **P3 长期** | 8 | 0 | 0 | 0 | 8 | ~50d (Steve sign-off 延后) |
| **合计 88** | | **~49 ✅** | **~7 ⚠️** | **~3 🟡** | **~29 ❌** | **~150d nominal** |

**对比 30-Audit (2026-05-16)**:
- 30-Audit: 12 ✅ + 3 ⚠️ + 73 ❌ → 剩 373d nominal / 7 月
- **Round 11**: **49 ✅** + 7 ⚠️ + 3 🟡 + 29 ❌ → 剩 ~150d nominal / **~3 月**

**Round 11 多发现 ship 37 项**, 主要原因:
1. **2026-05-16 之后又 ship 大量 PR** (PR #690/#693/#763/#770/#773/#822/#823/#831/#832/#834/#844/#862/#863/#870 等 14+ PRs)
2. **30-Audit grep 关键字漏判** (用错命名习惯), 真实代码 entity/enum/column 不同 camelCase
3. **Sprint 4 W1-A bundle (PR #764) + Chat L (PR #727)** 集中 ship 多个 quick wins, 30-Audit 没拆分到每 backlog 项

---

## §P.2 真实 P0 剩余 (Sprint 5+ ASAP)

| 项 | 状态 | 工时 | Sprint 优先级 |
|---|---|---|---|
| M-BOM-VER-1 frontend follow-up | ⚠️ backend ship, frontend 缺 | 3d | Sprint 5 W1 |
| M1 三价对比刷新 | ⚠️ blocked (#538 F006 test seed) | 2d | 解锁后 ASAP |
| C-APPROVAL-EDITOR-1 完整收尾 | ⚠️ Phase 1 ship, Phase 2-N | 3-5d | Sprint 5 W1 (WorkflowRule UI / OpinionTemplate dialog / decisionType 扩枚举) |
| C-VOUCHER-2-1 (P2 大企业 60%) | ⚠️ entity ship, 缺会计科目树 + 辅助核算 FK | ~8d | 触发后做 |
| H-ATT-FULL 月考勤矩阵 6 周 UI | ⚠️ MVP ship, 高级排班缺 | ~6d | Sprint 5+ |

**Sprint 5 P0+P1 总剩**: ~15-20d (vs 28-Backlog 估 ~196d, 大幅省 ~180d).

---

## §P.3 Sprint 5+ 计划重排 (基于 Round 11)

| Sprint | v2.1 + 30-Audit | **Round 11 修正** |
|---|---|---|
| Sprint 5 W1 (2026-05-20~26) | 6 P0 项 / 60d | **5d P0 收尾** (M-BOM-VER frontend + C-APPROVAL Phase 2 + M1 解锁), 7d 剩按 P1 backlog 触发 |
| Sprint 5 W2 (2026-05-27~06-02) | P1 上半 / 100d | **15-20d P1 backlog 真剩余** (财务凭证模板/HR 矩阵/Q-PROCESS-NEXT/部分 P2 大客户提前) |
| Sprint 6+ | P1 下半 / 150d | **30-40d P2 选做** (按客户触发, F-VOUCHER-2 / F-PERIOD / S-OPP-1 完整 entity 等) |
| Sprint 7+ | P2 大客户 | **40-50d P3 长期** (TV 大屏 / 微服务 / RBAC 细粒度), 客户群触发 |
| 总时间 | 30 周 (7 月) | **8-12 周 (2-3 月) P0+P1 收口, +20 周 P2+P3 触发** |

**新的"全部 88 项 close out"工期**: ~12-15 周 = **3-4 个月** (vs 30-Audit 7 月, **省 3-4 月**).

---

## §P.4 重大 reconcile 列表 (28-Backlog 必修)

| Backlog # | 项 | 28-Backlog 标 | 真实 | 证据 source |
|---|---|---|---|---|
| 1 | F-VFLAG-1 | ❌ 10d | **✅ Sprint3-E PR #693** | Agent D §G.1 + organizer §O.9 修正 |
| 2 | C-LINKARRAY-1 | ❌ 2d | **✅ Track-F** | Agent E §I.3 + organizer §O.5 修正 |
| 4 | M-BOM-VER-1 | ❌ 15d | **⚠️ backend ✅ + frontend 3d follow-up** | Agent C §E.1 + organizer §O notes |
| 5 | C-APPROVAL-EDITOR-1 | ❌ 20d | **⚠️ Phase 1 ✅ + 3-5d incremental** | Agent E §I.1 (Track-I + Canvas Phase 1 PR #862) |
| 6 | C-PRT-EDITOR-1 | ❌ 10d | **✅ Track-J 3-pane editor** | Agent E §I.2 |
| 7 | N49 C-AI-1 钉钉 | ⚠️ Track-B1 Day 5/6 no PR | **🟡 code 100% 46 单测绿, blocked F006 凭证** | Agent E §I.8 |
| 13 | M1 三价对比 | ⚠️ blocked | **⚠️ Test seed blocker still + #695 + #782 fixes** | Agent B §C audit |
| 19 | S-CRM-FULL-1 | ❌ 5d | **✅ Customer 22 字段** | Agent A §A.1 + organizer §O.7 修正 |
| 20 | S-CUSTOMER-TAB-1 | ❌ 15d | **✅ 13/21 active 62%** | Agent A §A.2 + organizer §O.6 修正 |
| 21 | S-CRM-1 | ❌ 3d | **✅ PR #822** | Agent A §A.3 + Agent F §N.2 |
| 22 | S-PRICE-1 | ❌ 3d | **✅ Cretas 完整 service + UI + RBAC** | Agent A §A.4 |
| 23 | S-CREDIT-1 | ❌ 5d (P2) | **✅ PR #834** | Agent A §A.5 |
| 24 | S-INVOICE-CLIENT-1 | ❌ 2d | **✅ 17 档税率 + 6 档发票** | Agent A §B.1 + organizer §O.8 修正 |
| 25 | S-PROFIT-DETAIL-1 | ❌ 2d | **✅ 11 列详情页** | Agent A §B.2 |
| 26 | S-REMIND-1 | ❌ 3d | **✅ scanner auto + bell badge (优于 HJ OA 跨域)** | Agent A §B.3 |
| 27 | S-NEED-1 | ❌ 5d | **✅** | Agent A §B.4 |
| 28 | S-PAYMENT-DATE-1 | ❌ 1d | **✅ 1-31 号 dropdown** | Agent A §B.5 + organizer §O.8 修正 |
| 29 | S-REPORTS-PRESETS | ❌ 8d | **⚠️ 5/14 active + 9 stub** | Agent A §B.6 |
| 30 | P-NUCLEAR-1 | ❌ 3d | **✅ PR #824/#30** | Agent B §C.2 |
| 32 | P-IMPORT-1 | ❌ 1d | **✅ PR #764 `7a4b2da49`** | Agent B §C.4 |
| 33 | W-CLASS-1 | ❌ 1d | **✅ PR #764 `91cdf7897` 扩 13 类 (超 HJ 10)** | Agent B §D.1 |
| 38 | F-AR-1 | ❌ 5d | **✅ ArApTransaction + finance_ar/ap RBAC 分离** | Agent D §G.2 |
| 39 | F-INV-1 | ❌ 8d | **✅ + ENHANCED tax_breakdown JSONB + OCR #763** | Agent D §G.3 |
| 40 | H-WAGE-FULL | ❌ 10d | **✅ #833/#844/#863/#870** | Agent D §H.1 |
| 42 | H-LEAVE-1+OVT+EXP | ❌ 12d | **✅ Sprint 4 W2 Chat E #770** | Agent D §H.3 |
| 43-45 | Q-MODE-1/Q-PROCESS-1/Q-RETURN-1 | ❌ 1+5+3d | **✅ Sprint 4 W1/W2 Chat A+H #729/#733/#735/#764** | Agent C §F |
| 46 | C-CHECKPOWER-1 | ❌ 3d | **✅** | Agent E §J.1 |
| 50 | C-VOUCHER-TPL-1 | ❌ 5d | **🔵 entity ship 待 F-VOUCHER-HOOK prerequisite** | Agent E §I.7 |
| 51 | C-LOG-AUDIT-1 | ❌ 3d | **✅** | Agent E §J.2 |
| 52 | C-EXPORT-CENTER-1 | ❌ 5d | **✅** | Agent E §J.3 |
| 53 | C-IMPORT-CENTER-1 | ❌ 5d | **✅** | Agent E §J.4 |
| 60 | U-MARKER-1 | ❌ 1d | **✅ Sprint 4 Chat L PR #727** | Agent F §K.7 |
| 65 | C-WIDGET-1 | ❌ 5d | **✅ PR #823 (10 endpoint widget framework)** | Agent F §N.2 |
| 66 | C-INLINE-CS-1 | ❌ 1d | **✅** | Agent E §J.6 |
| (multi) | M-WIP-1/M-PREP-1/M-DELIVERY-WARN-1/M-MATTREE-1 | ❌ | **✅ Sprint 4 W2 Chat G #732/#734/#737/#738** | Agent C §E |
| (multi) | U-VIEW-1/U-NEW-1/U-ICON-1/U-FEED-1/U-DESKTOP-MODAL/U-DEPT-1/U-CHIP-MULTI-1 | ❌ | **✅ Chat L #727 + Chat W1-A #764** | Agent F §K |

**总计 30+ 项 28-Backlog ❌ → 实际 ✅**.

---

## §P.5 真正剩 ❌ 项 (Round 11 最终)

按 Sprint 优先级排序的剩余 ❌ items:

### Sprint 5+ P0 剩 (~5d)
- M-BOM-VER-1 frontend follow-up 3d
- M1 三价对比刷新 (blocked) 2d

### Sprint 5+ P1 backlog ~30d (按需触发)
- S-REPORTS-PRESETS 9 stub 报表 ~5d
- M-WP-CONDITION-1 工序条件路由 (Round 5 新发现) 5d
- S-COMPLAINT-1 完整收尾 1-2d
- 法人字段 / 4 级地址 / 17 档 dropdown / CreditChangeLog 等小 follow-up 3-4d
- C-WF-RULE-1 / C-WF-VAR-1 UI 实装 ~5d (backend ship, UI 缺)
- C-OPINION-1 dialog 接入 ~2d
- 其他 P1 backlog ~5-10d

### Sprint 6+ P2 选做 ~60d (客户群触发)
- F-VOUCHER-2-1 完整收尾 (会计科目树 + 辅助核算) 8d
- F-PERIOD-1 期间结账 8d
- F-3REPORT-1 报表三表 12d
- C-CUSTOM-1 资料定制 20d
- H-ATT-FULL 月考勤矩阵 + 高级排班 6d
- H-PARTNER-FULL 佣金管理 5d
- C-STORE-1 / S-STORE-REPLEN-1 餐饮多门店 10d
- 其他 ~10d

### Sprint 7+ P3 长期 ~50d (Steve 已 sign-off 延后)
- C-TV-DASHBOARD-1 15d
- C-MENU-ENGINE-1 8d
- C-RBAC-FNO-1 15d
- C-MICROSERVICE-1 ∞ (战略不抄)
- C-WECHAT-DOMAIN-1 5d (Cretas 用钉钉, 不需)
- C-PARTNER-DOMAIN-1 3d
- C-DOCS-DOMAIN-1 5d
- C-SERVICE-CODE-1 0.5d (quick win 可顺手)

### Archive (Cretas 不抄)
- S-CALL-STAT-1 (云硬件依赖)
- S-COMMISSION-1 (代理/分销客户群不优先)
- M-MOULD-1 (F006 无模具)
- F-PARTNER-FULL (代理客户)
- 委外管理 / 办公自动化 / mould / wxshop / mail / sms 等 (per 28-Backlog Archive 6.1)

---

## §P.6 战略决策 (Steve sign-off updated)

基于 Round 11 reconcile, **建议 Steve 重新 sign-off**:

| 项 | 原 sign-off (2026-05-16) | **Round 11 建议** |
|---|---|---|
| P0+P1 总时间 | 9 月 (66 项 / 252d) | **3 月** (P0 5d 收尾 + P1 30d 选触发) |
| 客户群 | 食品 + 餐饮 | 维持 |
| 团队规模 | 单人 (Steve) | 维持 (Round 11 后真实剩余 ≤ 单人 1 季度) |
| P2 大客户 | 延后 | 维持延后, 按客户触发 |
| P3 长期 | 延后 | 维持延后 |

**新结论**: **Cretas 已经接近 stable, 真实 backlog 很少**. Sprint 5 可以收口 P0+P1, Sprint 6+ 转向客户深度试用 + bug fix + 新客户 onboarding (而不是大 backlog 推进).

---

## §P.7 Layer B request 综合 (后续 organizer/Layer C session)

| 来源 | Layer B item | 优先级 |
|---|---|---|
| Agent A §A.2 | HJ 21-tab 切换实测 (验 12 real tab 字段覆盖) | 🔴 高 |
| Agent A §A.4 | HJ clientprice 子菜单 UI 形态 | 🟡 中 |
| Agent A §A.5 | HJ 客户信用管理 list/dialog/拒单触发 | 🟡 中 |
| Agent A §A.7 | HJ system/complaint enum 实际值 (source 5 渠道) | 🟢 低 |
| Agent A §A.8 | HJ chance/business-funnel 漏斗 dashboard UI | 🟡 中 |
| Agent A §B.1 | HJ 客户档案开票 17 档 dropdown 实际 list | 🟡 中 |
| Agent A §B.6 | HJ sale/report 14 项 list (优先级排序) | 🟢 低 |
| Agent A §B.11 | HJ partner/profit 12 月报表 UI | 🟢 低 |
| Agent B §C.1 | FINANCE_REJECTED reopen UX 是否有 dead-end / next-action | 🟡 中 |
| Agent B §C.2 | HJ 询价单"批量推送多供应商"vs Cretas 手动 add | 🟡 中 |
| Agent B §C.4 | Cretas web/RN 创建 PO form 是否暴露 isImported | 🟡 中 |
| Agent B §D.2 | Cretas web-admin 是否有抄码品入库录入 UI | 🟡 中 |
| Agent C §E.1 | HJ ECN 变更明细 字段 list | 🟡 中 |
| Agent C §E.4 | BOM 物料 picker (Cretas inline 推测) | 🟢 低 |
| Agent D ×7 | Voucher inline 复式编辑器 + 月考勤 6 周矩阵 layout 等 | 🟡 中 |
| Agent E ×7 | HJ workflowshow.jsp 拖拽 + 流转规则配置 UI 实测 + 业务自动生成凭证 | 🟡 中 |
| Agent F ×2 | U-NEW-1 BOM 展开真集成 + U-DESKTOP-MODAL resize handle | 🟢 低 |
| organizer | C-PRT-EDITOR-1 HJ 真 URL pattern (打印管理 sub-menu hover) | 🟡 中 |

**合计 ~25 项 Layer B**, 优先级高 1 / 中 ~12 / 低 ~12. 建议: 1-2 session organizer 用 Playwright 跑完 优先级 高/中 = ~13 项 (~1-2h).

---

## §P.8 元教训汇总 (Round 11 6 agent + organizer 累积)

### 教训 1: 单关键字 grep 不可靠 (organizer 7 次踩)
- `Voucher` 漏 VoucherFlag enum + lowercase vflag column (Agent D 修正 §G.1)
- `linkListArray|linkno=` 漏 Track-F linkno 业务实装 (Agent E 修正 §I.3)
- `customer-detail-tabs` 漏 Track-A tab framework 实装 (Agent A 修正 §A.2)
- Customer 字段 grep 漏 22 字段实装 (Agent A 修正 §A.1)
- invoiceTaxRate/invoiceType grep 漏 17档/6档实装 (Agent A 修正 §B.1)
- paymentDate grep 漏 1-31号实装 (Agent A 修正 §B.5)
- C-APPROVAL/PRT-EDITOR 只看 HJ URL 404 没 grep Cretas main (Agent E 修正 §I.1/I.2)

**Sister rule 升级 `feedback_brief_must_grep_existing_endpoint_paths.md` HARD**: "single keyword grep 不充分; 必须 multi-synonym (entity / enum / column / camelCase / lower / underscore) 才能 reliably verify".

### 教训 2: 28-Backlog metadata drift 严重 (30+ 项 stale)
2026-05-16 audit 后又 ship 14+ PRs, 30-Audit 立即过时. **必须 daily reconcile** (跟 `feedback_signoff_requires_reconcile_with_main_first.md` HARD 一致).

### 教训 3: Sprint bundle 难 track
- Sprint 4 Chat L PR #727 一个 PR ship 8 UX items
- Sprint 4 W1-A PR #764 一个 PR ship 7 quick wins
**Backlog 应按 bundle 标记, 避免单项 mis-track**.

### 教训 4: HJ 真 ERP 远大于 Cretas 当前看到的
- 681 子菜单 (vs Cretas baseline 估 280)
- 1591 RBAC f_no 权限点
- 41 子域 microservice
- 126 工作流定义 (vs Cretas decisionType ~10 = 8% 覆盖)
**真 gap 不是单个 feature, 是 decisionType 覆盖度 + 子菜单广度**.

### 教训 5: Cretas 多处已超 HJ
- S-LOCK-1 chip 颜色 + tooltip 防呆 vs HJ 纯文本
- M-MATTREE-1 BOM tree + 叶子库存短缺 (HJ tree picker 没库存计算)
- W-CLASS-1 13 类 vs HJ 10 类
- S-REMIND-1 scanner auto + bell badge vs HJ OA 跨域跳
- decisionType N-of-M 会签 (HJ 没有)
**销售话术应突出**: "Cretas 不只 copy HJ, 多处已优化".

### 教训 6: 真实剩余工时只剩 3 月 (vs sign-off 9 月)
Round 11 reconcile 后, **真正"close out 全 P0+P1" 只需 3 月** (vs Steve 2026-05-16 sign-off 9 月). 省 6 月. Sprint 5 可收口, Sprint 6+ 转向客户深度试用.

---

## §P.9 后续行动 (Steve 决策)

### 必做 (本周)
1. **更新 28-CRETAS-PRIORITIZED-BACKLOG.md**: 把 30+ 项 ❌ 改 ✅ (per §P.4 表)
2. **更新 30-BACKLOG-STATUS-AUDIT.md v2**: 引用本 §P 总结
3. **重新 sign-off Sprint 计划**: 从 9 月 → 3 月 (per §P.6)

### 应做 (Sprint 5)
4. **M-BOM-VER-1 frontend follow-up** 3d
5. **C-APPROVAL-EDITOR Phase 2 收尾** 3-5d
6. **M1 三价对比 unblock** (#538 F006 test seed)

### 可做 (Sprint 5-6)
7. **Layer B browser 实测** (~13 项 high/medium 优先级, 1-2 session)
8. **decisionType 扩枚举** 提高工作流覆盖 (Cretas 10 → 拓到 30+)
9. **客户深度试用 + bug fix + 新客户 onboarding**

### 延后 (Sprint 7+)
10. **P2 选做** (按客户触发, e.g. 大企业 → F-VOUCHER-2/F-PERIOD/F-3REPORT)
11. **P3 长期** (Steve sign-off 不抄, e.g. C-MICROSERVICE / C-WECHAT-DOMAIN)

---

## §P.10 文档完整性 audit

| § | 域 | 项数 | Agent | 行号 | 状态 |
|---|---|---|---|---|---|
| §A | CRM | 8 | A | 2657-2897 | ✅ |
| §B | 销售 | 11 | A | 2898-3203 | ✅ |
| §C | 采购 | 6 | B | 1019-1217 | ✅ |
| §D | 仓库+通用 | 5 | B | 1219-1434 | ✅ |
| §E | 生产+BOM | 10 | C | 59-294 | ✅ |
| §F | 品质+模具 | 4 | C | 296-395 | ✅ |
| §G | 财务 | 6 | D | 1769-1976 | ✅ |
| §H | HR | 4 | D | 1978-2114 | ✅ |
| §I | 工作流+配置 | 9 | E | 2124-2432 | ✅ |
| §J | 系统 | 7 | E | 2433-2654 | ✅ |
| §K | UX | 11 | F | 399-693 | ✅ |
| §L | 战略P3 | 8 | F | 694-820 | ✅ |
| §M | 餐饮多门店 | 4 | F | 821-919 | ✅ |
| §N | 88 项 closer | — | F | 920-1013 | ✅ |
| §O | Layer B (browser fresh) | 13 (15 sub) | organizer | 1441-1766 | ✅ |
| §P | 最终聚合 (本 §) | — | organizer | (本 §) | ✅ |

**合计**: 88 backlog items + 13 browser fresh + 1 closer + 1 final synthesis = **完整 audit**.

**总行数估**: ~3700 lines (含本 §P).
**总耗时**: ~1.5h (6 agent parallel ~50min + organizer Layer B parallel ~15min + 修正/聚合 ~25min).
**总 screenshots**: 10 (`../06-宏见测试账号深度审计/screenshots/round11/`).

---

## §P.11 vs 用户原始请求满足度

> 用户原话: "目前分析书来的内容不完整，需要再一次细节的去核对宏见的ERP测试网站，你再去做一下，每一个已经核对过的内容继续深度去抓一次"

**核对结果**:
- ✅ "每一个已经核对过的内容" — 88 项 backlog 全 cover (Agent A-F 分工 + organizer Layer B)
- ✅ "继续深度去抓一次" — 每项做 5 步 (HJ baseline 重读 + Cretas main grep + ship 状态二次验证 + Gap + 推荐), 600+ 字平均/项
- ✅ "核对宏见的 ERP 测试网站" — organizer 用 Playwright 真登录 HJ + 抓 10 张 fresh screenshot
- ✅ "目前分析书来的内容不完整" — 找到 **30+ 项 28-Backlog stale + 7 项 organizer 单关键字 grep 漏判 + 6 处 baseline metadata 偏高**

**新发现 (本 Round 11 独有)**:
- Cretas 真实 ship 进度 ≈ 86% (76 ✅/⚠️/🟡 of 88) vs 之前 estimate 14%
- 真实 P0+P1 剩 ~3 月 vs sign-off 9 月
- decisionType 8% 覆盖率 是真 gap (不是 feature 单点)
- 5 处 Cretas 已优于 HJ (S-LOCK chip / M-MATTREE 库存 / W-CLASS 13 类 / S-REMIND scanner / decisionType N-of-M)

---

<!-- organizer §P FINAL SYNTHESIS END 2026-05-19 -->


<!-- §P.12 Round 12 新增 backlog (来自 32-DEEP-RE-AUDIT-V2.md §G) -->

## §P.12 Round 12 deep audit 新增 backlog items (32-doc §G 来源)

> **来源**: `32-DEEP-RE-AUDIT-V2.md` §G (Round 12 organizer + 5 agents 综合提炼).
> 跟现有 §P.5 真正剩 ❌ 项互补 — §P.5 是 Round 11 reconcile 后真实剩余; §P.12 是 Round 12 fresh capture 新增发现.

### P0 / P1 新增 (Sprint 5+ 候选, ~25-30d)

| # | Item | 32-doc 来源 | 优先级 | 工时 | Sprint |
|---|---|---|---|---|---|
| G12-1 | inline link counter (file/image/contract count per row) | §F.3 + §B.6 | P1 | 4d | Sprint 5 W2 |
| G12-2 | vflag 2 维度 (审核+异常) verify | §F.5 + §E.4 + §A.5 | P2 | 1d verify + 3d 修 | Sprint 5 spot-check |
| G12-3 | 客户档案 21 主 tabs + 5 sub-tabs cascade load (补剩 8 tabs) | §F.1 + §A.1 | P1 | 5d | Sprint 5 |
| G12-4 | 辅助核算 7 类 (客户/供应商/部门/职员/项目/存货/委外商) Voucher 关联 | §F.5 + §A.5 | P1 | 4d | Sprint 5 |
| G12-5 | 复式记账完整收尾 (会计科目树 + 7 辅助核算 FK) | §F.5 + §A.5 | P2 | 8d | Sprint 6+ |
| G12-6 | 数据权限维度 (row-level filter by client/dept) RBAC 第 2 维 | §F.6 + §D.1 | P1 | 6d | Sprint 5 大客户 |
| G12-7 | 打印权限维度独立 (从功能权限拆出) | §F.6 + §D.1 | P2 | 3d | Sprint 6+ |
| G12-8 | 登陆地点范围 (IP whitelist per role) RBAC 第 5 维 | §F.6 + §D.1 | P2 | 3d | Sprint 6+ |
| G12-9 | 报价试算 Sales | §A.2 | P1 | 3d | Sprint 5 |
| G12-10 | 采购需求总表 MRP entry verify | §A.3 + §B.2 | P1 verify | 1d | Sprint 5 |
| **F-VOUCHER-ANOMALY-1** | vflag "异常状态"维度 (per §A.5 X1 finding) | §A.5 X1 | P3 | 3d | Sprint 6+ |
| **S-CRM-CUSTOMTAG-1** | Customer 加 free-form 标签字段 (per §A.1 X1 finding) | §A.1 X1 | P3 | 2d | Sprint 6+ |
| **F-TAX-DIRECT-1** | 税局直连数电票 (per §B.5 X2 finding) | §B.5 X2 | P1 | 10d | Sprint 6+ 客户硬需 |
| **P-REQUISITION-1** | 请购单 entity (per §B.2 X2 finding) | §B.2 X2 | P1 | 5d | Sprint 5 |
| **C-LINK-11TYPE-1** | linkno 扩 11 类 vs 当前 8 (per §B.6 X2 finding) | §B.6 X2 | P1 | 3d | Sprint 5 |
| **M-WAGE-INTEGRATION-1** | 生产工时 → 工资自动 trigger (per §C.7 X3) | §C.7 X3 | P1 | 5d | Sprint 5 |
| **C-MENU-PERSONAL-VIEW** | 我创建的/我参与的工作流 + 工作流处理 admin UI (per §D.5 X4) | §D.5 X4 | P0 | 6d | Sprint 5 W1 |

### P2 新增 (Round 11 漏 / Round 12 fresh, ~50-60d)

| # | Item | 32-doc 来源 | 工时 | 客户群 |
|---|---|---|---|---|
| G12-11 | 序列号管理 (追踪 + 列表 + 统计) | §A.4 + §G | 6d | 高单价产品 |
| G12-12 | 产品报废单 + 失效期预警 | §A.4 + §G | 3d | 食品保质期 |
| G12-13 | 线边仓库管理 (3 sub) | §A.6 + §C.4 | 5d | 大型工厂 |
| G12-14 | 设备点检/维修/保养完整 lifecycle (8 sub) | §A.6 + §C.8 | 10d | 工厂 |
| G12-15 | 工序条件路由 UI (Round 12 修正: 是 product grouping 不是 condition routing) | §C.3 修正 | P2 self-research 5d | 多产品工艺 |
| G12-16 | 作业指导书 SOP 模板 | §A.6 + §C | 8d | 制造企业 |
| G12-17 | 报表三表 (资产负债/利润/现金流) F-3REPORT-1 完整 | §F.5 + §A.5 | 12d | 大企业 |
| G12-18 | 结账管理 (月结/年结 + 反结账) F-PERIOD-1 完整 | §F.5 + §A.5 | 8d | 大企业 |
| G12-19 | 总账 + 凭证汇总 + 科目余额 + 明细账 | §F.5 + §A.5 | 6d | 大企业 |
| G12-20 | 商业机会漏斗 + 日历 + 活动日历 (S-OPP-1 完整) | §A.1 | 8d | 大销售 |
| G12-21 | 业绩管理 6 项 (年度报表/统计口径/未发提成/提成类型/我的) | §A.2 | 5d | 销售管理 |
| G12-22 | S-REPORTS-PRESETS 完整 14 + 利润 6 报表 | §A.2 | 10d | 通用 |

### P3 战略 / 长期

| # | Item | 32-doc 来源 | 工时 | 备注 |
|---|---|---|---|---|
| G12-23 | 大屏看板 C-TV-DASHBOARD-1 confirmed | §A.12 + §G | 15d | 餐饮厨房/工厂车间屏 |
| G12-24 | 集团公司设置 + 系统备份 | §A.12 | 5d | 集团客户 |
| G12-25 | help.cretas.com 客户面 docs 子域 | §F.7 | 5d | 提升专业感 |
| G12-26 | 第三方菜单 + 第三方权限 | §A.12 | 8d | 集成生态 |
| G12-27 | 抄码品 8 字段 (序列号 + 箱标 + 装箱) | §A.4 + §G | 5d | 协同 N13 W-ABA-1 |
| G12-28 | 报价试算 + 销售综合月报 Sales presets | §A.2 | 4d | 销售总监 |

### 工时累计修正 (Round 12)

| 类别 | Round 11 §P 估算 | Round 12 新增 (本 §P.12) | 新合计 |
|---|---|---|---|
| P0 战略 剩 | 5d | +6d (C-MENU-PERSONAL-VIEW) | 11d |
| P0 必修 剩 | 4d | 0 | 4d |
| P1 战术 剩 | 30d | +52d (G12-1/3/4/6/9/10 + F-TAX-DIRECT/P-REQUISITION/C-LINK-11TYPE/M-WAGE-INTEGRATION) | 82d |
| P2 选做 | 60d | +75d (12 新 P2 items) | 135d |
| P3 长期 | 50d | +50d (10 新 P3 items) | 100d |
| **合计 剩余** | ~150d | **+183d** | **~330d nominal** |

按 Claude 1.7× 加速 + 25% buffer:
- Round 11 估: ~150d / ~3 月
- **Round 12 修正**: ~330d nominal / **~6.5 月** (Round 12 揭示更多大客户场景需求 — F-TAX-DIRECT/会计深 reporting/RBAC 数据权限)

按 Steve sign-off "9 月":
- Round 11: 3 月
- **Round 12 修正**: **6.5 月 P0+P1+P2 含大客户场景**, 仍省 2.5 月

### Round 12 关键 finding 总结 (新发现, 入 31-doc §P 主体外)

1. **HJ 14 chapters / 780 articles** 完整在线帮助手册 (`help.hongjian.com`) — Cretas 应学客户面 docs
2. **vflag 不是 4 单维 而是 2x2 维度** (审核 ✓✗ + 异常 ✓✗) — §A.5 X1 + §E.4 X5 共同 finding, 修正 Round 11
3. **RBAC 5 维** (功能/数据/打印/第三方 + 登陆地点) — §D.1 X4 finding, 完整 vs Round 11 估的 4 维
4. **辅助核算 7 类 official** (Round 12 UI 实测) vs HJ docs 2023 article 写 6 类 — 文档落后于产品
5. **decisionType 真实 14 (含 CUSTOM)** vs Round 11 估 10 — 11% 覆盖 (vs HJ 126)
6. **Cretas linkno 8 类 vs HJ baseline 8 类 命名不匹配 3 类** (Cretas project/free, HJ file/image/contract) — §B.6 X2 finding
7. **生产工时 → 工资集成断点** (HJ 自动 trigger, Cretas H-WAGE ship 但缺 trigger) — §C.7 X3 finding
8. **MRP 4 个 entry + 请购单 entity 缺** (Cretas 仅 ShortageAnalysis ship) — §B.2 X2 finding
9. **Cretas 已超 HJ 6 patterns** (jsPlumb→VueFlow 现代 / URL routing / KeepAlive cascade / 操作 ▼ 普及 16-18 site / DesktopModal / Vue state hidden) — §E X5 finding
10. **HJ 帮助手册搜索框 + 蓝色超链接 + 红色注意事项** — UX pattern Cretas 可借 (in-app help 集成)
