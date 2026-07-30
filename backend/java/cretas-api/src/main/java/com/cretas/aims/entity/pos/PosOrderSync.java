package com.cretas.aims.entity.pos;

import com.cretas.aims.entity.BaseEntity;
import com.cretas.aims.entity.enums.PosBrand;
import com.cretas.aims.entity.enums.PosSyncStatus;
import lombok.*;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import org.hibernate.annotations.Where;

/**
 * POS订单同步日志
 *
 * 记录每次从POS系统同步的订单，实现：
 * - 幂等性：posOrderId + brand 唯一约束，防止重复导入
 * - 审计追溯：保留POS原始数据(rawPayload)
 * - 关联映射：posOrderId → localSalesOrderId
 *
 * @author Cretas Team
 * @since 2026-02-19
 */
@Data
@EqualsAndHashCode(callSuper = true)
@ToString(exclude = "rawPayload")
@AllArgsConstructor
@NoArgsConstructor
@Entity
@Table(name = "pos_order_syncs",
        uniqueConstraints = {
                @UniqueConstraint(name = "uk_pos_order_brand", columnNames = {"pos_order_id", "brand"})
        },
        indexes = {
                @Index(name = "idx_pos_factory", columnList = "factory_id"),
                @Index(name = "idx_pos_brand", columnList = "brand"),
                @Index(name = "idx_pos_status", columnList = "sync_status"),
                @Index(name = "idx_pos_local_order", columnList = "local_sales_order_id"),
                @Index(name = "idx_pos_sync_time", columnList = "synced_at")
        }
)
@Where(clause = "deleted_at IS NULL")
public class PosOrderSync extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "factory_id", nullable = false, length = 191)
    private String factoryId;

    @Enumerated(EnumType.STRING)
    @Column(name = "brand", nullable = false, length = 32)
    private PosBrand brand;

    /** POS系统的原始订单ID */
    @Column(name = "pos_order_id", nullable = false, length = 200)
    private String posOrderId;

    /** POS系统的订单号 */
    @Column(name = "pos_order_number", length = 100)
    private String posOrderNumber;

    /** 映射到本地的SalesOrder.id */
    @Column(name = "local_sales_order_id", length = 191)
    private String localSalesOrderId;

    /** 订单金额（POS侧） */
    @Column(name = "order_amount", precision = 15, scale = 2)
    private BigDecimal orderAmount;

    /** POS侧订单时间 */
    @Column(name = "pos_order_time")
    private LocalDateTime posOrderTime;

    /** 同步状态 */
    @Enumerated(EnumType.STRING)
    @Column(name = "sync_status", nullable = false, length = 32)
    private PosSyncStatus syncStatus = PosSyncStatus.PENDING;

    /** 同步时间 */
    @Column(name = "synced_at")
    private LocalDateTime syncedAt;

    /** 失败原因 */
    @Column(name = "error_message", length = 1000)
    private String errorMessage;

    /** 重试次数 */
    @Column(name = "retry_count")
    private Integer retryCount = 0;

    /** POS原始订单数据（JSON） */
    @Column(name = "raw_payload", columnDefinition = "TEXT")
    private String rawPayload;
}
