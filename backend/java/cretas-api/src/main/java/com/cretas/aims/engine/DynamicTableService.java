package com.cretas.aims.engine;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Service
@RequiredArgsConstructor
public class DynamicTableService {

    private final JdbcTemplate jdbcTemplate;
    private final DDLExecutor ddlExecutor;

    private static final java.util.regex.Pattern COLUMN_NAME_PATTERN =
        java.util.regex.Pattern.compile("^[a-zA-Z_][a-zA-Z0-9_]{0,60}$");
    private static final java.util.regex.Pattern TABLE_NAME_PATTERN =
        java.util.regex.Pattern.compile("^[a-z][a-z0-9_]{2,80}$");

    /** Validate table name to prevent SQL injection from any caller (not just controllers). */
    private static void validateTableName(String tableName) {
        if (tableName == null || !TABLE_NAME_PATTERN.matcher(tableName).matches()) {
            throw new IllegalArgumentException("非法表名: " + tableName);
        }
    }

    /** Validate and normalize a user-supplied column key to a safe SQL column name. */
    private static String safeColumnName(String key) {
        String colName = key.startsWith("cf_") ? key : "cf_" + key;
        if (!COLUMN_NAME_PATTERN.matcher(colName).matches()) {
            throw new IllegalArgumentException("非法列名: " + key);
        }
        return colName;
    }

    // R4 Fix P0-7 (discovered by R4-④ Phase F test): sub-table parent_id casting
    // was hardcoded `?::uuid` / `CAST(? AS uuid)`. This is only correct when the
    // parent table's `id` is UUID. Canvas V3 has mixed id types:
    // - sales_orders.id: VARCHAR (e.g. "SO-F001-202501-001")
    // - bom_recipe_items.id: BIGINT
    // - most others: UUID
    // For VARCHAR-id parents (like sales_orders), the hardcoded cast rejected
    // every sub-table CRUD call with "ERROR: invalid input syntax for type uuid".
    // Fix: detect the sub-table's declared parent_id column type at runtime and
    // build the appropriate cast. Cached per-subTableName.
    private final Map<String, String> parentIdTypeCache = new ConcurrentHashMap<>();

    private String getParentIdColumnType(String subTableName) {
        return parentIdTypeCache.computeIfAbsent(subTableName, t -> {
            try {
                String type = jdbcTemplate.queryForObject(
                    "SELECT data_type FROM information_schema.columns " +
                    "WHERE table_name = ? AND column_name = 'parent_id'",
                    String.class, t);
                return type != null ? type.toLowerCase() : "uuid";
            } catch (Exception e) {
                log.warn("Cannot determine parent_id column type for {}, defaulting to uuid: {}",
                    t, e.getMessage());
                return "uuid";
            }
        });
    }

    /**
     * Build the cast placeholder appropriate for parent_id comparison / insertion.
     * - UUID column: returns "?::uuid" for WHERE clauses, "CAST(? AS uuid)" for INSERT VALUES
     * - VARCHAR/TEXT column: returns "?" (no cast needed, PG auto-binds String)
     * - BIGINT/INTEGER column: returns "?" (JDBC auto-binds)
     *
     * For INSERT placeholders where the value is bound as String, use forInsert=true.
     */
    private String parentIdPlaceholder(String subTableName, boolean forInsert) {
        String type = getParentIdColumnType(subTableName);
        if (type.contains("uuid")) {
            return forInsert ? "CAST(? AS uuid)" : "?::uuid";
        }
        // varchar/text/bigint/integer: no cast needed, JDBC parameter binding handles it
        return "?";
    }

    /**
     * Verify that the parent record belongs to the given factory.
     * Prevents cross-tenant sub-table access.
     */
    public void verifyParentOwnership(String moduleCode, String parentId, String factoryId) {
        String parentTable = ddlExecutor.resolveTable(moduleCode);
        validateTableName(parentTable);
        // Check if parent table has factory_id column before using it in the query.
        // Some tables (e.g. global config tables) may not have tenant scoping.
        boolean hasTenantCol;
        try {
            Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM information_schema.columns WHERE table_name = ? AND column_name = 'factory_id'",
                Integer.class, parentTable);
            hasTenantCol = count != null && count > 0;
        } catch (Exception e) {
            log.warn("Cannot check factory_id column for {}: {}", parentTable, e.getMessage());
            hasTenantCol = false;
        }
        if (!hasTenantCol) {
            // Table has no factory_id — verify record exists at minimum
            Integer exists = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM " + parentTable + " WHERE id::text = ?",
                Integer.class, parentId);
            if (exists == null || exists == 0) {
                throw new com.cretas.aims.exception.BusinessException(
                    "记录不存在: " + moduleCode + "/" + parentId);
            }
            return;
        }
        Integer count = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM " + parentTable + " WHERE id::text = ? AND factory_id = ?",
            Integer.class, parentId, factoryId);
        if (count == null || count == 0) {
            throw new com.cretas.aims.exception.BusinessException(
                "记录不属于当前工厂或不存在: " + moduleCode + "/" + parentId);
        }
    }

    public List<Map<String, Object>> getRows(String subTableName, String parentId) {
        return getRows(subTableName, parentId, null);
    }

    /**
     * Round 4 Fix P1-13: filter support for sub-table rows.
     *
     * filters map values:
     *   cf_xxx  : equality (becomes "cf_xxx = ?")
     *   dateFrom: inclusive from created_at (becomes "created_at >= ?")
     *   dateTo  : inclusive until created_at (becomes "created_at <= ?")
     *   limit   : max rows (becomes LIMIT N)
     *
     * Column names are validated against a whitelist regex to prevent SQL injection.
     */
    public List<Map<String, Object>> getRows(String subTableName, String parentId, Map<String, Object> filters) {
        validateTableName(subTableName);
        // R4 Fix P0-7: type-aware parent_id cast (see parentIdPlaceholder Javadoc)
        StringBuilder sql = new StringBuilder("SELECT * FROM ").append(subTableName)
                .append(" WHERE parent_id = ").append(parentIdPlaceholder(subTableName, false));
        List<Object> params = new ArrayList<>();
        params.add(parentId);

        Integer limit = null;
        if (filters != null) {
            for (Map.Entry<String, Object> entry : filters.entrySet()) {
                String key = entry.getKey();
                Object value = entry.getValue();
                if (value == null || key == null) continue;

                if ("dateFrom".equals(key)) {
                    sql.append(" AND created_at >= ?");
                    params.add(value);
                } else if ("dateTo".equals(key)) {
                    sql.append(" AND created_at <= ?");
                    params.add(value);
                } else if ("limit".equals(key)) {
                    try { limit = Integer.parseInt(value.toString()); } catch (NumberFormatException ignored) {}
                } else if (key.matches("^[a-zA-Z_][a-zA-Z0-9_]{0,60}$")) {
                    // safe column name
                    String colName = key.startsWith("cf_") ? key : "cf_" + key;
                    sql.append(" AND ").append(colName).append(" = ?");
                    params.add(value);
                } else {
                    log.warn("Rejected suspicious sub-table filter key: {}", key);
                }
            }
        }

        sql.append(" ORDER BY created_at");
        if (limit != null && limit > 0 && limit <= 1000) {
            sql.append(" LIMIT ").append(limit);
        }
        return jdbcTemplate.queryForList(sql.toString(), params.toArray());
    }

    // R4 Fix (depth-first-e2e Rule 8 same-cause sweep): the next 3 methods all use raw
    // JdbcTemplate writes for sub-table CRUD. Combined with HikariCP `auto-commit=false`
    // (application-pg-prod.properties), they need an explicit @Transactional boundary,
    // otherwise the connection-level implicit transaction is never committed and the
    // change rolls back when the connection returns to the pool. Same root cause as R3
    // discovered in DynamicFieldController.setCustomFields. R4 fixes the 3 sibling
    // endpoints (addSubTableRow / updateSubTableRow / deleteSubTableRow) at the service
    // layer for a cleaner architecture than R3's controller-level fix.
    // See `tests/canvas-security-e2e/EVIDENCE.md` §12.
    @Transactional
    public Map<String, Object> addRow(String subTableName, String parentId, Map<String, Object> row) {
        validateTableName(subTableName);
        List<String> columns = new ArrayList<>(List.of("parent_id"));
        List<Object> values = new ArrayList<>(List.of(parentId));
        // R4 Fix P0-7: type-aware parent_id cast (see parentIdPlaceholder Javadoc)
        List<String> placeholders = new ArrayList<>(List.of(parentIdPlaceholder(subTableName, true)));

        for (Map.Entry<String, Object> entry : row.entrySet()) {
            columns.add(safeColumnName(entry.getKey()));
            values.add(entry.getValue());
            placeholders.add("?");
        }

        String sql = "INSERT INTO " + subTableName + " (" + String.join(", ", columns) + ") VALUES (" + String.join(", ", placeholders) + ") RETURNING *";
        return jdbcTemplate.queryForMap(sql, values.toArray());
    }

    @Transactional
    public void updateRow(String subTableName, String parentId, String rowId, Map<String, Object> row) {
        validateTableName(subTableName);
        List<String> setClauses = new ArrayList<>();
        List<Object> params = new ArrayList<>();

        for (Map.Entry<String, Object> entry : row.entrySet()) {
            setClauses.add(safeColumnName(entry.getKey()) + " = ?");
            params.add(entry.getValue());
        }
        setClauses.add("updated_at = NOW()");
        params.add(rowId);
        params.add(parentId);

        // R4 Fix P0-7: parent_id cast is type-aware; sub-table id column is ALWAYS UUID (auto-gen DEFAULT gen_random_uuid()), so "?::uuid" for id stays correct.
        String sql = "UPDATE " + subTableName + " SET " + String.join(", ", setClauses)
            + " WHERE id = ?::uuid AND parent_id = " + parentIdPlaceholder(subTableName, false);
        jdbcTemplate.update(sql, params.toArray());
    }

    @Transactional
    public void deleteRow(String subTableName, String parentId, String rowId) {
        validateTableName(subTableName);
        // R4 Fix P0-7: same id (UUID) / parent_id (type-aware) split as updateRow
        jdbcTemplate.update(
            "DELETE FROM " + subTableName + " WHERE id = ?::uuid AND parent_id = " + parentIdPlaceholder(subTableName, false),
            rowId, parentId);
    }
}
