-- Sprint 4 W1 S-CUSTOMER-TAB-1 — tab 1 防呆 R3 dropdown enable.
-- Adds tracking_type column (varchar) to customer_tracking_records so frontend can
-- enforce 6-option dropdown (电话/微信/邮件/上门/视频/其他) per fool-proof-design.md R3.
--
-- Spec drift #4 resolution: original spec §12.5 R3 required type dropdown but entity
-- was shipped (Sprint 4 W2 Chat L) without the column. This catches up.

ALTER TABLE customer_tracking_records
  ADD COLUMN IF NOT EXISTS tracking_type VARCHAR(32) NULL;

-- No index needed — column is enum-bounded (6 values), low cardinality, not a filter key.
-- Frontend dropdown enforces values; backend service validates enum membership.
