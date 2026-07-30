package com.cretas.aims.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;
import org.hibernate.annotations.Where;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * 客户业务员变更 history
 *
 * Sprint 4 W1 S-CUSTOMER-TAB-1: tab 20 数据源.
 * 每次 Customer.assignedSalesUserId 变更通过 CustomerServiceImpl.updateAssignedSalesUser
 * 显式插入一条 history. 防呆 R4 idempotent: 5min 内同 (factoryId, customerId, newSalesUserId)
 * 二次变更返 409 + existingId (见 CustomerSalesUserHistoryRepository.findRecentChange).
 *
 * @author Cretas Team
 * @since 2026-05-17
 */
@Data
@SuperBuilder
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode(callSuper = true)
@Entity
@Table(name = "customer_sales_user_history",
       indexes = {
           @Index(name = "idx_csuh_customer_changed", columnList = "customer_id,changed_at"),
           @Index(name = "idx_csuh_factory_changed", columnList = "factory_id,changed_at"),
           @Index(name = "idx_csuh_dedup", columnList = "factory_id,customer_id,new_sales_user_id,changed_at")
       })
@Where(clause = "deleted_at IS NULL")
public class CustomerSalesUserHistory extends BaseEntity {

    @Id
    @Column(name = "id", nullable = false, length = 36)
    private String id;

    @PrePersist
    void assignUUID() {
        if (id == null) {
            id = UUID.randomUUID().toString();
        }
    }

    @Column(name = "factory_id", nullable = false, length = 50)
    private String factoryId;

    @Column(name = "customer_id", nullable = false, length = 36)
    private String customerId;

    @Column(name = "previous_sales_user_id")
    private Long previousSalesUserId;

    @Column(name = "new_sales_user_id")
    private Long newSalesUserId;

    @Column(name = "changed_by")
    private Long changedBy;

    @Column(name = "changed_at", nullable = false)
    private LocalDateTime changedAt;

    @Column(name = "reason", length = 500)
    private String reason;
}
