package com.cretas.aims.entity;

import com.cretas.aims.security.PriceSensitive;
import lombok.*;
import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import org.hibernate.annotations.Where;

/**
 * 批次工作会话关联实体类
 *
 * 记录员工参与生产批次的工作情况，支持两种模式：
 * 1. 主管分配模式：由主管将员工分配到批次
 * 2. 工作会话关联模式：通过 EmployeeWorkSession 自动关联
 *
 * @author Cretas Team
 * @version 1.0.0
 * @since 2025-01-09
 */
@Data
@EqualsAndHashCode(callSuper = true)
@ToString(exclude = {"batch", "workSession", "employee", "assigner"})
@AllArgsConstructor
@NoArgsConstructor
@Entity
@Table(name = "batch_work_sessions",
       indexes = {
           @Index(name = "idx_batchwork_batch", columnList = "batch_id"),
           @Index(name = "idx_batchwork_session", columnList = "work_session_id"),
           @Index(name = "idx_batchwork_employee", columnList = "employee_id"),
           @Index(name = "idx_batchwork_status", columnList = "status")
       }
)
@Where(clause = "deleted_at IS NULL")
public class BatchWorkSession extends BaseEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "batch_id", nullable = false)
    private Long batchId;

    @Column(name = "work_session_id")
    private Long workSessionId;  // 可为空，主管分配模式下无工作会话

    @Column(name = "employee_id", nullable = false)
    private Long employeeId;

    @Column(name = "work_minutes")
    private Integer workMinutes;  // 可为空，checkout时计算

    @PriceSensitive
    @Column(name = "labor_cost", precision = 10, scale = 2)
    private BigDecimal laborCost;  // 可为空，checkout时计算; stripped for non-procurement:price:view roles (PR #455 BUG-2 sweep)

    // 新增字段：支持主管分配模式
    @Column(name = "check_in_time")
    private LocalDateTime checkInTime;  // 开始参与时间

    @Column(name = "check_out_time")
    private LocalDateTime checkOutTime;  // 结束参与时间

    @Column(name = "assigned_by")
    private Long assignedBy;  // 分配人ID（主管）

    @Column(name = "checkin_method", length = 20)
    private String checkinMethod;  // NFC / QR / MANUAL

    @Column(name = "status", length = 20)
    private String status;  // assigned, working, completed, cancelled

    @Column(name = "notes", length = 500)
    private String notes;  // 备注

    // 关联关系
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "batch_id", referencedColumnName = "id", insertable = false, updatable = false)
    private ProductionBatch batch;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "work_session_id", referencedColumnName = "id", insertable = false, updatable = false)
    private EmployeeWorkSession workSession;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "employee_id", referencedColumnName = "id", insertable = false, updatable = false)
    private User employee;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "assigned_by", referencedColumnName = "id", insertable = false, updatable = false)
    private User assigner;

    /**
     * 批次工作会话状态常量
     */
    public static final class Status {
        public static final String ASSIGNED = "assigned";    // 已分配
        public static final String WORKING = "working";      // 工作中
        public static final String COMPLETED = "completed";  // 已完成
        public static final String CANCELLED = "cancelled";  // 已取消
    }
}
