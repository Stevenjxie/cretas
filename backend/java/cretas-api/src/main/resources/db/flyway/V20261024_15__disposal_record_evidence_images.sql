-- V20261024_15 — Add evidence_images column to disposal_records
-- Replaces the notes-overload hack where evidence URLs were concatenated into
-- notes as "[证据] <urls>\n备注". Structured storage for photo evidence.
-- Format: comma-separated URL list (e.g. "https://cdn.example.com/a.jpg,https://cdn.example.com/b.jpg")
-- Existing notes data is preserved unchanged; only new records use evidence_images.
-- Guard: disposal_records is a Hibernate entity table; fresh CI DB runs Flyway before Hibernate.

DO $$
BEGIN
    IF to_regclass('public.disposal_records') IS NULL THEN
        RAISE NOTICE 'V20261024_15 skipped: disposal_records not present before Hibernate DDL';
        RETURN;
    END IF;

    ALTER TABLE disposal_records
        ADD COLUMN IF NOT EXISTS evidence_images TEXT;
END $$;
