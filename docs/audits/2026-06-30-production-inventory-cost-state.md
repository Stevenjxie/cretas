# 生产 ↔ 库存 ↔ 成本 完整现状审计 (2026-06-30)

**触发**: 龙盛 A2 会议(库存生产业态需求)→ 完整核对系统现状。
**方法**: verify-first, 6 个独立 Explore agent 对 origin/main + 本 session 成本深测。全部 file:line 可溯。
**Base**: `backend/java/cretas-api/src/main/java/com/cretas/aims/`

---

## 0. 一句话总览

系统有**两条平行的生产录入路径**,库存账完整度天差地别:
- **报工路径**(操作员手机 → Web 审批):raw → `SemiFinishedInventory`(成本+流水+审核)→ `FinishedGoodsBatch` → 销售出库。**库存账接近闭环**。
- **逐道录入路径**(文员,**客户在用的**):raw → WIP `MaterialBatch`(寄生 material_batches)→ ... → **断**。它是"成本溯源优先"路径,**从没完整桥接到库存账和规范库存模型**。

客户会议提的一堆问题,根因都是:**他用的逐道录入路径在库存侧没接通**。成本侧反而是本 session 重点加固过、最扎实的一层。

---

## 1. 生产计划生命周期 (spine)

### 状态机
`PENDING`(建)→ `IN_PROGRESS`(start/转批次)→ `COMPLETED`(settle 或 complete);`PAUSED ⇄ IN_PROGRESS`;`CANCELLED` 终态。
- **无 reopen / un-settle**:settle 单次性(已结单再 settle → 409, `ProductionPlanServiceImpl:1520/1526`),强制 `COMPLETED`(:1570)。
- **COMPLETED 几乎终态**:计划级取消/撤回全被拒,只能走批次级「整单撤回」(ReportReversalService)恢复库存。
- 死代码:`PENDING_APPROVAL` 审批撤回链(入口 :3169 停用 + 回调 :3193 @Deprecated 零调用)、`PLANNED` 枚举无写入点、`AI_FORECAST/AI_CHAT/SAFETY_STOCK` 枚举全库零服务端写入。

### 创建入口(9 条)
手动 / 草稿 / 以销定产(批量,CUSTOMER_ORDER)/ 批量 / Excel(EXCEL_IMPORT)/ 复制 / 二次加工(SECONDARY)/ AI对话(**不设 sourceType → 落 MANUAL,AI 来源无法溯源**)。

### 销售订单 vs 库存生产 — **无生命周期模式标志(双重确认)**
`sourceType`(CUSTOMER_ORDER/SAFETY_STOCK)只在 **create 阶段**造成校验严格度差异;`start/settle/warehouse-receipt` 对 sourceType **零分支**(唯一分支是 `planSourceType=="SECONDARY"` 开工扣 WIP)。`planType`(FUTURE/FROM_INVENTORY)生命周期方法也不读。**成品入库不回写 SO**(`createFinishedGoodsFromReceipt:2777` 不引用 sourceOrderId、不对 SO 行履约扣减)。⇒ 销售订单关联是纯数据溯源,不是模式。系统只有单一生产生命周期。

---

## 2. 副作用时机表(谁在何时动什么)

✓=是 ✗=否。

| 事件 | 建 ProductionBatch | 建 WIP MaterialBatch | 建 FinishedGoodsBatch | 写 SemiFinishedInventory | 扣原料 usedQty | 写 MaterialConsumption 台账 |
|---|---|---|---|---|---|---|
| plan create | ✗ | ✗ | ✗ | ✗ | ✗ | ✗ |
| start | ✗ | ✗ | ✗ | SECONDARY:✓扣 (:1402) | ✗ | ✗ |
| 转批次(开工) | ✓ (:3797) | ✗ | ✗ | ✗ | ✗ | ✗ |
| **逐道录入 row save** | ✗(用已建批) | **✓ 半成品 (Model B, :329-335)** | ✗ | ✗ | **✗(不扣!)** | **✓ 仅成本边 (:260)** |
| **settle 结单** | ✗ | ✗ | ✗(延后) | ✓扣半成品 (:2690) | **✓ (:2668)** | ✗(写 ProductionSettlementConsumption) |
| complete(免结单工厂) | 级联完成 (:1468) | (事件链) | ✓(事件 :1480) | ✗ | (事件链) | ✗ |
| warehouse-receipt 实收 | 标完成,**跳过 CLERK_WIP** (:2382) | ✗ | **✓ (:2328)** | ✗ | ✗ | ✗ |
| recordMaterialConsumption(独立端点) | ✗ | ✗ | ✗ | ✗ | **✓ (:3474)** | **✓ (:3470)** |

**两套消耗记账并存**:`recordMaterialConsumption` 写 MaterialConsumption 台账;`settle` 写 ProductionSettlementConsumption。都扣 usedQuantity 但台账不互通 → 若两端点对同一计划都被调用有**重复扣减风险**。

---

## 3. 库存三层

### 原料库 (MaterialBatch, WH-LOG)
采购收货自动建批(`PurchaseServiceImpl:2037`,AVAILABLE)。`currentQuantity = receipt − used − reserved`(`MaterialBatch:202`)。扣减点很多(消耗/预留/调拨/领料/报废/退货/撤回)。**断点**:逐道录入消耗原料只写 MaterialConsumption(:260),**不扣 usedQuantity** → 库存余量到 settle 才真扣。

### 半成品库 — 两套互不连通模型 ⭐
| | Model A `SemiFinishedInventory` | Model B WIP `MaterialBatch` |
|---|---|---|
| 驱动 | 工序报工 → Web 审批 (`postApprovedOutput` ← `ProcessWorkReporting:610`) | 逐道录入 (`createWipMaterialBatch:546`) |
| 字段 | 重量+**成本(移动均价)**+流水+审核态+乐观锁 | 重量+unitPrice,无成本滚动无审核 |
| 半成品库页读? | **✓**(`SemiFinishedInventoryController:43`) | **✗ 不可见** |
| 被下游扣减? | ✓(consumeSourceWip / deductForSecondaryPlan) | **✗ 从不扣** |

⇒ 同工厂两条录入路径产出的半成品落两个**互不可见**存储。

### 成品库 (FinishedGoodsBatch, WH-WKS)
生产唯一自动入库 = `confirmWarehouseReceipt→createFinishedGoodsFromReceipt`(:2328,settle 后再点"仓库实收")。出库已连通(`SalesServiceImpl.deductFinishedGoodsInventory:2885`,FEFO+预留释放)。**断点**:逐道录入 isFinished 批只建 ProductionBatch(REGULAR,`CLK-B-`),**不进成品库**。

### 端到端流向(含断点)
```
采购收货 ─自动─▶ 原料库(WH-LOG)
   ├─ 报工路径 ─自动审批─▶ 半成品 Model A ─自动─▶ 成品库 ─自动FEFO─▶ 销售出库   [闭环✓]
   └─ 逐道录入 ─手动─▶ 半成品 Model B(库页✗见, 余量✗扣) ──✗断──▶ 成品库(✗不进) ──▶ ✗
```

---

## 4. 成本层 (本 session 加固, 最扎实)

六桶(原料/人工/调料/副产/留样/辅料对账)全部按配置流进生产 + 三方对账(oracle==API==DOM)。本 session:
- **抓+修+部署真 bug**:同道 labor+seasoning 双写 output → getYield totalOutput/totalInput 虚高 2×(成本桶不受影响,只 yield-summary)。fix `4ab0568ae` v20260630_132507,独立 review SOUND。
- **深测验证**:跨天+混批+调料组合(d70897cad)、多桶同道(2db09a1ea)、副产/人工传播无双计。
- 已知相邻 pre-existing(留观察):跨步骤 group-key 碰撞 / seasoningCost==0+无人工产出 getYield output 丢失。
- 成本是当前测试覆盖最深的一层。**注意**:成本溯源走 MaterialConsumption 边(逐道录入有写),所以成本对;但**库存账**(usedQuantity)逐道录入没动 —— 成本与库存两套账在逐道录入路径下口径不一致。

---

## 5. 完整 bug / 缺口 / 隐患 地图

### 真 bug
| | 项 | 根因 | 状态 |
|---|---|---|---|
| B1 | 结单后生产批次看不到 | CLERK_WIP 被 `ProductionBatchRepository:46-48` 过滤 + settle 不建/不完成批次 + `findByFactoryIdAndStatus` 口径矛盾 + 列表不传 planId | 待修(小) |
| B2 | getYield 产出/投入 2× | 同道 labor+seasoning 双写 output | **本 session 已修+部署** |

### 系统性根因
- **R1 两条生产路径库存完整度割裂**:逐道录入(客户用)成本优先、库存侧没接通;报工路径才闭环。
- **R2 两套半成品模型脱节**(Model A SemiFinishedInventory vs Model B WIP MaterialBatch)。
- **R3 逐道录入路径成本账(MaterialConsumption)与库存账(usedQuantity)口径不一致**(成本扣了、库存没扣,直到 settle)。

### 缺失功能(库存生产业态)
| | 项 | 依赖 |
|---|---|---|
| G1 | 自动入半成品库 + 成品库(可带审核) | R2 架构决策 |
| G2 | 库存生产永续计划(BY_STOCK 模式,入库不关计划) | 新字段 + settle 改多次 |
| G3 | 逐道录入"小结"(入库+实质扣减+不关计划) | G1 + G2 |
| G4 | 半成品直接产 C + 工序选半成品库 | R2 架构决策 |
| G5(later) | 月度出成率汇总 / 人工成本 view / 盘点不改历史成本 | G3 |

### 其他隐患(latent,非客户提)
- 两套原料消耗记账(MaterialConsumption vs ProductionSettlementConsumption)→ 重复扣减风险。
- 死代码:PENDING_APPROVAL 撤回链 / PLANNED 枚举 / AI source 枚举。
- AI 对话建计划落 MANUAL(来源无法溯源)。
- complete vs settle 成品创建时机分叉(同字段双关语义)。

---

## 6. 给 Steve 的决策点

1. **R2 架构决策(keystone,G1/G4 都依赖)**:两套半成品模型 —— 方案A 桥接(逐道录入物化时同步写 SemiFinishedInventory)/ 方案B 统一视图(半成品库页 UNION WIP MaterialBatch)。涉及成本/库存正确性(🔒红线),建议 Opus 设计 + 独立 review。
2. **B1 真 bug**:可单独快修,但先确认客户要"结单即出已完成生产批" vs "半成品 WIP 可见"——实现不同。
3. **库存生产业态(G1-G4)是一条线**:建议先定 R2 方案,再 G2+G3(永续计划+小结,共生),再 G1(自动入库)、G4(半成品起步)。客户期望明后天 —— B1 + G2/G3 骨架可能赶上,G4 较难。
4. **R3 口径不一致**:逐道录入成本扣了库存没扣,本身就是 G3 小结要解决的(小结时实质扣减),与库存生产业态合并解决。

**未开工** —— 本文档是完整审计交付,等 Steve 定 R2 方案 + 优先级再分发实现。
