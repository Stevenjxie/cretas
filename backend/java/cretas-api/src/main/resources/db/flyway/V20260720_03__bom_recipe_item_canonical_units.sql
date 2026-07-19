-- BOM item units are governed by UnitContractService and the factory unit catalog.
-- The old static CHECK list predates canonical package codes such as box/case and
-- rejects valid material-master values after the UI has normalized their labels.
ALTER TABLE bom_recipe_items
    DROP CONSTRAINT IF EXISTS chk_bri_unit;

COMMENT ON COLUMN bom_recipe_items.unit IS
    'Canonical unit code resolved by UnitContractService; legacy localized values remain supported';
