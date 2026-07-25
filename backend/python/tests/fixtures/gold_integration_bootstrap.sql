-- Minimal Java-owned prerequisite for the Python field_provenance migration.
-- The disposable Gold integration database contains no Java Flyway history,
-- but V20260430_01__c_field_provenance.sql requires this upload table and its
-- BIGSERIAL sequence. Production schema remains owned by Java migrations.
CREATE TABLE smart_bi_pg_excel_uploads (
    id BIGSERIAL PRIMARY KEY,
    factory_id VARCHAR(50) NOT NULL,
    file_name VARCHAR(500) NOT NULL,
    upload_status VARCHAR(50)
);
