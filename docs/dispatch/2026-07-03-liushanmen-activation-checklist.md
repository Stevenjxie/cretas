# LIUSHANMEN 客户激活 Checklist（生产领料闭环 + 仓库路由）

**日期**: 2026-07-03（含 prod 预检实测结果）
**触发**: Fable 5 战略纠偏 review 判定 —— 系统层面生产闭环已通（F006 测试租户 23/23 逐克对上），但**付费客户 LIUSHANMEN 租户上这套模型还没激活**。**功能 shipped ≠ 客户在用。** 本 checklist 是把已上线能力在真客户租户上"打开开关 + 建正账 + 培训"的可执行 runbook。
**红线**: 真客户真实数据。默认绝不删/碰无关数据；所有动作可回滚（见回滚表）；建账窗口期，历史库存校正一次做对。

---

## ⚠️ 首要：两条 factory 记录，别配错租户（2026-07-03 实测）

六膳门在 prod 有**两条 factory 记录**，激活只动 **LIUSHANMEN**：

| factoryId | 名称 | 产品 | 物料 | 生产计划 | 销售单 | 角色 |
|---|---|---|---|---|---|---|
| **`LIUSHANMEN`** | 六膳门 | 141 | **217** | 2 | **0** | ✅ **真客户租户（激活目标）** — 装了客户真实 ERP 主数据（Jul-1 导入 132/205），几乎没开始用 |
| `F006` | 六膳门食品科技 | 191 | 7 | 837 | 161 | ⛔ **测试租户（别碰）** — 7 月所有 headed E2E 都在这，837 测试计划 |

> **0 销售单 + 2 生产计划 = 客户一步没走。这正是 Fable "系统跑到客户采用度前面"的最硬证据。** 所有激活配置命令的 `{factoryId}` = **`LIUSHANMEN`**。F006 已有的配置是测试配置，与激活无关。

**LIUSHANMEN 7 仓（实测 id）**：

| code | type | name | id |
|---|---|---|---|
| WH-RAW | RAW | 主仓 | **`fc489fd3-468c-40cd-a229-fd1fccd23359`** ← 采购入库落点 |
| WH-WKS | WORKSHOP | 生产仓 | `c865c899-f228-4613-a86c-911475753888` |
| WH-WIP | WIP | 半成品仓 | `b06b46c0-51da-4577-b4f3-d6328d33c950` |
| WH-FG | FINISHED | 成品仓 | `0f067c19-da49-452f-8b14-c3f7fbe51496` |
| WH-LOG | LOGISTICS | 外仓 | `2dd007f5-4dd1-429b-bdb5-2a87188eea8f` |
| WH-RD | RD | 研发库 | `cd8555c3-8775-4b7a-b546-ab43f1830ae3` |
| SALTED-01 | SALTED | 盐化仓 | `f7ec3a00-471c-4428-8c69-f4c9c83f6f6f` |

---

## ✅ 前提已验证（verify-first，2026-07-03 recon + prod 实测）

激活**无代码前置**。之前 memory/规划里"仓库拆分半拆、销售 4 写路径没 repoint、藏着雷"是**规划意图残影**：

- **Part A #1161 一次性完成**（3 purpose + 3 resolver + 三侧全 repoint），**#1176** 把销售 blank 来源仓 3 写路径脱离单一默认仓 resolver（跨全部可售仓，排 WH-RD）。配 `PURCHASE_INBOUND_DEFAULT=WH-RAW` 对销售**零影响**。
- **prod 实测（2026-07-03）**：`aims-...jar` 含 3 个 purpose 枚举 ✓；`factory_settings.require_requisition_before_report` 列在 ✓；迁移 `20261027.30`（warehouse default）/`.31`（stocktake import）/`.32`（require requisition）全 success ✓。

---

## 🔒 本次激活锁定的决策（Steve 2026-07-03 拍板）

| 决策 | 拍板 |
|---|---|
| 历史幽灵库存处理 | **实盘为准、2A 一次校正**：客户现场实盘 → 系统幽灵库存盘亏冲平 → 期初按实盘坐实 |
| 校正盘点凭证科目 | **1403 原料 / 4001 实收资本** |
| 领料 gate 开启时点 | **激活会当天、培训完立即生效**（不早开，避免挡住未培训的仓管） |

---

## 步骤（依赖顺序）

### 步骤 0 — Pre-flight ✅ 已完成（2026-07-03）
枚举 / gate 列 / 迁移 全部实测通过（见上"前提已验证"）。**无需再跑。**
> 复检命令留档：`unzip -p …jar WarehouseDefaultPurpose.class | strings | grep PURCHASE_INBOUND_DEFAULT`；`SELECT … information_schema.columns … require_requisition_before_report`；`SELECT version,success FROM flyway_schema_history WHERE version LIKE '20261027%'`。

### 步骤 1 — 配 LIUSHANMEN 仓库 purpose ✅ 已完成
实测 LIUSHANMEN 的 `PURCHASE_INBOUND_DEFAULT` **已配 → WH-RAW 主仓**（`fc489fd3…`）。采购入库已会落主仓。**无需操作。**
> 若日后要改：`PUT /api/mobile/LIUSHANMEN/factory/warehouse-defaults  {"purpose":"PURCHASE_INBOUND_DEFAULT","warehouseId":"fc489fd3-468c-40cd-a229-fd1fccd23359"}`（`factory_super_admin`，多租户安全校验仓属本厂）。
> 可选 `PRODUCTION_RAW_DEFAULT` / `SALES_OUTBOUND_DEFAULT` 一般不配（fallback WH-LOG + #1176 销售跨可售仓已够）。

### 步骤 2 — 历史库存校正盘点 ⭐（实盘为准、2A 一次校正）
**目的**：清掉修复前造出的幽灵库存，把期初按实盘坐实。

> **好消息**：LIUSHANMEN 只有 2 个生产计划、0 销售单 —— 几乎没运行过，**幽灵库存大概率极少甚至没有**，建账窗口又干净又宽。这一步很可能接近"直接用实盘坐实期初"，而非大规模冲平。

1. 客户现场/配合做一次**全量实物盘点**（原料 + 半成品 + 成品）。
2. 用 **盘点批量导入工具**（迁移 `20261027.31` 已上线，含期初）录入实盘：账面 > 实盘的差额**盘亏冲平**；期初数量 = **实盘数**。
3. 校正盘点凭证：**1403 原料 / 4001 实收资本**。
4. 验证：盘点后 `库存 = 实盘`，无残留幽灵批次，凭证正确。

> ⚠️ #1167 幽灵库存修复是 forward-only，历史脏数据靠这一步校正。

### 步骤 3 — 仓管操作培训（gate 开启前必做）
- **领料确认流程**：生产计划 → 生成领料单 → 拣货（start-picking）→ 确认领料（confirm-picking）→ 料到车间仓（transfer）。端点 `FactoryMaterialRequisitionController`。
- **报工来源仓选择**：料到生产仓（WH-WKS）后从生产仓报工消耗。
- **收货 / 盘点** 日常操作。
- fool-proof 铁律（客户原话）："仓管年纪大、文化素质低，你告诉他要收/领多少就行" → 强调系统会带出数量，仓管只确认。

### 步骤 4 — 开领料 gate（激活会当天、培训后）
LIUSHANMEN 当前 `require_requisition_before_report = **false**`。培训后翻 true：
```
PUT /api/mobile/LIUSHANMEN/settings   Body: {"requireRequisitionBeforeReport": true}
```
（partial-update：只有该字段被显式传时才应用；也可走 web-admin 工厂配置页开关）
开启后报工的计划须有状态 ∈ {TRANSFERRED, ISSUED, IN_USE} 且覆盖被消耗物料的领料单，否则拦截。
验证：不走领料直接报工 → 被拦；走完领料 → 放行。

---

## 回滚表（每步独立回滚，backward-safe）

| 步骤 | 回滚 |
|---|---|
| 1 仓库 purpose | `DELETE /api/mobile/LIUSHANMEN/factory/warehouse-defaults/PURCHASE_INBOUND_DEFAULT` → fallback WH-LOG |
| 2 校正盘点 | 盘点单可撤销（撤销小结机制，含悲观锁防并发双撤）；凭证红冲 |
| 3 培训 | — |
| 4 领料 gate | `PUT .../settings {"requireRequisitionBeforeReport": false}` → 报工回宽松校验 |

---

## 激活会产出物（Fable review 核心主张）

> 本周最重要的产出物**不是 PR，是一次 LIUSHANMEN 现场/远程激活会 + 一次历史库存校正盘点**。系统已经比客户使用深度稳很多了；杠杆在"让张权的仓管每天真的在系统里确认领料"，不在第 N 轮工程完善。

**激活会实际只剩 3 件事**（步骤 0/1 已验证完成）：**步骤 2 校正盘点（客户配合实盘，大概率轻）→ 步骤 3 培训 → 步骤 4 开 gate**。会后：观察 1-2 周真实使用 → 回读 6-09 的 103 模块 roadmap 定下一个 P1（由客户反馈驱动，不猜）。
