package com.cretas.aims.entity.hr;

import com.cretas.aims.entity.BaseEntity;
import com.cretas.aims.entity.hr.enums.LeaveType;
import lombok.*;
import org.hibernate.annotations.Where;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 假期余额 (月度累计).
 *
 * <p>每个 {@code (factory_id, user_id, leave_type, year_month)} 一行.
 * {@code balance_hours} 是 PostgreSQL generated column ({@code earned - used}),
 * Java 侧仅读, 不写.
 *
 * <p>累计逻辑 (Service 层):
 * <ul>
 *   <li>OvertimeRequest approve (COMPTIME) → balance(COMPTIME).earnedHours += hours</li>
 *   <li>LeaveRequest approve (ANNUAL/SICK/COMPTIME/OTHER) → balance(type).usedHours += durationHours</li>
 *   <li>年假入职/月度发放 → balance(ANNUAL).earnedHours += monthly_quota (定时任务, scope 外)</li>
 * </ul>
 *
 * @author Cretas Team — Sprint 4 W2 Chat E (H-LEAVE-1)
 * @since 2026-05-16
 */
@Entity
@Table(name = "leave_balances",
       uniqueConstraints = @UniqueConstraint(
           name = "uq_balance_user_type_month",
           columnNames = {"factory_id", "user_id", "leave_type", "year_month"}),
       indexes = {
           @Index(name = "idx_balance_factory_user_month",
                  columnList = "factory_id,user_id,year_month"),
           @Index(name = "idx_balance_factory_type_month",
                  columnList = "factory_id,leave_type,year_month")
       })
@Where(clause = "deleted_at IS NULL")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class LeaveBalance extends BaseEntity {

    @Id
    @GeneratedValue(generator = "uuid2")
    @org.hibernate.annotations.GenericGenerator(name = "uuid2", strategy = "uuid2")
    @Column(length = 36)
    private String id;

    @Column(name = "factory_id", nullable = false, length = 50)
    private String factoryId;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "leave_type", nullable = false, length = 20)
    @Enumerated(EnumType.STRING)
    private LeaveType leaveType;

    /** 'YYYY-MM' 格式 */
    @Column(name = "year_month", nullable = false, length = 7)
    private String yearMonth;

    @Column(name = "earned_hours", nullable = false, precision = 8, scale = 2)
    @Builder.Default
    private BigDecimal earnedHours = BigDecimal.ZERO;

    @Column(name = "used_hours", nullable = false, precision = 8, scale = 2)
    @Builder.Default
    private BigDecimal usedHours = BigDecimal.ZERO;

    /** Generated column ({@code earned - used}), Java 仅读 — 不要 setter 写入. */
    @Column(name = "balance_hours", precision = 8, scale = 2, insertable = false, updatable = false)
    private BigDecimal balanceHours;

    @Column(name = "last_calculated_at")
    private LocalDateTime lastCalculatedAt;
}
