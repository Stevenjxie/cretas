# LIUSHANMEN 客户激活 Checklist（生产领料闭环 + 仓库路由）

**日期**: 2026-07-03
**触发**: Fable 5 战略纠偏 review 判定 —— 系统层面生产闭环已通（F006 测试租户 23/23 逐克对上），但**付费客户 LIUSHANMEN 租户上这套模型还没激活**：领料 gate 默认关、仓库 purpose 未配、修复前造出的历史幽灵库存未校正。**功能 shipped ≠ 客户在用。** 本 checklist 是把已上线的能力在真客户租户上"打开开关 + 建正账 + 培训"的可执行 runbook。
**目标租户**: **LIUSHANMEN**（客户实际运营租户，**不是 F006** —— F006 是测试租户）。执行前先确认 LIUSHANMEN 的 `factoryId` 字符串。
**红线**: 这是真客户真实数据。默认绝不删/碰无关数据；所有动作可回滚（见末尾回滚表）；建账窗口期，历史库存校正一次做对。

---

## ✅ 前提已验证（verify-first，2026-07-03 recon）

激活**无代码前置**。之前 memory/规划里那颗"仓库拆分 Part A 半拆、销售 4 写路径没 repoint、藏着雷"是**规划意图残影，不是代码现实**。git 铁证：

- **Part A 在单个提交 #1161 里一次性完成**：三个独立 purpose（`PURCHASE_INBOUND_DEFAULT` / `SALES_OUTBOUND_DEFAULT` / `PRODUCTION_RAW_DEFAULT`）+ 三个独立 resolver，同 PR 把采购/销售/生产三侧调用点**全部 repoint**。没有"只配 PURCHASE 半拆"的中间状态。
- **#1176** 进一步把销售 blank 来源仓 3 个写路径（recommend/allocate/deduct）**彻底脱离任何单一默认仓 resolver**，改成跨全部可售仓（排 WH-RD）。
- **结论**：给 LIUSHANMEN 配 `PURCHASE_INBOUND_DEFAULT=WH-RAW` 对销售**零影响**——采购落 raw 到 WH-RAW，销售发的是成品 FG（跨可售仓发现），两个数据集不相交。**雷是幻影，早拆了。**

> 权威分支 = `origin/main`（prod 部署源）。上述均在 origin/main + 已由 7-03 campaign 部署 prod（#1161/#1174/#1176/#1177 + 迁移 `V20261027_30/32`）。

---

## 🔒 本次激活锁定的决策（Steve 2026-07-03 拍板）

| 决策 | 拍板 |
|---|---|
| 历史幽灵库存处理 | **实盘为准、2A 一次校正**：客户现场实盘 → 系统幽灵库存盘亏冲平 → 期初按实盘坐实 |
| 校正盘点凭证科目 | **1403 原料 / 4001 实收资本** |
| 领料 gate 开启时点 | **激活会当天、培训完立即生效**（不早开，避免挡住未培训的仓管） |

---

## 步骤（依赖顺序，不可跳序）

### 步骤 0 — Pre-flight：核实 prod 真跑着修复码（belt-and-suspenders）

激活前确认 LIUSHANMEN prod 后端（47:10010，从 main 部署）确实含这些能力：

```bash
# 1) 枚举含 6 个 purpose（尤其 PURCHASE_INBOUND_DEFAULT）
ssh root@47.100.235.168 "unzip -p /www/wwwroot/cretas/aims-0.0.1-SNAPSHOT.jar \
  'BOOT-INF/classes/com/cretas/aims/entity/factory/WarehouseDefaultPurpose.class' | strings | grep -c PURCHASE_INBOUND_DEFAULT"   # 期望 ≥1

# 2) 领料 gate 列存在
psql -h localhost -U cretas_user -d cretas_prod_db -c \
  "SELECT column_name FROM information_schema.columns WHERE table_name='factory_settings' AND column_name='require_requisition_before_report';"   # 期望 1 行

# 3) 迁移已跑
psql ... -c "SELECT version FROM flyway_schema_history WHERE version LIKE 'V20261027%' ORDER BY installed_rank DESC LIMIT 5;"
```

任一不满足 → **停止激活**，先从 main 部署 prod 补齐（`git checkout main && git pull && ./scripts/deploy/deploy-backend.sh --env prod`），再继续。

---

### 步骤 1 — 配 LIUSHANMEN 仓库 purpose

**目的**：采购入库把原料落到主原料仓（而非 fallback WH-LOG）。

1. 查 LIUSHANMEN 的原料主仓 id：
   ```
   GET /api/mobile/{LIUSHANMEN}/factory/warehouses     # 找 code=WH-RAW（或客户命名的"原料仓/主仓"）的那条，记下其 id (UUID)
   ```
2. 配置（`factory_super_admin` 权限，多租户安全：仓必须属本厂）：
   ```
   PUT /api/mobile/{LIUSHANMEN}/factory/warehouse-defaults
   Body: { "purpose": "PURCHASE_INBOUND_DEFAULT", "warehouseId": "<WH-RAW 的 UUID>" }
   ```
   （或走 web-admin 配置 UI：仓库默认配置面板，按 purpose 分组的下拉）
3. 验证：查 `GET .../factory/warehouse-defaults` 返回该行；做一笔采购入库确认 → 批次落 WH-RAW。

> 可选：`PRODUCTION_RAW_DEFAULT` / `SALES_OUTBOUND_DEFAULT` 一般不需要配（fallback WH-LOG + #1176 销售跨可售仓已够）。只有客户明确要求"报工默认从某仓领 / 销售 UI 默认列某仓批次"才配。

---

### 步骤 2 — 历史库存校正盘点 ⭐（实盘为准、2A 一次校正）

**目的**：清掉修复前造出的幽灵库存，把期初按客户实盘坐实。**客户正在建账，这是把账建对的窗口期，越早越好。**

1. 客户现场/配合做一次**全量实物盘点**（原料 + 半成品 + 成品）。
2. 用 **盘点批量导入工具**（7-02 已上线，含期初）把实盘数录入：
   - 系统账面幽灵库存 > 实盘 → 差额**盘亏冲平**
   - 期初数量 = **实盘数**（坐实）
3. 校正盘点过账凭证：**1403 原料 / 4001 实收资本**（按拍板科目）。
4. 验证：盘点后 `库存 = 实盘`；查无残留幽灵批次；凭证生成正确。

> ⚠️ #1167 幽灵库存修复是 **forward-only**（只防未来产生），历史脏数据必须靠这一步校正——不做则期初带着假库存，后续成本/出成率全歪。

---

### 步骤 3 — 仓管操作培训

gate 开启前**必须**完成，否则 gate 一开挡住没培训的仓管日常报工。培训内容：

- **领料确认流程**：生产计划 → 生成领料单 → 拣货（start-picking）→ 确认领料（confirm-picking）→ 料到车间仓（transfer）。端点在 `FactoryMaterialRequisitionController`。
- **报工来源仓选择**：料到车间仓后从车间仓报工消耗。
- **收货 / 盘点** 日常操作。
- 客户原话铁律（fool-proof）："做仓管的年纪大、文化素质低，你告诉他要收多少就行" → 演示时强调系统会带出该收/该领多少，仓管只需确认。

---

### 步骤 4 — 开领料 gate（激活会当天、培训后）

**目的**：实现客户要的"仓管没确认领料，生产不能报工"。

```
PUT /api/mobile/{LIUSHANMEN}/settings
Body: { "requireRequisitionBeforeReport": true }
```
- factory 级布尔，默认 false（opt-in）。partial-update：只有该字段被显式传时才应用。
- 也可走 web-admin 工厂配置页的对应开关。
- 开启后 `ProcessSheetServiceImpl.ensureRawMaterialWarehouse` 会强制：报工的计划须有状态 ∈ {TRANSFERRED, ISSUED, IN_USE} 且覆盖被消耗物料的领料单，否则拦截报工。
- 验证：不走领料直接报工 → 被拦（提示先确认领料）；走完领料 → 报工放行。

---

## 回滚表（每步可独立回滚，backward-safe）

| 步骤 | 回滚 |
|---|---|
| 1 仓库 purpose | `DELETE /api/mobile/{factoryId}/factory/warehouse-defaults/PURCHASE_INBOUND_DEFAULT` → resolver 回退 fallback WH-LOG（现状） |
| 2 校正盘点 | 盘点单本身可撤销（撤销小结机制，7-01 上线，含悲观锁防并发双撤）；凭证红冲 |
| 3 培训 | — |
| 4 领料 gate | `PUT .../settings { "requireRequisitionBeforeReport": false }` → 报工回到宽松校验（现状） |

---

## 激活会产出物（Fable review 的核心主张）

> 本周最重要的产出物**不是 PR，是一次 LIUSHANMEN 现场/远程激活会 + 一次历史库存校正盘点**。系统已经比客户使用深度稳很多了；下一步的杠杆在"让张权的仓管每天真的在系统里确认领料"，不在第 N 轮工程完善。

激活会 checklist：步骤 0（预检）→ 步骤 1（配仓）→ 步骤 2（校正盘点，客户配合实盘）→ 步骤 3（培训）→ 步骤 4（开 gate）。会后：观察 1-2 周真实使用，收集客户反馈 → 回读 6-09 的 103 模块 roadmap 定下一个 P1（大概率财务凭证链 + 人效对比，但由客户反馈驱动，不猜）。
