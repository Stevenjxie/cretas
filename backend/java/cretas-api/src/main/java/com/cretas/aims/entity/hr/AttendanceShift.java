package com.cretas.aims.entity.hr;

import com.cretas.aims.entity.BaseEntity;
import lombok.*;
import org.hibernate.annotations.Where;

import jakarta.persistence.*;
import java.time.LocalTime;

/**
 * 班次定义 (Shift template).
 *
 * <p>一个工厂多个班次, 通过 EmployeeShiftAssignment 把员工某天分配到某个班次.
 * AttendanceService.detectExceptions(...) 根据 shift 起止时间与 TimeClockRecord 比对生成异常.
 *
 * <p>MVP 假设固定时间 (e.g. 早班 08:00-17:00). 高级排班 (轮班/弹性) 留后续迭代.
 *
 * @author Cretas Team — P1 #41 H-ATT-FULL MVP
 * @since 2026-05-17
 */
@Entity
@Table(name = "attendance_shifts",
       uniqueConstraints = {
           @UniqueConstraint(name = "uq_shift_factory_code", columnNames = {"factory_id", "shift_code"})
       },
       indexes = {
           @Index(name = "idx_shift_factory", columnList = "factory_id")
       })
@Where(clause = "deleted_at IS NULL")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AttendanceShift extends BaseEntity {

    @Id
    @GeneratedValue(generator = "uuid2")
    @org.hibernate.annotations.GenericGenerator(name = "uuid2", strategy = "uuid2")
    @Column(length = 36)
    private String id;

    @Column(name = "factory_id", nullable = false, length = 50)
    private String factoryId;

    /** 班次代码 — 工厂内唯一 (e.g. EARLY / LATE / NIGHT / FLEX). */
    @Column(name = "shift_code", nullable = false, length = 30)
    private String shiftCode;

    @Column(name = "shift_name", nullable = false, length = 100)
    private String shiftName;

    /** 上班时间. */
    @Column(name = "start_time", nullable = false)
    private LocalTime startTime;

    /** 下班时间. */
    @Column(name = "end_time", nullable = false)
    private LocalTime endTime;

    /** 是否跨夜 (e.g. 22:00 - 06:00). */
    @Column(name = "is_overnight", nullable = false)
    @Builder.Default
    private Boolean isOvernight = false;

    /** 是否启用 (false = 不再用于新分配, 历史记录保留). */
    @Column(name = "is_active", nullable = false)
    @Builder.Default
    private Boolean isActive = true;

    /** 备注. */
    @Column(name = "notes", length = 500)
    private String notes;
}
