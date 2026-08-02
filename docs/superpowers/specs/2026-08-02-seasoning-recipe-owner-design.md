# 调料配方归属：中间道报工该按哪个 SKU 查 BOM — 设计

**日期**: 2026-08-02
**触发**: 六膳门酱鸭腿「熟制」道每次报工都提示「未设置当前 BOM 调料配方，调料成本暂记 0」
**范围**: 后端 `ProcessSheetServiceImpl` / `ClerkProcessEntryServiceImpl` / `MaterializeContext`

---

## 1. 现象

熟制道报工回执恒带提示：

> 产品 `2df4c600-47e0-4123-b26f-98dcd584a1c2` 未设置当前 BOM 调料配方，调料成本暂记 0；
> 请在「生产 → BOM 配方 → 调料配方」完成配置后重新核算。

`2df4c600` 是「酱制鸭腿(半成品)」，不是成品。

---

## 2. 根因：查找方拿错了 SKU

```java
// 改前 —— ctx.productTypeId 对 WIP 批次就是该道产出的半成品
isSeasoningStep(ctx.getFactoryId(), ctx.getProductTypeId(), st)
computeSeasoningCost(ctx.getFactoryId(), ctx.getProductTypeId(), st, warnings)
```

而两条查找路径最终都落到：

```java
bomRecipeRepo.findByFactoryIdAndProductTypeIdAndIsCurrentTrue(factoryId, productTypeId)
```

**BOM 只挂成品**（Steve 确认的产品口径）。`BomWorkflowRevisionService#autoBindUniqueDraft`
要求该 SKU 是 Workflow 的**终端产出**才允许建 BOM，中间产出永远不满足。prod 实测：

```
POST /bom/recipes/ensure-draft  → 409 BOM_WORKFLOW_DRAFT_NOT_FOUND
POST /bom/recipes               → 409 BOM_WORKFLOW_DRAFT_NOT_FOUND
```

于是形成死结：**提示让用户去配的东西，系统本身不允许配**。按提示去配，配不出来；
不配，每次报工都提示。凡是用中间半成品做工序产出的产线都会踩到。

### 2.1 为什么不是「闸太严」

第一直觉是放宽那道终端产出闸。**方向错了** —— 闸在维护一条真实的产品口径：

- BOM 描述的是**一个成品由什么构成**，中间半成品不是独立售卖对象，没有自己的配方
- 调料配方本来就是**按工序绑定**的（`bom_seasoning_items.work_process_id`）：
  成品 BOM 说「熟制这道用这几味调料」—— 正是为本场景设计的
- 放宽闸会让每个中间产出都能长出一份 BOM，同一条产线出现多份互相矛盾的配方

所以要改的是**查找方**，不是被查方。

---

## 3. 方案

`MaterializeContext` 增加 `recipeProductTypeId`（配方归属的**成品** SKU），
取所属生产计划的产品；两处调料查找改用它。

| 场景 | 行为 |
|---|---|
| 成品道 | 计划成品 == 该道产出 → **逐字不变** |
| 中间道 | 用计划的成品 SKU 查 → 找得到 BOM 与调料 |
| 无 planId / 计划不存在 / 计划成品为空 | 回落该道自己的 `productTypeId` → 与旧行为一致 |

`resolveStepWorkProcess` 不受影响：它主要按**工序名**匹配，`productTypeId`
只在重名时作次要判据，而 `product_work_processes` 对 workflow 产品是 0 行。

---

## 4. 承载点：5 处，靠编译器找齐

`MaterializeContext` 是 `@AllArgsConstructor`，加位置参数后**编译不过**，
逼着把 5 个构造点全改到：

| 文件 | 行 |
|---|---|
| `ProcessSheetServiceImpl` | 935 / 1524 / 2249 / 2301 |
| `ClerkProcessEntryServiceImpl` | 228 |

这一点是有意的：若改的是某个判据（而非构造签名），很可能只改一处、其余静默失效 ——
与既有的「一个闸由多处独立承载」同型。

---

## 5. 测试

`SeasoningRecipeProductTypeTest` 5 条，直接打纯函数，不起 Spring：

1. **回归**：中间道传半成品 SKU，必须解析成计划的成品 SKU
2. 成品道结果不变（零回归）
3. 无 `planId` / 空串 → 回落，且**不查库**（用 `verify(never())` 钉住）
4. 计划不存在 → 回落，不抛
5. 计划成品为 null / 空串 → 回落，不返回空值

**变异实证**：把 `resolveRecipeProductTypeId` 退回 `return fallback` →
只有第 1 条红（`expected: <c57c36e0-finished-duck> but was: <2df4c600-semi-braised>`），
其余 4 条保持绿，证明各钉各的。

依赖注入按**参数类型**定位而非顺序 —— 依赖表将来增删不会让测试悄悄注错对象。

---

## 6. 数据侧的更正

排查过程中我曾给半成品直接写库塞了一份 BOM + 调料，**那是错的**（半成品不该有 BOM）。
已更正为：

- 3 条调料迁到成品当前版 BOM（`BOM-20260802-002`）
- 半成品 BOM 及其物料项删除（残留 0）

---

## 7. 更正记录

| 轮次 | 当时的结论 | 被什么推翻 |
|---|---|---|
| 1 | 提示是漏配数据，补上就行 | 补不了 —— 系统 409 拒绝给中间半成品建 BOM |
| 2 | 那就放宽终端产出闸 | Steve：「半成品不用配置 BOM，只需要成品需要」「BOM 选择方式也是针对成品的」 |
| 3 | **闸是对的，错的是查找方** | ✅ 代码 + prod 实测双实证 |

判据：**看到「系统提示让我去配 X，但 X 配不出来」，先问「X 该不该存在」** ——
若不该，那错的是提示/查找方，不是拦住你的那道闸。
