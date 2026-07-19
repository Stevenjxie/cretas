-- PR-01: BOM tables are the only recipe truth.
--
-- Production preview (2026-07-19):
--   product_recipes    = 2 rows, both DEMO_FACTORY / DF_pt10
--   recipe_ingredients = 17 rows, all owned by those two recipes
--   inbound FK         = recipe_ingredients.recipe_id only
--   other inbound FK / view / trigger dependencies = 0
--
-- The lock and snapshot guards make the destructive cutover fail closed if an
-- unexpected writer or non-test recipe appears between review and deployment.

LOCK TABLE public.recipe_ingredients IN ACCESS EXCLUSIVE MODE;
LOCK TABLE public.product_recipes IN ACCESS EXCLUSIVE MODE;

DO $pr01$
DECLARE
    recipe_rows BIGINT;
    ingredient_rows BIGINT;
BEGIN
    SELECT COUNT(*) INTO recipe_rows FROM public.product_recipes;
    SELECT COUNT(*) INTO ingredient_rows FROM public.recipe_ingredients;

    IF recipe_rows > 2 OR ingredient_rows > 17 THEN
        RAISE EXCEPTION
            'PR-01 blocked: legacy recipe rows changed after review (recipes=%, ingredients=%)',
            recipe_rows, ingredient_rows;
    END IF;

    IF EXISTS (
        SELECT 1
        FROM public.product_recipes
        WHERE factory_id IS DISTINCT FROM 'DEMO_FACTORY'
           OR product_type_id IS DISTINCT FROM 'DF_pt10'
           OR id NOT IN (
               '460add70-680f-4257-8f02-2d595e18c92b',
               '53d9c92c-9c35-4989-bbb7-e400e1a4a5ca'
           )
    ) THEN
        RAISE EXCEPTION
            'PR-01 blocked: product_recipes contains data outside the authorized test snapshot';
    END IF;

    IF EXISTS (
        SELECT 1
        FROM public.recipe_ingredients
        WHERE factory_id IS DISTINCT FROM 'DEMO_FACTORY'
           OR recipe_id NOT IN (
               '460add70-680f-4257-8f02-2d595e18c92b',
               '53d9c92c-9c35-4989-bbb7-e400e1a4a5ca'
           )
    ) THEN
        RAISE EXCEPTION
            'PR-01 blocked: recipe_ingredients contains data outside the authorized test snapshot';
    END IF;
END
$pr01$;

DROP TABLE public.recipe_ingredients;
DROP TABLE public.product_recipes;
