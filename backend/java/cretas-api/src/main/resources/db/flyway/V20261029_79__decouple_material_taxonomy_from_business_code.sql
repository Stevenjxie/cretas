-- Retire the 16-digit material-code model while preserving classification names as an
-- optional hierarchy. Classification nodes are re-keyed to generated BIGINT IDs; the old
-- 3/6/10-digit segment codes and parent prefixes are physically removed.

-- V20261028_92 preserved historical taxonomy collisions by temporarily clearing their
-- normalized identity. That state cannot be carried into the simplified taxonomy because
-- it would make duplicate active categories indistinguishable to the uniqueness constraint.
-- Detect collisions directly from the business labels before restoring the identity helper.
-- This ordering ensures the migration reports the actual occupants instead of letting the
-- pre-existing partial unique index surface an opaque constraint violation first.
DO $$
DECLARE
    conflict_details TEXT;
BEGIN
    SELECT STRING_AGG(
               FORMAT(
                   'factory=%s level=%s parent=%s normalized=%s occupants=%s',
                   conflict.factory_id,
                   conflict.level,
                   conflict.parent_identity,
                   conflict.normalized_identity,
                   conflict.occupants
               ),
               '; ' ORDER BY conflict.factory_id, conflict.level,
                            conflict.parent_identity, conflict.normalized_identity
           )
    INTO conflict_details
    FROM (
        SELECT factory_id,
               level,
               COALESCE(parent_code, '<ROOT>') AS parent_identity,
               LOWER(REGEXP_REPLACE(TRIM(segment_label), '[[:space:]]+', '', 'g'))
                   AS normalized_identity,
               STRING_AGG(id::TEXT || ':' || segment_label, ', ' ORDER BY id) AS occupants
        FROM material_code_segments
        WHERE deleted_at IS NULL
        GROUP BY factory_id,
                 level,
                 COALESCE(parent_code, '<ROOT>'),
                 LOWER(REGEXP_REPLACE(TRIM(segment_label), '[[:space:]]+', '', 'g'))
        HAVING COUNT(*) > 1
        ORDER BY factory_id, level, parent_identity, normalized_identity
        LIMIT 20
    ) conflict;

    IF conflict_details IS NOT NULL THEN
        RAISE EXCEPTION
            'Cannot simplify material taxonomy: duplicate active category identity exists: %',
            conflict_details;
    END IF;

    IF EXISTS (
        SELECT 1
        FROM material_code_segments
        WHERE deleted_at IS NULL
          AND (
              segment_label IS NULL
              OR LOWER(REGEXP_REPLACE(TRIM(segment_label), '[[:space:]]+', '', 'g')) = ''
          )
    ) THEN
        RAISE EXCEPTION
            'Cannot simplify material taxonomy: active category has no normalized identity';
    END IF;
END $$;

UPDATE material_code_segments
SET normalized_label = LOWER(REGEXP_REPLACE(TRIM(segment_label), '[[:space:]]+', '', 'g'))
WHERE normalized_label IS DISTINCT FROM
      LOWER(REGEXP_REPLACE(TRIM(segment_label), '[[:space:]]+', '', 'g'));

ALTER TABLE raw_material_types
    ADD COLUMN IF NOT EXISTS classification_segment_code VARCHAR(10);

UPDATE raw_material_types
SET classification_segment_code = SUBSTRING(code FROM 1 FOR 10)
WHERE classification_segment_code IS NULL
  AND code ~ '^[0-9]{16}$';

-- Fail closed instead of silently assigning an arbitrary short-code family.
DO $$
BEGIN
    IF EXISTS (
        SELECT 1
        FROM raw_material_types
        WHERE code ~ '^[0-9]{16}$'
          AND COALESCE(BTRIM(category), '') NOT IN (
              '原料', '主材', '肉类', '包材', 'PACKAGING',
              '辅料', '辅材', '调料', '调味料', '调味品', '添加剂'
          )
    ) THEN
        RAISE EXCEPTION 'Cannot retire 16-digit material codes: unmapped material category exists';
    END IF;
END $$;

-- Retire every remaining 16-digit material code. Sequence allocation includes soft-deleted rows
-- because the factory_id + code unique constraint also includes them.
WITH legacy_rows AS (
    SELECT id,
           factory_id,
           CASE
               WHEN BTRIM(category) IN ('原料', '主材') THEN 'YL'
               WHEN BTRIM(category) = '肉类' THEN 'RL'
               WHEN BTRIM(category) IN ('包材', 'PACKAGING') THEN 'BC'
               WHEN BTRIM(category) IN ('辅料', '辅材', '调料', '调味料', '调味品', '添加剂') THEN 'WL'
           END AS prefix,
           ROW_NUMBER() OVER (
               PARTITION BY factory_id,
                   CASE
                       WHEN BTRIM(category) IN ('原料', '主材') THEN 'YL'
                       WHEN BTRIM(category) = '肉类' THEN 'RL'
                       WHEN BTRIM(category) IN ('包材', 'PACKAGING') THEN 'BC'
                       WHEN BTRIM(category) IN ('辅料', '辅材', '调料', '调味料', '调味品', '添加剂') THEN 'WL'
                   END
               ORDER BY created_at NULLS LAST, id
           ) AS sequence_offset
    FROM raw_material_types
    WHERE code ~ '^[0-9]{16}$'
), existing_max AS (
    SELECT candidate.factory_id,
           candidate.prefix,
           COALESCE(MAX(
               CASE
                   WHEN existing.code ~ ('^' || candidate.prefix || '[0-9]+$')
                   THEN SUBSTRING(existing.code FROM LENGTH(candidate.prefix) + 1)::BIGINT
               END
           ), 0) AS max_sequence
    FROM (SELECT DISTINCT factory_id, prefix FROM legacy_rows) candidate
    LEFT JOIN raw_material_types existing
        ON existing.factory_id = candidate.factory_id
    GROUP BY candidate.factory_id, candidate.prefix
), replacements AS (
    SELECT legacy.id,
           legacy.prefix || LPAD(
               (maximum.max_sequence + legacy.sequence_offset)::TEXT,
               GREATEST(3, LENGTH((maximum.max_sequence + legacy.sequence_offset)::TEXT)),
               '0'
           ) AS short_code
    FROM legacy_rows legacy
    JOIN existing_max maximum
      ON maximum.factory_id = legacy.factory_id
     AND maximum.prefix = legacy.prefix
)
UPDATE raw_material_types material
SET code = replacements.short_code,
    updated_at = CURRENT_TIMESTAMP
FROM replacements
WHERE material.id = replacements.id;

-- Remove the dual-code allocator. raw_material_types.code is now the only material identifier.
DROP TABLE IF EXISTS material_business_code_counters;
DROP TABLE IF EXISTS material_business_code_prefixes;
DROP INDEX IF EXISTS uk_raw_material_factory_business_code;
DROP INDEX IF EXISTS idx_raw_material_business_code;
ALTER TABLE raw_material_types
    DROP CONSTRAINT IF EXISTS chk_raw_material_business_code_ascii;
ALTER TABLE raw_material_types
    DROP COLUMN IF EXISTS business_code;

ALTER TABLE raw_material_types
    DROP CONSTRAINT IF EXISTS chk_raw_material_classification_segment_code;

-- Replace every taxonomy code reference with generated node IDs before dropping the old columns.
ALTER TABLE material_code_segments
    ADD COLUMN IF NOT EXISTS parent_id BIGINT;

UPDATE material_code_segments child
SET parent_id = parent.id
FROM material_code_segments parent
WHERE child.parent_id IS NULL
  AND child.parent_code IS NOT NULL
  AND parent.factory_id = child.factory_id
  AND parent.segment_code = child.parent_code;

DO $$
BEGIN
    IF EXISTS (
        SELECT 1
        FROM material_code_segments
        WHERE deleted_at IS NULL
          AND level > 1
          AND parent_id IS NULL
    ) THEN
        RAISE EXCEPTION 'Cannot simplify material taxonomy: active node has no resolvable parent';
    END IF;
END $$;

ALTER TABLE raw_material_types
    ADD COLUMN IF NOT EXISTS classification_segment_id BIGINT;

UPDATE raw_material_types material
SET classification_segment_id = taxonomy.id
FROM material_code_segments taxonomy
WHERE material.classification_segment_id IS NULL
  AND material.classification_segment_code IS NOT NULL
  AND taxonomy.factory_id = material.factory_id
  AND taxonomy.segment_code = material.classification_segment_code;

DO $$
BEGIN
    IF EXISTS (
        SELECT 1
        FROM raw_material_types
        WHERE classification_segment_code IS NOT NULL
          AND classification_segment_id IS NULL
    ) THEN
        RAISE EXCEPTION 'Cannot simplify material taxonomy: material classification code has no matching node';
    END IF;
END $$;

DROP INDEX IF EXISTS idx_raw_material_classification_segment;
DROP INDEX IF EXISTS uq_mcs_parent_normalized_label_active;
DROP INDEX IF EXISTS idx_mcs_parent;

ALTER TABLE material_code_segments
    DROP CONSTRAINT IF EXISTS uk_mcs_factory_segment;

ALTER TABLE material_code_segments
    DROP COLUMN IF EXISTS parent_code,
    DROP COLUMN IF EXISTS segment_code;

ALTER TABLE raw_material_types
    DROP COLUMN IF EXISTS classification_segment_code;

ALTER TABLE material_code_segments
    ADD CONSTRAINT uk_mcs_factory_id UNIQUE (factory_id, id);

ALTER TABLE material_code_segments
    ADD CONSTRAINT fk_mcs_parent_id
        FOREIGN KEY (factory_id, parent_id)
        REFERENCES material_code_segments(factory_id, id);

ALTER TABLE raw_material_types
    ADD CONSTRAINT fk_raw_material_classification_id
        FOREIGN KEY (factory_id, classification_segment_id)
        REFERENCES material_code_segments(factory_id, id);

CREATE UNIQUE INDEX uq_mcs_parent_normalized_label_active
    ON material_code_segments (
        factory_id,
        level,
        coalesce(parent_id, 0),
        normalized_label
    )
    WHERE deleted_at IS NULL
      AND normalized_label IS NOT NULL;

CREATE INDEX idx_mcs_parent
    ON material_code_segments(factory_id, parent_id);

CREATE INDEX idx_raw_material_classification_segment
    ON raw_material_types(factory_id, classification_segment_id)
    WHERE classification_segment_id IS NOT NULL;

COMMENT ON TABLE material_code_segments IS
    'Optional material taxonomy. IDs are system-generated; users maintain names and parent relationships only.';
COMMENT ON COLUMN material_code_segments.level IS
    'Taxonomy depth: 1=large category, 2=middle category, 3=small category';
COMMENT ON COLUMN material_code_segments.parent_id IS
    'System-generated parent node ID; NULL for root categories';
COMMENT ON COLUMN raw_material_types.classification_segment_id IS
    'Optional L3 taxonomy node ID; independent from the short material code';
COMMENT ON COLUMN raw_material_types.primary_code IS
    'Deprecated segmented-code compatibility field; new material creation leaves it NULL';
