-- T161 per-photo annotation: add photo_annotations JSONB column alongside existing photos column.
-- Backward-compatible: existing rows get NULL (treated as no annotations by application layer).
-- Each element: {"url":"...", "label":"称重投入|称重产出|装盒|副产物|损耗|托盘重|工序中|留样|其它", "note":"optional free text"}
ALTER TABLE production_reports
    ADD COLUMN IF NOT EXISTS photo_annotations jsonb;
