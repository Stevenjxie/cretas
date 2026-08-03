# 交接 — 单位口径治理（2026-08-03 一整条线，7 个 PR 全部已上线并 prod 实测）

**状态**: ✅ 收口。无待合并项，无待部署项。
**起点**: `docs/dispatch/handoff-2026-08-03-warehouse-fixes.md`（仓储六处修复）留下的「单位存储口径未决」

---

## 一、最终状态（prod 实测）

| 指标 | 治理前 | 治理后 |
|---|---|---|
| 档案 vs 批次 单位混写 | **11 行** | **1 行** |
| 那 1 行是缺陷吗 | — | **不是**（羊排按「箱」存量，有 `1箱=10kg` 真规格，是合法形态）|
| `raw_material_types` 计数单位 | `pcs 72` | 个67 / 只3 / 件2 |
| `product_types` 计数单位 | 合并态 | 还原 41 行（个37 / 只4）|
| 「别的仓有货但过期了」 | 界面完全不显示 | 「原料仓过期 300kg，不可投料，请联系仓管处理」 |

---

## 二、Steve 的两条拍板，以及它们的**作用域边界**

### 拍板 1：「单位存中文」→ 查存量后改为**分两步**

原话是「存中文」。但 prod 实测存量：

| 表 | 现状 |
|---|---|
| `raw_material_types.unit` | 766 行 **100% 英文码** |
| `product_types.unit` | 771 行，只有 1 行中文 |
| `material_batches.quantity_unit` | 885 行，中文仅 11 行 |

全量中文化要动 **~2400 行**，而它要治的中英混写只有 **11 行**。把这个数摆出来后，
Steve 改为**分两步：先自定义单位存中文，内置单位存量不动**。

> ⚠️ 这个决定还与 `V20261029_48`（2026-08-02 **前一天**刚上线的「SKU 单位统一存英文码」）相容。
> 全量中文化会把它整个推翻。**接到口径决定，先 `ls db/flyway | tail` 看最近的迁移在干什么。**

落地口径（`UnitContractService#storageUnit`，全系统唯一承载点）：

```
1 权威表认不出        → 原样 trim
2 同码多中文写法      → 保用户字面        ← 拍板 2
3 工厂自定义单位      → displayName 中文名
4 内置单位            → code 英文码（存量不动）
```

### 拍板 2：「只 ≠ 件，算两个单位」

⛔ **作用域仅限「数量 / 库存」这一侧。** Workflow **槽位匹配**侧
（`BomWorkflowRevisionService#canonicalUnit` → `canonicalCodeOrRaw`）**刻意仍把 件/个/只 折成 `pcs`**，
因为它判的是「这个投入槽还在不在」，本就要认本地化写法；既有契约
`localizedCountUnitMatchesCanonicalBomUnitDuringStableSlotRekeying` 明确断言
`unitsCompatible("pcs","只")` 为 true。

**这个不对称是设计，不是遗漏。** 已加测试 `ScopeIsInventoryNotSlotMatching` 钉住，
防后来者看到两处不一致就「统一」掉。

---

## 三、七个 PR

| PR | 内容 | prod 判据 |
|---|---|---|
| #2220 | 仓储/结单六处修复 | 结单 409→COMPLETED；过期提示 expired:300 |
| #2222 | 交接文档 | — |
| #2230 | `storageUnit` 收敛 + 自定义单位纯中文创建 + **P0-1 写入侧真根因** | jar 字节码核过 5 处 |
| #2232 | 只/个/件 三个单位 + 还原 113 行档案 | 五条判据全中（含反向：box 8 / case 7 中文 0）|
| #2236 | 带鱼 2 行 `箱`→`kg`，**羊排刻意不动** | 改 2 跳 0；混写 3→1 |
| #2237 | 包装单位批次：拆第 1 层栅栏 | 单测绿，**但线上没生效**（见下）|
| #2238 | 拆第 2、3 层栅栏 | `expiredElsewhere=[{原料仓,300,kg}]` |

### 🔴 P0-1 的真根因在**写入侧**，不在查询侧

`PurchaseServiceImpl` 建批次时 `batch.setQuantityUnit(inventoryUnit)` **完全没有归一** ——
采购单行写什么就往批次里抄什么；而档案侧走权威归一存 `box`。
所以「放宽调拨查询侧归一」那个修法**方向本来就错**（已在 #2220 前撤回）。

**判据：两处值对不上时，先看两处分别是谁写进去的，别先改读的那一侧。**

### 🔴 那个缺陷有**三层栅栏**，#2237 只拆了第一层

部署 #2237 后去验，羊排那 100kg **仍然不可见**：

| 层 | 位置 | 挡住的原因 |
|---|---|---|
| 1 | `unitMatches` 不认包装单位 | #2237 拆 |
| 2 | `findAvailableBatchesFEFO` 只取 `status='AVAILABLE'` | 过期的取不出来 |
| 3 | `findExpiredBatchesByWarehouse` 只取传入的那一个仓（调用方传生产仓）| 别的仓取不出来 |

第 2、3 层在**仓储查询**里，**早于任何单位判断执行**——单元测试怎么测都测不到。

> 讽刺的是第 3 层那条查询的 Javadoc 写的动机就是「实测 F006 羊排**在原料仓**有 100kg 但全部 EXPIRED」，
> 可它的实现按**生产仓**过滤。**实现与自己写的动机相反。**

> 📌 也解开一个一直对不上的数：界面 `expired=300` 是**生产仓**三条 g 批次，
> 与交接记的「原料仓 100kg 全过期」根本不是一回事，两个数撞巧接近。
> 原料仓真实过期量是 300kg（100 g批 + 100 kg批 + 100 箱批）。

---

## 四、刻意没做的（下一个人别当成遗漏）

### 1. 可投量**不含**按包装单位存量的批次

扣减侧 `kgToStorageQuantity` 只做 g↔kg，对「箱」是**原样返回**：

```java
kgToStorageQuantity(kg, unit) { return "g".equals(unit) ? kg*1000 : kg; }
```

把 100kg 的分配落到只有 10 箱的批次上会**超扣 10 倍**，比原缺陷严重得多。
所以边界是：**展示看得见（过期提醒 / 别处还有），可投量不含**。

**要放开必须先让扣减侧也走包装规格反算**——那是独立的一步，且要连
`ProcessSheetServiceImpl` 的消费路径一起验。已加测试
`displayIsWiderThanAllocationOnPurpose` 钉住这个边界。

### 2. 用户**显式选**一个包装单位批次仍报 409

`PRODUCTION_INPUT_BATCH_UNIT_MISMATCH`。这条是**明确报错不是静默丢失**，
不在本轮「消掉静默不可见」的范围里。同样要等扣减侧支持后再放开。

### 3. `V20261029_48` 与 `#1976` 的矛盾只解开了一半

- **已定**：库存/数量侧按「只≠件」（本轮做完）
- **未动**：槽位匹配侧仍折 `pcs`（见上，这是有意的）

备份表 `backup_sku_units_20260802` 存着合并前原值，若将来要再改方向可从那里还原。

---

## 五、复验方法

```bash
# 1) 单位混写现状（预期 1 行, 且是羊排那条合法的）
SELECT b.factory_id, b.batch_number, rt.name, rt.unit AS 档案, b.quantity_unit AS 批次
FROM material_batches b JOIN raw_material_types rt ON rt.id=b.material_type_id
WHERE b.deleted_at IS NULL AND rt.deleted_at IS NULL
  AND b.quantity_unit IS DISTINCT FROM rt.unit
  AND (b.quantity_unit ~ '[一-龥]' OR rt.unit ~ '[一-龥]');

# 2) 计数单位（预期 个67/只3/件2, pcs 为 0）
SELECT unit, count(*) FROM raw_material_types
WHERE deleted_at IS NULL AND unit IN ('pcs','个','只','件') GROUP BY 1;

# 3) 反向: 盒/箱 不得被还原成中文（预期 box 8 / case 7, 中文 0）
SELECT unit, count(*) FROM raw_material_types
WHERE deleted_at IS NULL AND unit IN ('box','case','盒','箱') GROUP BY 1;
```

界面侧：F006 计划 `fe3548a3-284b-4072-9676-3f6b40fb5781` 的「继续录入」抽屉，
羊排那行应显示「**原料仓过期 300kg，不可投料，请联系仓管处理**」。
那 300 里有 100 来自「10 箱 × 1箱=10kg」——**一个数同时验三层栅栏**。

---

## 六、这一轮反复应验的判据

1. **拿到口径决定先查存量。** 两次都因此转了方向：「存中文」→ 分两步；「kg/箱 都是错的」→ 只有 2 行是错的（羊排有真规格，改了会让 100kg 变 10kg）。
2. **mock 掉权威表的测试证明不了任何事。** 撤回的那个修复（自造 catalog，对「只→pcs」塌陷完全无感）、`convert()` 的 `at` 不能为 null（为 null 直接返回 `PRODUCT_CONVERSION_MISSING`，走不到包装规格那段）——都是「用真实实现」才暴露的。
3. **部署完要去验，别信单元测试绿。** #2237 单测全绿但线上没生效。
4. **写数据迁移必须先在 prod 事务里 `BEGIN…ROLLBACK` 干跑。** `V20261029_50` 干跑当场抓到 `record "r" is not assigned yet`（同一个 DO 块里既声明 `RECORD r` 又拿 `r` 当表别名），不干跑就是 Flyway 启动失败。
5. **计数型契约要数「代码构造」不是「字符出现」。** 迁移契约第一版用「全文出现 ≥2 次」判守卫，删掉一处 UPDATE 守卫仍绿（那个串在诊断 SELECT 里也有一次）。
6. **读变异结果前先确认变异真的落地。** 有两次 sed 因中文注释没匹配上，「没红」其实是变异没生效。
7. **`gh pr checks` 会把 cancelled 显示成 `fail`。** 判断 CI 失败要看 `conclusion`。
