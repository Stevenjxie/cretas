# HANDOFF 2026-08-18 — 判据B：web + App 两端走链

> 目标：从销售订单、采购、生产到仓储的整条链在两端都对得上。
> 本文只写**实测**：每条读数都带来源命令/接口，未验证的一律标「未验」。

PR：[#2803](https://github.com/Stevenjxie/cretas/pull/2803)（7 个 commit，每个改动都带能红的闸 + 变异对照）

---

## 0. 一句话结论

**通过**：判据七（仓储/调拨，逐个数字对上）、判据八（忘了调拨，web 端说得清楚）、
判据四（无订单入库，全链走完并冲销）、判据六（采购链，收货量与在手 9/9 对账）、
判据一大部分（换算率 3/3 验真、计数单位不硬折）。

**没通过**：**判据二（两端实时通）** —— App 报工与 web 逐道录入**不是同一条账**，
料的扣减只在 web 那条路上发生。这条是本轮最重的发现，见 §3。

**顺带挖出两个原来不知道的**：报工撤回会留下可被领用的**幻库存**（§4）、
**半成品能通过 API 开销售单**（防呆只在前端下拉里，§7，已修）。

---

## 1. 判据一（单位）—— 大部分通过，两处已修，一处上棘轮

### ✅ 换算率验真（实测领料单 MR20260818-0001，计划 80 盒）

| 物料 | BOM 标准用量 | ×计划 | 应为 | 实际需求 | 单位 |
|---|---|---|---|---|---|
| 外箱 | 0.1250 片/盒 | 80 | 10 | **10.0** | 片 ✅ |
| 成品盒 | 1.0000 盒/盒 | 80 | 80 | **80.0** | 盒 ✅ |
| 封膜 | 0.0500 卷/盒 | 80 | 4 | **4.0** | 卷 ✅ |

3/3 对上，**单位全程一致且中文**。

### ✅ 计数单位不许硬折

- 中央换算器 `MaterialUomConverter` 认不出就返回 `UNCONVERTIBLE`，**绝不 default 成 1.0**
- 工序② 半成品(kg) → 成品(盒) 跨量纲，系统返回 **`yieldRate: null`** 而不是编一个数 ✅

### 🔴 英文码：我上一轮的结论是错的，已订正

先前据 6 张手挑的表得出「英文码已清干净」—— 那是形态 A⁗。
**全库逐列扫（101 个单位列，阳性对照有效）**真值：

| 表 | 列 | 条数 | 值 |
|---|---|---|---|
| work_processes | unit | 20 | `unitless` |
| bom_recipes | output_unit | 6 | `box` |
| material_packaging_specs | package_unit | 4 | `case`/`jin`/`ton` |
| bom_recipe_items | unit/price_unit/natural_unit | 6 | `pcs` |
| production_plans | **source_display_unit** | 2 | `box`/`case` |
| sales_order_items | **unit** | 2 | `box`/`case` |
| material_packaging_hierarchy | level2_unit | 1 | `ton` |
| quality_check_items | unit | 1 | `score` |

⚠️ **存码本身是设计**（「库存码、前端翻」）—— web 的 `displayUnit()` 确实把 `box` 翻成中文。
真正的缺口在**后端拼给用户看的文案**：61 处在 `BusinessException`/`withHint` 里拼单位，**0 处**走 `UnitDisplayNames`。

**本轮修了 9 处**（有实测数据证明今天就会打出英文码的那些），其余 52 处上棘轮
（`UserFacingUnitTranslationRatchetTest`）。⛔ 不做一次性大扫除 —— 大扫除做出来的闸当天会被关掉（形态 E）。

### ✅ 展示名两份钉成一致

`UnitContract.displayName` / 静态 `UnitDisplayNames` 抽不成一份（异常类注入不了 Spring），
加 `UnitDisplayNameCoverageTest` 钉「两份逐字相同」。建闸当天抓到 `jin`（拼音）会原样丢给用户，已修。
⚠️ 克制：`t` 没动 —— 它是 GB 3100 里吨的法定符号，既有断言守着它，那是需求不是历史。

---

## 2. 判据七 / 八（仓储与「忘了调拨」）—— 通过

### ✅ web 端逐道录入把话说清楚了（浏览器实测）

F006 计划 PLAN-1786954657305（黄油鸡 80 盒），4 个原料各 200kg 全在原料仓、生产仓 0：

> 生产仓可用 **0kg**　　*原料仓另有 200kg，待调拨入生产仓*

同屏还印证判据五：**多产出（本道同时产 2 个产品）**：处理后半成品（kg）＋ 肥油（kg）。

### 🔴 但计划列表那句话在误导人 —— 已修（PR #2803）

同一个计划，两块界面各说各话：

| 位置 | 口径 | 说的话 |
|---|---|---|
| 计划列表「原料参考」 | 全厂（无仓库过滤） | 「暂无缺料预警」 |
| 逐道录入「生产仓可用」 | 生产仓 | 「0kg / 原料仓另有 200kg」 |

两个数各自没算错，错在**那句话不报口径**。已改成：
- 新增预警类型 `NOT_IN_WORKSHOP`（全厂够但生产仓不够 → 指向**领料/调拨**，⛔ 不许说采购）
- 「无预警」改三态：全厂够且生产仓已备料 / 全厂够但**未核对**生产仓 / 有预警
- 量不到生产仓时返回 **null 而不是 0**（兜底 0 会让每个计划长出假的「请先领料」）
- 前端原来硬编码 `'原料库存参考: 暂无缺料预警'`，后端说什么都不算数 —— 形态 D，已改成以后端为准

### ✅ 领料链真的把货挪了（原料仓 → 生产仓）

`generate → start-picking → confirm-picking → transfer → receive`，逐个数字对：

| 物料 | 原料仓 | 生产仓 | 单位 |
|---|---|---|---|
| 原料A/B/C/D | 200 → **180** ×4 | 0 → **20** ×4 | kg |
| 外箱 | 200 → **190** | 0 → **10** | 片 |
| 封膜 | 20 → **16** | 0 → **4** | 卷 |
| 成品盒 | 1100 → **1020** | 0 → **80** | 盒 |

**调拨完成后立刻能用** ✅：`input-availability` 从 `available:0 / elsewhere:200` 变成
`available:20.0 / elsewhere:180.0`，两侧相加守恒 200。

### ✅ 判据九（痕迹）

`factory_material_requisition_items.batch_numbers` 逐条记
`{qty, batchId, batchNumber, workshopBatchId}` —— 源批次 → 生产仓批次可追。

---

## 3. 🔴 判据二（两端实时通）—— 未通过

从 App 侧报工序①（4 原料各 20kg → 半成品 60kg + 肥油 5kg 副产物），逐屏对数：

| 通道 | 结果 |
|---|---|
| web 报工审批列表 | ✅ 看得见，`outputQuantity 60.0` / `240 分` / `2 人` / 副产物「肥油 5 kg」全对 |
| 半成品台账 | ✅ 60kg 入账，`unit_cost 4.6667`（280/60 ✅），`material_batch_refs` 溯源齐全 |
| 审批链路 | ✅ 待审批 2 → 审批 → 0；4 眼原则生效（自审被 403 拒） |
| **物料消耗流水** | ❌ **一行都没写** |
| **生产仓扣减** | ❌ `used_quantity` 全程 0，`updated_at` 停在领料那一刻 |
| 计划 `actualQuantity` | ❌ 仍是 null |
| web 逐道录入行 | ❌ 0 行 |

**阳性对照**：`material_consumptions` 里 3292 号（`production_batch_id=10760`, 8-15）
是**web 逐道录入**写的 ⇒ 机制是活的，只是 App 这条路不走它。

⇒ **工人在 App 报了 80kg 投料，仓管在 web 上看生产仓，80kg 还在。**

代码里的设计说明（`FactoryMaterialRequisitionServiceImpl.close` 注释）写的是
「物料消耗采**延迟扣减** —— 报工时写 MaterialConsumption（未结），到小结/结单才扣」。
**而 App 这条路连那条未结消耗行都没写**，所以延迟扣减的两半都没发生。

---

## 4. 🔴 撤回会留下幻库存（新发现，根因已定位）

走完链后做整批次报工撤回（`/processing/batches/10761/reversal` → 主管审批）：

```
WIP 342  60 kg  available 0    DEPLETED   ✅
WIP 343  75 盒  available 75   AVAILABLE  ❌ 撤回后仍被 /wip/available 列为可领
```

**根因**：`ReportReversalServiceImpl` 按 `semi_finished_inventory_transactions` 的
**IN 行**冲销（`findByFactoryIdAndReportId` → 只处理 `TxnType.IN`）。
而全库 SIT 只有 **1 行，且是 OUT**（id 83，工序②消耗 342 的 60kg）——
**两次产出都没写 IN 行**，撤回于是无从冲销。

> 报工产出写 `semi_finished_inventory` 但不写 IN 流水
> → 撤回只按 IN 流水冲销 → 撤不掉 → 留下可被领用的幻库存

（Goal A 记过「SFI 不写 IN 流水」，当时不知道它有这个后果。）

**本轮的那 75 盒已清理**：保留行（判据九），置 `available=0 / adjustment=-75 / DEPLETED`，
并写一条 `REVERSE` 流水说明原因。带 4 条硬断言的事务，跨租户 0 行受影响。

---

## 5. 其它实测（顺带）

- **App 拿不到多产出/副产物选项**：`output-options` 返回空 —— 它读
  `work_processes.semi_finished_output_code`（工序模板字段），而工作流驱动的产品把产出声明在
  `workflow_task_ports` 上。同一件事两份（形态 D），本例中 `semi_finished_output_code` 为 NULL。
  `expectedByproducts` 同样是空。⇒ App 只能把副产物当**文本**报，web 能把它当**真实产出**入账。
- **BOM 里原料没有标准用量是设计**：全库 ACTIVE BOM 中「标准量为空 && 未绑 workflow 端口」的组合
  **0 条**，12 条空标准量的行**无一例外**都绑着投料端口 —— 用量由报工当场决定。
  第一版把它们写成「未配标准用量」等于诬告用户数据坏了，已按形态 D′ 收窄成两类措辞。
- **origin/main 上已有 ~20 条红**（当场在 detached worktree 跑同命令复现）：
  `ProductionWorkflowOrchestratorUnitConversionTest` 8 errors、`WorkflowUnitReviewWritePathTest` 2 errors、
  `ProductionPlanSettlementTest` 4 failures、`ProductionPlanMaterialOwnershipGuardTest` 1 failure、
  Sales 族 3F+10E。全部**与本轮改动无关**，且 CI 看不见（本仓 Java PR 的绿 = 编译成功）。
- **`sales_order_items.unit` 里有 `box`/`case`**，`production_plans.source_display_unit` 同样 ——
  后者名字里就写着 display，且 API 响应里原样返回。

---

## 6. 冲销对账

| 项 | 基线 | 现在 | 说明 |
|---|---|---|---|
| 原料仓 各物料在手 | 200/200/200/200 kg・200 片・20 卷・1100 盒 | **完全相同** | ✅ |
| 生产仓 | 只有冻猪蹄 15kg | **完全相同** | ✅ |
| `/wip/available` | 1 条 | **1 条** | ✅ |
| WKS 批次合计在手 | — | **0** | ✅ 无幻库存 |
| 跨租户 mb/fgb/plans | 24 / 0 / 0 | **24 / 0 / 0** | ✅ 全程未动 |
| production_reports | 19 | 21（2 条**软删**） | 判据九痕迹，非脏数据 |
| factory_material_requisitions | 0 | 1（CLOSED） | 同上 |
| semi_finished_inventory | 1 | 3（2 条 DEPLETED/0） | 同上 |

---

## 7. 判据三（销售品类）—— 正向通过，两条防呆缺一半

实测建了 6 张探针单（**已全部取消**）：

| 行 | 结果 |
|---|---|
| 成品 / 原料 / 辅材 / 包材（有库存） | ✅ 四类都能开单 —— 正向要求满足 |
| 🔴 **半成品** | **200 建单成功**（SO-20260818-0006） |
| 🔴 **从没入过库的包材** | **200 建单成功**（SO-20260818-0005） |

而 `GET /product-types/sellable` 确实把半成品排除了（141 个可售项里没有它）——
**防呆只在前端下拉里**（形态 B）。本 PR 已补后端守卫 `assertItemsAreSellable`，
判定复用 `ProductCategory.isSellable`（它的 javadoc 写着「这里是唯一权威」），双向变异对照。

⚠️ 「**都必须是入过库的东西**」这条**没修**：实测 F006 有 **236 个启用物料而只有 10 个有过批次**，
一刀切会把大量正常下单挡死。需要业务口径（下单拦还是发货拦），见 §9。

---

## 8. 判据四（无订单入库）—— 通过，已冲销

| 步骤 | 结果 |
|---|---|
| 建申请 | ✅ `GFT-20260818-F2DEB7E4`，`PENDING_APPROVAL`（单号前缀随原因走） |
| 审批**前**收货待办 | ✅ **0 条** —— 门控正确 |
| 审批 | ✅ 「审批通过，已进入入库任务与批次」→ `OPEN` |
| 审批**后**收货待办 | ✅ 1 条，带明确下一步「待仓储核实实际物料、数量与仓库」 |
| 收货 | ✅ 生成原料批次 `UIN-20260818-3201759B69E9`，3 kg 入原料仓，单位一致 |
| 库存 | ✅ 香辛料 20 → 23 kg，冲销后回 20 |

---

## 8b. 判据六（采购整条链）—— 通过（对账法，未新建单据）

⚠️ 先订正一条过期记忆：memory 记着「采购 OA 零实例」，**同库复核已作废** ——
`approval_workflow_instances` 0→**6**、`approval_history` 0→**13**、F006 采购单 0→**4**。

**采购收货量 vs 入库合计 vs 当前在手，9/9 在用物料逐条对上**：

| 物料 | 采购收货 | 入库合计 | 当前在手 |
|---|---|---|---|
| 黄油鸡-原料A/B/C/D | 200 kg ×4 | 200 ×4 | 200 ×4 |
| 外箱 / 封膜 / 成品盒 | 200 片 / 20 卷 / 1100 盒 | 同 | 同 |
| 香辛料 / 黄油调味料 | 20 kg ×2 | 同 | 同 |

这也解释了本轮一路在用的那批库存的来源。`PO-20260815-0001`（¥40000，`FINANCE_REJECTED`）
证明财审那一腿能拒。18 个批次带 `source_doc_type=PURCHASE_RECEIVE`，可回溯。

唯一对不上的 `SOP0729-黄油鸡-原料A`（采购收 1600kg / 库存 0）已查清：批次 `MT-20260816-7814`
存在但**已软删**且 `source_doc_type` 为 NULL，物料 `is_active=false` —— 退役测试数据，不是缺陷。
（边角：那条批次回溯不到源采购单。）

---

## 8c. 还没验的（本轮未做，不假装做了）

- 判据三的「必须入过库」（见 §7，需口径）。
- 判据五：多入一出 ✅、副产物 ✅（web 侧）；**一份原料出多个成品**与**复杂辅料计算**未单独造场景。
- 判据二的反向（**web 改完 App 反映**）未验。
- 判据九的「手工出库」未单独验（录入 ✅、转移 ✅ 已验）。

---

## 9. 需要业务口径才能定的（列出来，不替你定）

1. **App 报工要不要扣生产仓的料？**
   现状是不扣（连未结消耗行都不写），而 web 逐道录入扣。
   两条路要合成一条，还是明确「App 只报产量、料以 web 为准」？后者的话，
   App 报工屏上应当写明这一点，否则工人以为报完就扣了。
2. **副产物在 App 侧是文本还是真实产出？**
   工艺图把肥油声明成带 SKU 的产出端口，web 能把它入账；App 的 `byproducts[]` 只有名字/数量/单位。
   要不要让 App 也按端口报？
