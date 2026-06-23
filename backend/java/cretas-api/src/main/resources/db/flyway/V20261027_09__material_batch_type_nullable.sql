-- Fix: WIP MaterialBatch records produced by ClerkProcessEntryServiceImpl have no raw_material_types
-- FK to set (only a product_types ID was available). Allow NULL so inserts don't fail on PG FK check.
ALTER TABLE material_batches ALTER COLUMN material_type_id DROP NOT NULL;
