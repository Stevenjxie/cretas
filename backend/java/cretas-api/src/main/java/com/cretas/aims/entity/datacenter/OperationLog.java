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
 * 通用业务操作日志 — 通过 @Loggable AOP 自动采集 entity-level CRUD.
 *
 * <p>Sprint 4 Chat K C-LOG-AUDIT-1. 与 AIAuditLog (AI 专用) / DecisionAuditLog 并存.
 * 字段命名与 AIAuditLog 对齐 (factoryId / userId / ipAddress / userAgent). LoggableAspect
 * 在被 @Loggable 标注的方法上 @Around 拦截, async 写库, 异常不阻塞业务.
 */
@Data
@EqualsAndHashCode(callSuper = true)
@ToString
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Entity
@Table(name = "operation_logs",
        indexes = {
                @Index(name = "idx_operation_logs_factory_created", columnList = "factory_id,created_at"),
                @Index(name = "idx_operation_logs_user_created", columnList = "user_id,created_at"),
                @Index(name = "idx_operation_logs_module_action", columnList = "factory_id,module,action,created_at"),
                @Index(name = "idx_operation_logs_entity", columnList = "entity_type,entity_id")
        })
@Where(clause = "deleted_at IS NULL")
public class OperationLog extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "factory_id", nullable = false, length = 64)
    private String factoryId;

    @Column(name = "user_id")
    private Long userId;

    @Column(name = "username", length = 100)
    private String username;

    @Column(name = "module", nullable = false, length = 64)
    private String module;

    /** CREATE | UPDATE | DELETE | EXPORT | IMPORT | OTHER. */
    @Column(name = "action", nullable = false, length = 20)
    private String action;

    @Column(name = "entity_type", length = 200)
    private String entityType;

    @Column(name = "entity_id", length = 100)
    private String entityId;

    /** Full entity snapshot before change; null for CREATE. */
    @Type(JsonBinaryType.class)
    @Column(name = "old_value", columnDefinition = "jsonb")
    private Map<String, Object> oldValue;

    /** Full entity snapshot after change; null for DELETE. */
    @Type(JsonBinaryType.class)
    @Column(name = "new_value", columnDefinition = "jsonb")
    private Map<String, Object> newValue;

    /** Field-level diff: {@code [{"field":"name","from":"foo","to":"bar"},...]}. */
    @Type(JsonBinaryType.class)
    @Column(name = "diff", columnDefinition = "jsonb")
    private List<Map<String, Object>> diff;

    /** 业务级说明, e.g. "客户 ABC 状态改为停用". */
    @Column(name = "summary", columnDefinition = "TEXT")
    private String summary;

    @Column(name = "ip_address", length = 64)
    private String ipAddress;

    @Column(name = "user_agent", length = 500)
    private String userAgent;

    @Column(name = "elapsed_ms")
    private Long elapsedMs;

    @Column(name = "success", nullable = false)
    private Boolean success;

    @Column(name = "error_message", length = 1000)
    private String errorMessage;
}
