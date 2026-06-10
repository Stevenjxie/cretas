package com.cretas.aims.entity.inventory;

import com.cretas.aims.entity.BaseEntity;
import com.cretas.aims.entity.enums.ExceptionDecision;
import com.cretas.aims.entity.enums.ReceiveExceptionType;
import lombok.*;
import org.hibernate.annotations.Where;

import jakarta.persistence.*;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * 采购异常单（SP6）
 *
 * <p>入库完成后，若实收数量与 PO 数量存在差异（超出超收上限或少收），
 * 系统自动生成本单，采购员在单上做 ACCEPT/RETURN 决策。
 *
 * <p>事务边界：decideException(RETURN_OVER) 调用 ReturnOrderService 使用
 * {@code REQUIRES_NEW} 隔离，防止异常单决策事务被 ReturnOrder 创建失败拖垮。
 *
 * @see com.cretas.aims.entity.enums.ExceptionDecision
 */
@Data
@EqualsAndHashCode(callSuper = true)
@ToString
@AllArgsConstructor
@NoArgsConstructor
@Entity
@Table(name = "purchase_exceptions",
        uniqueConstraints = {
                @UniqueConstraint(columnNames = {"factory_id", "exception_number"})
        },
        indexes = {
                @Index(name = "idx_pe_factory", columnList = "factory_id"),
                @Index(name = "idx_pe_receive", columnList = "receive_record_id"),
                @Index(name = "idx_pe_status", columnList = "status")
        }
)
@Where(clause = "deleted_at IS NULL")
public class PurchaseException extends BaseEntity {

    @Id
    @Column(name = "id", nullable = false, length = 191)
    private String id;

    @PrePersist
    void assignUUID() {
        if (id == null) {
            id = UUID.randomUUID().toString();
        }
    }

    @NotBlank
    @Column(name = "factory_id", nullable = false, length = 191)
    private String factoryId;

    @NotBlank
    @Column(name = "exception_number", nullable = false, length = 50)
    private String exceptionNumber;

    @NotBlank
    @Column(name = "receive_record_id", nullable = false, length = 191)
    private String receiveRecordId;

    @NotBlank
    @Column(name = "purchase_order_id", nullable = false, length = 191)
    private String purchaseOrderId;

    @Column(name = "supplier_id", length = 191)
    private String supplierId;

    @Column(name = "material_type_id", length = 191)
    private String materialTypeId;

    @Column(name = "material_name", length = 200)
    private String materialName;

    @NotNull
    @Enumerated(EnumType.STRING)
    @Column(name = "exception_type", nullable = false, length = 32)
    private ReceiveExceptionType exceptionType;

    @NotNull
    @Column(name = "po_quantity", nullable = false, precision = 15, scale = 4)
    private BigDecimal poQuantity;

    @NotNull
    @Column(name = "received_quantity", nullable = false, precision = 15, scale = 4)
    private BigDecimal receivedQuantity;

    @NotNull
    @Column(name = "exception_qty", nullable = false, precision = 15, scale = 4)
    private BigDecimal exceptionQty;

    @Column(name = "unit", length = 20)
    private String unit;

    /** 采购员决策结果，null = 尚未决策 */
    @Enumerated(EnumType.STRING)
    @Column(name = "decision", length = 32)
    private ExceptionDecision decision;

    @Column(name = "decision_by")
    private Long decisionBy;

    @Column(name = "decision_at")
    private LocalDateTime decisionAt;

    @Column(name = "decision_notes", columnDefinition = "TEXT")
    private String decisionNotes;

    /**
     * 异常单状态：PENDING = 待决策，RESOLVED = 已处理
     */
    @Column(name = "status", nullable = false, length = 32)
    private String status = "PENDING";

    @NotNull
    @Column(name = "created_by", nullable = false)
    private Long createdBy;

    /**
     * D-6 责任绑定：该异常单的责任人（源采购单的创建人 userId）。
     * 生成异常单时自动从 PurchaseOrder.createdBy 带入。
     * 决策端点要求本人或主管角色（factory_super_admin / procurement_manager）才可决策。
     */
    @Column(name = "owner_user_id")
    private Long ownerUserId;

    /**
     * 责任人用户名（展示用，冗余存储避免联表查询）。
     */
    @Column(name = "owner_name", length = 100)
    private String ownerName;
}
