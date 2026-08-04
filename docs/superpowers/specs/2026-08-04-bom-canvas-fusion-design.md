# BOM 收进工序画布 — 产品配置画布设计

**日期**: 2026-08-04
**状态**: 设计已拍板，待实施计划
**原型**: https://claude.ai/code/artifact/31729220-1a43-4b16-a1e9-fc2f38bcca7d
**范围**: web-admin 产品配置（工序编排 + BOM 配方）；不含 RN

---

## 1. 背景

工序结构和配方用量今天分居两个页面：

| 页面 | 文件 | 规模 |
|---|---|---|
| 工艺画布 | `web-admin/src/views/system/product-processes/workflow/ProductProcessWorkflowEditor.vue` | 3435 行 |
| BOM 编辑器 | `web-admin/src/views/production/bom/index.vue` | 4085 行 |

配一个产品要在两处来回跳。而数据层其实已经缝合了：`BomRecipe` 钉死一份不可变的 workflow revision（含 `workflow_nodes_snapshot_json` / `workflow_edges_snapshot_json`），三类物料各自都有画布锚点。

**画布事实上已经是 BOM 的骨架，只是没画出来。**

---

## 2. 已拍板的决策

| # | 决策 | 依据 |
|---|---|---|
| D1 | 一张画布装下全部，**不再有第二个能写的地方** | 同一个人一次配完（用户确认的使用场景） |
| D2 | 辅料、包材各自是**独立 cell**，不是贴在方块上的格子 | 每种一行会把方块撑爆 |
| D3 | **每道工序一个辅料 cell**，每个终端产出一个包材 cell | 就近好认，分母天然是本工序投入 |
| D4 | 一张画布**全部装下多产出**，方块自带成本归属 | 钱派给谁要一眼可见 |
| D5 | 工艺与 BOM **合并成一个版本号** | 见 §5 |
| D6 | **人工成本、均摊费用移出 BOM** | 见 §6 |

**贯穿判据**：

> BOM 只配「每单位固定消耗的实物」。凡是要等实际发生才知道的，都不进 BOM。

---

## 3. 领域模型：三类数量语义

融合的关键不是把表格搬上画布，而是承认三类物料的数量口径本来就不同 —— 分母不一样，挂载对象也不一样。

| cell 类型 | 数量语义 | 分母 | 现有数据列 |
|---|---|---|---|
| **原料 / 半成品** | 没有数量，只声明「允许投」+ 单位换算 | —— | `workflow_material_node_id`<br>`workflow_input_port_id`<br>`workflow_edge_id` |
| **辅料 cell** | 每 1 kg **本工序投入**用多少克 | 该工序的投入基准量 | `bom_seasoning_items.workflow_process_node_id`<br>`dosage_per_kg_g` |
| **包材 cell** | 每 1 个 SKU 基本单位**成品**用多少 | 成品基本单位（盒 / kg） | `bom_recipe_items.target_terminal_node_id`<br>`natural_quantity` / `natural_unit` |

### 3.1 分母必须印在 cell 上

辅料写「12 g/kg」分母是**这道工序投进去多少**；包材写「1 个/盒」分母是**出了多少盒成品**。两个格子长得一样，算的东西完全不同。

**规格**：cell 里每一行都带完整分母，禁止只显示「12g」。按重量卖的副产品（鸭油按 kg），桶的整数用量换算成每 kg 写 `0.05 个/kg`，同时在 tooltip 显示原始表达「= 1 个 / 20 kg」——「0.05 个」对仓管毫无意义，「1 桶装 20kg」才是他认识的说法。

### 3.2 主链路确认没有数量

现状核实（不是推测）：

- BOM「添加原辅料」对话框，非包材类别**没有数量输入框**，只有说明文字「原料与辅料在 BOM 中维护配方资格；本批计划投入和实际消耗由生产计划与正式报工记录」（`index.vue:2815`）
- 画布侧 `ProcessPort.standardQuantity` 已标注 *"Legacy snapshot field. The editor no longer authors planned input/output quantities."*

---

## 4. 画布规格

### 4.1 节点类型

沿用现有 `ProductProcessNodeKind` 四类，不新增：`RAW_MATERIAL` / `PROCESS` / `SEMI_FINISHED` / `FINISHED_GOOD`。

辅料 cell 和包材 cell 是**新增的两类挂载 cell**，通过虚线连到它们服务的工序 / 终端产出。

### 4.2 连线语义

| 线型 | 含义 |
|---|---|
| 实线（钢青） | 主链路：原料 → 工序 → 半成品 → 产出。**线上没有任何数量** |
| 虚线（琥珀） | 辅料 cell → 工序。分母是本工序投入量 |
| 虚线（暗莓） | 终端产出 → 包材 cell。分母是成品基本单位 |

### 4.3 标记体系

一条辅料身上挂着多种属性。全展开就变回表格，全藏起来等于没融合。

**规则：默认状态不标，只标异常。** 共享成本是默认，不标；不共享才亮 `◑`。不按锅序是默认，勾了才亮 `◷`。扫一眼就知道哪几行有特殊规则。

| 标记 | 含义 | 数据列 |
|---|---|---|
| `◷` | 按锅序 | `subsequent_pot_ratio` |
| `⊘` | 不计入成本 | `count_in_seasoning = false` |
| `⇄` | 有替代物料 | `BomItemSubstitute` |
| `◑` | 成本只算部分产出 | `cost_scope` / `cost_scope_key` |
| `⚑` | 系统有建议待采纳 | `BomYieldSuggestion` |
| `⊞` | 按份数投料，不随出成率折算 | `per_portion` |
| `○` | 配方可选项 | `is_optional` |
| `▤` | 包装层级（箱 / 盒 / 袋） | `packaging_spec_id` |
| `⊙` | 嵌套子产品 | `sub_product_type_id` |

hover 显示具体值（如「首锅 100% · 后续 60%」），点开 cell 看全部。

### 4.4 锅序：卤汁的特例，属于辅料

锅序**不是**工序开关，也不是「工序 × 辅料」的关系属性 —— 它是**逐条辅料自己的**设置。

领域含义：卤锅里的老汤反复用，第一锅下全量料，后面几锅因为汤里还有残味只补一部分。所以同一道卤制工序里可以是：

- 八角、桂皮、香叶 → 按锅序补（`◷` 首锅 100% / 后续 60%）
- 生抽 → 每锅照下（无标记）
- 老汤 → 用了但不计成本（`⊘`）

**⚠️ 副作用必须在画布上说出来**：这道工序只要有**一条**辅料勾了锅序，它的工序单就会多出「锅数」和「逐锅原料重量」两栏。

证据链：
```
SeasoningBindingDialog.vue:354        「按锅序计算」开关
  → ProcessSheet.vue:415              seasoningPotEnabled = potEnabled.has(workProcessId)
  → ProcessDataTable.vue:265          needsPotCount = seasoningPotEnabled === true
  → potAllocation.ts                  逐锅原料重量分配
```

`ProcessDataTable.vue:98` 注释原文：*"锅数由 seasoningPotEnabled 唯一驱动"*。

技术员勾一个开关，车间就多两栏要填。这个后果今天完全不可见，画布上必须写出来 —— 工序 cell 显示「报工需录锅数」，并在详情里说明它从哪来。

### 4.5 注射：普通工序，无特殊规则

注射是一道独立工序，其辅料 cell 与其他工序无差异，不做特殊处理。注射工序不复用卤汁，所以它的辅料里不会出现 `◷`。

（`BomProcessInjectionConfig.injection_amount_kg` 的去留见 §8 待定项。）

---

## 5. 版本：合并成一个

### 5.1 现状：两套版本号是假象

系统**已经在强制两者同步**：

| 位置 | 校验 |
|---|---|
| `ProductProcessWorkflowServiceImpl:133` | 发布 workflow 时调 `requireActiveBomPinsRevision` |
| `ProductProcessWorkflowActivationServiceImpl:68` | 启用时调 `requireExactPublishedRevisionForActiveBom` |
| `BomWorkflowRevisionService#requireCompleteActiveFamily` | 要求**每个终端产出**都有钉住该修订、属于同一 family 的 ACTIVE BOM，缺一个 409 |

也就是说工艺发布/启用的前提，就是所有终端产出的 BOM 都已经对齐到这个修订。**两套版本在生效那一刻本来就是一体的**，UI 上的两个版本号是假象。

合并版本号不是推翻地基 —— `workflow_revision_hash` / `bom_family_id` 正是它的实现基础。

### 5.2 目标行为

| 操作 | 后果 |
|---|---|
| 在已发布版本上开始编辑 | 自动创建草稿：从当前生效修订克隆出 workflow revision(DRAFT) + 整个 BOM family 的草稿，不需要用户先点「新建版本」 |
| 画布上改任何东西（拖边、加工序、改克数） | 只动**草稿**：workflow revision(DRAFT) + 同 family 的所有 BOM 草稿，同生共死。版本号不动 |
| 点「发布」 | 走现有 `requireCompleteActiveFamily` 闸 → 工艺转 PUBLISHED + 所有终端 BOM 转 ACTIVE，产品配置版本 +1 |
| 在跑的生产计划 | **不受影响** |
| 「升级到最新工艺」按钮 | **下线** |

在跑的计划不受影响的依据：`ProductionPlan.selectedWorkflowId` / `selectedWorkflowVersion` 在建计划时钉死，注释原文 *"Plans pin an exact workflow/version. Activation may move after plan creation"*。

### 5.3 发布闸的两面

防呆的价值在被拦的那一面。发布按钮在校验不过时**直接禁用**，不是点了之后弹错；每条不通过项都带「去哪补」的跳转。

---

## 6. 移除项

### 6.1 人工成本（`LaborCostConfig`）

**移除理由**：人工要等结算才有 —— 实际工时 × 时薪 ÷ 实际箱数。

核实结果：

- BOM 侧 `LaborCostConfig` 只被算标准成本的 `BomServiceImpl:229/302` 读
- 报工侧 `web-admin/src/utils/processSheetLaborCost.ts#calculateLaborPerBox` 是真实路径（默认时薪 26 元/小时）
- **下游成本报表读的是报工那条**：`OrderCostBreakdownService:182`、`YieldReportServiceImpl:1497` 取的都是 `BatchYieldDTO.totalLaborCost`（批次实际）

BOM 里那份标准值，下游没有消费者。

### 6.2 均摊费用（`OverheadCostConfig`）

**移除理由**：同上 —— 均摊是成本分析时才摊。

核实结果比人工更干净：`OrderCostBreakdownService` 和 `YieldReportServiceImpl` 里 **`overhead` 零命中**。`total_overhead_cost` 除了被自己快照（`BomVersionServiceImpl:216`、`BomBatchOperationServiceImpl:413`）、复制（`SkuAssemblyService:281`）、按比例分摊（`BomRecipeServiceImpl:2127`）之外，**没有任何业务读取者**。

### 6.3 下线清单

| 项 | 处理 |
|---|---|
| `BomController` 人工成本 5 个端点（`/labor` GET·GET all·POST·PUT·DELETE，:106–158） | 下线 |
| `BomController` 均摊费用 4 个端点（`/overhead` GET·POST·PUT·DELETE，:165–205） | 下线 |
| BOM 页「人工与均摊费用」区块（`index.vue:2614` 起） | 下线 |
| `bom_recipes.total_labor_cost` / `total_overhead_cost` 列 | **保留，恒为 null** |
| `bom_recipe_items.standard_quantity` 老列 | 清理，见 §6.4 |

**null 而不是 0**：跟 `computeItemCost()` 在没有用量时返回 null 是同一个诚实口径。注释原文：*"未配置参考用量时成本是'尚未归集'，不能伪装成 0 元"*。空着代表「这里不归集」，写 0 代表「人工不要钱」，后者是假话。

### 6.4 标准成本口径

原料没有数量，人工和均摊等结算，所以：

> **标准成本 = 辅料 + 包材**

这不是缺陷，是这个产品的口径：**BOM 是配方规格，不是成本卡**。真实成本在批次结算时由实际投料 + 实际工时汇总。

前端其实已经在小心处理这件事 —— BOM 页总成本标题在有物料没配用量时会自动从「当前总成本」切成「**当前归集成本**」（`index.vue:2276`）。

**⚠️ 要一起清的老列**：`bom_recipe_items.standard_quantity` 已经没有录入口，但 `index.vue:1645` 的成本合计还在读它，`hasPendingActualMaterialUsage` 还在检测非包材行为空。不清掉会出现「画布上没地方填、成本里却冒出个数」。

---

## 7. 边界状态与防呆

三种状态今天要么藏在保存时的报错里，要么根本没提示。

### 7.1 配不了（灰态）

工序的投入基准换算不出重量时，挂在它上面的辅料 cell **整个灰掉**并写明原因：「本工序投入基准未形成换算契约，暂不能配用量」+「去工艺层为该工序绑定明确产出 →」。

判据 `standardUsageSupported` 今天就有，但只在点保存时才告诉用户。按防呆 Rule 1，**边界要预先显示，不是事后报错**。

典型场景：组合装的「装盒」工序，投入基准是「盒」不是 kg。

### 7.2 没配（空态）

空 cell 不能长得像配好的 cell。显示「0 种 · 未配」+「缺包材，本条工艺发布不了 →」。

按防呆 Rule 5，空状态必须带 next action。

### 7.3 配了多层（包装层级）

袋 / 盒 / 箱是**同一个 SKU 的三个包装层级**，不是三种不相干的包材。分母统一到基本单位「盒」，所以外箱是 `0.125 个/盒`，hover 显示「1 箱 8 盒」。

### 7.4 替代物料的等价系数

点开带 `⇄` 的行进入替代编辑面：

- 同单位 → 系数默认 1.0，只读
- **跨单位 → 系数必填**，输入框标红，明示「不填不能保存」

系统不猜换算关系。替代辅料只在**当前这道工序**生效，不作为额外需求，不重复计入成本。创建时快照父行的物料 / 工序 / 类别，档案改名也追得回。

### 7.5 权限

不做权限态设计 —— 采购角色本来就看不到产品工序配置，只有生产角色能进这个页面。

---

## 8. 待定项

| 项 | 情况 | 建议 |
|---|---|---|
| **注射量**<br>`BomProcessInjectionConfig` | 后端**没有任何 controller 端点**；唯一写入路径是跟调料一起整体保存的老接口（`BomRecipeServiceImpl:1407`）。但它**是活的**：`ClerkProcessEntryServiceImpl:1122` 读 `injectionAmountKg`，`WorkProcessServiceImpl:217` 用它挡工序删除，BOM 复制会带上 | **一期不动**：不删（有活读取者）、不建新入口（注射按普通工序处理）。二期再定它算「固定消耗」还是「实际才知道」 |

---

## 9. 影响面

### 9.1 后端

| 文件 | 改动 |
|---|---|
| `controller/BomController.java` | 下线人工 5 + 均摊 3 个端点 |
| `service/impl/BomServiceImpl.java` | 移除 labor/overhead 读取与成本合计 |
| `service/bom/impl/BomRecipeServiceImpl.java` | `total_labor_cost` / `total_overhead_cost` 恒 null；清理 `standard_quantity` 读取 |
| `service/bom/BomWorkflowRevisionService.java` | 复用现有闸，不改语义 |
| `service/impl/ProductProcessWorkflowServiceImpl.java` | 草稿保存改为 workflow revision + BOM 草稿家族原子写 |

**⚠️ 实施最大风险点**：「workflow revision 草稿 + 整个 BOM family 草稿同生共死」是本设计唯一的新机制，其余全是复用。它必须原子 —— 半途失败留下「工艺草稿新、BOM 草稿旧」的状态，就是今天两套版本脱节问题的翻版。写实施计划时这一条要单独排期并有回滚验证。

### 9.2 前端

| 文件 | 改动 |
|---|---|
| `views/system/product-processes/workflow/ProductProcessWorkflowEditor.vue` | 主体：接入辅料 / 包材 cell、标记体系、边界状态 |
| `views/production/bom/seasoning/BomAuxiliaryWorkspace.vue` | 逻辑迁入画布；组件复用其数据加载与冲突处理 |
| `views/production/bom/index.vue` | 三 tab 写入口下线，保留只读：成本汇总 / ECN / 导出；「人工与均摊费用」区块删除 |
| `views/production/bom/seasoning/SeasoningBindingDialog.vue` | 改为画布内编辑面 |

### 9.3 不动的

生产计划、报工、工序单全部不改 —— 它们读的是钉死的 workflow 版本和批次实际值。

---

## 10. 验收判据

1. 配一个新品全程不离开画布，BOM 页无写入口
2. 改克数不产生新版本号；点发布才 +1
3. 发布前缺任一终端产出的 BOM，发布按钮**禁用**并指出缺哪个
4. 辅料勾锅序后，该工序的工序单出现「锅数」栏；取消后消失
5. 投入基准不可换算的工序，其辅料 cell 灰态且给出跳转
6. 跨单位替代不填系数不能保存
7. `total_labor_cost` / `total_overhead_cost` 为 null 而非 0
8. 已建生产计划在工艺发新版后行为不变

---

## 11. 附：UX Flow Gate

CLAUDE.md 的 UX Flow Gate 针对 **RN 屏幕设计**。本设计是 web-admin 画布，使用者是技术员，不触发该门。锅序对工序单的影响属于既有 web-admin 工序单，不在本次改动范围。
