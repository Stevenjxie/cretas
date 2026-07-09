DO $$
BEGIN
    IF to_regclass('public.work_processes') IS NOT NULL THEN
        ALTER TABLE work_processes
            ADD COLUMN IF NOT EXISTS custom_field_schema JSONB;
    END IF;
END $$;
