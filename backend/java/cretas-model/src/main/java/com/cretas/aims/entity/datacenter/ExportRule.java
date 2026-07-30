package com.cretas.aims.entity.datacenter;

import com.cretas.aims.entity.BaseEntity;
import io.hypersistence.utils.hibernate.type.json.JsonBinaryType;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.Type;
import org.hibernate.annotations.Where;

import java.util.List;
import java.util.Map;

/**
 * 导出规则中心 — 用户可配置的跨模块导出规则.
 *
 * <p>Sprint 4 Chat K C-EXPORT-CENTER-1. 与现有 12 module-specific /export 端点
 * (CustomerController:356, EquipmentController:501, ...) 并存,
 * 与 ReportExportService (per-reportType hardcoded) 也并存.
 *
 * <p>列定义 {@link #columns} 是 {@code [{"field":"...","header":"...","width":N},...]}
 * 形式; ExportService 用反射读 entity field 写 EasyExcel WriteSheet.head(...).
 */
@Data
@EqualsAndHashCode(callSuper = true)
@ToString
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Entity
@Table(name = "export_rules",
        indexes = {
                @Index(name = "idx_export_rules_factory_module", columnList = "factory_id,module_code"),
                @Index(name = "idx_export_rules_factory_created", columnList = "factory_id,created_at")
        })
@Where(clause = "deleted_at IS NULL")
public class ExportRule extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "factory_id", nullable = false, length = 64)
    private String factoryId;

    @Column(name = "module_code", nullable = false, length = 64)
    private String moduleCode;

    @Column(name = "rule_name", nullable = false, length = 200)
    private String ruleName;

    @Column(name = "description", columnDefinition = "TEXT")
    private String description;

    /** Column definitions: [{"field":"customerName","header":"客户名","width":20},...]. */
    @Type(JsonBinaryType.class)
    @Column(name = "columns", nullable = false, columnDefinition = "jsonb")
    private List<Map<String, Object>> columns;

    /**
     * SpEL filter expression evaluated against rowMap (NOT raw SQL/JPQL — SQL injection risk).
     * Example: {@code #row['status'] == 'ACTIVE' && #row['createdAt'] >= #startDate}
     */
    @Column(name = "filter_expression", columnDefinition = "TEXT")
    private String filterExpression;

    /** XLSX | CSV | PDF. */
    @Column(name = "format", nullable = false, length = 10)
    private String format;

    @Column(name = "is_async", nullable = false)
    private Boolean isAsync;

    @Column(name = "row_threshold", nullable = false)
    private Integer rowThreshold;

    /** Fully-qualified JPA entity class name, e.g. {@code com.cretas.aims.entity.Customer}. */
    @Column(name = "target_entity", nullable = false, length = 200)
    private String targetEntity;

    @Column(name = "created_by")
    private Long createdBy;
}
