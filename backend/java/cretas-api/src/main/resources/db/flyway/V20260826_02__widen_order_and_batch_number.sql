-- V20260826_02__widen_order_and_batch_number.sql
--
-- Defensive widening of business-key columns that are built by string concatenation and
-- can grow beyond VARCHAR(50) in edge cases (same class as the business_entity_id 50→120
-- fix in V20260826_01 / #325).
--
-- Surfaced by the 2026-05-31 prod schema-drift audit:
--   - sales_orders.order_number: SplitOrderTool builds "SO-yyyyMMdd-SPLIT-" + <caller tag> + "-" + i.
--     The tag is an AI-tool parameter (caller/LLM supplied); an over-long tag overflows VARCHAR(50)
--     → "value too long" → split-order transaction rollback. (The tag is now also length-bounded in
--     code; this widening adds headroom.)
--   - finished_goods_batches.batch_number / material_batches.batch_number: ReturnOrderServiceImpl
--     builds "RTN-" + returnNumber + "-" + idx (~27 chars today, safe, but no headroom at 50).
--
-- Widen all three to 64. Idempotent ALTER; preserves data and the order_number UNIQUE constraint.
-- Versioned 20260826_02 (after 20260826_01) so prod flyway (out-of-order=false) applies it.

ALTER TABLE sales_orders
    ALTER COLUMN order_number TYPE VARCHAR(64);

ALTER TABLE finished_goods_batches
    ALTER COLUMN batch_number TYPE VARCHAR(64);

ALTER TABLE material_batches
    ALTER COLUMN batch_number TYPE VARCHAR(64);
