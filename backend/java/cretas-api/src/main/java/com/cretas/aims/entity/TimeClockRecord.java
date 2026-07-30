package com.cretas.aims.entity;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import lombok.Builder;
import lombok.ToString;
import lombok.experimental.SuperBuilder;
import jakarta.persistence.*;
import java.time.LocalDate;
import java.time.LocalDateTime;
import org.hibernate.annotations.Where;
/**
 * 考勤打卡记录实体
 *
 * @author Cretas Team
 * @version 1.0.0
 * @since 2025-01-09
 */
@Data
@EqualsAndHashCode(callSuper = true, exclude = {"user"})
@ToString(exclude = {"user"})
@Entity
@Table(name = "time_clock_records")
@SuperBuilder
@NoArgsConstructor
@AllArgsConstructor
@Where(clause = "deleted_at IS NULL")
public class TimeClockRecord extends BaseEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    /**
     * 工厂ID
     */
    @Column(name = "factory_id", nullable = false, length = 50)
    private String factoryId;
     /**
      * 用户ID
      */
    @Column(name = "user_id", nullable = false)
    private Long userId;

    /**
     * 关联的用户实体
     * <p>用于直接访问用户信息，避免额外查询</p>
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", referencedColumnName = "id", insertable = false, updatable = false)
    private User user;

     /**
      * 用户名（计算字段，不映射到数据库）
      */
    @Transient
    private String username;
     /**
      * 打卡日期
      */
    @Column(name = "clock_date", nullable = false)
    private LocalDate clockDate;
     /**
      * 上班打卡时间
      */
    @Column(name = "clock_in_time")
    private LocalDateTime clockInTime;
     /**
      * 下班打卡时间
      */
    @Column(name = "clock_out_time")
    private LocalDateTime clockOutTime;
     /**
      * 休息开始时间
      */
    @Column(name = "break_start_time")
    private LocalDateTime breakStartTime;
     /**
      * 休息结束时间
      */
    @Column(name = "break_end_time")
    private LocalDateTime breakEndTime;
     /**
      * 工作时长（分钟）- 数据库字段名: work_duration
      */
    @Column(name = "work_duration")
    private Integer workDurationMinutes;
     /**
      * 休息时长（分钟）- 数据库字段名: break_duration
      */
    @Column(name = "break_duration")
    private Integer breakDurationMinutes;
     /**
      * 加班时长（分钟）- 计算字段，不存储在表中
      */
    @Transient
    private Integer overtimeMinutes;
     /**
      * 状态: WORKING, ON_BREAK, OFF_WORK, completed
      */
    @Column(name = "status", length = 20)
    private String status;
     /**
      * 考勤状态: NORMAL, LATE, EARLY_LEAVE, LATE_AND_EARLY_LEAVE, ABSENT
      */
    @Column(name = "attendance_status", length = 30)
    private String attendanceStatus;
     /**
      * 工作类型ID（扩展字段，表中暂无）
      */
    @Transient
    private Integer workTypeId;
     /**
      * 工作类型名称（扩展字段，表中暂无）
      */
    @Transient
    private String workTypeName;
     /**
      * 打卡位置 - 数据库字段名: location
      */
    @Column(name = "location", length = 255)
    private String clockLocation;
     /**
      * 打卡设备 - 数据库字段名: device
      */
    @Column(name = "device", length = 255)
    private String clockDevice;
     /**
      * GPS纬度
      */
    @Column(name = "latitude")
    private Double latitude;
     /**
      * GPS经度
      */
    @Column(name = "longitude")
    private Double longitude;
     /**
      * 备注 - 数据库字段名: remarks
      */
    @Column(name = "remarks", length = 500)
    private String notes;
     /**
      * 是否为 HR 手动编辑 (qa-prompt v2.4 Rule 17.3 fix + V20260424_04 migration).
      * Prior: @Transient with "表中暂无" 注释 → TimeClockServiceImpl:450 的 setIsManualEdit(true)
      * 被 Hibernate 忽略, HR 改打卡审计线索丢失. 现 @Column 持久化.
      */
    @Column(name = "is_manual_edit")
    @Builder.Default
    private Boolean isManualEdit = false;
     /**
      * 手动编辑操作人 user_id (qa-prompt v2.4 Rule 17.3 fix).
      */
    @Column(name = "edited_by")
    private Integer editedBy;
     /**
      * 手动编辑原因 — HR 填写, 审计用 (qa-prompt v2.4 Rule 17.3 fix).
      */
    @Column(name = "edit_reason", length = 500)
    private String editReason;
    // createdAt, updatedAt, deletedAt 继承自 BaseEntity

    /**
     * 获取打卡日期（从clock_in_time派生）
     */
    public LocalDate getClockDate() {
        if (clockInTime != null) {
            return clockInTime.toLocalDate();
        }
        return LocalDate.now();
    }

    /**
     * 计算工作时长
     */
    public void calculateWorkDuration() {
        if (clockInTime != null && clockOutTime != null) {
            long totalMinutes = java.time.Duration.between(clockInTime, clockOutTime).toMinutes();
            // 减去休息时间
            if (breakStartTime != null && breakEndTime != null) {
                long breakMinutes = java.time.Duration.between(breakStartTime, breakEndTime).toMinutes();
                this.breakDurationMinutes = (int) breakMinutes;
                totalMinutes -= breakMinutes;
            }
            this.workDurationMinutes = (int) totalMinutes;
            // 计算加班时间（假设标准工作时间是8小时）
            int standardWorkMinutes = 8 * 60;
            if (this.workDurationMinutes > standardWorkMinutes) {
                this.overtimeMinutes = this.workDurationMinutes - standardWorkMinutes;
            } else {
                this.overtimeMinutes = 0;
            }
        }
    }

    // ==================== 前端字段别名 ====================

    /**
     * location 别名（兼容前端）
     * 前端使用 location，后端使用 clockLocation
     */
    @JsonProperty("location")
    public String getLocation() {
        return clockLocation;
    }

    /**
     * device 别名（兼容前端）
     * 前端使用 device，后端使用 clockDevice
     */
    @JsonProperty("device")
    public String getDevice() {
        return clockDevice;
    }

    /**
     * workDuration 别名（兼容前端）
     * 前端使用 workDuration，后端使用 workDurationMinutes
     */
    @JsonProperty("workDuration")
    public Integer getWorkDuration() {
        return workDurationMinutes;
    }

    /**
     * breakDuration 别名（兼容前端）
     * 前端使用 breakDuration，后端使用 breakDurationMinutes
     */
    @JsonProperty("breakDuration")
    public Integer getBreakDuration() {
        return breakDurationMinutes;
    }

    /**
     * remarks 别名（兼容前端）
     * 前端使用 remarks，后端使用 notes
     */
    @JsonProperty("remarks")
    public String getRemarks() {
        return notes;
    }

    /**
     * employeeId 别名（兼容前端）
     * 前端使用 employeeId，后端使用 userId
     */
    @JsonProperty("employeeId")
    public Long getEmployeeId() {
        return userId;
    }

    /**
     * startTime 别名（兼容前端）
     * 前端使用 startTime，后端使用 clockInTime
     */
    @JsonProperty("startTime")
    public LocalDateTime getStartTime() {
        return clockInTime;
    }

    /**
     * endTime 别名（兼容前端）
     * 前端使用 endTime，后端使用 clockOutTime
     */
    @JsonProperty("endTime")
    public LocalDateTime getEndTime() {
        return clockOutTime;
    }

    /**
     * date 别名（兼容前端）
     * 前端使用 date，后端使用 clockDate
     */
    @JsonProperty("date")
    public LocalDate getDate() {
        return getClockDate();
    }
}
