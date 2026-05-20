# Cretas vs HJ 打印模板分类 coverage report (Sprint 5 Track Z-4)

**日期**: 2026-05-19
**Owner**: Sprint 5 Track Z agent
**Status**: 📊 COVERAGE REPORT — Cretas 6 / HJ 21 ≈ 29%

---

## TL;DR

**Cretas Track-J (C-PRT-EDITOR-1 ship) 含 6 个系统预置打印模板 entity types, 跟 HJ 21 分类对比覆盖 ~29%. 缺 15 类**. F006 食品溯源刚需场景 (称重单) 已 ship, 大客户场景 (称重/序列号/装箱/静态/供应商协同) 部分覆盖.

---

## HJ baseline (Round 13 §13 verify)

**URL**: `https://print.hongjian.com/print/temp.jsp` (Round 13 新发现的 41+1 子域)

**21 分类** (Round 13 实测 left nav — 19 顶层 + 2 subcategories under #1):

| # | HJ 分类 | 大客户 / 食品行业重要性 |
|---|---|---|
| 1a | 客户模板 — 个人客户信息 | 通用 |
| 1b | 客户模板 — 公司客户信息 | 通用 |
| 2 | 销售模板 | 高 |
| 3 | 采购模板 | 高 |
| 4 | 仓库模板 | 高 |
| 5 | 财务模板 | 大客户高 |
| 6 | 委外模板 | 中 (外协场景) |
| 7 | 生产模板 | 高 |
| 8 | 人力资源模板 | 中 |
| 9 | 办公自动模板 | 低 |
| 10 | **外账模板** ⭐ | 大客户 (外部账目格式) |
| 11 | 产品模板 | 通用 |
| 12 | 售后服务模板 | 中 |
| 13 | **称重模板** ⭐ | **F006 食品行业刚需 (N13 W-ABA-1 抄码品)** |
| 14 | 装箱模板 | 食品 + 物流 |
| 15 | 合作伙伴模板 | 中 |
| 16 | **序列号模板** ⭐ | 大客户 (机器设备) |
| 17 | 门店模板 | 餐饮场景 |
| 18 | **静态模板** ⭐ | 灵活补 (任意字段) |
| 19 | **供应商协同** ⭐ | 大客户供应链 |
| (subcategories under 个人/公司客户 makes 21 total) | | |

---

## Cretas 现状 (audit 2026-05-19)

### Seed file: `V20260603_09__seed_print_templates.sql` (Track-J Sprint 3)

| # | entity_type | Cretas 模板名 | HJ 对应 |
|---|---|---|---|
| 1 | `PRINT_SALES_ORDER` | 销售单-默认 | #2 销售模板 ✅ |
| 2 | `PRINT_PURCHASE_ORDER` | 采购单-默认 | #3 采购模板 ✅ |
| 3 | `PRINT_QUOTATION` | 报价单-默认 | #2 销售模板 (报价是销售子项) ⚠️ partial |
| 4 | `PRINT_PRODUCTION_TASK` | 生产任务单-默认 | #7 生产模板 ✅ |
| 5 | `PRINT_MATERIAL_REQUISITION` | 领料单-默认 | #7 生产模板 (子项) ⚠️ partial |
| 6 | **`PRINT_WEIGHING_SLIP`** | **称重单-默认 ⭐** | **#13 称重模板 ✅ (F006 刚需直击)** |

**Cretas 实装数**: 6 模板, 覆盖 **HJ 3 顶层分类** (销售/采购/生产) + 1 关键 ⭐ 分类 (称重).

---

## Coverage 矩阵

| HJ 分类 | Cretas 覆盖 | 缺失说明 |
|---|---|---|
| 1a 个人客户 | ❌ | 缺 `PRINT_CUSTOMER_INDIVIDUAL` |
| 1b 公司客户 | ❌ | 缺 `PRINT_CUSTOMER_CORPORATE` |
| 2 销售模板 | ⚠️ 50% | 有 SALES_ORDER + QUOTATION, 缺 出货/发货单 / 退货单 |
| 3 采购模板 | ⚠️ 30% | 有 PURCHASE_ORDER, 缺 询价单 / 收货单 / 入库单 |
| 4 仓库模板 | ❌ | 缺 `PRINT_STOCK_TRANSFER` / `PRINT_INVENTORY_REPORT` / `PRINT_WAREHOUSE_DOC` |
| 5 财务模板 | ❌ | 缺 `PRINT_INVOICE` / `PRINT_VOUCHER` / `PRINT_RECEIPT` |
| 6 委外模板 | ❌ | 缺 `PRINT_OUTSOURCING_ORDER` |
| 7 生产模板 | ⚠️ 40% | 有 PRODUCTION_TASK + MATERIAL_REQUISITION, 缺 BOM 打印 / 工序单 |
| 8 HR 模板 | ❌ | 缺 `PRINT_PAYROLL` / `PRINT_EMPLOYEE_CARD` |
| 9 办公自动 | ❌ | 缺 `PRINT_LEAVE_FORM` / `PRINT_TRIP_FORM` |
| 10 外账模板 ⭐ | ❌ | 大客户外账格式 (P2) |
| 11 产品模板 | ❌ | 缺 `PRINT_PRODUCT_CARD` / `PRINT_SKU_LABEL` |
| 12 售后服务 | ❌ | 缺 `PRINT_SERVICE_TICKET` |
| 13 **称重模板** ⭐ | ✅ 100% | F006 N13 W-ABA-1 配套 ship |
| 14 装箱模板 | ❌ | 食品 + 物流场景缺 `PRINT_PACKING_SLIP` |
| 15 合作伙伴 | ❌ | 缺 `PRINT_PARTNER_DOC` |
| 16 序列号模板 ⭐ | ❌ | 大客户机器设备缺 `PRINT_SERIAL_NO` |
| 17 门店模板 | ❌ | 餐饮场景缺 `PRINT_STORE_DOC` |
| 18 静态模板 ⭐ | ❌ | 灵活补全任意字段缺 `PRINT_STATIC_*` |
| 19 供应商协同 ⭐ | ❌ | 大客户供应链缺 `PRINT_SUPPLIER_COLLAB` |

**总体覆盖**: 6/21 = **29%** 直接对应 / 4/21 = 19% 完整覆盖 / 2/21 = 9% partial (报价/领料分别归到销售/生产 顶层).

---

## Gap 优先级

### P0 (Sprint 5 必补 — Steve sign-off "F006 刚需"):

| 分类 | Cretas entity_type | 工时 | 备注 |
|---|---|---|---|
| #4 仓库模板 (出入库单) | `PRINT_STOCK_IN` / `PRINT_STOCK_OUT` | 2d | F006 仓管员每日打 |
| #5 财务模板 (发票/凭证) | `PRINT_INVOICE` + `PRINT_VOUCHER` | 2d | 大客户必有 |
| #14 装箱模板 | `PRINT_PACKING_SLIP` | 1d | 食品物流刚需 |

**P0 小计**: 5d (Sprint 5 Track C-3 已规划 4d, 微调到 5d 即可)

### P1 (Sprint 6+):

| 分类 | 工时 | 备注 |
|---|---|---|
| #10 外账模板 ⭐ | 1.5d | 大客户合同要求 |
| #16 序列号模板 ⭐ | 2d | 设备类大客户 |
| #18 静态模板 ⭐ | 3d (复杂 — 含任意字段拖拽) | 可用现有 PrintTemplateEditor 替代 |
| #19 供应商协同 ⭐ | 2d | 大客户供应链 |
| #2 销售模板补全 (出货/退货) | 2d | |
| #3 采购模板补全 (询价/收货) | 2d | |
| #7 生产模板补全 (BOM/工序) | 2d | |

**P1 小计**: ~14.5d (Sprint 6+ 中)

### P2 (Sprint 7+ 或永不):

| 分类 | 工时 | 备注 |
|---|---|---|
| #1a/1b 客户模板 | 1d | UX 可用 customer detail PDF 替代 |
| #6 委外模板 | 1d | 客户少见 |
| #8 HR 模板 (工资条/工卡) | 1.5d | 通用 |
| #9 办公自动 (请假/出差) | 1.5d | 通用 |
| #11 产品模板 | 1d | 通用 |
| #12 售后服务 | 1d | 少见 |
| #15 合作伙伴 | 1d | 少见 |
| #17 门店模板 | 1d | 仅餐饮 |

**P2 小计**: ~9d (defer)

---

## Sprint 5 Track C-3 brief 调整建议

**原 Track C-3** (Sprint 5 dispatch §C):
> C-3: 打印模板 21 分类 + 称重模板 (4d, 含 L13-6 verify)
> - 加 **称重模板** (L13-8, F006 N13 W-ABA-1 抄码品配合 P1 3d)
> - 加 **静态模板** (L13-7 P3 2d, 可选)

**Z-4 audit 后修正建议**:
> C-3 改为 **3 个 P0 模板补 (5d)**:
> 1. **PRINT_STOCK_IN / PRINT_STOCK_OUT** (仓库出入库 2d) ← P0 补 #4
> 2. **PRINT_INVOICE / PRINT_VOUCHER** (财务发票/凭证 2d) ← P0 补 #5
> 3. **PRINT_PACKING_SLIP** (装箱 1d) ← P0 补 #14
>
> - 称重模板 **已 ship** (Z-4 verify), 从 Track C-3 移除 (不重复)
> - 静态模板 (#18) 是 P3 复杂工程, 不在 Sprint 5 做
> - 21 分类全覆盖 Sprint 6+ 渐进式

---

## File:line evidence

| 主题 | 文件 | 行 | 现状 |
|---|---|---|---|
| Cretas 6 seed | `V20260603_09__seed_print_templates.sql` | 1-185 | 6 PRINT_* templates |
| Cretas FormTemplate entity | `FormTemplate.java` (config/) | 39-66 | entityType String (非 enum), `factoryId=null` = 系统级 |
| Track-J 3-pane editor | (Sprint 3) | — | PrintTemplateEditor.vue exists, 客户可自定义 |
| HJ 21 分类 | `33-DEEP-RE-AUDIT-V3-Layer-BC.md` | 310-340 | Round 13 实测 |
| Round 13 backlog | `33-doc` | 337-340 | C-PRT-STATIC-1 + C-PRT-WEIGHING-1 (后者已 ship) |

---

## Decision summary

- **Z-4 → 📊 COVERAGE REPORT delivered**
- Cretas 6 templates, ~29% HJ baseline coverage
- Track C-3 brief 调整为 **3 个 P0 补 (5d)** — 仓库/财务/装箱
- 称重已 ship, 静态 defer Sprint 6+
- 21 分类全覆盖 → Sprint 6+ ~15d 工程

---

**Sign-off**: Track Z agent, 2026-05-19
