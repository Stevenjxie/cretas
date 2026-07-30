package com.cretas.aims.entity.bom;

import com.cretas.aims.entity.BaseEntity;
import com.cretas.aims.security.PriceSensitive;
import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;
import org.hibernate.annotations.Where;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Entity
@Table(name = "bom_price_adjustment_proposals", indexes = {
        @Index(name = "idx_bpap_factory_status", columnList = "factory_id, status"),
        @Index(name = "idx_bpap_material", columnList = "factory_id, material_type_id"),
        @Index(name = "idx_bpap_recipe_item", columnList = "recipe_item_id")
})
@Where(clause = "deleted_at IS NULL")
@Data
@SuperBuilder
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode(callSuper = true)
public class BomPriceAdjustmentProposal extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "factory_id", nullable = false, length = 50)
    private String factoryId;

    @Column(name = "recipe_id", nullable = false, length = 191)
    private String recipeId;

    @Column(name = "recipe_item_id", nullable = false)
    private Long recipeItemId;

    @Column(name = "recipe_code", length = 50)
    private String recipeCode;

    @Column(name = "product_type_id", length = 100)
    private String productTypeId;

    @Column(name = "product_name", length = 200)
    private String productName;

    @Column(name = "material_type_id", nullable = false, length = 191)
    private String materialTypeId;

    @Column(name = "material_name", length = 200)
    private String materialName;

    @PriceSensitive
    @Column(name = "current_unit_price", nullable = false, precision = 15, scale = 4)
    private BigDecimal currentUnitPrice;

    @PriceSensitive
    @Column(name = "proposed_unit_price", nullable = false, precision = 15, scale = 4)
    private BigDecimal proposedUnitPrice;

    @PriceSensitive
    @Column(name = "delta_amount", nullable = false, precision = 15, scale = 4)
    private BigDecimal deltaAmount;

    @PriceSensitive
    @Column(name = "delta_percent", precision = 10, scale = 2)
    private BigDecimal deltaPercent;

    @Column(name = "affected_product_count", nullable = false)
    private Integer affectedProductCount;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 32)
    @Builder.Default
    private Status status = Status.PENDING;

    @Enumerated(EnumType.STRING)
    @Column(name = "source_type", nullable = false, length = 32)
    private SourceType sourceType;

    @Column(name = "source_receive_record_id", length = 191)
    private String sourceReceiveRecordId;

    @Column(name = "source_receive_item_id")
    private Long sourceReceiveItemId;

    @Column(name = "approved_by")
    private Long approvedBy;

    @Column(name = "approved_at")
    private LocalDateTime approvedAt;

    @Column(name = "approval_comment", columnDefinition = "TEXT")
    private String approvalComment;

    @Column(name = "applied_at")
    private LocalDateTime appliedAt;

    public enum Status {
        PENDING, APPROVED, REJECTED
    }

    public enum SourceType {
        PURCHASE_RECEIVE, MANUAL_CHECK
    }
}
