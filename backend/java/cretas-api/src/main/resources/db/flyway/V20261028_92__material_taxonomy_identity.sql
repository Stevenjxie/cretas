ALTER TABLE material_code_segments
    ADD COLUMN IF NOT EXISTS normalized_label varchar(100);

-- Backfill only an identity helper.  The business label, hierarchy and codes are
-- deliberately left untouched: historical taxonomy rows are auditable facts and
-- must never be auto-merged by a deployment migration.
UPDATE material_code_segments
SET normalized_label = lower(regexp_replace(trim(segment_label), '[[:space:]]+', '', 'g'))
WHERE normalized_label IS NULL;

-- Existing installations can contain labels that collapse to the same normalized
-- identity.  Quarantine every member of such a conflict by clearing only the new
-- helper column.  Keeping all members NULL is intentional: the migration neither
-- guesses a canonical row nor rewrites/deletes production taxonomy.  A later,
-- explicitly authorized governance workflow can resolve these rows.
WITH conflicts AS (
    SELECT factory_id,
           level,
           coalesce(parent_code, '') AS parent_identity,
           normalized_label
    FROM material_code_segments
    WHERE deleted_at IS NULL
      AND normalized_label IS NOT NULL
    GROUP BY factory_id, level, coalesce(parent_code, ''), normalized_label
    HAVING count(*) > 1
)
UPDATE material_code_segments segment
SET normalized_label = NULL
FROM conflicts conflict
WHERE segment.deleted_at IS NULL
  AND segment.factory_id = conflict.factory_id
  AND segment.level = conflict.level
  AND coalesce(segment.parent_code, '') = conflict.parent_identity
  AND segment.normalized_label = conflict.normalized_label;

-- New and conflict-free rows remain concurrency-safe.  Historical conflicts are
-- intentionally excluded until a user resolves them; the service compares the
-- normalized form of legacy segment_label values and rejects new collisions.
CREATE UNIQUE INDEX IF NOT EXISTS uq_mcs_parent_normalized_label_active
    ON material_code_segments (
        factory_id,
        level,
        coalesce(parent_code, ''),
        normalized_label
    )
    WHERE deleted_at IS NULL
      AND normalized_label IS NOT NULL;

COMMENT ON COLUMN material_code_segments.normalized_label IS
    'Parent-scoped normalized identity; NULL marks an unresolved historical collision and is not an auto-merge decision';
