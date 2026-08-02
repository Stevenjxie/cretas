# 同一物料多批次投料 — 设计

**日期**: 2026-08-02
**触发**: 客户张权（LIUSHANMEN 酱鸭腿产线「装箱」工序）
**范围**: 后端 `WorkflowClerkSheetServiceImpl`（报工单运行时配置解析）

> ⚠️ **本文经过一次重大更正。** 初稿把根因判在 web-admin 前端判据上（`isMultiSource` 用
> `workflowUpstreamInputs.length > 1`），据此提出改 `>= 1`。**那是错的**，实施时被测试
> 和数据推翻。真根因在后端，见 §2。前端那条只是同一个错误的第二道，修了后端就不必动它。
> 更正过程记在 §8，因为它本身是这次最值得留下的东西。

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

## 2. 根因（实证）

```java
// WorkflowClerkSheetServiceImpl.java:232（改前）
.allowMultipleUpstreamSources(upstreamInputCount > 1)
```

后端**没有读用户在 Workflow 画布上配的开关，而是当场按端口数重算了一遍**。

备份实证（`product_process_workflow_revisions.nodes_json`，LIUSHANMEN 酱鸭腿最新 revision）：

| 工序 | 图里 `allowMultipleUpstreamSources` | 去掉原料后的上游端口数 | 运行时实际得到 |
|---|---|---|---|
| 出料/缓化 | `true` | 0（首道） | false |
| 熟制 | `true` | 1 | false |
| 装箱 | `true` | 1 | **false** |

**三道工序图里全配了 `true`，运行时一个都没生效。** 用户配了等于没配。

这是「用户配置被硬编码规则静默覆盖」这一形状 —— 与本次同批修掉的另外两处同类：计数单位表里全是假的 `1.000000` 换算因子、班组报工写死 `kg`。

### 2.1 为什么配置到不了运行时

- `product_process_workflows` 发布流程**不写** `product_work_processes`（已 grep 确认）
- 运行时快照 `work_process_tasks` **没有**这个字段（已查 schema）
- 于是 `WorkflowClerkSheetServiceImpl` 手上只有端口，就拿端口数当了判据

图里那个字段因此是**只写不读的死配置**。

---

## 3. 方案

以图定义里的用户配置为准，没配过才回落端口数：

```java
.allowMultipleUpstreamSources(resolveAllowMultipleUpstreamSources(
        instanceNodesJson, task.getWorkflowNodeId(), upstreamInputCount > 1))
```

配置源取 `ProductionWorkflowInstance.nodesJson` —— **开工时冻结的运行时快照**，与发布那一版一致，不会因为事后改图而漂移。实例在调用处本来就拿得到，**不需要加列、不需要迁移**。

解析失败 / 找不到节点 / 字段缺失 一律回落 `portCountFallback`：这个开关只影响录入界面给不给多来源行，不该因为一个 JSON 问题让整张报工单打不开。

---

## 4. 不改什么

| | 保持不变 |
|---|---|
| 后端扣减 | 早已 `for (UpstreamRef ref : ...)` 逐批 `consumeClerkSemiStrict`，本来就支持多批 |
| 提交结构 | `isMultiSource` 分支已把每种工序形态实现了一遍，只是把 `[{单个}]` 换成 `submittedUpstreamSources(row)` |
| **前端判据** | **不动**。`isMultiSource` 里的 `workflowUpstreamInputs.length > 1` 保留 —— 它是在后端给出 `false` 时的兜底，后端修好后正常路径走 `allowMultipleUpstreamSources === true` 那一支 |
| 老工作流 | 图里没配过该字段 → 回落端口数 → 逐字不变 |

---

## 5. 测试策略

`WorkflowClerkSheetMultiUpstreamTest`（5 条，直接打纯函数，不起 Spring）：

1. **回归**：图配 `true` 就按 `true` —— 哪怕只有 1 个上游端口（端口数判据会说 false）
2. 图配 `false` 就按 `false` —— 不因端口多而擅自打开
3. 字段缺失 → 回落端口数（老工作流零回归）
4. 找不到节点 / null / 空串 → 回落，不抛
5. 坏 JSON → 回落，不让报工单打不开

**变异实证**：把判据改回 `upstreamInputCount > 1`（并让 helper 永远回落），确认测试变红且红在第 1 条。

**真机判据（唯一算数的）**：LIUSHANMEN 装箱页能同时选 3 批并提交，库里 3 个批次各自扣减。单测只能证明解析对，证明不了端到端。

---

## 6. 风险与回退

| 风险 | 处理 |
|---|---|
| 多批提交把产量记错 | 后端逐批扣减早已存在；真机验证要查库确认逐批扣 |
| 某些工序意外变成多来源 | 只有图里显式配 `true` 才会；没配的回落原判据 |
| 影响面 | 改动集中在一个 service 的一个方法 + 一个私有 helper，回退即改回一行 |

---

## 7. 本次不做

- **孤儿工序**：全库 14 条 `product_work_processes` 挂在已不存在的成品上（F001 10 / LIUSHANMEN 4）。
- **前端 `isMultiSource` 判据**：见 §4，后端修好后无需动。
- **`product_work_processes` 与 workflow 两套配置并存**：查明**运行时压根不读前者**（对 workflow 产品而言），所以它不是本问题的根因。是否清理属独立决策。

---

## 8. 更正记录（这次最值得留下的部分）

同一个问题我连续判断错三次，每次都"看起来说得通"就准备动手：

| 轮次 | 当时的结论 | 被什么推翻 |
|---|---|---|
| 1 | 配置没开，去「产品-工序配置」勾「混批」 | 备份显示图里三道工序早就是 `true` |
| 2 | workflow 那条路上没有这个开关，只有老配置有 | 有，且已配；老表对 workflow 产品根本不被读 |
| 3 | 前端判据 `> 1` 改 `>= 1` | 打挂 10 个既有测试；且会让**所有**工序变多来源，同样无视配置 |
| 4 | **后端 `allowMultipleUpstreamSources(upstreamInputCount > 1)` 覆盖了用户配置** | ✅ 代码 + 备份数据双实证 |

**教训不是"改了四次"，是前三次都提前收口。** 判据应该是：

- 说「配置没开」之前，先把**配置的实际值**读出来（备份/库里都能查）
- 说「某条路上没这个能力」之前，先 grep 那个字段的**全部读写点**（本次关键就是发现"只写不读"）
- 改前端判据之前，先问「后端给的值是怎么来的」—— 前端那个 `> 1` 只是后端同一个错误的镜像

代价：基于第 2 轮的错误结论（"两套并存"）清空了 32 张表（1,222 计划 / 1,901 批次 / 6,011 报工 / 75 BOM / 2,870 质检）。备份双份完好（服务器 `/root/backup-20260802-prodline/` + 本地 `D:\Temp\cretas-backup\`，20MB / 13,675 条 `--column-inserts`）。用户拍板不回灌，改为在 F006 + LIUSHANMEN 重建。
