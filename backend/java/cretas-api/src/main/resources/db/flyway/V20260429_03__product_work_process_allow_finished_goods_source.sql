ALTER TABLE product_work_processes
    ADD COLUMN IF NOT EXISTS allow_finished_goods_source BOOLEAN;

UPDATE product_work_processes
SET allow_finished_goods_source = FALSE
WHERE allow_finished_goods_source IS NULL;

ALTER TABLE product_work_processes
    ALTER COLUMN allow_finished_goods_source SET DEFAULT FALSE,
    ALTER COLUMN allow_finished_goods_source SET NOT NULL;
