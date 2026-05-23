# Sprint 12 Workdesk E2E Expanded — 20260523_040816

## Per-Workdesk Summary

| Workdesk | Total | Strict PASS | Strict % | Operational | Op % |
|---|---:|---:|---:|---:|---:|
| sales-owner | 10 | 10 | 100.0% | 10 | 100.0% |
| finance-manager | 10 | 5 | 50.0% | 5 | 50.0% |
| quality-manager | 10 | 5 | 50.0% | 5 | 50.0% |
| warehouse-keeper | 10 | 9 | 90.0% | 9 | 90.0% |
| purchaser | 10 | 9 | 90.0% | 9 | 90.0% |
| quality-chief | 10 | 10 | 100.0% | 10 | 100.0% |
| **TOTAL** | **60** | **48** | **80.0%** | **48** | **80.0%** |

## Close-gate row: Strict useful rate ≥80%
- Strict overall: **80.0%** (PASS)
- Operational overall: **80.0%** (FAIL — 12 FAILED)

## Per-path detail (FAIL only)

| Workdesk | Path | Category | Status | Len | Strict | Op | Negative | CN run | Domain kw |
|---|---|---|---|---:|---|---|---|---|---|
| finance-manager | B-base | baseline | COMPLETED | 30 | **FAIL** | FAIL | — | ✓ | `✗` |
| finance-manager | B-syn2 | synonym | SUCCESS | 2647 | **FAIL** | FAIL | _toolCount, _executionOrder, _query(underscore-key | ✓ | `¥0` |
| finance-manager | Bd-empty | boundary | COMPLETED | 30 | **FAIL** | FAIL | — | ✓ | `✗` |
| finance-manager | Bd-period | boundary | SUCCESS | 89 | **FAIL** | FAIL | — | ✓ | `✗` |
| finance-manager | Bd-vague | boundary | NEED_CLARIFICATION | 34 | **FAIL** | FAIL | — | ✓ | `✗` |
| purchaser | Bd-period | boundary | TOOL_DISABLED | 18 | **FAIL** | FAIL | — | ✗ | `采购` |
| quality-manager | B-syn1 | synonym | SUCCESS | 293 | **FAIL** | FAIL | _toolCount, _executionOrder, _executionOrder(under | ✓ | `批次` |
| quality-manager | B-syn3 | synonym | SUCCESS | 19 | **FAIL** | FAIL | — | ✗ | `质检` |
| quality-manager | Bd-large | boundary | SUCCESS | 293 | **FAIL** | FAIL | _toolCount, _executionOrder, _executionOrder(under | ✓ | `批次` |
| quality-manager | Bd-period | boundary | SUCCESS | 19 | **FAIL** | FAIL | — | ✗ | `质检` |
| quality-manager | Bd-vague | boundary | SUCCESS | 19 | **FAIL** | FAIL | — | ✗ | `质检` |
| warehouse-keeper | Bd-period | boundary | SUCCESS | 33 | **FAIL** | FAIL | — | ✗ | `批次` |


Saved docs/audits/2026-05-23-sprint12-e2e-framework/runs/20260523_040816/analysis.json
