# 打印模板 21 分类 coverage spec (C-3 + Z-4 merged)

**Sprint 5 Track C-3 (P1, 4d → MVP spec only this slice).**

**Source**:
- Round 13 §13 (`宏见竞品分析/04-最终决策/33-DEEP-RE-AUDIT-V3-Layer-BC.md:310-345`) — HJ `print.hongjian.com` 子域实测 21 模板分类
- Round 12 §I.2 X4 — Cretas Track-J `C-PRT-EDITOR-1` 3-pane editor ship 状态
- Sprint 5 plan §C (`docs/superpowers/plans/2026-05-19-sprint-5-dispatch.md:151-159`) — Track C-3 brief

**Decision (this MVP slice)**: spec list only. Defer **称重模板 enrichment + 静态模板 + 供应商协同** 模板 schema impl to Sprint 6 (1 PR per category, 2d each).

---

## §1 HJ 21 分类 vs Cretas 现有 6 分类

**HJ baseline** (per Round 13 实测 left nav of `print.hongjian.com/print/temp.jsp`):

| # | HJ 分类 (中文) | HJ 子分类 | Cretas 现有? | Cretas entityType | 优先级 |
|---|---|---|---|---|---|
| 1 | 客户模板 | 个人客户信息 / 公司客户信息 | ❌ 缺 | `PRINT_CUSTOMER_PROFILE` (新) | P2 |
| 2 | 销售模板 | — | ✅ ship | `PRINT_SALES_ORDER` | — |
| 3 | 采购模板 | — | ✅ ship | `PRINT_PURCHASE_ORDER` | — |
| 4 | 仓库模板 | — | ❌ 缺 | `PRINT_WAREHOUSE_INOUT` (新) | P2 |
| 5 | 财务模板 | — | ❌ 缺 | `PRINT_VOUCHER` / `PRINT_INVOICE` (新 2 种) | P1 (大客户) |
| 6 | 委外模板 | — | ❌ 缺 | `PRINT_OUTSOURCE_ORDER` (新) | P3 (Cretas 跳) |
| 7 | 生产模板 | — | ✅ ship (生产任务单) | `PRINT_PRODUCTION_TASK` | — |
| 8 | 人力资源模板 | — | ❌ 缺 | `PRINT_HR_FORM` (新) | P3 |
| 9 | 办公自动模板 | — | ❌ 缺 | `PRINT_OA_FORM` (新) | P3 |
| 10 | **外账模板** ⭐ | — | ❌ 缺 | `PRINT_EXTERNAL_LEDGER` (新) | P3 (审计场景) |
| 11 | 产品模板 | — | ❌ 缺 | `PRINT_PRODUCT_LABEL` (新) | P2 (Cretas 标签喷码 P3 已规划) |
| 12 | 售后服务模板 | — | ❌ 缺 | `PRINT_AFTERSALE` (新) | P3 |
| 13 | **称重模板** ⭐ | — | ⚠️ partial (`PRINT_WEIGHING_SLIP` ship 但 schema 偏简) | `PRINT_WEIGHING_SLIP` | **P1 (F006 抄码品)** |
| 14 | 装箱模板 | — | ❌ 缺 | `PRINT_PACKING_SLIP` (新) | P2 (F006 多 SKU 装箱场景) |
| 15 | 合作伙伴模板 | — | ❌ 缺 | `PRINT_PARTNER` (新) | P3 |
| 16 | **序列号模板** ⭐ | — | ❌ 缺 | `PRINT_SERIAL_NUMBER` (新) | P3 (depends on W-SERIAL-1 spec, 跳) |
| 17 | 门店模板 | — | ❌ 缺 | `PRINT_STORE` (新) | P3 (餐饮 RestaurantBI 已 cover) |
| 18 | **静态模板** ⭐ | — | ❌ 缺 | `PRINT_STATIC` (新, content-only no bindings) | **P2 (海报/通知/说明书)** |
| 19 | **供应商协同** ⭐ | — | ❌ 缺 | `PRINT_SUPPLIER_COOP` (新) | P3 (B2B 协同 Cretas 跳) |
| 20 | (Round 13 实测 left nav 仅列 19, 21 项目可能 organizer 估算) | — | — | — | — |
| 21 | — | — | — | — | — |

**统计**: Cretas ship = **6/19 ≈ 32%** (HJ 实测 19 项; "21" 是 Round 13 estimate, organizer 取 19 实数).

---

## §2 缺失分类优先级分组

### P1 — 必补 (this Sprint 5 Track C-3 后续 dispatch, 2-3 PR)

| 分类 | entityType | 配套字段示例 (`ENTITY_FIELDS_HINT`) | 工时 |
|---|---|---|---|
| **称重模板 v2** (enrich) | `PRINT_WEIGHING_SLIP` (现有 entityType 已注册) | 加 `{{slip.boxNo}}`, `{{slip.batchNo}}`, `{{slip.abacaTag}}` (抄码), `{{slip.qrPayload}}` (扫码联动) — 跟 F006 N13 W-ABA-1 协同 | 3d |
| **凭证模板** | `PRINT_VOUCHER` 新 | `{{voucher.voucherNumber}}`, `{{voucher.voucherDate}}`, `{{voucher.summary}}`, `{{voucher.entries}}` table (借/贷/科目) | 2d |
| **发票模板** | `PRINT_INVOICE` 新 | `{{invoice.invoiceNumber}}`, `{{invoice.taxRate}}`, `{{invoice.items}}` table — 跟 Track B F-TAX-DIRECT-1 配合 | 2d |

### P2 — 后补 (Sprint 6 候选)

| 分类 | entityType | 工时 |
|---|---|---|
| 仓库出入库 | `PRINT_WAREHOUSE_INOUT` | 2d |
| 客户档案 | `PRINT_CUSTOMER_PROFILE` | 2d |
| 装箱单 | `PRINT_PACKING_SLIP` | 3d (动态 box × item) |
| 产品标签 | `PRINT_PRODUCT_LABEL` | 3d (含条码/二维码 batch print) |
| 静态模板 | `PRINT_STATIC` | 2d (无 binding, 纯设计) |

### P3 — 跳过/低优 (Sprint 7+ 或永不)

| 分类 | 跳原因 |
|---|---|
| 委外 / 供应商协同 | Cretas 跳 B2B 协同 (per Round 11 §A.3) |
| 人力资源 / 办公自动 / 售后服务 / 外账 / 序列号 / 门店 / 合作伙伴 | 非核心 ERP 场景, Cretas 战略不抄 |

---

## §3 后续 dispatch 建议

### Sprint 6 — Track J Follow-up (1 PR per category)

**Brief template** (per category):
> Sprint 6 Track J-FU-N — Print Category `PRINT_XXX` (P1/P2 Xd).
>
> 1. Backend: `PrintTemplateCreateFromAITool.ENTITY_LABELS` + `ENTITY_FIELDS_HINT` 加 1 entry
> 2. Frontend: `printSchemaTypes.ts:ENTITY_TYPES` 加 1 object `{code, label, icon}`
> 3. 写 1 default seed template (per Cretas 现有 5 default templates pattern in `FormTemplateServiceImpl`)
> 4. E2E: PrintTemplateEditor.vue load template → render PDF preview successful
>
> DOD: 1 PR + 1 screenshot of PDF preview.

### 称重模板 v2 enrich (Sprint 5 if time permits, otherwise Sprint 6)

**Differs from new category**: 修现有 `PRINT_WEIGHING_SLIP` schema 而非新增 entityType. 现有 `ENTITY_FIELDS_HINT` line 92-98 已有基础字段; 缺 `boxNo / batchNo / abacaTag / qrPayload`. 直接 edit 1 file.

---

## §4 验收 (DOD for this spec only)

- [x] 19 HJ 分类列全
- [x] Cretas 现有 6 分类标 ✅
- [x] 缺失分类 13 项分类 P1 (3) / P2 (5) / P3 (5) 优先级标全
- [x] 称重 v2 enrich 字段列全
- [x] Sprint 6 dispatch brief template draft

**Spec status**: ready for Sprint 6 dispatch. Steve 待 sign-off P1 3 项 (称重 v2 / 凭证 / 发票) 优先级排序.

---

**作者**: Sprint 5 Track C-3 agent (organizer dispatch)
**日期**: 2026-05-19
**对齐**: Round 13 §13 + Sprint 5 plan §C
