-- BS-01: the empty mixed-purpose process table becomes injection-only.
-- Cooking pot ratios remain on bom_seasoning_items.subsequent_pot_ratio.

LOCK TABLE public.bom_process_seasoning IN ACCESS EXCLUSIVE MODE;
LOCK TABLE public.bom_seasoning_items IN SHARE MODE;

DO $bs01$
DECLARE
    config_rows BIGINT;
BEGIN
    SELECT COUNT(*) INTO config_rows FROM public.bom_process_seasoning;
    IF config_rows <> 0 THEN
        RAISE EXCEPTION
            'BS-01 blocked: bom_process_seasoning changed after the reviewed empty snapshot (rows=%)',
            config_rows;
    END IF;

    IF EXISTS (
        SELECT 1
          FROM public.bom_seasoning_items
         WHERE deleted_at IS NULL
           AND work_process_id IS NOT NULL
           AND material_type_id IS NULL
    ) THEN
        RAISE EXCEPTION 'BS-01 blocked: process-bound seasoning item has no material binding';
    END IF;

    IF EXISTS (
        SELECT 1
          FROM public.bom_seasoning_items bsi
          JOIN public.bom_recipes br ON br.id = bsi.recipe_id
         WHERE bsi.deleted_at IS NULL
           AND bsi.work_process_id IS NULL
           AND bsi.section = 'COOKING'
           AND bsi.subsequent_pot_ratio IS NULL
           AND br.subsequent_pot_ratio IS NULL
    ) THEN
        RAISE EXCEPTION 'BS-01 blocked: whole-recipe cooking binding has no item or header pot ratio';
    END IF;
END
$bs01$;

-- Preserve whole-recipe cooking behavior before removing the header-level truth.
UPDATE public.bom_seasoning_items bsi
   SET subsequent_pot_ratio = br.subsequent_pot_ratio,
       updated_at = NOW()
  FROM public.bom_recipes br
 WHERE br.id = bsi.recipe_id
   AND bsi.work_process_id IS NULL
   AND bsi.section = 'COOKING'
   AND bsi.subsequent_pot_ratio IS NULL
   AND br.subsequent_pot_ratio IS NOT NULL;

ALTER TABLE public.bom_process_seasoning
    RENAME TO bom_process_injection_configs;

ALTER TABLE public.bom_process_injection_configs
    DROP COLUMN subsequent_pot_ratio;

ALTER TABLE public.bom_process_injection_configs
    ALTER COLUMN injection_amount_kg SET NOT NULL;

ALTER TABLE public.bom_recipes
    DROP COLUMN cooking_pot_base_kg,
    DROP COLUMN subsequent_pot_ratio,
    DROP COLUMN injection_rate;

ALTER INDEX IF EXISTS public.bom_process_seasoning_pkey
    RENAME TO bom_process_injection_configs_pkey;
ALTER INDEX IF EXISTS public.uq_bps_recipe_wp
    RENAME TO uq_bpic_recipe_wp;
ALTER INDEX IF EXISTS public.idx_bps_factory_recipe
    RENAME TO idx_bpic_factory_recipe;

COMMENT ON TABLE public.bom_process_injection_configs IS
    'Absolute injection amount per BOM recipe and work process; cooking pot ratios live on seasoning bindings';
COMMENT ON COLUMN public.bom_process_injection_configs.injection_amount_kg IS
    'Absolute injection amount in kg';
