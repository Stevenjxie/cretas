-- V20261023_04: add invoice metadata to product_samples.
-- product_samples is entity-owned in fresh CI DBs: Flyway runs before Hibernate DDL.
DO $$
BEGIN
    IF to_regclass('public.product_samples') IS NULL THEN
        RAISE NOTICE 'V20261023_04 skipped: product_samples not present before Hibernate DDL';
        RETURN;
    END IF;

    ALTER TABLE product_samples
        ADD COLUMN IF NOT EXISTS has_invoice BOOLEAN;

    ALTER TABLE product_samples
        ADD COLUMN IF NOT EXISTS invoice_number VARCHAR(100);

    COMMENT ON COLUMN product_samples.has_invoice IS
        'Sample purchase invoice flag: true=invoice, false=no invoice, null=unspecified';

    COMMENT ON COLUMN product_samples.invoice_number IS
        'Sample purchase invoice number when has_invoice=true';
END $$;
