package com.cretas.aims.engine;

import com.cretas.aims.entity.config.CanvasDDLLog;
import com.cretas.aims.entity.config.CanvasDynamicField;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.repository.config.CanvasDDLLogRepository;
import com.cretas.aims.repository.config.CanvasDynamicFieldRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class DDLExecutor {

    private final JdbcTemplate jdbcTemplate;
    private final CanvasDynamicFieldRepository fieldRepo;
    private final CanvasDDLLogRepository ddlLogRepo;

    private static final Map<String, String> MODULE_TABLE_MAP = Map.ofEntries(
        Map.entry("sales_order", "sales_orders"),
        Map.entry("bom", "bom_recipe_items"),
        Map.entry("production_plan", "production_plans"),
        Map.entry("production_report", "process_work_reports"),
        Map.entry("purchase_order", "purchase_orders"),
        Map.entry("quality_inspection", "quality_inspections"),
        Map.entry("equipment", "equipment"),
        Map.entry("material_batch", "material_batches"),
        Map.entry("customer", "customers"),
        Map.entry("supplier", "suppliers"),
        Map.entry("transfer", "inventory_transfers"),
        Map.entry("invoice_record", "invoice_records"),
        Map.entry("rd_sample", "product_samples"),
        Map.entry("material_requisition", "factory_material_requisitions"),
        Map.entry("product", "products"),
        Map.entry("inventory", "inventory_items"),
        Map.entry("hr_employee", "employees")
    );

    /**
     * Round 5 Fix PERF-2: REQUIRES_NEW propagation isolates DDL into its own short-lived
     * transaction so ALTER TABLE's ACCESS EXCLUSIVE lock is released immediately after each
     * DDL, not held until the parent publishConfig transaction commits (which includes
     * saving the FactoryConfiguration row and audit logging).
     *
     * Trade-off: if the parent TX rolls back AFTER this method commits, the added columns
     * will remain orphaned in the database. This is acceptable because: (1) ADD COLUMN IF
     * NOT EXISTS is idempotent on retry, (2) the dangling column holds no data, (3)
     * CanvasDynamicField rows are also committed in this TX so the schema stays consistent.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void executePendingDDL(String factoryId, int configVersion) {
        List<CanvasDynamicField> pending = fieldRepo.findByFactoryIdAndStatus(factoryId, "PENDING_DDL");
        if (pending.isEmpty()) {
            log.info("No pending DDL for factory {}", factoryId);
            return;
        }

        log.info("Executing {} pending DDL statements for factory {}", pending.size(), factoryId);
        for (CanvasDynamicField field : pending) {
            // Pre-check: detect type conflict for shared columns across factories
            String conflictError = checkTypeConflict(field);
            if (conflictError != null) {
                CanvasDDLLog conflictLog = CanvasDDLLog.builder()
                    .factoryId(factoryId)
                    .configVersion(configVersion)
                    .ddlStatement("-- type conflict detected, not executed")
                    .targetTable(resolveTableName(field))
                    .status("FAILED")
                    .errorMessage(conflictError)
                    .build();
                ddlLogRepo.save(conflictLog);
                throw new BusinessException(conflictError);
            }

            String ddl = generateDDL(field);
            CanvasDDLLog logEntry = CanvasDDLLog.builder()
                .factoryId(factoryId)
                .configVersion(configVersion)
                .ddlStatement(ddl)
                .targetTable(resolveTableName(field))
                .status("PENDING")
                .build();
            ddlLogRepo.save(logEntry);

            try {
                jdbcTemplate.execute(ddl);
                field.setStatus("ACTIVE");
                // Round 4 Fix P0-5: record the config version at which this field first went active
                // This enables rollback to disable fields added in later versions.
                if (field.getActiveFromVersion() == null) {
                    field.setActiveFromVersion(configVersion);
                }
                fieldRepo.save(field);
                logEntry.setStatus("EXECUTED");
                logEntry.setExecutedAt(LocalDateTime.now());
                ddlLogRepo.save(logEntry);
                log.info("DDL executed: {} -> {}.{} (v{})", field.getFieldType(), field.getModuleCode(), field.getColumnName(), configVersion);
            } catch (Exception e) {
                logEntry.setStatus("FAILED");
                logEntry.setErrorMessage(e.getMessage());
                ddlLogRepo.save(logEntry);
                throw new BusinessException(500, "DDL execution failed [" + field.getModuleCode() + "." + field.getFieldCode() + "]: " + e.getMessage())
                        .withHint("请联系管理员检查数据库 DDL 权限或字段定义");
            }
        }
    }

    /**
     * Check if target column already exists with an incompatible type.
     * Returns null if no conflict, or an error message if a conflict is detected.
     * Sub-tables are skipped — table name uniqueness is enforced by the naming scheme.
     */
    private String checkTypeConflict(CanvasDynamicField field) {
        if ("SUB_TABLE".equals(field.getFieldType())) {
            return null;
        }
        String tableName = resolveTableName(field);
        String colName = field.getColumnName();
        if (colName == null) {
            return null;
        }
        try {
            List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT data_type, character_maximum_length, numeric_precision, numeric_scale " +
                "FROM information_schema.columns WHERE table_name = ? AND column_name = ?",
                tableName, colName
            );
            if (rows.isEmpty()) {
                return null; // column does not exist, no conflict
            }
            String existingDataType = String.valueOf(rows.get(0).get("data_type"));
            String expectedSql = mapFieldTypeToSQL(field.getFieldType());
            if (!isCompatible(existingDataType, expectedSql)) {
                return "DDL type conflict: column " + tableName + "." + colName +
                       " already exists as " + existingDataType +
                       " but factory " + field.getFactoryId() +
                       " requested " + field.getFieldType() + " (" + expectedSql + "). " +
                       "Rename the fieldCode to avoid cross-factory collision.";
            }
            return null;
        } catch (Exception e) {
            log.warn("Type conflict check failed for {}.{}: {}", tableName, colName, e.getMessage());
            return null; // fail-open: do not block on metadata query errors
        }
    }

    /**
     * Check if an existing PostgreSQL data_type is compatible with the expected SQL type.
     * Maps our mapFieldTypeToSQL() output family to PG information_schema.data_type values.
     */
    private boolean isCompatible(String existingDataType, String expectedSql) {
        if (existingDataType == null) return true;
        String existing = existingDataType.toLowerCase();
        String expected = expectedSql.toLowerCase();

        if (expected.startsWith("varchar")) {
            return existing.equals("character varying") || existing.equals("text");
        }
        if (expected.startsWith("integer")) {
            return existing.equals("integer") || existing.equals("bigint") || existing.equals("smallint");
        }
        if (expected.startsWith("numeric")) {
            return existing.equals("numeric") || existing.equals("decimal");
        }
        if (expected.startsWith("timestamp")) {
            return existing.contains("timestamp") || existing.equals("date");
        }
        return false;
    }

    private String generateDDL(CanvasDynamicField field) {
        if ("SUB_TABLE".equals(field.getFieldType())) {
            return generateSubTableDDL(field);
        }
        String tableName = resolveTableName(field);
        String colName = field.getColumnName();
        String colType = mapFieldTypeToSQL(field.getFieldType());
        return "ALTER TABLE " + tableName + " ADD COLUMN IF NOT EXISTS " + colName + " " + colType;
    }

    @SuppressWarnings("unchecked")
    private String generateSubTableDDL(CanvasDynamicField field) {
        String subTableName = field.getModuleCode() + "_" + field.getFieldCode() + "_items";
        // R4 Fix P0-7 (same-cause sweep): parent_id column type MUST match the parent
        // table's id type. Canvas V3 has mixed id types — sales_orders.id is VARCHAR
        // while bom_recipe_items.id is BIGINT and most others are UUID. Using UUID blindly
        // broke every sub-table CRUD call for VARCHAR-id parents. Query the parent
        // table's id type at CREATE TIME so new sub-tables have the correct type.
        // Existing sub-tables with UUID parent_id are preserved (empty in test env,
        // 2 orphan rows in prod with no matching parent); R5 ADR will handle
        // back-migration if needed.
        String parentTable = resolveTableName(field);
        String parentIdSqlType = resolveParentIdSqlType(parentTable);
        StringBuilder sb = new StringBuilder();
        sb.append("CREATE TABLE IF NOT EXISTS ").append(subTableName).append(" (");
        sb.append("id UUID PRIMARY KEY DEFAULT gen_random_uuid(), ");
        sb.append("parent_id ").append(parentIdSqlType).append(" NOT NULL, ");

        // Round 4 Fix P2-22: collect unique columns for CREATE UNIQUE INDEX
        List<String> uniqueColumns = new ArrayList<>();

        List<Map<String, Object>> columns = (List<Map<String, Object>>) field.getConfig().get("columns");
        if (columns != null) {
            for (Map<String, Object> col : columns) {
                String code = (String) col.get("code");
                // Round 5 Fix SEC-3: validate sub-column code before SQL concatenation
                if (code == null || !code.matches("^[a-zA-Z_][a-zA-Z0-9_]{0,60}$")) {
                    throw new BusinessException(400, "Invalid sub-table column code: " + code)
                            .withHint("列代码必须以字母或下划线开头, 仅含字母数字下划线, 最多 61 字符").withHintTarget("code");
                }
                String type = (String) col.getOrDefault("type", "TEXT");
                sb.append("cf_").append(code).append(" ").append(mapFieldTypeToSQL(type)).append(", ");
                if (Boolean.TRUE.equals(col.get("unique"))) {
                    uniqueColumns.add("cf_" + code);
                }
            }
        }
        sb.append("created_at TIMESTAMP DEFAULT NOW(), ");
        sb.append("updated_at TIMESTAMP DEFAULT NOW()");
        sb.append("); ");
        sb.append("CREATE INDEX IF NOT EXISTS idx_").append(subTableName).append("_parent ON ").append(subTableName).append("(parent_id)");
        // Round 4 Fix P2-22: emit UNIQUE indexes for columns marked unique:true in config.
        // Scoped to parent_id so same ear-tag can exist across different parent orders but
        // not twice within the same parent. Adjust to global UNIQUE by removing parent_id.
        for (String uniqueCol : uniqueColumns) {
            sb.append("; CREATE UNIQUE INDEX IF NOT EXISTS uq_").append(subTableName).append("_").append(uniqueCol)
              .append(" ON ").append(subTableName).append(" (parent_id, ").append(uniqueCol).append(") WHERE ")
              .append(uniqueCol).append(" IS NOT NULL");
        }
        return sb.toString();
    }

    private String resolveTableName(CanvasDynamicField field) {
        return MODULE_TABLE_MAP.getOrDefault(field.getModuleCode(), field.getModuleCode());
    }

    /**
     * R4 Fix P0-7: resolve the SQL column type to use for `parent_id` in a new sub-table
     * by querying the parent table's `id` column type from information_schema.
     * Canvas V3 has mixed id types across modules (VARCHAR, BIGINT, UUID), and a hardcoded
     * UUID broke sub-table CRUD for every non-UUID parent (e.g. sales_orders).
     * Returns a SQL type literal safe to concatenate into a CREATE TABLE statement.
     */
    private String resolveParentIdSqlType(String parentTable) {
        try {
            String type = jdbcTemplate.queryForObject(
                "SELECT data_type FROM information_schema.columns " +
                "WHERE table_name = ? AND column_name = 'id'",
                String.class, parentTable);
            if (type == null) {
                log.warn("Parent table {} has no 'id' column found in information_schema — defaulting sub-table parent_id to UUID", parentTable);
                return "UUID";
            }
            String t = type.toLowerCase();
            if (t.contains("uuid")) return "UUID";
            if (t.contains("bigint")) return "BIGINT";
            if (t.contains("integer")) return "INTEGER";
            // character varying / text / others → VARCHAR wide enough to hold any expected id
            return "VARCHAR(100)";
        } catch (Exception e) {
            log.warn("Could not determine id type for parent table {}, defaulting to UUID: {}",
                parentTable, e.getMessage());
            return "UUID";
        }
    }

    public String resolveTable(String moduleCode) {
        return MODULE_TABLE_MAP.getOrDefault(moduleCode, moduleCode);
    }

    private String mapFieldTypeToSQL(String fieldType) {
        // Round 4 Fix P1-11: Added DATETIME and BOOLEAN types.
        // Round 7a Fix: REFERENCE and LINE_ITEMS had no case and fell through to
        // default -> VARCHAR(500), silently corrupting intended data types. REFERENCE
        // should store a FK id (UUID-width VARCHAR is the safe choice since our id
        // columns mix BIGINT and UUID::text). LINE_ITEMS should store the row collection
        // as JSONB so downstream callers can round-trip the Vue LineItemsEditor value.
        //
        // If the caller really has a custom type not in this list we now throw rather
        // than silently fall through — better to fail the DDL than corrupt the column.
        return switch (fieldType == null ? "TEXT" : fieldType.toUpperCase()) {
            case "TEXT", "STRING" -> "VARCHAR(500)";
            case "TEXTAREA", "LONGTEXT" -> "TEXT";
            case "NUMBER", "INTEGER" -> "INTEGER";
            case "DECIMAL" -> "NUMERIC(18,4)";
            case "SELECT" -> "VARCHAR(100)";
            case "DATE" -> "DATE";
            case "DATETIME", "TIMESTAMP" -> "TIMESTAMP";
            case "BOOLEAN", "BOOL" -> "BOOLEAN";
            case "ATTACHMENT" -> "VARCHAR(2000)";
            case "REFERENCE" -> "VARCHAR(64)";   // FK id — works for both UUID::text and BIGINT
            case "LINE_ITEMS" -> "JSONB";         // row collection as JSONB, parsed by LineItemsEditor
            default -> {
                log.error("Unknown fieldType '{}' — cannot generate DDL. Add a case to mapFieldTypeToSQL or reject at Controller validation.", fieldType);
                throw new com.cretas.aims.exception.BusinessException(
                        "不支持的动态字段类型: " + fieldType + " (请联系开发补充 DDL 映射)");
            }
        };
    }
}
