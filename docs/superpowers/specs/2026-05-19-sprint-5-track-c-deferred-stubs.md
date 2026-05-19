# Sprint 5 Track C — Deferred Items Backlog (C-1 + C-4)

**Status**: Sprint 5 Track C bundle MVP shipped C-2 (linkcounter) + C-3 spec.
C-1 + C-4 deferred to Sprint 6 with these stubs.

**Sprint 5 plan ref**: `docs/superpowers/plans/2026-05-19-sprint-5-dispatch.md` §C

---

## C-1: G12-9 报价试算 (Quote Trial Calc)

**Source**: Round 12 §A.2 X1 (`宏见竞品分析/04-最终决策/32-DEEP-RE-AUDIT-V2.md:133-134`) — HJ 销售模块 `报价试算 20190809172755012`. Round 11 漏抓.

**HJ business model**:
> 客户先试算后正式报价 — 销售员不必每次都创建正式 quotation entity, 可即时算
> 1 张试算 + 利润预估, 给客户报口头价

**Cretas gap**: `OperationalQuoteController.java` 现有 OperationalQuote entity (正式
报价) 但**缺试算 endpoint** (no-entity, in-memory calculation, throwaway).

### MVP scope (3d nominal, Sprint 6 dispatch)

1. **Backend (1.5d)**:
   - 新 `QuotationCalculatorService.tryQuote(factoryId, request)` (in-memory,
     no persist)
   - Request DTO: `{ customerId?, productLines: [{ productTypeId, qty, unit,
     discount? }] }`
   - Response DTO: `{ items: [{ name, unitPrice, subtotal }], totalAmount,
     estimatedProfit, profitMargin, alertLevel }`
   - 价格源: CustomerPriceHistory → ProductType.standardPrice → fallback 0
   - 成本源: BomSnapshot.standardCost × qty (depth-first 递归)
   - 利润算法: `estimatedProfit = totalAmount - totalCost`,
     `profitMargin = estimatedProfit / totalAmount * 100` (`Decimal.quantize`
     ROUND_HALF_UP)
   - Controller: `POST /sales/quotes/try-calc` (`sales:read_write` perm)

2. **Frontend (1d)**:
   - 销售模块顶部 Quick Action: "试算" button (next to "新增报价")
   - Dialog: customer dropdown (optional) + product table (add/remove rows)
     + result panel (read-only totalAmount + estimatedProfit + chip "毛利率
     X%")
   - 实施 fool-proof Rule 2 (context — title 必含客户名 if 选 customer)

3. **Test (0.5d)**:
   - JUnit: 3 scenarios (1 product / N products / customer-specific pricing
     applied)
   - E2E happy path: 选客户 → 加 2 product line → 显结果 → 关闭

### DOD

销售员可不创建正式 quotation, 即时算 1 张试算返客户口头价 (含利润预估 + 毛利率
chip). 试算不落库, dialog 关闭即丢.

### 依赖

- `CustomerPriceHistory` (#841 ship)
- `BomSnapshot` (Sprint 3 H ship)
- `OperationalQuote` 仅参考 (不复用 — 试算无 entity)

---

## C-4: G12-10 采购需求总表 entry verify (cross-ref Z-2)

**Source**: Round 12 §A.3 X1 — HJ 采购模块 "采购需求总表" entry.

**Z-2 task** (Sprint 5 Pre-Spike): grep Cretas main 是否已实装 entry.
Round 11 N31 S-MRP-1 PR #682 已 ship `ShortageAnalysisService`. **Verify**: entry
跟 HJ 实测 entry 一致?

### Verify steps (1d, Sprint 6 dispatch — Z-2 已 spec, 这里仅 cross-ref)

1. **Backend grep** (15min):
   - `find backend/java -name "*Shortage*" -o -name "*MaterialRequirement*"`
   - Read `ShortageAnalysisService.java` + 关联 controller
   - 列出 endpoint paths

2. **Frontend grep** (15min):
   - `find web-admin/src/views/procurement -name "*shortage*" -o -name
     "*material-requirement*"`
   - 检查路由 `web-admin/src/router/index.ts` 是否含 `mrp-shortage` 或类似 entry

3. **Compare to HJ** (30min):
   - HJ 实测 entry (per Round 12 §A.3) 在采购模块下, sub-menu 名"采购需求总表"
   - Cretas 现有 entry 名 = ?, route path = ?, scope = (是否含 BOM 递归展开?)
   - Outcome: ✅ entry 一致 / ⚠️ entry 名 mismatch (建议 rename) / ❌ entry 缺
     (建议加 frontend route)

4. **Output** (1h):
   - 单 PR with 1 行 frontend route fix 如缺 + screenshot
   - 或 verify-only doc 如已 ship — 1 行 PR 补 doc 引 PR #682 link

### DOD

Cretas 采购模块 "采购需求总表" entry 跟 HJ baseline 一致 (entry 名 / route
path / scope 三对齐), 或一行 PR 修补 mismatch.

### 依赖

- `ShortageAnalysisService` (PR #682 ship)
- Sprint 5 Z-2 task spec (per `docs/superpowers/plans/2026-05-19-sprint-5-dispatch.md:38-43`)

---

## Sprint 6 dispatch suggestion

| Track | Item | 工时 | 优先级 | 备注 |
|---|---|---|---|---|
| C-1 (Sprint 6) | 报价试算 | 3d | P1 | 跟 #841 CustomerPriceHistory 配合 |
| C-4 (Sprint 6) | 采购需求总表 verify | 1d | P2 | Z-2 已 spec, 短任务 |
| C-3 follow-up | 称重 v2 enrich | 3d | P1 | per print-categories-coverage spec |
| C-3 follow-up | 凭证模板 PRINT_VOUCHER | 2d | P1 | 大客户场景 |
| C-3 follow-up | 发票模板 PRINT_INVOICE | 2d | P1 | 跟 Track B F-TAX-DIRECT-1 配合 |

Bundle 5 items ≈ 11d, 适合 Sprint 6 1 dev 2 周完成 or 2 chat 并行 1 周.

---

**作者**: Sprint 5 Track C agent
**日期**: 2026-05-19
