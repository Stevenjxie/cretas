# 副产单价来源收敛 — 存量对比报告

**日期**: 2026-07-31
**任务**: SDD Task 7（只出报告，不改数）
**库**: `cretas_prod_db`（已核对 `/www/wwwroot/cretas/.env.prod` 的 `DB_NAME`，只读查询）
**结论状态**: 🔴 **计划给 Task 7 的三个问题，其中两个的前提不成立** —— 见下

---

## 0. 一句话结论

**「两套单价来源要并成一套」这个前提是错的：BOM 侧那套从来没有被使用过（0 行有值）。**
真正需要 Steve 拍板的不是「怎么并」，而是另一件在取证时撞出来的事 ——
**缺 NRV 会静默清空整个 BOM family 的标准成本，而线上 100% 的配方都缺 NRV。**

---

## 1. 取证方法与证据等级

本仓 2026-07-31 连续栽过三次「没验就写」（spec 初稿说副产能力从零、grep 不存在的文件永远返 0、
交接说唯一阻碍是一条过期契约）。所以本报告每条结论标注证据等级：

| 等级 | 含义 |
|---|---|
| **实测** | prod 查询/本地跑出来的数字，附阳性对照 |
| **读码** | 读源码得出，未跑 |
| **推断** | 由前两者推出，标明推理链 |

**阳性对照**：本报告里每一条「0 行」的结论，都先用不带过滤的同一张表/同一 join 确认能查出行。
逐条列在各节。

---

## 2. 线上存量（实测）

### 2.1 BOM 侧单价：**一行都没有**

```
bom_recipes (deleted_at IS NULL) 共 61 行
  output_role = NULL        42 行
  output_role = MAIN        18 行
  output_role = BY_PRODUCT   1 行
byproduct_nrv_unit_price 有值的：0 行（三组皆为 0）
```

**阳性对照**：同一查询 `count(*)` 返回 61（非 0），且 `count(byproduct_nrv_unit_price)` 与
`count(*)` 同时出现在一行结果里 —— 说明列存在、表有数据，只是该列全 NULL。

> 🔴 这直接推翻交接与 spec §1 的表格里写的「BOM 侧 NRV 抵扣 …… **在用**」。
> 代码路径在（`BomFamilyOutputCostingDialog.vue` 有录入界面，`BomRecipeController` 有保存端点），
> **数据一行都没有**。「在用」应改为「有实现、无使用」。

### 2.2 报工侧单价：15 条，**全部在 DEMO_FACTORY**

```
production_reports 共 6002 行，byproducts 非空 15 行
按工厂: DEMO_FACTORY 2015 / DEMO_FACTORY2 1972 / F004 1942 / F001 50 / F006 13 / LIUSHANMEN 7 / F_CLY_DEMO 3
15 条 byproducts 的 factory_id: **全部 DEMO_FACTORY**
```

15 条明细（7 个不同名称）：

| 名称 | 出现次数 | 单价 |
|---|---|---|
| 肥油 | 7 | 8（7 次一致） |
| 料头 | 3 | **5 / 3 / 3 ← 同名两个价** |
| 副产 | 1 | **无 unitPrice 字段** |
| 无价副产 | 1 | **无 unitPrice 字段** |
| 贵副产 | 1 | 100 |
| 碎肉 | 1 | 5 |
| 边角 | 1 | 20 |

> 🔴 这 15 条是 **demo 数据**，不是真实客户生产数据。交接把它作为「线上已有能力」的证据，
> 方向对（能力确实存在且被走通过），但**不能据此推断真实工厂在依赖它**。真实工厂 F006 只有
> 13 条报工记录，其中 0 条带副产。

### 2.3 副产 SKU：**一个都还没建**

```
raw_material_types 共 764 行，32 个不同 category
category = '副产' 的：0 行
上述 7 个副产名称（肥油/料头/碎肉/…）作为物料名存在的：0 行
```

**阳性对照**：同表 `count(*)` = 764，`category` 分组能列出 辅料 167 / 原料 127 / 调味料 119 /
包材 99 / 主材 64 …… 说明表和列都正常，「0」是真的 0。

符合预期 —— Task 1 的「副产」大类今天才上线，还没有人建过副产 SKU。
**含义**：SKU 化没有存量映射负担，也没有存量冲突。

---

## 3. 计划要求回答的三个问题

### ① 两侧单价有没有「同一副产取值不同」？

**答：跨两侧的分歧在今天不可能存在 —— 因为 BOM 侧是空的。**（实测）

但取证时发现**报工侧自己内部就有分歧**：

- `料头` 在 3 条记录里取值 **5、3、3**（`production_reports` id 22062 / 22112 / 22117）
- 另有 2 条记录**根本没填单价**（`副产` qty 5、`无价副产` qty 5）

**这两点恰好支持 Steve 定的方向**：
- 同名不同价 → 说明「在报工时逐次填单价」本来就会漂，把单价收敛到 SKU 上是对的
- 有 2 条没填价 → 说明报工时填单价**本来就是可选的**，把它挪到盘点不会破坏既有流程

### ② 迁移到 SKU 参考价后，哪些 BOM 标准成本会变、变多少？

**答：既有已发布的标准成本，一个都不会变。**（推断，链条如下）

1. 迁移的对象是 `byproduct_nrv_unit_price` 的用法（实测：0 行有值）
2. 没有值可迁移 → 没有任何配方的成本输入会因迁移而改变
3. 唯一带 `BY_PRODUCT` 的 family（`41a4ad42…`，SOP-20260731-01 拓扑成品C/D）当前
   `total_material_cost` / `total_cost` **均为 NULL**（实测），本来就没有可变的数字

**但方向是反的 —— 迁移不会「改变」成本，而会「恢复」成本。** 见第 4 节。

> ⚠️ 我一度写下「这个 family 的成本被 NRV 清空了」，随后查它的明细行发现
> **它只有 1 条 RAW 行且 `standard_quantity` 与 `unit_price` 都是 NULL** ——
> 会在更早的「未定价 → 成本不完整」那条分支就短路返回。
> 也就是说这个 family 的 NULL **不能**归因于 NRV。原判断已修正，见第 4 节的独立取证。

### ③ 要不要保留每配方的覆盖位？

**建议：不保留。** 但这是 🔒 成本口径，最终由 Steve 定。理由：

- **没有存量成本**：0 行有值，删掉/停用不需要迁移任何数据，也不会让任何数字变化（实测）
- **它正是 §1.2 要消除的第二个权威**：本仓 07-31 一天连修五处「同一件事多套实现」
  （单位别名表在五处各抄一份），再留一个「每配方可覆盖的单价」就是重新开一个漂移源
- **抵扣已经改在盘点按实际重量做**：BOM 标准成本只需要一个**估计值**，
  SKU 参考价足够；真正的钱在盘点确认那一步落地
- **YAGNI**：spec §2 已明确不做会计级自动化。真需要覆盖位可以以后再加，
  而**加**比**去掉一个已经在参与成本计算的字段**便宜得多

---

## 4. 🔴 取证时撞出来的真问题（不在计划里，但比上面三问更要紧）

### 4.1 现象：缺 NRV 会**静默清空整个 family** 的标准成本

`BomRecipeServiceImpl.recomputeFamilyCosts` 对 family 里**每一个** `outputRole == BY_PRODUCT`
的成员调用 `byproductGrossNrv(...)`；该方法在 `byproductNrvUnitPrice == null` 时返回 null，
调用处随即 `markFamilyCostIncomplete(targets)` —— 把 **family 全体**的
`totalMaterialCost` / `totalCost` 置 NULL 并保存。（读码：`BomRecipeServiceImpl:2736-2740`、`:2899-2903`）

**实测验证**（本地跑，两组只差 NRV 一个变量）：

| 场景 | MAIN 原料成本 | MAIN 总成本 | BY_PRODUCT 成本 |
|---|---|---|---|
| A. NRV = null（**= 线上 61 行的状态**） | **null** | **null** | null |
| B. NRV = 2（阳性对照） | 98.0000 | 98.0000 | 2.0000 |

B 组同时证明抵扣逻辑本身是好的（成本池 100，副产 NRV 2 → 主产品 98）。
A 组证明**唯一的差别就是 NRV**，不是别的短路分支。

> 复现：按 `BomRecipeFamilyCostAllocationTest` 的 mock 装配，family = MAIN（一条**已定价**
> 原料行，排除「未定价」短路）+ BY_PRODUCT（`outputQuantityPerUnit=1`，NRV 分别为 null / 2），
> 反射调用 `recomputeFamilyCosts`。探针已跑完删除。

### 4.2 为什么这事现在才浮出来：PR #2080 只修了一半

客户 2026-07-31 现场撞的是 `validateByProductCreditRules` ——「副产品缺少单位可变现净值」
把一对多 workflow（两个都是正经成品）拦在生效门外。#2080 的修法是给它加了
**ACTUAL_IO 语义豁免**：自动编号出来的 `BY_PRODUCT` 不是用户标的副产品，不该拿它当真。

**但那个豁免只加在了生效闸上。**（实测：`targetProducedUnderActualIoSemantics` 在
`BomRecipeServiceImpl` 中**只出现 1 次**，位于 `:444` 的 `validateByProductCreditRules` 内，
`recomputeFamilyCosts` 完全没有引用它。）

于是失败模式从「**响亮地拦住**」变成了「**静默地把成本清空**」—— 后者更糟：
客户现在能生效了，但成本表是空的，而且不会有任何提示。

### 4.3 还有一层：自动标的 BY_PRODUCT 会把**正经联产品**按副产计价

`BomWorkflowRevisionService` 在 ACTUAL_IO 下按 `terminalIndex == 0 ? MAIN : BY_PRODUCT`
自动编号（该处注释自称 *compatibility-only storage metadata, not authored or shown to users*）。
而 `recomputeFamilyCosts` 对 `BY_PRODUCT` 成员的处理是（读码 `:2812-2822`）：

- 成本 = `byproductGrossNrv`（NRV × 产出量），**不走**分摊比例
- 分摊比例强制按 0 处理（`allocation = ZERO`）

线上那个 family 正是这样：拓扑成品C `ratio=100`、拓扑成品D `ratio=0`（实测）。
**即使把 NRV 填上**，成品D 也会被按「副产 NRV」计价、成品C 独吞 98% 成本 ——
而按 #2080 自己的判断，这两个都是正经成品。

> 推断链：#2080 的注释说「真正的成本分摊在报工时按比例/重量/数量算，那段从头到尾没读过
> `outputRole`」——这对**订单实际成本**成立。但 **BOM 标准成本**这一侧确实读 `outputRole`，
> 所以「占位标签不影响任何成本计算」这句话在标准成本上**不成立**。

---

## 5. 附带调研：`work_processes.expected_byproducts`

（spec Self-Review 里唯一没排进任务的一项）

**存量**（实测）：266 个工序中 4 个有声明 —— 其中 **2 个在真实工厂 F006**：

| 工序 | 工厂 | 声明 |
|---|---|---|
| WP-F006-ZS-01 修油 | **F006** | `{"name":"肥油","unit":"kg","defaultEnabled":true}` |
| WP-F006-ZS-04 去舌苔 | **F006** | `{"name":"舌苔碎肉","unit":"kg","defaultEnabled":true}` |
| DF2_wp29 修油 | DEMO_FACTORY2 | 同上 |
| DF2_wp20 去舌苔 | DEMO_FACTORY2 | 同上 |

**结构**：只有 `name` / `unit` / `defaultEnabled` —— **没有单价、没有 SKU、没有数量**。
所以它**不与任何单价来源竞争**，不属于「两套单价」的问题范围。

**它与本设计的关系**：它是**第三个自由文本名称来源**（工序级），
而 Task 5 新加的 BOM 第四类是 **SKU 级**声明（配方版本级）。两者键不同、粒度不同：

| | `work_processes.expected_byproducts` | BOM 第四类（Task 5） |
|---|---|---|
| 键 | 自由文本 name | 原料字典 SKU |
| 粒度 | 每道工序 | 每个 BOM 版本 |
| 用途 | 报工时预填提示 | 预计产出 + 报工预填与偏差提示 |
| 线上使用 | 4 条（2 条真实工厂） | 0（今天才上线） |

**🔴 但它目前是「声明了没人读」**：
`expectedByproducts` 从 `WorkProcess` 实体流到 `WorkProcessDTO` / `WorkProcessTaskDTO`
（后者注释写明「报工 OUTPUT 阶段预填提示」），但 **RN 报工屏
`YieldStepReportScreen.tsx` 里搜不到任何 `expectedByproducts` 引用**（实测 grep，
该文件确有 `ByproductInput` / `byproducts` 状态，是手工录入那套）。
**阳性对照**：同一次 grep 在该文件里命中了 `ByproductInput`（第 96 行）等符号，
说明搜的是对的文件、关键字拼写无误。

→ 即：工序声明预期副产这件事，**数据和 DTO 都在，最后一公里没接**。
这与 Task 6 Step 5 的处境相同（`ByproductCreditService` 至今零调用方）。

**建议**：两个声明位不要并存太久。要么让 BOM 第四类成为唯一声明位、
`expected_byproducts` 降级为历史兼容；要么反过来。**在接线之前决定**，
否则会变成第三处「同一件事多套实现」。这一条不紧急（4 条存量、且无人读），
但应在 Task 5 的 BOM 第四类真正接进报工预填**之前**定。

---

## 6. 附带：`V20261029_32__unit_codes_to_chinese.sql`

**与本设计无交互，可以放下。**（实测 + 读码）

该 migration 把**计数/包装**单位的英文码换成中文（pcs→只、box→盒…），
每条 UPDATE 都带 `cur.category NOT IN ('WEIGHT','VOLUME')`，明确**排除** kg/g/L/ml
（注释说明：国际计量符号，秤上单据上国标上都这么写，换中文反而不清楚）。

而副产这边**全部是 kg**：15 条报工副产 unit 全为 `kg`，4 条 `expected_byproducts` unit 全为 `kg`。
→ 落在被排除的 WEIGHT 类里，不受该 migration 影响。

---

## 7. 🔒 需要 Steve 拍板的事项

按红线「默认只记录不修」，以下全部**只记录、未改**：

| # | 事项 | 我的建议 | 影响面 |
|---|---|---|---|
| 1 | **缺 NRV 静默清空 family 标准成本**（4.1） | 修。最小且有先例的修法是把 #2080 那条 ACTUAL_IO 豁免**同样用在 `recomputeFamilyCosts`**：自动编号出来的 BY_PRODUCT 不按副产计价 | 今天实际受影响 family = 1 且已因别的原因为 NULL → **改动不会让任何已发布数字变化**；但决定了以后每个一对多 workflow 的成本对不对 |
| 2 | **自动标的 BY_PRODUCT 按 NRV 计价、比例强制 0**（4.3） | 同上一条一起修 —— 它们是同一个根因 | 同上 |
| 3 | **是否保留每配方 NRV 覆盖位**（③） | 不保留，单价只留 SKU 参考价一条链 | 0 行有值 → 无数据迁移、无数字变化 |
| 4 | **两个副产声明位如何收敛**（第 5 节） | 在 BOM 第四类接进报工预填**之前**定 | 4 条存量且无人读，不紧急 |
| 5 | **Task 6 Step 5 缺的后端面** | 需要 repository 查询 / DTO / 列表端点 / **确认单价写端点**；写端点产出的正是抵扣主产品成本的数 | 🔒 成本口径 |

**另需修正文档**（非红线，可直接做）：
spec §1 与交接里「BOM 侧 NRV 抵扣 …… 在用」应改为「有实现、有录入界面、**线上 0 行使用**」；
「15 条已录」应补注「全部在 DEMO_FACTORY，非真实工厂数据」。

---

## 8. 复现方法

```bash
# 单价两侧（阳性对照：count(*) 应非 0）
ssh root@47.100.235.168 "su - postgres -c \"psql -d cretas_prod_db -c \\\"
  SELECT output_role, count(*) n, count(byproduct_nrv_unit_price) with_nrv
    FROM bom_recipes WHERE deleted_at IS NULL GROUP BY output_role;\\\"\""

ssh root@47.100.235.168 "su - postgres -c \"psql -d cretas_prod_db -x -c \\\"
  SELECT id, factory_id, byproducts FROM production_reports
   WHERE byproducts IS NOT NULL AND byproducts::text NOT IN ('null','[]','{}');\\\"\""

# 副产 SKU（阳性对照：rmt_total 应为 764）
ssh root@47.100.235.168 "su - postgres -c \"psql -d cretas_prod_db -c \\\"
  SELECT count(*) rmt_total, count(*) FILTER (WHERE category='副产') byproduct_sku
    FROM raw_material_types;\\\"\""

# 工序声明
ssh root@47.100.235.168 "su - postgres -c \"psql -d cretas_prod_db -x -c \\\"
  SELECT id, factory_id, process_name, expected_byproducts FROM work_processes
   WHERE expected_byproducts IS NOT NULL AND expected_byproducts::text NOT IN ('null','[]','{}');\\\"\""
```

NRV 清空 family 的复现见 4.1 的说明（探针测试已跑完删除）。
