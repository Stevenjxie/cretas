-- Fixture data for RestockAggregationRepositoryTest
-- Uses MERGE INTO (H2 upsert) so re-execution is idempotent.
-- SET REFERENTIAL_INTEGRITY FALSE lets us insert in any order.
-- H2-only (test): SET REFERENTIAL_INTEGRITY and MERGE INTO are H2 dialect;
-- do NOT copy these statements into PostgreSQL migration scripts.

SET REFERENTIAL_INTEGRITY FALSE;

-- Factory
MERGE INTO factories (id, name, type, level, is_active, manually_verified, ai_weekly_quota, created_at, updated_at)
KEY(id)
VALUES ('F-TEST', 'Restock Test Factory', 'FACTORY', 0, TRUE, FALSE, 20, NOW(), NOW());

-- User (id=1, factory_id='F-TEST')
MERGE INTO users (id, factory_id, username, password_hash, is_active, created_at, updated_at, platform_type)
KEY(id)
VALUES (1, 'F-TEST', 'restock-fixture-user', '$2a$10$testHash', TRUE, NOW(), NOW(), 'web,mobile');

-- Customer
MERGE INTO customers (id, factory_id, code, customer_code, name, is_active, created_by, version, created_at, updated_at)
KEY(id)
VALUES ('CUST-TEST', 'F-TEST', 'CTEST', 'CC-TEST', 'Test Customer', TRUE, 1, 0, NOW(), NOW());

-- ProductType (for sales_order_items + production_plans FK)
MERGE INTO product_types (id, factory_id, code, name, unit, is_active, created_by, created_at, updated_at)
KEY(id)
VALUES ('PT-ZS', 'F-TEST', 'PT-ZS', '猪舌120g', '盒', TRUE, 1, NOW(), NOW());

MERGE INTO product_types (id, factory_id, code, name, unit, is_active, created_by, created_at, updated_at)
KEY(id)
VALUES ('PT-X', 'F-TEST', 'PT-X', 'X产品', '盒', TRUE, 1, NOW(), NOW());

SET REFERENTIAL_INTEGRITY TRUE;
