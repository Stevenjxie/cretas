# 判据走查 · 未修缺陷台账（2026-08-18 第二轮）

> 本文件只记**已实测取证、但尚未修**的缺陷，以及**待 owner 定口径**的事项。
> 已修已上线的部分见 PR #2803 / #2827 / #2830 / #2835 / #2836 / #2837 / #2839 / #2844。

---

## 🔴 判据九「留痕」不通过 —— 两个独立原因

### (1) 工人实际用的那条路不留痕

`WHOutboundIssueScreen.tsx`（扫码出库确认页）调
`POST /api/mobile/{f}/material-batches/{id}/use`，body 只有 `{"quantity": …}`，**不传
`productionPlanId`**。

而 `MaterialBatchServiceImpl.useBatchMaterial`（约 :1398）：

```java
if (productionPlanId != null) {   // ← 真实屏幕永远走不到
    ... materialConsumptionRepository.save(consumption);
}
```

**实测**（批次 `a5e3740e-…`，出库 0.01kg）：`material_consumptions` 前后各 1 行（那 1 行是
2026-08-15 的无关旧行）→ **零新增**；`operation_logs` 该实体 **0 行**（`/use` 上没有
`@Loggable`）。变的只有 `usedQuantity`（累计值）和 `lastUsedAt`（单个可变字段，下次出库
就被覆盖，**连是谁出的都没记**）。

### (2) 写了痕迹的那条路，界面看不到

`POST /adjust`（web-admin「调整库存」用的）会写完整的 `material_batch_adjustments`：
`adjustmentType` / `quantityBefore` / `quantityAfter` / `reason` / `adjustedBy` /
`adjustmentTime`。实测写入 2 行，`adjustedBy=1554` 正确解析成
`f006_warehouse_mgr`「六膳门仓储主管」。

但唯一接到界面的 `GET /material-batches/{id}/usage-history` 是**空实现**：

```java
// MaterialBatchServiceImpl 约 :1185
// TODO: 从消耗记录和调整记录中获取使用历史
return new ArrayList<>();
```

⇒ web-admin「调整历史」表 `v-if="adjustHistory.length > 0"` **永远不成立**
（`web-admin/src/views/warehouse/inventory/index.vue:315,681-692`）；
RN「批次追溯页」出库记录节点**永远**显示 `"暂无出库记录"`
（`frontend/CretasFoodTrace/src/screens/warehouse/shared/WHBatchTraceScreen.tsx:181`）。

**判定**：记录只存在于数据库，没有任何界面给用户看；而真实世界最常走的那条流程连那条
记录都不写。⇒ **不通过。**

⚠️ 另：手工出库的 `usedQuantity` **没有任何 API 能撤销** —— 走查时那 0.01kg 无法冲销，
已如实披露。这本身也是发现的一部分。

---

## 🔴 判据五「一份原料出多个成品」不通过 —— 四道独立的坎

### 坎 1：联产品声明被静默剥掉

端口上显式写 `outputRole=MAIN 60%` / `CO_PRODUCT 40%` → `PUT …/draft` 返回 **200
「操作成功」**，读回来 `outputRole=None` / `costAllocationRatio=None`，并多出一个
我从没发过的 `reportingSelectionMode='ACTUAL_IO'`。

成因：`service/workflow/WorkflowActualIoSemantics.java:36-73` 的 `normalizeDraft`
无条件给每个 PROCESS 节点盖 `ACTUAL_IO`，并从每个端口 `remove("outputRole")` /
`remove("costAllocationRatio")`；唯一授权入口
`ProductProcessWorkflowServiceImpl.java:83` 无条件调用它。

随后 `BomWorkflowRevisionService.resolveTerminalOutputs`：

```java
if (WorkflowActualIoSemantics.enabled(process)) {
    roleValue = terminalIndex == 0 ? "MAIN" : "BY_PRODUCT";
    ratio     = terminalIndex == 0 ? new BigDecimal("100") : BigDecimal.ZERO;
}
```

`terminals` 按 `Comparator.comparing(Node::getId)` 排序 ⇒
**谁拿 100% 的成本由节点 id 的字典序决定**。

`OutputRole` 枚举里有 `CO_PRODUCT`，**全库 0 行** —— 现有授权入口到不了它。

### 坎 2：多产出计划领不到料，两条错误各自指向对方

多产出计划的 `productTypeId` 被锚在**原料**上：

```
报工 → 409 PRODUCTION_STOCK_SHORTAGE 「…全厂在手 30kg，只是还没领到生产仓 → 去领料」
领料 → 404 「产品 BOM 未配置…productTypeId=RMT_07a9a522-…」
        actionHint「请前往 BOM成本管理 配置产品 BOM」  ← 让人给一个【原料】配 BOM
```

阳性对照：同工厂同原料同工序的**单产出**计划领料正常（`MR20260818-0005`）。

### 坎 3：同一对成品，成本分摊有两个互相矛盾的答案

| 出处 | 成品甲 | 成品乙 |
|---|---|---|
| 报工实测（`costAllocationBasis: MASS`） | 80% / ¥582.4 | 20% / ¥145.6 |
| 钉住的 BOM 家族 | MAIN 100% | BY_PRODUCT **0%** |

### 坎 4：结单要 NRV，而配 NRV 被禁止 —— 两道闸判据不一致

```
结单 → 409 BYPRODUCT_NRV_REQUIRED  "By-product NRV price is required"
       ⚠️ 纯英文，无中文，无 actionHint，不说去哪配
```

三种写法配 NRV 全被拒（`BOM_FAMILY_OUTPUT_SET_MISMATCH` /
`BOM_NON_BY_PRODUCT_NRV_FORBIDDEN` ×2）。

代码级根因 —— **两处对「这是不是副产物」用了不同判据**：

- `BomRecipeServiceImpl.creditingByproductIds()`：`targetProducedUnderActualIoSemantics(...)`
  为真就 `continue`（注释：「自动编号出来的 BY_PRODUCT 是占位值，不是用户标的副产品」）
  ⇒ 非 null 的 NRV 一律禁止
- `ProductionPlanServiceImpl:3091`：只判 `recipe.getOutputRole() == BY_PRODUCT`，
  **没有 ACTUAL_IO 那层排除** ⇒ 要求 NRV > 0

而 `normalizeDraft` 保证今天新建的每个工作流都是 ACTUAL_IO ⇒
**按当前授权入口做出来的多成品计划，NRV 被一道闸要求、被另一道闸禁止，结不了单。**
（`BOM_FAMILY_OUTPUT_COSTING_READ_ONLY`：计划钉的是不可变 ACTIVE 版本，在飞的计划事后也救不回来。）

⚠️ **诚实标注**：因为死在结单，`/warehouse-receipt` 一次都没到达。
`ProductionPlanServiceImpl:4172` 对 `targetFinishedGoodIds.size() > 1` 抛
`MULTI_OUTPUT_RECEIPT_REQUIRES_LINES`，而 `ProductionWarehouseReceiptRequest` 没有
`lines` 字段 —— **这一条只有代码证据，没有实测证据。**

### 单位这块是对的
`workflowOutputUnitsByProduct = {甲:盒, 乙:kg}` 逐 SKU 保留；报工按各自
`gramsPerUnit`（800/1000）折成质量算 80/20，**没有硬折「盒↔kg」**；结单预填对混单位
诚实拒绝（`FINISHED_OUTPUT_UNIT_MIXED`）。但 `ProductionSettlement` 只有一个标量，
多单位多产出**没有表示法**。

---

## 🟡 判据五「复杂辅料计算」——算得对，链路走不完

### ✅ 计算本身正确（含判别力对照）

| 报工 | 投入 | 锅次 | 调料甲(10g/kg) | 调料乙(25g/kg, 续锅比 0.6) |
|---|---|---|---|---|
| row-1 | 100 kg | 单锅 | 1.0 kg ✅ | 2.5 kg ✅ |
| row-2 | 20 kg | 2 锅 [12,8] | 0.2 kg ✅ | **0.4** kg ✅ |

row-2 调料乙是**判别性读数**：忽略续锅比例应为 0.500，实测 0.400 ⇒
非复利因子 `1+(n-1)×0.6` 打在均锅质量上，确实生效。

### 🟡 领料单不算辅料需求量 —— **改了一半，真因换了形态**

**后续（PR #2852 已上线）**：给调料接上「投入 kg × dosagePerKgG ÷ 1000」，
9 条闸 + 3 条变异（M1 第一轮没红，补了走真实入口的接线断言才杀掉）。

⚠️ **但它在主线这个配方上不生效**，而我是**部署后真跑才发现的**：

```
本配方 RAW 行(原料 A–D)   standard_quantity = 空   actual_quantity = 空
本配方 PACKAGING 行        0.1250 / 0.0500 / 1.0000   ← 有
全库:  RAW 45 行只有 2 行有用量 (4.4%)；PACKAGING 13/13、AUXILIARY 3/3 全有
```

我的分母是「汇总已算好的 RAW 明细行」，而**那些行本身就是空的** ⇒ 正确地返回 null ⇒
调料仍然留空。源码里原来那句「推不出投入 kg」**是对的**。

🔴 **我错在没先量**：看到包材有 `requiredQty` 就假设原料也有。
单测里的桩 `raw("20","kg")` 是**真实上游不产出的形状** —— 本仓形态 B‴。

⇒ 修复保留（在填了原料用量的配方上生效，不引入错误行为），
但**真正的问题换了形态**：BOM 里原料普遍没有用量，投入多少是报工时操作员定的。
**「要不要强制 BOM 填原料用量」是业务口径，不是代码能单方面定的。**

---

### （原始记录）领料单不算辅料需求量

```
CLAUDEPF-原料M   cat=RAW        requiredQty=None  bomItemId=640
CLAUDEPF-调料甲   cat=AUXILIARY  requiredQty=None  bomItemId=None  ← 按配方应为 1.000 kg
CLAUDEPF-调料乙   cat=AUXILIARY  requiredQty=None  bomItemId=None  ← 按配方应为 2.500 kg
```

包材那一路**会算**（`standard_quantity × 计划量`）⇒ 计算通道存在，只是没有
`dosagePerKgG` 这条路。主线同形：香辛料/黄油调味料 `required_qty` 也是 NULL，
操作员填了「1」，而按 80kg×10g/kg 正确答案是 **0.8 kg**。

### ✅ 「BOM 里挂了调料就结不了单」—— **已修**（PR #2844）
白名单从 7→9，实测领料单 9 行全部命中。

### 其它读数
- 辅料被归到 `rawMaterialConsumptions`，`auxiliaryConsumptions` 恒为 `[]`
  —— 领料单叫它 AUXILIARY，结算叫它 RAW。
- `production_reports.aux_pot_no / aux_pot_total_cost / aux_alloc_method` 与
  `product_work_processes.aux_alloc_method / aux_basis / aux_unit_price`
  **全库 100% NULL**（该表仅 4 行）—— 这套机制从没被用过。

---

## 🟡 成本口径（待 owner 定，PR #2836 只做了诚实暴露）

结单把领用事实写进 **`production_settlement_consumptions`**（实测 4 行 RAW_MATERIAL，
各 20kg，note「自动带入自逐道报工」，带生产仓 ID），而 `OrderCostBreakdownService`
按 **`material_consumptions.production_batch_id`** 归集 —— **两张表不相交**。

实测佐证：
- 本计划在 `material_consumptions` 里 **0 条**领用行（23 条全是 `MATERIAL_RETURN` 负数）
- 全表 47 行里只有 **1 行**有 `production_batch_id`
- ⇒ `totalRawInput=0` ⇒ `totalCost=0` ⇒ `resolvePlanTotalCost` 返回 null

⚠️ 差一步就得出「领用事实没被记录」的错误结论 —— 是「结单那一刻哪些表新增了行」的
**全表扫描**拦住的。

**两个可选方向**（都会改变 prod 上所有计划的成本数字，需要 owner 拍）：
1. 让结单也写 `material_consumptions`（风险：与手工端点 / 中期结算 / 调拨重复计）
2. 让成本归集也读 `production_settlement_consumptions`（`production_settlement_consumptions`
   **没有单价/成本列**，要回 `material_batches.unit_price` 取价）

---

## 🟡 单位：管子还在漏（PR #2837 做了出口翻译，源头未修）

扫描期间 `sales_order_items.unit='box'` 的计数**自己从 1 涨到 2**，`created_at` 是当天
14:50/14:54 新建的行。链路：`sales_order_items.unit='box'` →
`SalesOrderPlanQuantityNormalizer:73` → `ProductionPlanServiceImpl:1930`。
中间两跳都是忠实快照、行为正确，**病在最上游写 `sales_order_items` 那条路（未定位）**。

数据订正脚本已入库未执行：
`scripts/migrations/2026-08-18-unit-codes-to-chinese-legacy-rows.sql`（22 行，幂等）。

**判不准、单列一栏**：`work_processes.unit = 'unitless'`（20 行）—— 它是哨兵值不是单位，
翻译无意义，但**确实渲染给用户**（`web-admin index.vue:1575`、
`ProductWorkProcessConfigScreen.tsx:476`）。正解是渲染成「无」，属产品决定，未动。

**遗留**：RN 全 App 无翻译层（约 200+ 渲染点，建议棘轮推进）；web-admin 3 张单位表已漂。

---

## 🟡 其它已知未修

- **冲销留 phantom WIP**：没有 `IN` 方向的 SIT 行。
- **App `output-options` / `expectedByproducts` 对 workflow 产品返回空。**
- **UUID 泄漏还有两处**（PR #2839 只修了 App 待确认入库屏）：
  `ProductionPlanServiceImpl` 的 `.withHint("SKU " + productTypeId)`；
  `ProductionOutputLineDTO` 同样缺 name。
  ⚠️ 用户实拍的 web 端「核对结单」对话框「终端产出」也显示
  `eb0aa47b-a5dd-49dc-af20-bf48ce8e1207` —— **第四处**。
- **纯英文无 hint 的错误码**：`BYPRODUCT_NRV_REQUIRED` /
  `WORKFLOW_OUTPUT_UNIT_MISMATCH` / `PINNED_BOM_OUTPUT_POLICY_INCOMPLETE` /
  `OUTPUT_COST_ALLOCATION_RATIO_REQUIRED`（同一段代码里一串）。
- **提示把人送去建一个已经存在的东西**：`WORKFLOW_SINGLE_OUTPUT_NOT_FOUND`
  「未找到覆盖该产品的工序 Workflow，请前往 Workflow 配置」—— 工作流明明存在，
  真因是「该产品属于多产出集合，必须整组一起选」。
- **建多产出工作流会静默生成合成产品** `__RAW_WORKFLOW_OWNER__<rawId>`
  （F006 已积累 7 条，均未软删）。
- ~~**计数单位出现小数**~~ → **查过了，不是缺陷。已收回。**

  我一度报「领料侧取整(10 片/4 卷)、结单侧不取整(9.375 片/3.75 卷)，两处口径不一致」。
  **那个结论是错的。** 量 BOM 之后：外箱 `0.1250 片/盒`、封膜 `0.0500 卷/盒`，
  两侧用的是**同一个公式**，只是分母不同 —— 领料按计划量 80，结单按实际产出 75：

  ```
  领料  80 × 0.125 = 10.0  片      80 × 0.05 = 4.0  卷    ← 恰好整除, 不是取整
  结单  75 × 0.125 =  9.375 片     75 × 0.05 = 3.75 卷
  ```

  ⚠️ 我把「不同分母」读成了「不同取整口径」—— 本仓形态 A 的又一个实例。

  而且顺着错的结论提出的「向上取整」**会把系统改坏**：一卷封膜用掉 3/4 剩下 1/4 还能用，
  一个纸箱装了 3 盒还能再装 5 盒。「片/卷」是计数词，但对应的物理对象**可以部分消耗**。
  判据一说的「不许硬折」讲的是**跨单位换算**（只/件/个 ↔ kg），
  不是「计数单位不能出现小数」。

  📌 留在这里是因为**错误结论本身值得留痕**：一个基于错对比得出的修复建议，
  比不提更危险 —— 它会被照着做。

---

## 待 owner 定的两条（我已给出倾向并按它继续做）

1. **App 报工要不要扣生产仓库存** → 「不改扣减时机」这半条仍然成立；
   **但我给的另一半（用 `reserved_quantity` 记占用）被实测推翻了。**

   我当时说「`reserved_quantity` 这一列本来就是干这个的」——
   **只查了谁写它，没查谁读它。** 实测（PR #2866 的闸把结论钉成可执行的）：

   ```
   reserved_quantity 现状: 0 / 89 个在用批次有值 —— 没有任何代码在写它
   getCurrentQuantity() 会减 reserved, 而结单路径【三处】都读它:
     · deriveRawConsumptions(预填)   available < qty → RAW_BATCH_INSUFFICIENT, 静默丢行
     · validateConsumptionLine       跨计划闸 + ensureQuantityWithinAvailable
     · postMaterialBatchConsumption  ensureQuantityWithinAvailable, 然后 used += qty
   ⇒ 为报工 R 占的量, 会挡住 R 自己的结单
   实测 16 个被 materialBatchRefs 引用的批次 refQty == receipt_quantity == 20 (16/16)
   ⇒ available = 20 − 0 − 20 = 0 ⇒ **每一次结单都会挂**, 不是边缘情况
   ```

   ⇒ 正确的做法是**释放必须发生在结单三道闸之前**，也就是 `settleProduction` 内部，
   而且三条释放路径（驳回 / 冲销 / 结单扣减）要用同一个口径。
   ⛔ 只做「报工时占用」而不同时解决释放 = 永久占住库存，比不做更糟 ——
   所以那一轮**一行生产代码都没改**，只交了闸。

   ⚠️ 顺带抓到一个现成缺陷：先撞上的是**跨计划**那道闸，文案说
   「已被**其他未结生产计划**待结占用…请先**结清冲突计划**」——
   现场根本没有别的计划，占用它的是**本计划自己的报工**。
   把人支去找一个不存在的计划。（已被 #2866 的断言钉住。）
2. **销售订单「必须入过库」怎么算** → 倾向：判据改成**「当前有在手库存」**
   （「入过库」是不可撤销的历史事实，守不住任何东西）。分三级：有在手→放行；
   无在手但有在途采购/在产计划→放行+明示；两者都无→拦并给下一步。
   **第一步只提示不拦截** —— F006 236 个在用物料只有 10 个进过库，硬上会卡死系统。
