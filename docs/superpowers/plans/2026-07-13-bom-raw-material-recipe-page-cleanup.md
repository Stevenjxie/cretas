# BOM 原辅料配方页面 修复+精简 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 修复 `BOM / 配方管理` 原辅料配方 tab 的一批使用体验 bug 并精简两个弹窗表单(产品选品、漏字段名、配方头从 SKU 只读、添加原辅料按类别精简、高级字段折叠)。

**Architecture:** 纯前端(Vue 3 + Element Plus)+ 一次数据分类勘查。几乎所有改动在单文件 `web-admin/src/views/production/bom/index.vue`。不改后端 entity/DTO/controller 契约。产出规格改为从 `ProductType`(SKU)读、只读展示。出成率/实际用量按 `materialCategory` 区分显隐。

**Tech Stack:** Vue 3 `<script setup>`, Element Plus (`el-select`/`el-form`/`el-collapse`/`el-input-number`), TypeScript, Vite. 后端 Spring Boot(仅只读查询 `product_types`)。

**隔离/安全:** worktree `cretas-bom` off `origin/main`(已建)。单文件 2900 行,**每个 Task 完成立即 commit**(concurrent-edit-safety Rule 1),`git commit -- <file>` 锁 scope。禁止在主工作目录直接改。

**验证方式:** 无 dialog 单测 → 用 headed 浏览器驱动真实页面(playwright-test 独立实例,**不要用 plugin playwright**,那个驱动 Steve 本人的 Chrome)+ `npm run build` 不报 TS 错 + 现有 vitest 不破。

---

## 文件结构

| 文件 | 责任 | 改动 |
|---|---|---|
| `web-admin/src/views/production/bom/index.vue` | 原辅料配方 tab 全部逻辑 + 两个弹窗 + 产品选择器 | Modify(A2/B/C 全部) |
| `product_types` 表数据(该厂) | 成品分类 | 勘查 → 可能数据归类(A1) |
| (可能)`bom/index.vue:860-891` `fetchProductTypeOptions` | 产品选品过滤 | 视 A1 勘查结论,可能放宽白名单 |

前置只读参考:
- 后端产品实体 `backend/java/cretas-api/src/main/java/com/cretas/aims/entity/ProductType.java`(字段 `productCategory` / `gramsPerUnit` / `specification` / `unit`)。
- 产品类型端点 `GET /{factoryId}/product-types`(controller 搜 `ProductTypeController`)。

---

## Task 0: A1 勘查 —— 查该厂 product_types 分类现状

**Files:**
- 只读查询,不改文件。

- [ ] **Step 1: 找到查询该厂产品分类的只读途径**

优先用后端只读端点(避免直连 prod DB)。启动本地后端或用测试环境:
```
GET http://47.100.235.168:10011/api/mobile/{factoryId}/product-types?page=1&size=200
```
或本地 `mvn spring-boot:run` 后打 `http://localhost:10010/...`。factoryId 用出问题的那个厂(截图是 F006 六扇门,SHH0713 香辣孜然羊排 → 确认 factoryId)。

若必须直连 DB(cretas 主库,**非 smartbi,不涉及 RLS GUC**):
```sql
SELECT product_category, COUNT(*), STRING_AGG(name, ', ' ORDER BY name)
FROM product_types
WHERE factory_id = '<F006 factoryId>' AND deleted_at IS NULL
GROUP BY product_category ORDER BY 2 DESC;
```

- [ ] **Step 2: 判定修法**

记录结论到 plan 或 dispatch 台账:
- 若"该算成品"的产品 `product_category` 是 null / 非 FINISHED_PRODUCT → **修法 = 数据归类**(Task 1a)。
- 若成品散落多个类别、且这些类别语义都是"成品" → **修法 = 前端放宽白名单**(Task 1b)。
- 若发现真有非成品被误期望能建 BOM → 回报 Steve 确认(可能是 Phase 1 选品范围决策需复议)。

**Expected:** 明确知道 SHH0713 之外应能建 BOM 的产品有哪些、它们现在的 `product_category` 是什么。

---

## Task 1a: A1 修法 —— 数据归类(若 Step 2 判定数据问题)

**Files:**
- 数据修正(SQL / 后端管理端),不改前端。

- [ ] **Step 1: 写归类 SQL(先 dry-run SELECT 确认命中集)**

```sql
-- 先看要改哪些
SELECT id, name, product_category FROM product_types
WHERE factory_id = '<factoryId>' AND deleted_at IS NULL
  AND product_category IS DISTINCT FROM 'FINISHED_PRODUCT'
  AND <该算成品的条件>;   -- 根据 Task 0 结论填具体条件,不要盲改全部
```

- [ ] **Step 2: 执行归类(仅测试环境先验,prod 需 Steve 确认/走 Opus 出货闸)**

```sql
UPDATE product_types SET product_category = 'FINISHED_PRODUCT', updated_at = NOW()
WHERE factory_id = '<factoryId>' AND id IN (<明确 id 列表>);
```
> ⚠️ 改 prod 数据 = 🔒 红线,不由执行者自行在 prod 跑。测试环境验证后回 Opus 终审 + 决定 prod 执行。

- [ ] **Step 3: 验证选品下拉**

刷新页面,产品下拉应出现新归类的成品,不混原料/半成品/包材。

---

## Task 1b: A1 修法 —— 前端放宽白名单(若 Step 2 判定过滤太窄)

**Files:**
- Modify: `web-admin/src/views/production/bom/index.vue:860-891` (`fetchProductTypeOptions`)

- [ ] **Step 1: 改过滤为成品类别白名单**

现状硬编码 `productCategory: 'FINISHED_PRODUCT'`。若成品散落多类,改为不传单一 category、前端按白名单过滤,或后端支持多 category。示例(前端过滤兜底):
```js
const FINISHED_CATEGORIES = ['FINISHED_PRODUCT'];  // 按 Task 0 结论补齐,如 ['FINISHED_PRODUCT','SEMI_FINISHED_SALEABLE']
// 请求不带 productCategory,拉回后过滤:
const list = (response?.content ?? []).filter(
  (p) => !p.productCategory || FINISHED_CATEGORIES.includes(p.productCategory)
);
```
> 具体白名单成员由 Task 0 结论决定。**不要**默认拉全部产品(会混入原料/半成品/包材,违背"BOM 只给成品建")。

- [ ] **Step 2: build + headed 验证**

```bash
cd web-admin && npm run build
```
Expected: 无 TS 错。headed 打开页面,选品下拉出现全部应建 BOM 的成品。

- [ ] **Step 3: Commit**

```bash
git commit -m "fix(bom): 放宽原辅料配方产品选品为成品类别白名单" -- web-admin/src/views/production/bom/index.vue
```

---

## Task 2: A2 —— 清除弹窗漏出的开发字段名

**Files:**
- Modify: `web-admin/src/views/production/bom/index.vue` 行 2257 / 2276 / 2285 / 2297 / 2312 / 2522 / 2531

- [ ] **Step 1: 逐条替换 7 处文案**

| 行 | 现文案(old_string 锚点) | 新文案 |
|---|---|---|
| 2257 | `这里维护 BomRecipe 配方头，不是原辅料行项目` | `这里维护配方头(产出规格 + 整体出成率),原辅料明细在下方表格维护` |
| 2276 | `BomRecipe.outputQuantityPerUnit，必须大于 0；例如 1 份、200 g、0.5 kg。` | (B 会把此项改只读,tip 删除或改 `从 SKU 标准克重带入`) |
| 2285 | `BomRecipe.outputUnit，用于嵌套 BOM 成本、营养标签和添加剂合规换算。` | (B 会把此项改只读,tip 删除或改 `从 SKU 单位带入`) |
| 2297 | `BomRecipe.overallYieldRate，范围 0.01–100；行项目出成率仍在原辅料弹窗维护。` | `整体出成率,范围 0.01–100;单行出成率在原辅料弹窗里维护` |
| 2312 | `创建时会把当前原辅料明细作为 CreateBomRecipeRequest.items；请先确认每行已关联原料类型且成品含量大于 0。` | `创建时会把上方原辅料明细一起存为草稿配方;请先确认每行已关联原料且成品用量大于 0` |
| 2522 | `对应 BomRecipeItemDTO.isOptional，适用于装饰菜、可省略配料等。` | `可选料:不作为生产计划完整性硬性要求,适用于装饰菜、可省略配料等` |
| 2531 | `对应 BomRecipeItemDTO.substituteGroup；相同分组表示互为替代料。` | `相同分组的物料可互相替代` |

- [ ] **Step 2: grep 确认无残留类名**

```bash
grep -nE 'BomRecipe|BomRecipeItemDTO|CreateBomRecipeRequest|BomSeasoning' web-admin/src/views/production/bom/index.vue
```
Expected: 用户可见 `<template>` 文案里**零命中**(script 里的类型/接口名不算,只清 template 展示文案)。

- [ ] **Step 3: Commit**

```bash
git commit -m "fix(bom): 清除原辅料配方弹窗漏出的开发字段名" -- web-admin/src/views/production/bom/index.vue
```

---

## Task 3: B —— 配方头产出规格从 SKU 只读展示

**Files:**
- Modify: `web-admin/src/views/production/bom/index.vue` 创建配方头 dialog(2246-2320)+ `submitRecipeHeaderForm`(~530-564)+ 选中产品的 SKU 数据源。

- [ ] **Step 1: 拿到当前选中 SKU 的 gramsPerUnit / unit**

确认 `fetchProductTypeOptions` / `loadProductTypes` 返回的 product 对象是否含 `gramsPerUnit`、`unit`、`specification`。若 `el-option` 用的精简对象不含这些字段 → 扩展查询或在选中时按 id 取详情。选中产品存入如 `selectedProductMeta`(含 gramsPerUnit/unit)。

- [ ] **Step 2: 「每单位产出量」「产出单位」改只读展示,顺序产出单位在前**

把 2267(每单位产出量 input)+ 2278(产出单位 input)两个 `el-form-item` 改为只读文本,顺序调成 **产出单位 → 每单位产出量**:
```vue
<el-form-item label="产出单位">
  <el-input :model-value="selectedProductMeta?.unit || '份'" disabled />
  <div class="form-tip">从 SKU 单位带入,如需修改请到产品(SKU)维护</div>
</el-form-item>
<el-form-item label="每单位产出量">
  <template v-if="selectedProductMeta?.gramsPerUnit != null">
    <el-input :model-value="String(selectedProductMeta.gramsPerUnit)" disabled />
    <div class="form-tip">从 SKU 标准克重带入</div>
  </template>
  <template v-else>
    <el-alert type="warning" :closable="false"
      title="该产品尚未在 SKU 里填标准克重" show-icon />
    <el-button link type="primary" @click="goFillSkuWeight">去 SKU 补标准克重</el-button>
  </template>
</el-form-item>
```
`goFillSkuWeight` 跳到产品维护页(参考现有产品维护路由;防呆 Rule 5 dead-end→导航)。

- [ ] **Step 3: 提交时用 SKU 值填 payload**

`submitRecipeHeaderForm` 里 `outputQuantityPerUnit` / `outputUnit` 从 `selectedProductMeta` 取(gramsPerUnit / unit),不再读被删的手填字段。gramsPerUnit 为 null 时:提示不完整,允许存草稿(outputQuantityPerUnit 传 SKU 值或让后端默认),不静默传 0。

- [ ] **Step 4: build + headed 验证**

`npm run build` 无 TS 错。headed:打开创建配方头,产出单位/每单位产出量只读且值来自 SKU;换一个没克重的产品,看到"去 SKU 补克重"提示。

- [ ] **Step 5: Commit**

```bash
git commit -m "feat(bom): 配方头产出规格改从 SKU 只读带入" -- web-admin/src/views/production/bom/index.vue
```

---

## Task 4: C —— 添加原辅料表单精简

**Files:**
- Modify: `web-admin/src/views/production/bom/index.vue` 添加原辅料 dialog(2323-2585)+ 相关 computed/handler。

- [ ] **Step 1: C1 藏掉「物料名称」输入框**

删除 2325 的物料名称 `el-form-item`。保留 `onMaterialLink`(942-949)里 `bomForm.materialName = material.name` 赋值(后台带名不动)。确认提交 payload 仍带 `materialName`。

- [ ] **Step 2: C2 顺序改为 类别 → 关联原料 → 成品用量 → 计量单位**

把 dialog 顶部 form-item 顺序调整为:物料类别(2328)→ 关联原料(2335)→ 成品用量(2353)→ 计量单位(2464)。其余(评估/单价/税率/备注)接在后。

- [ ] **Step 3: C3 出成率/实际用量按类别显隐**

给出成率相关 form-item(2429 出成率% / 2449 实际原料用量 / 2369 评估按钮)加 `v-if="bomForm.materialCategory === 'RAW'"`。辅料/包材时整块隐藏。
提交时对非 RAW 固定 `yieldRate = 100`(满足后端 NOT NULL):在提交 handler 里
```js
const payloadYieldRate = bomForm.value.materialCategory === 'RAW'
  ? bomForm.value.yieldRate    // 可空 → 待评估
  : 100;
```
原料出成率保持"可空=待评估",不强制。

- [ ] **Step 4: C4 计量单位无关联物料时默认克(g)**

确认 `handleAddBomItem` 重置默认 `bomForm.unit = 'g'`;`onBomCategoryChange`/`onMaterialLink` 有物料时按物料带出、无物料回落 `'g'`。

- [ ] **Step 5: C5 高级字段收进「高级选项」折叠区**

把 可选料(2518)、替代料分组(2524)、按份数投料(2533)、半成品引用(2544)、嵌套子产品(2562)包进 `el-collapse`(默认收起):
```vue
<el-collapse v-model="showAdvancedBomFields" class="bom-advanced">
  <el-collapse-item title="高级选项(可选料 / 替代料 / 半成品引用 / 嵌套子产品)" name="adv">
    <!-- 原 2518-2579 五个 form-item 移入 -->
  </el-collapse-item>
</el-collapse>
```
新增 `const showAdvancedBomFields = ref<string[]>([])`(默认收起)。功能不删。

- [ ] **Step 6: build + headed 验证**

`npm run build` 无 TS 错。headed:添加原辅料无物料名称手填;选原料看到出成率、选辅料/包材看不到;高级字段默认折叠、展开后可用;嵌套 BOM/替代料仍能填能存。

- [ ] **Step 7: Commit**

```bash
git commit -m "feat(bom): 添加原辅料表单按类别精简+高级字段折叠" -- web-admin/src/views/production/bom/index.vue
```

---

## Task 5: 整体验收 + 回归

**Files:**
- 无改动,验证。

- [ ] **Step 1: 现有 vitest 不破**

```bash
cd web-admin && npx vitest run src/views/production 2>&1 | tail -30
```
Expected: 无新增失败(若原本无相关测试,记录之)。

- [ ] **Step 2: headed 端到端走查(playwright-test 独立实例,zh-CN,1920×1080)**

一条龙:选品(能选到多个成品)→ 创建配方头(产出规格只读来自 SKU,只填整体出成率+备注)→ 添加原料(有出成率、无物料名称手填)→ 添加辅料(无出成率,只填克)→ 高级字段折叠展开 → 保存草稿成功。截图存档。**不要用 plugin playwright(驱动 Steve 本人 Chrome)。**

- [ ] **Step 3: 无 dev 字段名回归**

```bash
grep -nE 'BomRecipe|BomRecipeItemDTO|CreateBomRecipeRequest' web-admin/src/views/production/bom/index.vue
```
Expected: template 展示文案零命中。

- [ ] **Step 4: PR scope 干净**

```bash
git diff origin/main...HEAD --stat
```
Expected: 只有 `bom/index.vue`(+ 可能数据归类脚本 + spec/plan 文档),无 sister 文件夹带。

---

## Self-Review(对照 spec)

- A1 选品 → Task 0(勘查)+ Task 1a/1b(按结论二选一)。✅
- A2 漏字段名 → Task 2(7 处 + grep 回归)。✅
- A3 字段顺序 → Task 3 Step 2(随只读化,产出单位在前)。✅
- B 配方头从 SKU 只读 → Task 3。✅
- C1 藏物料名称 → Task 4 Step 1。C2 顺序 → Step 2。C3 出成率按类别 → Step 3。C4 默认克 → Step 4。C5 高级折叠 → Step 5。✅
- D 边界(人工/调料/转换率 tab 不碰,后端契约不动)→ 计划全程未触及,验收 Step 4 scope 检查兜底。✅
- Placeholder 扫描:A1 具体修法故意留给 Task 0 勘查(spec 已声明数据依赖),非占位符。✅
- 一致性:`selectedProductMeta`(Task 3)、`showAdvancedBomFields`(Task 4)命名前后一致。✅

## Execution Handoff

见对话:organizer 决定 model × orchestration(单文件 → 单执行者,in-harness Sonnet 或 Composer)。
