ALTER TABLE bom_yield_suggestions
    ALTER COLUMN applied_by TYPE VARCHAR(64)
    USING applied_by::TEXT;
