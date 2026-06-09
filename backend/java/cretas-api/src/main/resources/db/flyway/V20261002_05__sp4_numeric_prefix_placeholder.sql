-- SP4-T4: NUMERIC_PREFIX_MAP placeholder migration.
-- getNumericPrefix(category) is a pure Java in-memory lookup (Map.of); no DB schema change required.
-- This file reserves the V20261002_05 slot in the Flyway sequence for SP4.
SELECT 1;
