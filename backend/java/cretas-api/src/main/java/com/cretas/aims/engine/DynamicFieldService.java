package com.cretas.aims.engine;

import com.cretas.aims.entity.config.CanvasDynamicField;
import com.cretas.aims.repository.config.CanvasDynamicFieldRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class DynamicFieldService {

    private final JdbcTemplate jdbcTemplate;
    private final CanvasDynamicFieldRepository fieldRepo;
    private final DDLExecutor ddlExecutor;

    private final Map<String, List<CanvasDynamicField>> cache = new ConcurrentHashMap<>();

    public List<CanvasDynamicField> getActiveFields(String factoryId, String moduleCode) {
        String key = factoryId + ":" + moduleCode;
        return cache.computeIfAbsent(key, k ->
            fieldRepo.findActiveByModuleCodeForFactory(factoryId, moduleCode));
    }

    public void refreshCache() {
        cache.clear();
        log.info("DynamicFieldService cache cleared");
    }

    public void refreshCache(String factoryId, String moduleCode) {
        cache.remove(factoryId + ":" + moduleCode);
    }

    /**
     * Determine whether the id column of a given table is UUID or VARCHAR.
     * Canvas V3 supports both because different modules use different id types:
     * - sales_orders.id: VARCHAR (legacy)
     * - bom_recipe_items.id: BIGINT
     * - canvas_dynamic_field.id: UUID
     * We query information_schema once per table and cache the result.
     */
    private final Map<String, String> idTypeCache = new ConcurrentHashMap<>();
    /** Cache whether a table has a factory_id column. Checked before adding tenant filter. */
    private final Map<String, Boolean> factoryIdColumnCache = new ConcurrentHashMap<>();

    private boolean hasFactoryIdColumn(String tableName) {
        return factoryIdColumnCache.computeIfAbsent(tableName, t -> {
            try {
                Integer count = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM information_schema.columns " +
                    "WHERE table_name = ? AND column_name = 'factory_id'",
                    Integer.class, t);
                return count != null && count > 0;
            } catch (Exception e) {
                log.warn("Could not check factory_id column for table {}: {}", t, e.getMessage());
                return false;
            }
        });
    }

    private String getIdColumnType(String tableName) {
        return idTypeCache.computeIfAbsent(tableName, t -> {
            try {
                String type = jdbcTemplate.queryForObject(
                    "SELECT data_type FROM information_schema.columns " +
                    "WHERE table_name = ? AND column_name = 'id'",
                    String.class, t);
                return type != null ? type.toLowerCase() : "character varying";
            } catch (Exception e) {
                log.warn("Could not determine id type for table {}, defaulting to text", t);
                return "character varying";
            }
        });
    }

    /** Build the id WHERE clause fragment appropriate for the id column type. */
    private String idWhereClause(String tableName) {
        String type = getIdColumnType(tableName);
        if (type.contains("uuid")) return "WHERE id = ?::uuid";
        if (type.contains("bigint") || type.contains("integer")) return "WHERE id = ?";
        return "WHERE id = ?"; // varchar/text — PostgreSQL auto-casts string literal
    }

    public Map<String, Object> getDynamicFields(String factoryId, String moduleCode, String recordId) {
        List<CanvasDynamicField> fields = getActiveFields(factoryId, moduleCode);
        if (fields.isEmpty()) return Map.of();

        String tableName = ddlExecutor.resolveTable(moduleCode);
        List<String> columns = fields.stream()
            .filter(f -> !"SUB_TABLE".equals(f.getFieldType()))
            .map(CanvasDynamicField::getColumnName)
            .collect(Collectors.toList());

        if (columns.isEmpty()) return Map.of();

        // Tenant isolation: filter by factory_id when the table has the column.
        boolean hasTenantCol = hasFactoryIdColumn(tableName);
        String sql = "SELECT " + String.join(", ", columns) + " FROM " + tableName
            + " " + idWhereClause(tableName) + (hasTenantCol ? " AND factory_id = ?" : "");
        try {
            Map<String, Object> row = hasTenantCol
                ? jdbcTemplate.queryForMap(sql, recordId, factoryId)
                : jdbcTemplate.queryForMap(sql, recordId);
            Map<String, Object> result = new LinkedHashMap<>();
            for (CanvasDynamicField f : fields) {
                if (!"SUB_TABLE".equals(f.getFieldType()) && row.containsKey(f.getColumnName())) {
                    result.put(f.getFieldCode(), row.get(f.getColumnName()));
                }
            }
            return result;
        } catch (Exception e) {
            log.warn("Failed to read dynamic fields for {}/{}: {}", moduleCode, recordId, e.getMessage());
            return Map.of();
        }
    }

    /**
     * Round 4 Fix P1-18: Change field type with ALTER COLUMN TYPE + USING cast.
     * Updates CanvasDynamicField.fieldType after successful DDL.
     * Throws IllegalArgumentException for unsafe transitions.
     */
    public void changeFieldType(com.cretas.aims.entity.config.CanvasDynamicField field, String newType) {
        String oldType = field.getFieldType();
        String newUpper = newType.toUpperCase();

        // Validate transition safety
        boolean safe = isSafeTypeTransition(oldType, newUpper);
        if (!safe) {
            throw new IllegalArgumentException(
                "Unsafe type transition: " + oldType + " → " + newUpper
                + ". Create a new field and migrate data manually instead.");
        }

        String tableName = ddlExecutor.resolveTable(field.getModuleCode());
        String columnName = field.getColumnName();
        String newSqlType = mapFieldTypeToSQL(newUpper);
        String usingClause = buildUsingClause(oldType, newUpper, columnName);

        String ddl = String.format(
            "ALTER TABLE %s ALTER COLUMN %s TYPE %s USING %s",
            tableName, columnName, newSqlType, usingClause
        );

        log.info("Changing field type: {} → {}, DDL: {}", oldType, newUpper, ddl);
        jdbcTemplate.execute(ddl);

        field.setFieldType(newUpper);
        fieldRepo.save(field);
        refreshCache(field.getFactoryId(), field.getModuleCode());
    }

    private boolean isSafeTypeTransition(String from, String to) {
        if (from.equals(to)) return true;
        // Any type → TEXT is safe (castable to text)
        if ("TEXT".equals(to) || "TEXTAREA".equals(to)) return true;
        // Number ↔ Decimal
        if (("NUMBER".equals(from) && "DECIMAL".equals(to))
            || ("DECIMAL".equals(from) && "NUMBER".equals(to))) return true;
        // TEXT → NUMBER/DECIMAL (will fail if data not numeric)
        if ("TEXT".equals(from) && ("NUMBER".equals(to) || "DECIMAL".equals(to))) return true;
        // TEXT → DATE/DATETIME (will fail if not parseable)
        if ("TEXT".equals(from) && ("DATE".equals(to) || "DATETIME".equals(to))) return true;
        // DATE ↔ DATETIME
        if (("DATE".equals(from) && "DATETIME".equals(to))
            || ("DATETIME".equals(from) && "DATE".equals(to))) return true;
        return false;
    }

    private String buildUsingClause(String from, String to, String col) {
        if ("TEXT".equals(to) || "TEXTAREA".equals(to)) return col + "::text";
        if ("NUMBER".equals(to)) return col + "::integer";
        if ("DECIMAL".equals(to)) return col + "::numeric";
        if ("DATE".equals(to)) return col + "::date";
        if ("DATETIME".equals(to)) return col + "::timestamp";
        return col;
    }

    /** Kept in sync with DDLExecutor.mapFieldTypeToSQL — do not add cases here without
     *  adding them there too, or ALTER COLUMN TYPE will produce a type mismatch. */
    private String mapFieldTypeToSQL(String fieldType) {
        return switch (fieldType) {
            case "TEXT", "STRING" -> "VARCHAR(500)";
            case "TEXTAREA", "LONGTEXT" -> "TEXT";
            case "NUMBER", "INTEGER" -> "INTEGER";
            case "DECIMAL" -> "NUMERIC(18,4)";
            case "SELECT" -> "VARCHAR(100)";
            case "DATE" -> "DATE";
            case "DATETIME", "TIMESTAMP" -> "TIMESTAMP";
            case "BOOLEAN", "BOOL" -> "BOOLEAN";
            case "ATTACHMENT" -> "VARCHAR(2000)";
            case "REFERENCE" -> "VARCHAR(64)";
            case "LINE_ITEMS" -> "JSONB";
            default -> throw new IllegalArgumentException(
                "不支持的字段类型转换: " + fieldType + " (请先在 DDLExecutor 补充映射)");
        };
    }

    // R5 arch cleanup (R4 backlog #1): @Transactional moved from
    // DynamicFieldController.setCustomFields (R3 surgical hot-fix) down to this
    // service method. Reasons:
    //   1. Matches R4 pattern (DynamicTableService.addRow/updateRow/deleteRow)
    //   2. Covers ALL callers (not just controller-direct): MaterialBatchServiceImpl,
    //      ProductionPlanServiceImpl, SalesServiceImpl, etc. all go through here
    //   3. Spring default propagation = REQUIRED — callers that already have an
    //      outer @Transactional will join; callers without will get a new tx here
    @Transactional
    public void setDynamicFields(String factoryId, String moduleCode, String recordId, Map<String, Object> fields) {
        if (fields == null || fields.isEmpty()) return;
        List<CanvasDynamicField> activeDefs = getActiveFields(factoryId, moduleCode);
        Map<String, CanvasDynamicField> defMap = activeDefs.stream()
            .collect(Collectors.toMap(CanvasDynamicField::getFieldCode, f -> f));

        // Round 4 Fix P2-31: enforce required=true on the server side.
        // Previously required was a frontend-only rendering hint — backend accepted NULL
        // values silently. Now we validate required fields using config.required flag.
        for (CanvasDynamicField def : activeDefs) {
            if ("SUB_TABLE".equals(def.getFieldType())) continue;
            Object reqFlag = def.getConfig() != null ? def.getConfig().get("required") : null;
            if (Boolean.TRUE.equals(reqFlag)) {
                Object val = fields.get(def.getFieldCode());
                if (val == null || (val instanceof CharSequence s && s.toString().trim().isEmpty())) {
                    throw new com.cretas.aims.exception.BusinessException(
                        "必填字段不能为空: " + def.getLabel() + " (" + def.getFieldCode() + ")");
                }
            }
        }

        String tableName = ddlExecutor.resolveTable(moduleCode);
        List<String> setClauses = new ArrayList<>();
        List<Object> params = new ArrayList<>();

        for (Map.Entry<String, Object> entry : fields.entrySet()) {
            CanvasDynamicField def = defMap.get(entry.getKey());
            if (def == null) {
                // R7 Issue 1 Path B (observability-first): log unknown fieldCode for
                // data collection before deciding Path A (throw) vs Path C (DTO validator).
                // Current silent-skip behavior is PRESERVED — this log is instrumentation only,
                // zero behavior change. Ambient frequency will reveal whether unknown fieldCodes
                // are mostly typos (→ Path A / C) or mostly stale-cache (→ keep silent as design).
                // See .claude/agent-team-outputs/2026-04-15_r7-issue1-setDynamicFields-caller-audit.md
                log.warn("Canvas dynamic field unknown (silently skipped): factoryId={}, moduleCode={}, fieldCode={}, recordId={}",
                    factoryId, moduleCode, entry.getKey(), recordId);
                continue;
            }
            if (!"SUB_TABLE".equals(def.getFieldType())) {
                setClauses.add(def.getColumnName() + " = ?");
                params.add(entry.getValue());
            }
            // def != null && SUB_TABLE: intentional silent skip (handled via sub-table endpoints)
        }

        if (setClauses.isEmpty()) return;
        params.add(recordId);
        // Tenant isolation: filter by factory_id when the table has the column.
        boolean hasTenantCol = hasFactoryIdColumn(tableName);
        if (hasTenantCol) params.add(factoryId);
        String sql = "UPDATE " + tableName + " SET " + String.join(", ", setClauses)
            + " " + idWhereClause(tableName) + (hasTenantCol ? " AND factory_id = ?" : "");
        int affected = jdbcTemplate.update(sql, params.toArray());
        if (affected == 0) {
            throw new com.cretas.aims.exception.BusinessException(
                "动态字段保存失败: 记录不存在或不属于当前工厂 (" + moduleCode + "/" + recordId + ")");
        }
    }
}
