-- =============================================================================
-- Bootstrap Class 9: update_updated_at() PL/pgSQL function
--
-- WHY THIS EXISTS
-- Function `update_updated_at()` is referenced by 4 db/flyway/ migrations:
--   V20260605_02__customer_sales_user_history.sql:39  -- FIRST USE → CI fail point
--   V20260606_17__inquiry_quote_pipeline.sql:93,101
--   V20260710_02__sales_opportunity.sql:55,94
--   V20260720_02__sales_target_commission.sql:50,90,130
--
-- but the function is only defined in LEGACY db/migration/ files (not on
-- Spring's flyway.locations classpath):
--   db/migration/V20260409_01__canvas_config_tables.sql
--   db/migration/V20260410_03__factory_validation_default_formula_scheduler_tables.sql
--
-- Spring config: `spring.flyway.locations=classpath:db/flyway` — db/migration
-- is never replayed by Flyway. Prod/test have the function because at some
-- point psql ran the legacy migration manually; fresh CI does not.
--
-- COMPREHENSIVE SWEEP (Round 10, 2026-05-21)
-- Audit ran:
--   grep "EXECUTE (FUNCTION|PROCEDURE)" db/flyway/      -- callers (24 sites)
--   grep "CREATE OR REPLACE FUNCTION"   db/flyway/      -- definitions (13 sites)
-- Cross-referenced: ONLY `update_updated_at` is used-but-not-defined in
-- db/flyway/. Every other function (bom_version_supersede_previous,
-- trg_cph_set_updated_at, update_approval_workflows_updated_at,
-- update_workflow_variable_def_updated_at, update_workflow_rules_updated_at,
-- update_salary_items_updated_at, update_salary_special_deductions_updated_at,
-- update_comp_time_*_updated_at, update_annual_tax_settlements_updated_at,
-- update_aw_instances_updated_at, fn_maintain_lineage_closure) is defined in
-- its own migration before first use.
--
-- POSITION
-- V20260416_11 — slot after V20260416_10 (Class 7 production_batches
-- bootstrap). First usage is V20260605_02 (~5 months later in version order),
-- so this bootstrap runs well before any caller.
--
-- IDEMPOTENT
-- `CREATE OR REPLACE FUNCTION` — safe on prod/test (no-op replace) and
-- creates the function fresh on clean CI DB.
-- =============================================================================

CREATE OR REPLACE FUNCTION update_updated_at()
RETURNS TRIGGER AS $$
BEGIN
    NEW.updated_at = NOW();
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

-- =============================================================================
-- End of V20260416_11 — Class 9 update_updated_at() function bootstrap
-- =============================================================================
