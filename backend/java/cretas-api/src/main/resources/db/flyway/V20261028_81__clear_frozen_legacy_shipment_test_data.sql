-- SH-01 phase B: clear only the reviewed legacy shipment test-data snapshot.
-- Phase A (V20261028.80) is already live and rejects every legacy mutation.
-- Keep the table and read model for old installed clients until the read contract retires.

LOCK TABLE public.shipment_records IN ACCESS EXCLUSIVE MODE;

DO $sh01_clear$
DECLARE
    total_rows BIGINT;
    live_rows BIGINT;
    soft_deleted_rows BIGINT;
    snapshot_checksum TEXT;
    deleted_rows BIGINT;
BEGIN
    SELECT COUNT(*),
           COUNT(*) FILTER (WHERE deleted_at IS NULL),
           COUNT(*) FILTER (WHERE deleted_at IS NOT NULL),
           MD5(STRING_AGG(ROW_TO_JSON(sr)::TEXT, E'\n' ORDER BY id))
      INTO total_rows, live_rows, soft_deleted_rows, snapshot_checksum
      FROM public.shipment_records sr;

    IF total_rows <> 64 OR live_rows <> 56 OR soft_deleted_rows <> 8 THEN
        RAISE EXCEPTION
            'SH-01 blocked: frozen shipment row counts changed (total=%, live=%, soft_deleted=%)',
            total_rows, live_rows, soft_deleted_rows;
    END IF;

    IF snapshot_checksum IS DISTINCT FROM '92e9ccab1c78eb13feb1239ac748df7d' THEN
        RAISE EXCEPTION
            'SH-01 blocked: frozen shipment checksum changed (actual=%)',
            snapshot_checksum;
    END IF;

    IF EXISTS (
        WITH expected(factory_id, total_count, live_count) AS (
            VALUES
                ('DEMO_FACTORY'::VARCHAR, 27::BIGINT, 27::BIGINT),
                ('DEMO_FACTORY2'::VARCHAR, 1::BIGINT, 1::BIGINT),
                ('F001'::VARCHAR, 27::BIGINT, 27::BIGINT),
                ('F006'::VARCHAR, 8::BIGINT, 0::BIGINT),
                ('FOOD_3101_048'::VARCHAR, 1::BIGINT, 1::BIGINT)
        ),
        actual AS (
            SELECT factory_id,
                   COUNT(*) AS total_count,
                   COUNT(*) FILTER (WHERE deleted_at IS NULL) AS live_count
              FROM public.shipment_records
             GROUP BY factory_id
        )
        SELECT 1
          FROM expected
          FULL JOIN actual USING (factory_id)
         WHERE expected.factory_id IS NULL
            OR actual.factory_id IS NULL
            OR expected.total_count <> actual.total_count
            OR expected.live_count <> actual.live_count
    ) THEN
        RAISE EXCEPTION 'SH-01 blocked: frozen shipment factory distribution changed';
    END IF;

    IF EXISTS (
        SELECT 1
          FROM pg_constraint
         WHERE contype = 'f'
           AND confrelid = 'public.shipment_records'::REGCLASS
    ) THEN
        RAISE EXCEPTION 'SH-01 blocked: shipment_records gained an incoming foreign key';
    END IF;

    DELETE FROM public.shipment_records;
    GET DIAGNOSTICS deleted_rows = ROW_COUNT;

    IF deleted_rows <> 64 OR EXISTS (SELECT 1 FROM public.shipment_records) THEN
        RAISE EXCEPTION 'SH-01 blocked: expected to delete 64 rows, deleted %', deleted_rows;
    END IF;
END
$sh01_clear$;
