package com.cretas.aims.entity.hr;

import com.cretas.aims.entity.BaseEntity;
import com.cretas.aims.entity.hr.enums.AttendanceExceptionStatus;
import com.cretas.aims.entity.hr.enums.AttendanceExceptionType;
import lombok.*;
import org.hibernate.annotations.Where;

import jakarta.persistence.*;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 考勤异常记录.
 *
 * <p>由 AttendanceService.detectExceptions(...) 自动生成, 或 HR 手工新增 (OTHER 类型).
 * 状态: PENDING → APPROVED/REJECTED.
 *
 * <p>幂等 (R4 防呆): detectExceptions 重复扫描同月时, 通过
 * (factory, user, workDate, exceptionType) 唯一约束避免重复生成.
 *
 * @author Cretas Team — P1 #41 H-ATT-FULL MVP
 * @since 2026-05-17
 */
@Entity
@Table(name = "attendance_exceptions",
       uniqueConstraints = {
           @UniqueConstraint(name = "uq_exception_user_date_type",
                             columnNames = {"factory_id", "user_id", "work_date", "exception_type"})
       },
       indexes = {
           @Index(name = "idx_exception_factory_date", columnList = "factory_id,work_date"),
           @Index(name = "idx_exception_factory_status", columnList = "factory_id,status,work_date")
       })
@Where(clause = "deleted_at IS NULL")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AttendanceException extends BaseEntity {

    @Id
    @GeneratedValue(generator = "uuid2")
    @org.hibernate.annotations.GenericGenerator(name = "uuid2", strategy = "uuid2")
    @Column(length = 36)
    private String id;

    @Column(name = "factory_id", nullable = false, length = 50)
    private String factoryId;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "work_date", nullable = false)
    private LocalDate workDate;

    /** 关联的 EmployeeShiftAssignment.id (可空 — ABSENT 时可能未排班). */
    @Column(name = "shift_assignment_id", length = 36)
    private String shiftAssignmentId;

    @Column(name = "exception_type", nullable = false, length = 30)
    @Enumerated(EnumType.STRING)
    private AttendanceExceptionType exceptionType;

    @Column(name = "status", nullable = false, length = 20)
    @Enumerated(EnumType.STRING)
    @Builder.Default
    private AttendanceExceptionStatus status = AttendanceExceptionStatus.PENDING;

    /** 异常描述 — 自动生成时含 employee + date + shift context (R2 防呆). */
    @Column(name = "description", length = 500)
    private String description;

    /** 处理人 user_id (审批/驳回时填). */
    @Column(name = "processed_by")
    private Long processedBy;

    @Column(name = "processed_at")
    private LocalDateTime processedAt;

    /** 备注 — HR 处理时填写 (e.g. 已与员工沟通, 病假补卡 等). */
    @Column(name = "notes", length = 1000)
    private String notes;

    /** 检测时延迟分钟数 (LATE/EARLY_LEAVE 时记录差异分钟). */
    @Column(name = "delta_minutes")
    private Integer deltaMinutes;
}
