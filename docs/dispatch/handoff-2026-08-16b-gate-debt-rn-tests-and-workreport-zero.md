# 交接 2026-08-16 夜班 —— A 组三件收尾 + 报工链「零实例」的定性

**承接**: `handoff-2026-08-16-purchase-chain-walkthrough.md` 第八节
**环境**: 生产 `cretas_prod_db` / `47.100.235.168`
**生产写入**: **0**（本轮全部只读；B1 的受控 E2E 未做，理由见第四节）

---

## 一、A 组三件，都完成了

| | 内容 | 状态 |
|---|---|---|
| **A1** | 还清 `KNOWN_UNCOVERED` 最后 28 个闸 | ✅ **PR #2721 已合并** `b7f8adfb9a` |
| **A2** | 让 RN 4 个集成测试重新执行 | ⚠️ **执行面已通，35 条存量断言未修**（见 §2）|
| **A3** | 补 fastlane §2b 的规则漏洞 | ✅ **fastlane 已上 main** `c5d013d38c` |

### A1 读数

| | 值 |
|---|---|
| 改名前跑那 28 个类（**当场重跑的基线**，⛔ 未引用交接里的数）| 28 类 / **129** 测试 / 0 红 |
| 改名后跑 CI 逐字选择器（本地）| 120 类 / **505** 测试 / 0 红 |
| **CI 上的独立复现**（run `31948846643`）| **505 / 0 红**，与本地逐字一致 |
| 逐类核对 | 28 个新名各命中 1 次（`SkuImport…` 3 次含 2 嵌套类）|
| **阴性对照** | 28 个旧名各命中 **0** 次（本地与 CI 日志都查了）|

**两条活断言各自变异实测红过一次**，且都先证明「变异真的落地」再读红绿：

- **M1** 把一个已还清的类改回 `…Test`（仍读 `src/main`）→ 🔴 `newSourceScanningGatesMustBeSelectable`：`Expecting empty but was: ["VoucherBackfillScopeTest"]`，另外 3 条仍绿。
- **M2** 往 `KNOWN_UNCOVERED` 塞一个**已被选择器覆盖**的类 → 🔴 `debtListMustNotRot`。

⚠️ 顺手修的一处**恒真式**：`KNOWN_UNCOVERED` 归零后 `debtListMustNotRot` 会静默变成恒真式
（空集 `removeIf` 之后还是空集）。照 `exclusionsMustStillExist` 已有的做法显式分出「当前为空」那一支。

### A3：规则文本的漏洞不止交接件说的那一条，是**四条**

⛔ 全部用 `--dry-run` 在**真脚本**上跑过，阳性/阴性成对，不是读源码推的：

| 你改的 | 规则原文读起来 | 脚本实际 |
|---|---|---|
| `docs/**.md`、`.claude/**`、`*.md`、`.gitignore` | fastlane | ✅ docs-only，跳过 ACTIVE 闸 |
| **`docs/dispatch/**`** | 「是 docs」 | 🔴 **不算 docs**，要 `--task-id`（台账就在这儿）|
| **「配置类」** | fastlane | 🔴 豁免面里**没有这一档**，要 `--task-id` |
| **`.agents/skills/*`、`.codex/rules/*`** | 与 `.claude/` 并列 | ⛔ **高风险**，要 `YES-HIGH-RISK-REVIEWED` |
| `--task-id` 打错 | 未提 | 直接拒（ID 必须在 `docs/dispatch/` 下 `grep -rwqF` 得到）|

🔴 最阴的是最后两行：`.claude/` 豁免、`.agents/` 和 `.codex/` **高风险**，三个目录名几乎一样、档位相反，
而规则原文一个字都没提后两个。顺带修了表格——那段 ⚠️ 原本夹在两行之间，把一张表劈成两张，第二张没表头。

---

## 二、A2：4 个文件从「不执行」变成「真的在跑」，但 35 条还红着

`git` 提交在 `codex/claude-rn-integration-tests`（`f716e2c306`），**尚未开 PR**。

三步，每一步的红都是上一步暴露出来的：

1. 摘 ignore + 配 `transformIgnorePatterns` → **我的正则多一个 `)`**，0/117 跑到。**当场炸，不是静默失效。**
2. 修好正则 → 3 个 suite 加载期崩：`ts-jest` 去**类型检查三方源码**，`expo-modules-core` 自带 5 个 TS2532。
   ⚠️ **这 3 条红看起来像存量欠账，其实是我这条配置造出来的。**
   ⇒ `node_modules` 的 `.ts` 交给 `babel-jest`（转译不类型检查），排在 `transform` 最前。
3. 再崩在 expo 原生运行时 → 补 3 个全局 mock：`@expo/vector-icons`（`src/` 下 **197** 个文件 import 它；
   此前只有 `AIChatScreen` 自己写了局部 mock —— **那正是 4 个文件里唯一一开始就绿的**）、
   `react-native-safe-area-context`、`expo-av`。

| | 解禁前 | 解禁后 |
|---|---|---|
| suites | 113 全绿 | 117 |
| 4 个目标文件在执行清单里 | **0 次**（阴性对照）| 全部出现 |
| 这 4 个文件里**真的跑了**的测试 | **0** | **63**（28 passed / 35 failed）|

**⛔ 剩下的 35 条不许用「把 ignore 加回去」解决** —— 那等于把闸关掉。
它们已经不是加载期崩溃，全是内容断言（`Unable to find an element with text: …`）。

🔴 **修之前必须逐条判「测试过期」还是「屏幕回归」**：已确认 `NfcCheckinScreen`
走 `isProcessMode()` 这个**工厂特性开关**，而测试是按 BATCH 模式写的 ——
一律改测试会把可能存在的回归一起抹掉。

---

## 三、🔴 本轮最重要的定性：报工链在生产上**零实例**

owner 当晚把重心改成「报工 + 仓储实时关联」。照「判某功能为什么没输入，先数上游表」查下来：

### live schema（`public`）的真值

| 段 | 行数 |
|---|---|
| `production_reports`（报工）| **0** |
| `batch_work_sessions` | **0** |
| `semi_finished_inventory_transactions`（工序产出进半成品库存）| **0** |
| `semi_finished_inventory` | 1（F006）|
| `process_checkin_records`（签到）| 2 |
| `material_consumptions` | 3 |
| `work_process_tasks` | 3（F006：1 COMPLETED / 2 PENDING）|
| `material_batches` | 186，其中 **182 条无来源标注** |

`material_batches` 来源分布：`(null)/(null)` **182** · `OTHER/CUSTOMER_MATERIAL_ARRIVAL` 2 · `(null)/PRODUCTION_BATCH` 1
⇒ **没有一条来自采购收货**，与上一轮「28 张收货单全是无来源」一致。

### ⚠️ 我自己在这里错了一次，写下来防止下一个人重蹈

我先从 `pg_stat_user_tables` 读到 `production_reports = 3`，差点据此写「报工有 3 条但不写库存」。
**那个 3 来自归档 schema。** 本库有 7 个 schema，`public` 之外的
`tenant_purge_68`(347k 行) / `f006_clear_71` / `bak_e2e_trf_*` / `legacy_retired` 全是**归档副本**，
而 `pg_stat_user_tables` **不带 schema 过滤**，同一个表名会出现好几行。

▎ **判据：查行数一律带 schema 限定；`pg_stat_user_tables` 的结果必须先按 `schemaname` 分组看一眼。**
（`material_batches` 在四个 schema 里分别是 417 / 325 / 186 / 2 —— 挑错一个，结论全反。）

### 定性（两个结论是相反的，别搞混）

- ❌ 「报工不写库存」—— **不成立**，`ProcessWorkReportingServiceImpl` 引用了 `WipInventoryService`，
  写入方 `WipInventoryServiceImpl` 存在。
- ✅ **「报工在生产上一次都没发生过」** —— 没有输入，所以那条写入路径谈不上通不通。

⇒ 客户说的「漏报工 → 下工序没库存」，**当前不是漏报的问题，是这条链一次都没被走过**。
与上一轮「采购 OA 零实例」是同一形态：**先数上游表，再判下游为什么空。**

⛔ **下一步不是改代码，是先按顺序真走一遍**（上一轮就是这么找到 RN 扫码入库 100% 失败的）。
在没走过之前，任何「报工↔库存要怎么接」的设计都是在猜。

---

## 四、B1 `FIX-F006-PRESTOCKED-SHIPMENT-E2E-20260809`：台账过期了

owner 已同意转交（全套）。查下来**不需要合并、也不需要部署**：

| 判据 | 读数 |
|---|---|
| 代码在 `origin/main` | ✅ Controller / Entity / Repository / Service / 两个 Flyway 全在 |
| 运行中 jar mtime | **2026-08-16 20:29:23**（今晚）|
| 进程启动 | 20:29:51，**晚于制品** ✅ |
| jar 里 `SalesDeliveryBatchAllocationServiceImpl` | ✅ 1 |
| jar 里 `SalesDeliveryItemBatchAllocation` / `…Controller` | ✅ 2 / 2 |
| 阳性对照 `SalesServiceImpl` | ✅ 1 |

⇒ 台账的 `NOT_DEPLOYED` 与交接件 §5 的「生产上仍是旧行为」**都已过期**。

⚠️ 上一轮那条「发货不挂批次」的读数（`sales_delivery_items` 32 行 / 挂批次 0 条）是
**8-09→8-13 的单子，全在这次部署之前** —— ⛔ 不能拿它判断新代码。

**仍然欠着的就是台账原本写的那一条：F006 受控写入 E2E。** 本轮没做，理由：
F006 仅有的 3 张 PRESTOCKED 单（`SO-20260809-0001/0002/0003`）**全是 `COMPLETED`**，
没法在既有单子上重放；要做就得新建一张销售订单+预留，那是一批新的生产写入，
而 owner 当晚已把优先级改到报工侧。**这是一个有意的取舍，不是遗漏。**

---

## 五、B2 材料：12 条采购任务，「改的是走得到的地方吗」

owner 当晚口头已给了方向（**采购订单可以无所谓，主要是仓储入库**），下表是支撑它的读数。
⛔ 只读，未改台账。

| 任务 | 它改的接缝 | 生产上走过没有 |
|---|---|---|
| `…AP-PAYMENT-BOUNDARY-001` / `-CORE` | 应付/付款核销 | `purchase_invoices` **0**、`ar_ap_payment_allocations` **0** |
| `ENH-F006-SUPPLIER-IMPORT-001` | 供应商导入 | `supplier_import_receipts` **0** |
| `ARCH-…-UNIFIED-MATERIAL-RECEIPT-SOURCE-001` | 批次来源 | 186 条里 **182 条无来源**；`source_type` 列**尚不存在** |
| `…ORDER-TAX-RATE-INHERIT-001` | 采购行税率 | 17 行全有税率，但 **distinct = 1**（只有一种税率，继承逻辑没有区分度）|
| `…ORDER-CREATE-ATTACHMENTS-001` / `…ORDER-ACTION-IA-001` | 采购单新建/列表 UI | `purchase_orders` 仅 **9 张** |
| `…RECEIPT-DUPLICATE-001` / `…WAREHOUSE-RECEIVING-BOUNDARY-001` | 收货去重/边界 | `purchase_receive_records` 28（但全为无来源收货）|
| `ARCH-…-DEMAND-DRIVEN-…-001` | 销售需求驱动采购 | `purchase_requisitions` 11 |
| 两条 `MATERIAL-TAXONOMY-*` | 物料分类 | `raw_material_types` 627、`material_code_segments` 13 live（524 含删）|

⇒ **前三条改的接缝生产上零流量**；税率那条虽有行但无区分度。

---

## 六、下一个人接着做的（按 owner 当晚给的优先级）

1. 🔴 **按顺序真走一遍报工链**（工序任务 → 签到 → 报工 → 半成品库存 → 下一道工序领用）。
   F006 现成有 2 个 `PENDING` 工序任务可用。**走之前先读 §3 的「零实例」定性。**
2. **A2 剩的 35 条**：逐条判「测试过期 vs 屏幕回归」，尤其 `NfcCheckinScreen` 的
   `isProcessMode()` 分支。⛔ 不许把 ignore 加回去。
3. **B1 的 F006 受控 E2E**（需新建一张 PRESTOCKED 订单）。
4. 仓储入库/出库要在手机上完成 —— 与 §3 第 1 条同一条链，建议一起走。

## 七、环境订正（都是本轮实测，与上一份交接件不一致）

- **蓝绿槽位现在是 `10010`**，不是上一份写的 10020。
- **本库有 7 个 schema**，`public` 之外全是归档 —— 见 §3 的判据。
- `production_reports_20260809` / `material_consumptions_20260809` 这些 `_日期` 后缀表**不在 `public`**。
