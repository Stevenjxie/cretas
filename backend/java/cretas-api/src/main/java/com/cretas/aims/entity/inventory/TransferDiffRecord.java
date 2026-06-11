package com.cretas.aims.entity.inventory;

import com.cretas.aims.entity.BaseEntity;
import com.cretas.aims.entity.enums.TransferDiffDecision;
import lombok.*;
import org.hibernate.annotations.Where;

import jakarta.persistence.*;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * 调拨差异记录（调拨找单）
 *
 * <p>调拨签收时，若任一 item 实收量 < 发货量，系统自动生成本单。
 * 记录差异量并追踪后续找单流程：找回/核销损耗/转报损。
 *
 * <p>复用 PurchaseException 同构模式（SP6），针对调拨场景。
 *
 * <p>状态机: PENDING → RESOLVED（任意 decision 均可关闭）
 *
 * @since 2026-06-11
 */
@Data
@EqualsAndHashCode(callSuper = true)
@ToString
@AllArgsConstructor
@NoArgsConstructor
@Entity
@Table(name = "transfer_diff_records",
        uniqueConstraints = {
                @UniqueConstraint(columnNames = {"source_factory_id", "diff_number"})
        },
        indexes = {
                @Index(name = "idx_tdr_source_factory", columnList = "source_factory_id"),
                @Index(name = "idx_tdr_transfer", columnList = "transfer_id"),
                @Index(name = "idx_tdr_status", columnList = "status")
        }
)
@Where(clause = "deleted_at IS NULL")
public class TransferDiffRecord extends BaseEntity {

    @Id
    @Column(name = "id", nullable = false, length = 191)
    private String id;

    @PrePersist
    void assignUUID() {
        if (id == null) {
            id = UUID.randomUUID().toString();
        }
    }

    /** 调出方工厂 ID（单号的所属工厂） */
    @NotBlank
    @Column(name = "source_factory_id", nullable = false, length = 191)
    private String sourceFactoryId;

    /** 调入方工厂 ID（签收方） */
    @NotBlank
    @Column(name = "target_factory_id", nullable = false, length = 191)
    private String targetFactoryId;

    /** 差异单号，唯一，格式 TDIFF-{factoryId}-{yyyyMMdd}-{seq} */
    @NotBlank
    @Column(name = "diff_number", nullable = false, length = 60)
    private String diffNumber;

    /** 关联调拨单 ID */
    @NotBlank
    @Column(name = "transfer_id", nullable = false, length = 191)
    private String transferId;

    /** 调拨单号（冗余，便于展示） */
    @Column(name = "transfer_number", length = 60)
    private String transferNumber;

    /** 具体明细行 ID（InternalTransferItem.id） */
    @NotNull
    @Column(name = "transfer_item_id", nullable = false)
    private Long transferItemId;

    /** 物料/产品名称（冗余） */
    @Column(name = "item_name", length = 200)
    private String itemName;

    /** 物料类型 ID（RAW_MATERIAL item） */
    @Column(name = "material_type_id", length = 191)
    private String materialTypeId;

    /** 产品类型 ID（FINISHED_GOODS item） */
    @Column(name = "product_type_id", length = 191)
    private String productTypeId;

    /** 调拨发货量（来自 InternalTransferItem.quantity） */
    @NotNull
    @Column(name = "shipped_quantity", nullable = false, precision = 15, scale = 4)
    private BigDecimal shippedQuantity;

    /** 实收量（来自 InternalTransferItem.receivedQuantity） */
    @NotNull
    @Column(name = "received_quantity", nullable = false, precision = 15, scale = 4)
    private BigDecimal receivedQuantity;

    /** 差异量 = shippedQuantity - receivedQuantity（> 0 代表少收） */
    @NotNull
    @Column(name = "diff_quantity", nullable = false, precision = 15, scale = 4)
    private BigDecimal diffQuantity;

    @Column(name = "unit", length = 20)
    private String unit;

    /** 差异单状态：PENDING = 待处理，RESOLVED = 已处理 */
    @Column(name = "status", nullable = false, length = 32)
    private String status = "PENDING";

    /** 决策结果，null = 尚未决策 */
    @Enumerated(EnumType.STRING)
    @Column(name = "decision", length = 32)
    private TransferDiffDecision decision;

    @Column(name = "decision_by")
    private Long decisionBy;

    @Column(name = "decision_at")
    private LocalDateTime decisionAt;

    @Column(name = "decision_notes", columnDefinition = "TEXT")
    private String decisionNotes;

    /** 创建者（调入方签收人） */
    @NotNull
    @Column(name = "created_by", nullable = false)
    private Long createdBy;
}
