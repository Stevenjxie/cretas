-- product_work_processes 是 entity-only 表(Hibernate ddl-auto 建), fresh-DB Flyway 先于 ddl-auto → 必须守卫
DO $$
BEGIN
    IF to_regclass('public.product_work_processes') IS NOT NULL THEN
        ALTER TABLE product_work_processes
          ADD COLUMN IF NOT EXISTS responsible_worker_id BIGINT;
        COMMENT ON COLUMN product_work_processes.responsible_worker_id IS '默认责任小组长 user_id; spawn 时作为 work_process_task.assigned_to 默认值';
    END IF;
END $$;
