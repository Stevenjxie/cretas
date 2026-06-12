# Codex 完整(gold)测试 — 收尾深测 + 真实数据闭环 (自包含卡)

**日期**: 2026-06-12
**前置**: Gate1 smoke / Gate2 §7+六大流 / RN 第二波 全部已跑+organizer gated。本卡是**收尾深测**——把"建了+浅验"升到"真实数据端到端 deep closed"，并最终回归今晚所有修复。
**环境/账号/铁律**: 同 `2026-06-12-codex-test-master-plan.md` §0（headed + 真实数据 + SQL坐实 + 不改码 + DEMO标记 + 诚实判定）。

---

## ⚠️ 先读: organizer gate 纠正 (别重复测已证实的)

Gate 已查实，以下"看似缺"的**其实都已实现**，**不要再当 gap 测**:
| 项 | 真实状态 (gate 实查 origin/main) |
|---|---|
| 盐化独立扣量/报表 | ✅ `SaltedWarehouseController` 全套端点 (POST扣量/GET列表/GET /report 独立报表), 注释明写"SP7 F11 转录[91:31]×4次" |
| RN 收货屏 | ✅ `WHPurchaseReceiveListScreen` 已注册 WHInboundStack (SP6 #25) + WHReceiptCreate 扫码收货 |
| 入库守卫 assertCanReceive | ✅ 2 调用点 (MaterialBatchServiceImpl:256 + PurchaseServiceImpl:1819) |

以下是**内部 spec gold-plating (客户转录 0 提)，不要测/不要建**:
- 通用 WorkflowEngine 引擎集成 (DisposalRecord 那套是 dead code 负资产)
- 16位分段字典 CRUD 管理 UI (老板原话"编码规则无所谓能搜到即可")
- @PriceSensitive 注解机制本身 (注: 脱敏**目标**=销售看不到成本**是客户要的**, 只是机制实现是 spec)

---

## 1. 回归: 今晚所有修复最终复验 (~15min)

逐个 live 复验 (都已 LIVE 验过一次, 这是最终回归确认):
| 修复 | 复验 |
|---|---|
| #774 confirm-500 doomed-tx | 建DEMO收货→confirm→HTTP200+批次创建 (非500) |
| #774 MR-500 主键 | material-receipt 带warehouseId→200 |
| #775 onBatchCompleted doomed-tx | 报工完工→批次完成→FG入库不 500 (报工不被副作用回滚) |
| #775 MR-400 | material-receipt 缺warehouseId→明确400"请指定入库仓库" |
| #776 出纳银行信息 | cashier待付款API→bankName/bankAccount非null |
| #777 报损幂等 | 已审批record再approve→拒绝"已审批" |
| #777 标签前缀 | 建原料(无primaryCode)标签→前缀YL/RL/BC/WL非MA |
| BOM单位数据修 | 触发receive confirm→orchestrator重检不抛"无法换算"→掌中宝计划可"原料到齐" |

---

## 2. 🔴 #6 生产深测 (核心收尾, 客户核心要求, 尚未 deep closed)

> 撤回(C流78项客户核心)+半成品混合计价(客户原话"核心算法唯一具体规格")功能已建(#770/#771)但**未真实数据运行时闭环**。这是最该补的深测。

### A. 撤回自愈"清null→重报新值" 真运行时闭环
- 建DEMO SO(含税)→财审(SO行有costUnitPrice)→转批次→两点报工→审批 → SQL查 sales_order_items.cost_unit_price 有值
- **整单撤回(WHOLE_ORDER)**→审批撤回 → **SQL查 cost_unit_price 被清 null** (自愈)
- 重报(产量改一点让新成本可区分)→审批 → **SQL查重新回填新值≠旧脏值**
- 判定: 三时点 SQL 全 paste, 撤回后真清null + 重报真新值 = PASS

### B. 真多段半成品链 (原料→半成品A→半成品B→成品)
- 用真实微信报工数据 (掌中宝/猪舌真实逐工序) 建二次加工链: createSecondaryPlan + secondarySourceWipId, 3 批次每步两点报工
- 调 `/api/mobile/F006/sales/orders/{orderId}/multi-stage-cost`
- 判定: 真出≥3段, 每段料+人工+制费分列, 半成品unitCost逐段涨, 每盒贡献; 两点报工人工null时 laborHint="登下一期"(诚实)
- 混合计价算例验证 (客户原话"淋1吨用500剩500, 下批500旧+新淋1吨同步投入加权价"): 真造这个场景验加权均价正确

---

## 3. 🔴 微信报工图真实录入到生产链 (deep closure, Codex 自己标的未闭环)

> Codex Gate2 诚实标"微信报工照片未OCR/逐图录入→不能判生产报工链 deep closed"。这次补。

- 数据源: `六扇门工厂数据、/6.1-6.3/群内图片/` (5.31~6.5 群内报工真实照片) + 已有 groundtruth `cretas-e2e-replica/e2e-replica/`(猪舌6道/牛腱5道/掌中宝5道真实逐工序重量/工时/副产物/照片映射)
- 跑: 用真实照片的逐工序投入产出(如猪舌"294.5+245.5=540Kg"/托盘24.5/第1车320盒) + 真实副产物(肥油/骨头/料头) 经 RN 真实上传照片+表单提交报工 → 完工FG
- 判定: 真实数字 SQL 坐实 (非编造) + 照片真上传OSS + 出成率自动算 + 成本链真算; 这条链能从微信图→系统报工→成品→成本 端到端跑通

---

## 4. RN 端剩余 (operator 已双真机坐实 505/506, 补其他角色 deep)

- operator 报工 ✅ 已坐实 (f006_moyun 505 / f006_weizj 506) — 回归即可
- 仓管/采购/销售 RN: Gate2 验到列表级, 补 deep (仓管真入库写库 / 采购真建PO / 销售真确认SO) + 防呆5条
- ⚠️ **财务/出纳 RN 审批待办 = 真客户gap(审批流手机端, catalog行402)→organizer 正设计 OA待办新功能**, Codex 不用测(还没建)

---

## 5. 交付
每块一 audit doc → `docs/audits/liushanmen/2026-06-12-gold-<块>.md`: ✅/⚠️/🔴 + SQL/响应原文 + headed截图 + 诚实结论(撤回自愈/多段链/微信图链 能否 deep closed)。回 organizer gate。
