# 同一物料多批次投料 — 设计

**日期**: 2026-08-02
**触发**: 客户张权（LIUSHANMEN 酱鸭腿产线「装箱」工序）
**范围**: web-admin `ProcessDataTable.vue`（生产报工/结单）

---

## 1. 问题

装箱要投上一道「熟制」产出的「酱制鸭腿(半成品)」。prod 实况该半成品有 **3 个在制批次**：

```
CLK-W-20260802-91160   余 102 kg
CLK-W-20260802-50192   余  11 kg
CLK-W-20260802-23427   余  50 kg
```

客户要把这 3 批**一次装完**，但：

- 「熟制批次」下拉**只能选 1 个**
- 旁边的 ⊕（加一个来源批）**点了没反应**

结果他只能开 3 行装箱，163 kg 货被迫记成 **3 个成品批次**，而现实里就是装了一次。

---

## 2. 根因

判据回答错了问题。现有判据问的是「这道工序要投**几种**不同物料」：

```js
isMultiSource = allowMultipleUpstreamSources === true
             || workflowUpstreamInputs.length > 1
```

而 `workflowUpstreamInputs` 已经把原料滤掉了：

```js
workflowUpstreamInputs = workflowContext.inputs.filter(p => p.materialKind !== 'RAW_MATERIAL')
```

酱鸭腿产线（workflow 实例 56）去掉原料后的上游端口数：

| 工序 | 上游端口数 |
|---|---|
| 出料/缓化 | 0（首道） |
| 熟制 | 1 |
| 装箱 | 1 |

**没有一道 > 1** → 恒为单来源 → 单选 + ⊕ 不可点。

客户要的是「**同一种**物料的**几批**」，与「几种物料」是两件事，现在被同一个判据管着。

### 2.1 为什么另外 4 道工序能多选

那 4 道（卤猪蹄/纸片牛腱肉 的熟制、气调包装）属于**另外两个产品**，走老的 `product_work_processes` 配置，有显式开关 `allow_multiple_upstream_sources = true`。

**酱鸭腿在 `product_work_processes` 里 0 行** —— 纯 workflow 驱动，那条路上没有这个开关，所以客户在配置界面上也找不到地方开。

### 2.2 同一判据写在两处（首次尝试失败的原因）

```js
isMultiSource = ... || workflowUpstreamInputs.length > 1   // 决定用哪套模板
mixExpanded   =        workflowUpstreamInputs.length > 1   // 决定混批区默认展开
```

首次尝试只改了前者，后者漏了 → 模板切成多来源但混批区**折叠**（`v-if="row.mixExpanded"`）→ 什么都不渲染 → 10 个既有测试红。

当时误判成「与 2026-07-30 客户诉求冲突」，**实际不冲突**：「唯一候选不给下拉」由 `soleBatchLabel` 机制保证，与展开无关，是两层。

---

## 3. 方案（已与用户确认）

**判据从「有几种物料」改成「有没有上游端口可挂批次」，两处一起改。**

```js
isMultiSource = allowMultipleUpstreamSources === true
             || workflowUpstreamInputs.length >= 1     // 改
mixExpanded   = isMultiSource                          // 跟随, 不再独立判断
```

### 3.1 界面效果

**多批（张权场景）** — 混批区摊开，可勾多批、各填投入量、⊕ 追加：

```
装箱  新建  2026-08-02   来源批次 (3) ▼
  ☑ 酱制鸭腿(半成品)  [CLK-W-…-91160 | 余102kg ▾]  投入 [102] kg  🗑
  ☑ 酱制鸭腿(半成品)  [CLK-W-…-50192 | 余 11kg ▾]  投入 [ 11] kg  🗑
  ☑ 酱制鸭腿(半成品)  [CLK-W-…-23427 | 余 50kg ▾]  投入 [ 50] kg  🗑
                                        ⊕ 加一批   合计 163 kg
```

一行装箱 → 3 批料 → **1 个成品批次**。

**单批（2026-07-30 诉求）** — 摊开，但**直接显示批号、不给下拉**：

```
  酱制鸭腿(半成品)   CLK-W-…-91160 | 余102kg     投入 [102] kg
```

由 `soleBatchLabel` 保证（`optionGroups: sole ? [] : …`），不需要操作员多点一下。

### 3.2 为什么不按「候选批次数」决定展开

候选来自 `sfiOptions` / `fgOptions`，**异步加载**（`sfiLoading` / `fgLoading`）。按数量决定默认展开会让界面在加载中途改变形态。`autoSelectSoleUpstreamBatches` 已经为同一原因加了加载守卫。

---

## 4. 不改什么（边界）

| | 保持不变 |
|---|---|
| 后端 | 已经是 `for (UpstreamRef ref : req.getUpstreamSources())` 逐批 `consumeClerkSemiStrict`，不关心是否同端口。**零改动** |
| 提交结构 | `isMultiSource` 分支已把每种工序形态实现了一遍（`isSingleUpstream` / `isQuSheTou` / `isShuZhi\|isGenericUpstream` / `isQidiao`），与各自单来源分支逐字对应，只是把硬构造的 `[{单个}]` 换成 `submittedUpstreamSources(row)` |
| 未接 workflow 的老产品 | `workflowContext` 为空 → `workflowUpstreamInputs` 恒空 → 仍只看显式开关 `allowMultipleUpstreamSources`，行为完全不变 |
| 首道工序 | `isFirstProcess === true` → `workflowUpstreamInputs` 恒空 → 不受影响 |
| 「唯一候选不给下拉」 | 由 `soleBatchLabel` / `upstreamBatchIsForegoneChoice` 保证，本次不动 |

---

## 5. 测试策略

### 5.1 必须钉住的四件事

1. **回归目标**：1 个上游端口时 `isMultiSource === true`（修复前恒 false）
2. **不伤既有**：单批次时提交内容与修复前**逐字一致** —— 这条比 1 更要紧，改错了就是记错产量
3. **边界**：首道工序、未接 workflow 的老产品行为不变
4. **展开**：`mixExpanded` 与 `isMultiSource` 同步，否则界面空白（首次尝试就栽在这）

### 5.2 变异实证（不可省）

写完必须把判据改回 `> 1`，确认测试**变红**且红在回归那条断言上。首次尝试已实证过这一步有效（还原后 1 failed / 3 passed，其余 3 条保持绿说明它们钉的是不同的东西）。

### 5.3 既有套件

`web-admin/src/views/production/components/processSheet/__tests__/` 基线为 **26 files / 153 tests 全绿**，改动后必须仍然全绿。首次尝试打挂 10 个，正是漏改 `mixExpanded` 的信号。

---

## 6. 风险与回退

| 风险 | 处理 |
|---|---|
| 多批次提交把产量记错 | 后端逐批扣减已存在且在用（另外 4 道工序在跑）；测试第 2 条钉住单批次提交不变 |
| 混批区默认摊开变啰嗦 | 已与用户确认可接受；单批时里面是直接显示，不增加点击 |
| 影响面超出预期 | 改动只碰 2 个 computed，回退 = 把 `>= 1` 改回 `> 1`、`mixExpanded` 改回独立判断 |

---

## 7. 本次不做（已查实，另行处理）

- **孤儿工序**：全库 14 条 `product_work_processes` 挂在已不存在的成品上（F001 10 条 E2E 残留 / LIUSHANMEN 4 条鸡产线）。配置页按成品分组加载，选不到成品就列不出来。属历史脏数据，与本问题无关。
- **`product_work_processes` 与 workflow 两套配置并存**：老产品走前者（有混批开关），新产品走后者（没有）。本次让 workflow 侧不再依赖那个开关，但两套并存本身是更大的架构问题。
