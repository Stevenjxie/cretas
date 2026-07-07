-- AUDIT-002: 包装明细 — 把包装道单一成本拆为 膜/气体(气调)/标签/其他, 让单盒成本可解释到具体包材项.
-- jsonb [{name, cost}]; 各项 cost 之和 = 该道包装材料成本. null = 未拆 (向后兼容, 包装仍按单一成本显示).
-- Guard: production_reports is a Hibernate entity table; fresh CI DB runs Flyway before Hibernate.

DO $$
BEGIN
    IF to_regclass('public.production_reports') IS NULL THEN
        RAISE NOTICE 'V20261026_02 skipped: production_reports not present before Hibernate DDL';
        RETURN;
    END IF;

    ALTER TABLE production_reports
        ADD COLUMN IF NOT EXISTS packaging_detail jsonb;

    COMMENT ON COLUMN production_reports.packaging_detail IS
        'AUDIT-002 包装明细 [{name,cost}] (膜/气体/标签/其他); null=未拆';
END $$;
