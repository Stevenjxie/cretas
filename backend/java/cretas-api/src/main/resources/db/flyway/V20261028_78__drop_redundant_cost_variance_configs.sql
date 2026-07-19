-- CV-01: remove the unused SP10 fallback table.
--
-- The live application uses product_cost_variance_configs through
-- ProductCostVarianceConfig. cost_variance_configs has no Entity, Repository,
-- API, foreign-key dependency, trigger, view, or production row as of the
-- 2026-07-19 deletion preview.
--
-- Fail closed if any row appears before this migration is deployed. The
-- ACCESS EXCLUSIVE lock closes the count/drop race, and omitting CASCADE makes
-- any newly introduced dependency block the migration instead of being
-- removed implicitly.
LOCK TABLE public.cost_variance_configs IN ACCESS EXCLUSIVE MODE;

DO $cv01$
BEGIN
    IF EXISTS (SELECT 1 FROM public.cost_variance_configs) THEN
        RAISE EXCEPTION
            'CV-01 blocked: cost_variance_configs is no longer empty; review and explicitly authorize its data before cleanup';
    END IF;
END
$cv01$;

DROP TABLE public.cost_variance_configs;
