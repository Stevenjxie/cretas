package com.cretas.aims.entity.workprocess;

import com.cretas.aims.entity.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;
import org.hibernate.annotations.Where;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 工序任务实例.
 *
 * <p>Track D2 — M-WP-1 / M-WP-2 (六扇门第四次会议 line 49-104).
 *
 * <p>生命周期: 生产批次启动时, {@code WorkProcessTaskService.spawnTasks(batchId)} 从
 * {@code product_work_processes} 模板生成多个 {@code WorkProcessTask} 实例 (1 个工序 = 1 个任务,
 * 按 {@code processOrder} 排序). 责任人 / 主管按状态机推进。
 *
 * <p>状态机:
 * <pre>
 *  PENDING ───start───→ IN_PROGRESS ───complete──→ COMPLETED (终态)
 *     │                      │
 *     │                      └────skip────→ SKIPPED (终态, 必填 notes)
 *     │                      │
 *     └────skip────→ SKIPPED └────cancel──→ CANCELLED (整批取消时级联)
 * </pre>
 */
@Data
@EqualsAndHashCode(callSuper = true)
@AllArgsConstructor
@NoArgsConstructor
@SuperBuilder
@Entity
@Table(name = "work_process_tasks", indexes = {
        @Index(name = "idx_wpt_factory_batch", columnList = "factory_id, production_batch_id, process_order"),
        @Index(name = "idx_wpt_status", columnList = "factory_id, status"),
        @Index(name = "idx_wpt_assignee", columnList = "factory_id, assigned_to, status"),
        @Index(name = "idx_wpt_process", columnList = "factory_id, work_process_id")
})
@Where(clause = "deleted_at IS NULL")
public class WorkProcessTask extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "factory_id", nullable = false, length = 50)
    private String factoryId;

    @Column(name = "production_batch_id", nullable = false)
    private Long productionBatchId;

    @Column(name = "product_work_process_id")
    private Long productWorkProcessId;

    @Column(name = "workflow_instance_id")
    private Long workflowInstanceId;

    @Column(name = "workflow_node_id", length = 128)
    private String workflowNodeId;

    @Column(name = "work_process_id", nullable = false, length = 50)
    private String workProcessId;

    @Column(name = "product_type_id", nullable = false, length = 50)
    private String productTypeId;

    @Column(name = "process_order", nullable = false)
    private Integer processOrder;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 32)
    private Status status;

    @Column(name = "planned_quantity", precision = 15, scale = 4)
    private BigDecimal plannedQuantity;

    @Column(name = "planned_unit", length = 20)
    private String plannedUnit;

    @Column(name = "planned_start_at")
    private LocalDateTime plannedStartAt;

    @Column(name = "planned_end_at")
    private LocalDateTime plannedEndAt;

    @Column(name = "estimated_minutes")
    private Integer estimatedMinutes;

    @Column(name = "actual_quantity", precision = 15, scale = 4)
    private BigDecimal actualQuantity;

    @Column(name = "actual_start_at")
    private LocalDateTime actualStartAt;

    @Column(name = "actual_end_at")
    private LocalDateTime actualEndAt;

    @Column(name = "actual_minutes")
    private Integer actualMinutes;

    @Column(name = "assigned_to")
    private Long assignedTo;

    @Column(name = "completed_by")
    private Long completedBy;

    @Column(name = "completed_at")
    private LocalDateTime completedAt;

    @Column(name = "notes", length = 500)
    private String notes;

    public enum Status {
        PENDING,
        IN_PROGRESS,
        COMPLETED,
        SKIPPED,
        CANCELLED;

        /**
         * 终态判断 (不可再状态转换).
         */
        public boolean isTerminal() {
            return this == COMPLETED || this == SKIPPED || this == CANCELLED;
        }
    }
}
