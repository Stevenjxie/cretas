package com.cretas.aims.entity.hr;

import com.cretas.aims.entity.BaseEntity;
import lombok.*;
import org.hibernate.annotations.Where;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 调休账单月度聚合余额 (CompTime Balance).
 *
 * <p>每 {@code (factory_id, user_id, year_month)} 一行 (UNIQUE 约束).
 *
 * <p>跟现有 {@link LeaveBalance}(leaveType=COMPTIME) 的关系:
 * <ul>
 *   <li>LeaveBalance 是泛假期余额, 月度 earned/used 聚合, balance_hours 为 generated column.</li>
 *   <li>CompTimeBalance 专为 调休 提供 ledger 钻取入口,
 *       {@code available_hours} 维护跨月累计可用余额 (service 层算, 不依赖 generated column).</li>
 *   <li>两者由 listener 同步更新; LeaveBalance 仍是 source of truth for 月度统计.</li>
 * </ul>
 *
 * <p>变更触发:
 * <ul>
 *   <li>OT 审批通过 (COMPTIME) → earnedThisMonth += hours, availableHours += hours</li>
 *   <li>调休假审批通过 → usedThisMonth += hours, availableHours -= hours</li>
 * </ul>
 *
 * @author Cretas Team — #835 follow-up CompTime Balance
 * @since 2026-05-18
 */
@Entity
@Table(name = "comp_time_balances",
       uniqueConstraints = @UniqueConstraint(
           name = "uq_comptime_balance_user_month",
           columnNames = {"factory_id", "user_id", "year_month"}),
       indexes = {
           @Index(name = "idx_comptime_balance_factory_user",
                  columnList = "factory_id,user_id"),
           @Index(name = "idx_comptime_balance_factory_month",
                  columnList = "factory_id,year_month")
       })
@Where(clause = "deleted_at IS NULL")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CompTimeBalance extends BaseEntity {

    @Id
    @GeneratedValue(generator = "uuid2")
    @org.hibernate.annotations.GenericGenerator(name = "uuid2", strategy = "uuid2")
    @Column(length = 36)
    private String id;

    @Column(name = "factory_id", nullable = false, length = 50)
    private String factoryId;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    /** 'YYYY-MM' 格式 */
    @Column(name = "year_month", nullable = false, length = 7)
    private String yearMonth;

    /** 当月 OT 审批通过累计入账小时数 */
    @Column(name = "earned_this_month", nullable = false, precision = 8, scale = 2)
    @Builder.Default
    private BigDecimal earnedThisMonth = BigDecimal.ZERO;

    /** 当月调休假审批通过累计扣减小时数 */
    @Column(name = "used_this_month", nullable = false, precision = 8, scale = 2)
    @Builder.Default
    private BigDecimal usedThisMonth = BigDecimal.ZERO;

    /**
     * 截至当月可用余额 (跨月累计).
     *
     * <p>由 service 层维护, 计算公式:
     * <pre>available_hours = (上月 available_hours) + earned_this_month - used_this_month</pre>
     */
    @Column(name = "available_hours", nullable = false, precision = 8, scale = 2)
    @Builder.Default
    private BigDecimal availableHours = BigDecimal.ZERO;

    @Column(name = "last_calculated_at")
    private LocalDateTime lastCalculatedAt;
}
