package com.cretas.aims.entity.hr;

import com.cretas.aims.entity.BaseEntity;
import com.cretas.aims.entity.hr.enums.ShiftAssignmentStatus;
import lombok.*;
import org.hibernate.annotations.Where;

import jakarta.persistence.*;
import java.time.LocalDate;

/**
 * 员工排班分配 (User × WorkDate × Shift).
 *
 * <p>每行 = 某个工厂某员工某天的排班. AttendanceService.detectExceptions(...) 会
 * 扫描这些行, 与对应的 TimeClockRecord 比对生成 AttendanceException.
 *
 * <p>Unique: (factory, user, workDate) — 一员工一天一班.
 *
 * @author Cretas Team — P1 #41 H-ATT-FULL MVP
 * @since 2026-05-17
 */
@Entity
@Table(name = "employee_shift_assignments",
       uniqueConstraints = {
           @UniqueConstraint(name = "uq_assign_user_date",
                             columnNames = {"factory_id", "user_id", "work_date"})
       },
       indexes = {
           @Index(name = "idx_assign_factory_date", columnList = "factory_id,work_date"),
           @Index(name = "idx_assign_factory_user", columnList = "factory_id,user_id,work_date"),
           @Index(name = "idx_assign_factory_status", columnList = "factory_id,status")
       })
@Where(clause = "deleted_at IS NULL")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class EmployeeShiftAssignment extends BaseEntity {

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

    /** AttendanceShift.id. */
    @Column(name = "shift_id", nullable = false, length = 36)
    private String shiftId;

    @Column(name = "status", nullable = false, length = 20)
    @Enumerated(EnumType.STRING)
    @Builder.Default
    private ShiftAssignmentStatus status = ShiftAssignmentStatus.SCHEDULED;

    /** 备注 — HR 手工分配时填写. */
    @Column(name = "notes", length = 500)
    private String notes;
}
