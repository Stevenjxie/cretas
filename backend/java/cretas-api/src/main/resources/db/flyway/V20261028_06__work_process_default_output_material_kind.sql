DO $$
BEGIN
    IF to_regclass('public.work_processes') IS NOT NULL THEN
        ALTER TABLE work_processes
            ADD COLUMN IF NOT EXISTS default_output_material_kind VARCHAR(32);

        UPDATE work_processes
        SET default_output_material_kind = 'SEMI_FINISHED'
        WHERE default_output_material_kind IS NULL;

        ALTER TABLE work_processes
            ALTER COLUMN default_output_material_kind SET DEFAULT 'SEMI_FINISHED',
            ALTER COLUMN default_output_material_kind SET NOT NULL;

        ALTER TABLE work_processes
            DROP CONSTRAINT IF EXISTS chk_work_process_output_material_kind;
        ALTER TABLE work_processes
            ADD CONSTRAINT chk_work_process_output_material_kind
            CHECK (default_output_material_kind IN ('SEMI_FINISHED', 'FINISHED_GOOD'));
    END IF;
END $$;
