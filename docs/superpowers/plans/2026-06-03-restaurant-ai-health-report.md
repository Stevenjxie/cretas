reportMeta.period }}</span>
        <span v-if="report.reportMeta.cacheHit"> · (缓存)</span>
      </div>
    </template>
  </div>
</template>

<style scoped>
.health-report-view { padding: 16px; }
.page-title { font-weight: 700; font-size: 16px; }
.summary-row { display: flex; align-items: center; gap: 16px; flex-wrap: wrap; }
.summary-badge { display: flex; flex-direction: column; align-items: center; padding: 8px 16px; border-radius: 6px; min-width: 60px; }
.summary-badge.critical { background: #fee2e2; }
.summary-badge.warning  { background: #fef3c7; }
.summary-badge.info     { background: #dbeafe; }
.badge-count { font-size: 24px; font-weight: 700; line-height: 1; }
.badge-label { font-size: 11px; color: #6b7280; margin-top: 2px; }
.coverage-hint { font-size: 12px; color: #3b82f6; cursor: pointer; margin-left: 8px; }
.coverage-text { font-size: 11px; color: #6b7280; max-width: 500px; }
.all-healthy { padding: 32px 0; color: #16a34a; }
.report-footer { margin-top: 12px; font-size: 11px; color: #9ca3af; text-align: right; }
</style>

**Modify `smartbi.ts`** — insert before the closing `],` of the children array (after line 123):

```typescript
      {
        path: 'health-report',
        name: 'SmartBIHealthReport',
        component: () => import('@/views/smart-bi/HealthReportView.vue'),
        meta: {
          requiresAuth: true,
          title: 'AI 经营体检',
          icon: 'FirstAidKit',
          module: 'analytics',
          hideForFactoryTypes: ['FACTORY'],
        },
      },
```

### Step 4 — Run and confirm tests pass

```
cd C:\Users\Steve\my-prototype-logistics\web-admin
npx vitest run src/views/smart-bi/__tests__/HealthReportView.spec.ts
```

Expected: all 7 tests pass.

### Step 5 — Commit

```
git commit -m "feat(restaurant): HealthReportView.vue + health-report route registration" -- web-admin/src/views/smart-bi/HealthReportView.vue web-admin/src/router/modules/smartbi.ts web-admin/src/views/smart-bi/__tests__/HealthReportView.spec.ts
```

---

## Build Sequence Checklist

- [ ] Task 1: Three playbook YAMLs (channel_collection_rate_low / review_score_decline / delivery_dependency_high) + test_health_check_playbooks.py
- [ ] Task 2: `health_check_metrics.py` (HealthCheckMetricsBuilder, FACTORY_SUB_SECTOR_MAP, finance/POS/review asyncpg queries) + test_health_check_metrics.py
- [ ] Task 3: `restaurant_health_check.py` (FastAPI endpoint, tenant guard, in-process cache, coverage note builder) + `main.py` registration
- [ ] Task 4: `healthCheck.ts` (TypeScript API client, all interfaces, SUB_SECTOR_OPTIONS)
- [ ] Task 5: `DiagnosisCard.vue` (severity bar, number row, RxAction tabs 立即/本周/本月)
- [ ] Task 6: `HealthReportView.vue` (composition API, auto-expand first 3 critical, POS-only alert, footer) + `smartbi.ts` route

Tasks 1 and 2 are sequential (Task 2 tests reference YAML files). Tasks 3–6 can be split between two subagents: Subagent A handles Tasks 1–3 (Python), Subagent B handles Tasks 4–6 (Vue) working in parallel with no file overlap.

---

## Critical Details

**Scale contract**: `food_cost_ratio` and `labor_cost_ratio` are stored as 0–100 (percent) in `bundle.metrics`. The `diagnostics_registry.yaml` benchmarks use the same 0–100 scale (`range: [35.0, 45.0]`). Do not divide by 100 before passing to `DiagnosticsEngine.run()`.

**Inline threshold metrics** (`channel_collection_rate`, `delivery_dependency`, `review_score_decline`, `cost_rigidity`) use 0–1 scale because `diagnostics_registry.yaml` defines them that way (`healthy: ">= 0.78"`, `critical: "< 0.70"`, etc.). The builder stores these as 0–1 floats.

**finance asyncpg query fix**: The `_fetch_finance_metrics` query uses `$2` for `prev_start` and `$4` for `end` with a positional mismatch in the template above — the correct parameterized SQL is:

```sql
WHERE factory_id = $1
  AND record_date BETWEEN $2 AND $3
  AND record_type IN ('REVENUE', 'COST')
```
with args `(factory_id, prev_start, end)` — pass `prev_start` as `$2` and `end` as `$3` to pull both months in one query, then filter in Python by `in_current` / `in_prev` date range checks.

**Tenant guard**: `request.state.factory_id` is set by `JWTAuthMiddleware` (existing). The endpoint checks `jwt_factory != factory_id` and returns 403. If `jwt_factory` is `None` (unauthenticated), the existing middleware rejects before reaching the endpoint — no additional auth needed.

**Cache key**: Uses `bundle.upload_id` which may be `None` if no uploads exist. `None` is a valid dict key in Python — cache will still work correctly, just won't invalidate on new uploads until the endpoint is called fresh (acceptable for MVP).

**Frontend `apiClient`**: Import from `@/api/request` (default export), not named — matches pattern in existing gold.ts and other smartbi API files.

**Test for `_fetch_finance_metrics`**: The mock pool's `_fetch` function matches SQL by substring. The finance query contains `smart_bi_finance_data`; the upload query contains `smart_bi_pg_excel_uploads`. The mock `fetch_map` keys must match these substrings exactly.

**Flyway**: No migrations needed — this feature is pure read path on existing tables (`smart_bi_finance_data`, `fact_pos_transaction`, `agg_daily`, `smart_bi_dynamic_data`, `smart_bi_pg_excel_uploads`).
