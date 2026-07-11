-- Forward-fix for V20261008_01's UNIQUE(factory_id, card_no).
--
-- 卡号 (card_no) is NOT globally unique in the 有滋有味 export: the same card_no
-- appears under multiple 卡所属门店 (store), each row a DISTINCT store-membership
-- with its own balance / cumulative consumption / last-visit date (verified:
-- card 155990599 = ¥527@水飨家宴 last 2026-04 vs ¥463@水飨小镇 last 2023-05).
-- The real member-card grain is (factory_id, card_no, store_id) — 7477 rows,
-- 7477 distinct (card_no, store), 0 dupes.
--
-- V20261008_01's UNIQUE(factory_id, card_no) (a) wrongly collapses two different
-- people's memberships into one, and (b) breaks the loader's bulk UNNEST UPSERT
-- with `CardinalityViolationError: ON CONFLICT DO UPDATE cannot affect row a
-- second time` whenever two same-card_no rows land in one batch.
--
-- Idempotent (drop-if-exists both names, then add).
ALTER TABLE fact_member_consumption
    DROP CONSTRAINT IF EXISTS uq_fact_member_consumption;
ALTER TABLE fact_member_consumption
    DROP CONSTRAINT IF EXISTS uq_fact_member_consumption_card_store;
ALTER TABLE fact_member_consumption
    ADD CONSTRAINT uq_fact_member_consumption_card_store
    UNIQUE (factory_id, card_no, store_id);
