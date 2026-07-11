# 2B.2 — Workflow 多产出 (fan-out) 报工 设计

**日期**: 2026-07-11
**触发**: Steve "多产出还没做? 开工"。2B MVP 用 `WorkflowSingleOutputGuard` 在激活/物料化/投影拦多产出; 端口模型已就绪 (Steve 提示"后端有端口可复用")。

## 现状 (已复用的基建)
- 编译器 `ProductProcessWorkflowRuntimeCompiler` 已为多产出节点产出 **N 个 OUTPUT `CompiledPort`** (各带 skuId/unit/ordinal)。
- 物料化 `ProductProcessWorkflowRuntimeServiceImpl.materializeIfActive` 已 **持久化全部端口** (`compiled.portsFor(nodeId)` 循环 save `WorkflowTaskPort`)。
- B2 投影 `WorkflowClerkSheetServiceImpl` 已把 outputs 读成 **List**, 只是 >1 时抛 `multiOutputUnsupported`。
- **唯一阻塞** = `WorkflowSingleOutputGuard` 3 处调用 (activation:61 / runtime:111 / B2:122)。

## 关键架构事实 (来自 report-side map)
- clerk 报工是 **行驱动**: 一行 → `buildStepEntry` → `materializeBatch(ctx, List.of(step), edges)` → 一个 `ProductionBatch` + 一个 WIP `MaterialBatch`。
- 投入→产出链 = **`MaterialConsumption`** (production_batch_id=产出批, batch_id=来源); clerk 路径 **不写 BatchLineageEdge**。
- 成本在 saveRow 算 WIP 批成本, interim-settle 为 SFI/FG 重算; `unitCost = total/outputQty` (单产出除数)。
- interim-settle ③ SFI IN / ④ FG 都 **逐行** (`postClerkOutput` / `createFinishedGoodsForInterim`), 逐行单 productType+qty。撤销小结同样逐行。

## 设计: 分解 (decomposition) — 最低风险
**一个多产出报工 → N 个单产出物料化**, 共享投入按产出重量 **拆分**, 落 **N 行** `process_sheet_rows`。下游 (settle/reversal/cost/inventory) **完全不改** —— 每个产出行按普通单产出行结算。

### saveRow 分支
- `req.outputs` 空 / size==1 → 走**现有单产出路径, 一字不改** (F006 真实客户流不受影响)。
- size>1 (多产出):
  1. 算每个产出的**分摊权重** `w_i` = `output_i.productWeight` (非空) 否则 `output_i.quantity` (要求同报工内 all-or-none, 否则报错, 禁止降级瞎猜)。
  2. 对每个产出 i:
     - `edges_i` = `resolveEdges(req)` 的每条边 feedQty × `w_i / Σw`; **最后一个产出吃余数** 保证 Σ = 精确原值 (无精度漏)。
     - `ctx_i` = productTypeId=output_i.productTypeId / finished=output_i.finished / 独立 batchNumber。
     - `step_i` = buildStepEntry(output_i.quantity/unit/productWeight)。
     - `mat_i` = `clerkService.materializeBatch(ctx_i, List.of(step_i), edges_i, warnings)` —— **原方法不改**, 每次是带分数投入的普通单产出物料化。
  3. `persistRow` N 行 (clientRowId 派生 `${base}#${i}`), 每行 batchId=mat_i。
  4. B3: 逐 output 行按其 `workflowPortId` 对齐端口 kind/unit。
  5. stamp workflow task (一次)。

### 拆分正确性 (🔒 keystone)
- Σ(feedQty_i) == 原 feedQty (最后一行余数法)。
- 每个产出批的 MaterialConsumption = 其分数投入 → 成本随之; interim-settle 逐批扣减, Σ 扣减 = 全量。
- 分摊基准 productWeight (A 熟成鸡 350g×400盒=140kg vs 400g×160盒=64kg 按重量) 否则 quantity (D 筛选 合格206/损耗36 同 kg 直接按量)。

### 端口/DTO
- 去掉 3 处 guard 调用 (多产出可激活/物料化/投影)。
- `WorkflowClerkSheetConfigDTO.ProcessDescriptor`: 加 `List<PortDescriptor> outputs`; `output` 保留=outputs[0] 向后兼容。
- B2 `buildDescriptor`: 不抛; outputs 按 ordinal 排; 投影全部。
- `ProcessSheetRowRequest`: 加 `List<OutputLine> outputs` (productTypeId/workflowPortId/quantity/unit/finished/productWeight); 旧单产出字段保留 (向后兼容单产出行)。

### FE
- 过程单录入: 多产出端口 → N 个产出行 (产品只读=端口 SKU, 填数量/重量)。buildRequest 发 outputs[]。

## 验收
- 熟成鸡 A: 220kg 投入 → 350g 400盒 + 400g 160盒 → **两个 FinishedGoodsBatch**, 成本按 140:64 权重分摊, Σ 投入扣减=220kg。headed 走通。
- 单产出 F006 真实流 (六扇门) 回归不变。
