# BOM 原辅料配方页面 修复 + 精简 设计

**日期**: 2026-07-13
**分支**: `fix/bom-issues` (worktree `cretas-bom`, off `origin/main`)
**范围**: 仅 `BOM / 配方管理` 的 **原辅料配方** tab。调料配方按工序重构 = 独立 Phase 2 spec,本文不含。

---

## 背景

客户(Steve)对着 `BOM / 配方管理` 页面逐屏走查,发现原辅料配方这个 tab 一堆使用体验问题:产品选不了别的、弹窗漏开发字段名、表单字段太多太啰嗦、配方头和 SKU 内容重复。本 spec 把这些收敛成一批**隔离、低风险、以前端为主**的页面修复 + 精简。

**关键勘查结论**(三个 Explore agent):
- 原辅料配方 tab 的所有 dialog + 产品选择器都在一个文件:`web-admin/src/views/production/bom/index.vue`(~2900 行)。容器壳是 `web-admin/src/views/production/bom-unified/index.vue`(三 tab)。
- 后端 `BomRecipe`(header) + `BomRecipeItem`(line)是现代模型。产品绑定 = `BomRecipe.productTypeId`(逻辑 FK → `product_types.id`)。DRAFT/ACTIVE/ARCHIVED 版本化。
- **SKU(`ProductType`)本身不绑原料** —— 无 material 集合。原料绑定只在 BOM 里。这印证了客户的最终结论:配方头不是和 SKU 重复,BOM 确实是绑原料的唯一地方。
- **SKU 已存**规格字段:`gramsPerUnit`(标准克重,1 份=X 克,honest-null)、`specification`(规格)、`unit`(单位)。所以配方头的产出单位/克重可以从 SKU 读,不必再问。
- `BomRecipeItem` 无任何 工序 字段 —— 原辅料只统计总量、不按工序拆,与客户对原辅料配方的期望一致(工序拆分是调料配方 = Phase 2)。

---

## 目标(4 组改动)

### A. 三个纯 bug

#### A1. 产品选择器只能选「香辣孜然羊排」

**现状**:页面头部下拉 `el-select`(`bom/index.vue:1848-1865`)由 `fetchProductTypeOptions()`(`860-891`)填充,调用 `GET /{factoryId}/product-types?productCategory=FINISHED_PRODUCT&page=1&size=50`。**硬过滤 `productCategory === 'FINISHED_PRODUCT'`**。挂载时 `fetchProductTypeOptions('')` 后 auto-select `productTypes.value[0].id`(`804,810-812`)。该厂只有 SHH0713 被标为 `FINISHED_PRODUCT`,故成唯一可选项。dialog 里的「产品」是 disabled input(`2264-2266`),只镜像页面选择,不是它的锅。

**决策**:BOM 就是给**成品**建配方(客户心智模型"打开一个 SKU 的 BOM"),保留"只看成品"过滤在概念上正确。真因是**数据未归类**。

**修法(数据为主 + 可能小放宽)**:
1. **先勘查**该厂 `product_types` 的分类现状:哪些产品该算成品但 `product_category` 是 null / 其他值。用只读 SQL 或后端只读端点核对(注意 RLS:smartbi 库要 set GUC;但 `product_types` 在 cretas 主库,Java 端查)。
2. 根据勘查结果:
   - 若确是该算成品的产品没归类 → **把它们的 `product_category` 归到 `FINISHED_PRODUCT`**(数据修正,不改代码)。
   - 若"成品"在该厂散落在多个类别 → **放宽前端过滤**为一个成品类别白名单(如 `FINISHED_PRODUCT`),而非默认拉全部(避免半成品/原料混进 BOM 选品)。
3. **验证**:选品下拉能出现全部应建 BOM 的成品,且不混入原料/半成品/包材。

> ⚠️ 这一条的具体修法**依赖真实数据**,实施第一步就是勘查。spec 只锁定意图(成品-only + 归好类),不预判是纯数据还是要动过滤代码。

#### A2. 弹窗漏开发字段名

`bom/index.vue` 里 7 处 form-tip / alert / hint 把内部字段名直接显示给用户,全部换成人话或删掉:

| 行 | 现文案 | 改为 |
|---|---|---|
| 2257 | `这里维护 BomRecipe 配方头，不是原辅料行项目` | `这里维护配方头(产出规格 + 整体出成率),原辅料明细在下方表格维护` |
| 2276 | `BomRecipe.outputQuantityPerUnit，必须大于 0；例如 1 份、200 g、0.5 kg。` | (随 B 改为只读展示,tip 简化或删) |
| 2285 | `BomRecipe.outputUnit，用于嵌套 BOM 成本、营养标签和添加剂合规换算。` | (随 B 改为只读展示,tip 简化或删) |
| 2297 | `BomRecipe.overallYieldRate，范围 0.01–100；行项目出成率仍在原辅料弹窗维护。` | `整体出成率,范围 0.01–100;单行出成率在原辅料弹窗里维护` |
| 2312 | `创建时会把当前原辅料明细作为 CreateBomRecipeRequest.items；请先确认每行已关联原料类型且成品含量大于 0。` | `创建时会把上方原辅料明细一起存为草稿配方;请先确认每行已关联原料且成品用量大于 0` |
| 2522 | `对应 BomRecipeItemDTO.isOptional，适用于装饰菜、可省略配料等。` | `可选料:不作为生产计划完整性硬性要求,适用于装饰菜、可省略配料等` |
| 2531 | `对应 BomRecipeItemDTO.substituteGroup；相同分组表示互为替代料。` | `相同分组的物料可互相替代` |

规则:**任何用户可见文案不得出现 `BomRecipe`/`*DTO`/`*Request` 等类名/字段名。**

#### A3. 配方头字段顺序

现状(`2264→2299`):产品 → 每单位产出量 → 产出单位 → 整体出成率 → 备注。客户要求产出单位在每单位产出量之前。**随 B(两者都改为从 SKU 只读展示)自动解决** —— 只读展示时按"产出单位 → 每单位产出量"排。

---

### B. 创建配方头 精简 —— 产出规格从 SKU 只读

**决策**:「产出单位」+「每单位产出量(克重)」和 SKU 重复,改为**从 SKU 自动读、只读展示**。

- 数据来源:`ProductType.unit`(或 `outputUnit` 语义对应的单位)+ `ProductType.gramsPerUnit`(标准克重)/ `specification`。
  - 展示顺序:**产出单位 → 每单位产出量**。
  - 只读(disabled / 纯文本),不让用户在配方头里改。
- **SKU 未填克重(`gramsPerUnit` null)时**:显示提示 + 跳转/引导"去 SKU 补标准克重"(防呆 Rule 5:dead-end 改导航)。配方头此时可提示不完整,但不强行阻断(具体阻断策略实施时定,倾向:允许存草稿,提示克重待补)。
- 配方头弹窗**只剩「整体出成率% + 备注」两个可填字段**。
- 提交 payload(`CreateBomRecipeRequest`)的 `outputQuantityPerUnit` / `outputUnit` 由 SKU 值填充(前端读 SKU 带入),后端契约不变。

**边界**:后端 `BomRecipe.outputQuantityPerUnit` / `outputUnit` 字段**保留**(嵌套 BOM 成本、营养标签换算仍用),只是前端来源从"手填"变"读 SKU"。不改后端 entity / DTO。

---

### C. 添加原辅料 精简

现状字段顺序(`2325→2579`):物料名称(手填)→ 物料类别 → 关联原料 → 成品用量 → 出成率评估/出成率% → 实际原料用量 → 计量单位 → (包材)每产品用量 → 单价 → 税率 → 备注 → 可选料 → 替代料分组 → 按份数投料 → 半成品引用 → 嵌套子产品。

改动:

#### C1. 藏掉「物料名称」手填
- 删除「物料名称」输入框(`2325`)。
- 名称仍在后台从关联原料自动带入:`onMaterialLink`(`942-949`)已把 `material.name` 写入 `bomForm.materialName`。保留这个赋值,只是不再有可见输入框。
- 提交时 `materialName`(denormalized)照常带上。

#### C2. 顺序改为 类别 → 关联原料 → 成品用量 → 计量单位
- 顶部变:**物料类别(`2328`)→ 关联原料(`2335`)→ 成品用量(`2353`)→ 计量单位(`2464`)**。
- 用户只"选",不手打名字。

#### C3. 出成率 / 实际原料用量 按类别区分
- **原料(`materialCategory === 'RAW'`)**:
  - 出成率字段**保留但永不强制**,默认空/"待评估"。
  - 「评估建议出成率」按钮(`2369-2426`,现已 `v-if RAW && bomUnitIsWeight`)保留。
  - 「实际原料用量」(`computedActualQuantity`,`2449-2463`)**只读展示**,不让填(现状已是 computed display,确认保持只读)。
  - 后端出成率空 → 用标准克重展开(现有 `yield_rate` NOT NULL default 100 + `actualQuantity = standardQuantity/(yieldRate/100)` 逻辑;"待评估"= 前端不传 yieldRate 让后端用默认/克重展开,实施时确认后端对空出成率的行为)。
- **辅料 / 包材(`AUXILIARY` / `PACKAGING`)**:
  - 「出成率%」+「实际原料用量」两个字段**整块隐藏**(`v-if` 排除非 RAW)。
  - 语义:固定 100%(无损耗折算),只填「成品用量」= 一份成品需要多少克/多少包材。
  - 提交时 `yieldRate` 传 100(满足后端 NOT NULL)。

#### C4. 计量单位默认克(g)
- 现状 `onMaterialLink` / `onBomCategoryChange` 已按物料带出单位;无关联物料时默认 `克(g)`(确认默认值)。

#### C5. 高级字段收进「高级选项」折叠区
- 默认表单只显示核心:物料类别 / 关联原料 / 成品用量 / 计量单位 / (原料的出成率评估) / 单价 / 税率 / 备注。
- **收进折叠区(默认收起)**:可选料(`2518`)、替代料分组(`2524`)、按份数投料(`2533`)、半成品引用(`2544`)、嵌套子产品(`2562`)。
- 用 `el-collapse` 或"显示高级选项"toggle。功能全保留,只是默认不占屏。

---

### D. 明确不动的边界

- **人工费用表**(`原辅料配方` tab 底部)—— 客户明确"人工一会再说",Phase 1 不碰。
- **调料配方 / 转换率 两个 tab**(`ProductRecipeView.vue` / `conversions/index.vue`)—— 归 Phase 2 / 各自独立,不碰。
- **后端 entity / DTO / controller** —— 除非 A1 勘查结论要求动过滤,否则 Phase 1 **纯前端 + 可能的数据归类**,不改后端契约。
- 出成率评估按钮、一键重算出成率、对话微调、变更记录、Excel 导入/导出、模板下载 —— 现有功能,保留不动。

---

## 影响面 / 风险

| 项 | 风险 | 缓解 |
|---|---|---|
| A1 数据归类 | 若直接改 `product_category` 影响其他按类别过滤的地方 | 先勘查,只改确该算成品的;必要时前端白名单而非改数据 |
| B 从 SKU 只读 | SKU `gramsPerUnit` 为 null 的产品配方头缺克重 | 防呆提示"去 SKU 补克重",不静默 0 |
| C3 辅料隐藏出成率 | 后端 `yield_rate` NOT NULL | 前端对辅料/包材固定传 100 |
| 单文件 2900 行 | 并发编辑覆盖风险(concurrent-edit-safety) | worktree 隔离 + 里程碑 commit |

## 验收

- 产品下拉能选到该厂全部应建 BOM 的成品(不混原料/半成品/包材)。
- 创建配方头 / 添加原辅料弹窗**全程无** `BomRecipe`/`*DTO`/`*Request` 字样。
- 配方头:产出单位/每单位产出量只读(从 SKU),只填整体出成率 + 备注;SKU 无克重时有明确提示。
- 添加原辅料:无物料名称手填;顺序 类别→关联原料→成品用量→单位;辅料/包材看不到出成率/实际用量;高级字段默认折叠。
- headed 验证(web-admin,zh-CN,1920×1080),中文无方块,截图存档。
- 不破坏现有:嵌套 BOM、替代料、Excel 导入、成本重算。

---

## 后续(Phase 2,不在本 spec)

调料配方按工序重构:把现有 SKU 级注射/熟制段 + 锅序机制(`BomSeasoningItem.section` + `BomRecipe.subsequentPotRatio/cookingPotBaseKg/injectionRate` + `RecipeCostCalculator` 第一锅全量/第二锅比例)重新挂到**工序节点**,并把 报工锅数(现硬编码 `processCode==='shuzhi'`)泛化为按工序配置。依赖工序链模型(`ProductProcessWorkflow` 图 / 传统 `ProductWorkProcess` 线性链)稳定,且需先在三套 seasoning 模型(`BomSeasoningItem` / `ProcessMaterialRecipe` / 遗留 `ProductRecipe`)里定"哪个为准"。另开 spec。
