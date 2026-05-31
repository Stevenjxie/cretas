-- V20260826_01__widen_pricing_log_business_entity_id.sql
--
-- Fix: pricing_application_logs.business_entity_id was VARCHAR(50) (since creation,
-- V20260623_02 / commit be7768c38) but SalesServiceImpl writes it as
--   businessEntityId = orderNumber + "/" + productTypeId
-- e.g. "SO-20260531-0001/2098f5dc-6487-40a5-bdba-c8d04fb246fa" = 53 chars.
-- When productTypeId is a UUID (36 chars), the value is 53 chars > 50 → Postgres
--   ERROR: value too long for type character varying(50)
-- which rolls back the whole sales-order create transaction → HTTP 409
-- "数据处理异常" for EVERY priced sales-order line (pricingEngine path, unitPrice > 0)
-- on any factory whose products use UUID ids.
--
-- Surfaced 2026-05-31 on prod (F001, UUID product ids) while verifying the pricing
-- per-unit fix (#316) / quantity-BigDecimal fix (#318) end-to-end. Test env never hit
-- it because its products used short codes like PT-F006-TEST-001 (32-char composite).
--
-- Widen to 120 (16-char orderNumber + '/' + 36-char UUID = 53, with ample headroom for
-- longer order numbers). Idempotent ALTER; btree index on the column is unaffected.
-- Version 20260826_01: prod flyway max applied is 20260825.09 and out-of-order=false,
-- so this must sort AFTER it to be picked up.

ALTER TABLE pricing_application_logs
    ALTER COLUMN business_entity_id TYPE VARCHAR(120);
