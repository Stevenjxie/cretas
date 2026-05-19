# Canvas-Pricing Phase 4b Implementation Spec

**Created**: 2026-05-18
**Phase**: 4b — Canvas-Pricing (价格策略)
**Status**: Skeleton SHIPPED — sister chat to fill `PricingEngineImpl` + Canvas UI + SalesService integration
**Vision parent**: `docs/superpowers/specs/2026-05-18-canvas-business-rule-engine-vision.md` §3.4
**Skeleton estimate**: ~75 min (this PR)
**Sister chat impl estimate**: 2-3 days

---

## 1. Scope

Skeleton PR delivers JPA entities + repos + REST controller + 5 AI Tools + 2 Flyway migrations + interface contracts. **All business logic is `throw new UnsupportedOperationException("Sister chat to implement")`** — sister chat fills `PricingEngineImpl`, builds Canvas tab UI, and flips 1 line in `SalesServiceImpl.createOrderLine` from hardcoded price to `pricingEngine.calculate(...)`.

5 strategy types from vision doc:

| Type | Code | Rule shape (JSON) |
|---|---|---|
| TIERED | 阶梯定价 | `{"tiers": [{"minQty": 0, "maxQty": 99, "discountPct": 0}, {"minQty": 100, "discountPct": 5}]}` |
| PROMOTION | 促销 (满减/限时) | `{"thresholdAmount": 50000, "discountAmount": 1000, "validFrom": "...", "validTo": "..."}` |
| MEMBER | 会员折扣 | `{"membershipTier": "VIP", "discountPct": 5, "customerRatingFilter": "A"}` |
| BUNDLE | 套餐价 | `{"items": [{"productId": "X", "qty": 2}, {"productId": "Y", "qty": 1}], "bundlePrice": 18000}` |
| CYCLE | 跨周期返点 | `{"cycle": "MONTH", "tiers": [{"minAmount": 100000, "rebatePct": 3}]}` |

---

## 2. Schema (V20260623_01 + V20260623_02)

### `pricing_strategies`

| Column | Type | Notes |
|---|---|---|
| id | UUID PK | `gen_random_uuid()` |
| factory_id | VARCHAR(50) NOT NULL | tenant key |
| strategy_code | VARCHAR(100) NOT NULL | unique per factory (business key) |
| strategy_name | VARCHAR(255) | display name |
| strategy_type | VARCHAR(20) NOT NULL | TIERED / PROMOTION / MEMBER / BUNDLE / CYCLE |
| scope_filter_json | JSONB DEFAULT `{}` | `{"productCategories": ["..."], "customerGroups": ["..."], "regions": ["..."]}` |
| rules_json | JSONB DEFAULT `{}` | type-specific shape per §1 |
| priority | INT NOT NULL DEFAULT 100 | lower number = higher priority |
| enabled | BOOLEAN NOT NULL DEFAULT TRUE | quick toggle |
| valid_from | DATE | NULL = no lower bound |
| valid_to | DATE | NULL = no upper bound |
| created_at / updated_at / deleted_at | TIMESTAMP | BaseEntity audit |

Unique `(factory_id, strategy_code)`. Partial index `idx_pricing_strategies_factory_active` on `(factory_id, enabled, valid_from, valid_to, priority) WHERE deleted_at IS NULL` for hot path.

### `pricing_application_logs` (audit trail)

| Column | Type | Notes |
|---|---|---|
| id | UUID PK | |
| strategy_id | UUID FK | NULL allowed (e.g. no-match log row) |
| factory_id | VARCHAR(50) NOT NULL | |
| business_entity_type | VARCHAR(50) | e.g. `SO_LINE`, `QUOTE_LINE` |
| business_entity_id | VARCHAR(50) | e.g. SO_LINE_ID |
| original_price | NUMERIC(15,2) | unit price before strategies |
| final_price | NUMERIC(15,2) | unit price after all strategies |
| discount | NUMERIC(15,2) | `original_price - final_price` (positive = discount applied) |
| applied_strategies | JSONB DEFAULT `[]` | list of `{strategyId, strategyCode, type, discountApplied}` |
| applied_at | TIMESTAMP | |

Partial index on `(factory_id, business_entity_id, applied_at DESC) WHERE deleted_at IS NULL`.

---

## 3. PricingEngine.calculate flow (for sister chat)

```
calculate(PricingRequest req) -> PricingResult:
  1. Fetch active strategies:
       repo.findByFactoryIdAndEnabledTrueAndValidFromBeforeAndValidToAfter(
            req.factoryId, today, today, OrderByPriorityAsc)
  2. Filter by scope_filter_json (productCategories ∋ req.productCategory,
     customerGroups ∋ req.customerGroup, regions ∋ req.region)
  3. Group by strategyType. Within type, pick BEST (intra-type best):
       - TIERED: highest qualifying tier
       - PROMOTION: largest discount where threshold met
       - MEMBER: best for customer's tier
       - BUNDLE: matches if cart contains all items (sister chat: requires multi-line context)
       - CYCLE: requires customer cumulative — sister chat decides Day1 vs Day14 impl
  4. Inter-type stackability rules (sister chat decides):
       - default: MEMBER + (best of TIERED|PROMOTION|BUNDLE) is stackable
       - CYCLE applied as month-end rebate, NOT inline on SO line
       - flag in PricingStrategy.rulesJson: "stackableWith": ["MEMBER"]
  5. Compute finalPrice = unitPriceList - sum(applicable discounts), guard final >= 0.
  6. Warnings:
       - if finalPrice < cost (cost looked up from product master, sister chat impl) →
         warning "final price ¥X below cost ¥Y" — DO NOT BLOCK (per spec rule)
       - if discount > 50% → warning "deep discount"
  7. Persist PricingApplicationLog row (one row per business_entity_id).
  8. Return PricingResult.
```

`simulate(...)` does steps 1-7 but skips step 7 (logging) — preview mode.

---

## 4. Fool-proof rule (核心: per .claude/rules/fool-proof-design.md Rule 1)

**Customer's pain (六腾门 F006)**: sales rep on the line doesn't know if quoted price is below cost — they "winged it" and the company lost money on 3 SOs last month.

**Fix in PricingEngine**:
- Computed `finalPrice` < product master `cost` → **`PricingResult.warnings`** gets entry, **frontend renders red banner** but **submit still allowed** (sales manager can override).
- Audit log captures every warning (so finance can audit cost-violation SOs monthly).
- DO NOT throw exception or 4xx — the customer explicitly said "warning, not block".

Canvas UI (sister chat) shows simulate() result inline as user types qty/customer → user sees final price + any warnings BEFORE submitting (Rule 1: 预先显示边界).

---

## 5. Canvas "价格策略" Tab (sister chat to build)

```
[Canvas Editor → 模块: 销售订单 → Tab: 价格策略 (新增)]

  [+ 新建策略] [▼ 按类型筛选] [▼ 启用状态]

  ┌──────────────────────────────────────────────┐
  │ ⓘ TIERED  叮咚阶梯折扣            🟢 启用  │
  │   优先级 50 · 商品类目: 冻品 · 客户: 叮咚组 │
  │   规则: 100kg → 3%, 500kg → 5%              │
  │   有效期: 2026-05-01 → 2026-12-31           │
  │   [编辑] [模拟] [禁用] [删除]                │
  └──────────────────────────────────────────────┘

  ┌──────────────────────────────────────────────┐
  │ 🎉 PROMOTION  满 5 万减 1 千        🟢 启用   │
  │   优先级 80 · 全商品 · 全客户                │
  │   ...                                         │
  └──────────────────────────────────────────────┘

  [▶ AI: "叮咚月采购超 10 万给 5%"  →  preview → 应用]
```

Editor sketch: priority slider 1-200 (lower = higher), strategy type picker → unfolds matching form, scope filter chips, validity date range, simulate button (POST /simulate with mock request).

---

## 6. AI Tools (5 tools, all skeleton)

| Tool | Action | Description shown to LLM |
|---|---|---|
| `pricing_strategy_create` | WRITE | "创建价格策略 (阶梯/促销/会员/套餐/跨周期返点)" |
| `pricing_strategy_update` | UPDATE | "修改价格策略的规则/优先级/有效期" |
| `pricing_strategy_toggle` | UPDATE | "启用/禁用价格策略" |
| `pricing_strategy_list` | READ | "查询当前生效的价格策略" |
| `pricing_test_calculate` | READ (simulate) | "模拟某 SKU/客户/数量场景的成交价" |

Sister chat impl: bind intent codes after Tool name in DB:
```sql
INSERT INTO ai_intent_config (intent_code, intent_name, tool_name, ...)
VALUES ('PRICING_STRATEGY_CREATE', '创建价格策略', 'pricing_strategy_create', ...);
```

---

## 7. SalesServiceImpl integration (sister chat: 1-line change)

Current `SalesServiceImpl.createOrderLine` (or `addLine`) has:
```java
line.setUnitPrice(productMaster.getListPrice());   // hardcoded
```

Sister chat replaces with:
```java
PricingResult pricing = pricingEngine.calculate(
    PricingRequest.builder()
        .factoryId(factoryId)
        .productId(line.getProductId())
        .quantity(line.getQuantity().intValue())
        .unitPriceList(productMaster.getListPrice())
        .customerId(order.getCustomerId())
        .businessEntityType("SO_LINE")
        .businessEntityId(line.getId())
        .build()
);
line.setUnitPrice(pricing.finalPrice());
line.setOriginalPrice(pricing.originalPrice());   // new column if not exists
line.setPricingWarnings(pricing.warnings());      // optional, expose in UI
```

---

## 8. Acceptance criteria

1. `mvn clean compile` BUILD SUCCESS
2. Two Flyway migrations apply cleanly on test PG (sister chat verifies)
3. `GET /api/mobile/{factoryId}/pricing/strategies` returns 200 + `{success: true, data: []}` (empty list, no impl)
4. `POST /api/mobile/{factoryId}/pricing/strategies` with valid body → row created in `pricing_strategies`, returns saved entity
5. 5 AI Tools register in `ToolRegistry` (skeleton, throw UnsupportedOp on invoke)
6. `PricingEngine` interface + skeleton impl present (sister chat fills)
7. Sister chat 2-3 day estimate includes: PricingEngineImpl (5 strategy types apply logic), Canvas Tab UI, 1-line SalesServiceImpl flip, unit tests (≥10 cases covering 5 types + fool-proof warnings), E2E (depth-first-e2e skill).

---

## 9. NOT in this PR (sister chat scope)

- Pricing impl for 5 strategy types
- Inter-type stackability rules logic
- BUNDLE multi-line cart context (touch SalesService for cart-aware lookups)
- CYCLE month-end rebate scheduled job
- Canvas UI tab + simulate dialog
- Frontend warning banner in SO line editor
- SalesServiceImpl 1-line change (per `concurrent-edit-safety.md` Rule 1, skeleton chat keeps it touch-free)
- Tests (unit / integration / E2E)
- AI intent rows in `ai_intent_config` for the 5 Tools

---

## 10. Open questions for sister chat

1. Where to read `cost` for fool-proof warning? `RawMaterialMovingAverage` table? `product_master.standard_cost`?
2. CYCLE strategy: inline credit OR end-of-month rebate-as-credit-note?
3. BUNDLE matching: exact qty match or "contains at least"?
4. Stackability priority — does MEMBER stack on top of TIERED, or take max?
5. `customerGroup` resolution — `customer.tier` or new `customer_groups` table?

Sister chat to spike Day 1, decide & document.
