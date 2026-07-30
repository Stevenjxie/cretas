package com.cretas.aims.entity.bom;

import com.cretas.aims.entity.BaseEntity;
import com.cretas.aims.security.PriceSensitive;
import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;
import org.hibernate.annotations.Where;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Entity
@Table(name = "bom_price_adjustment_audits", indexes = {
        @Index(name = "idx_bpaa_proposal", columnList = "proposal_id"),
        @Index(name = "idx_bpaa_recipe_item", columnList = "recipe_item_id")
})
@Where(clause = "deleted_at IS NULL")
@Data
@SuperBuilder
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode(callSuper = true)
public class BomPriceAdjustmentAudit extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "proposal_id", nullable = false)
    private Long proposalId;

    @Column(name = "factory_id", nullable = false, length = 50)
    private String factoryId;

    @Column(name = "recipe_id", length = 191)
    private String recipeId;

    @Column(name = "recipe_item_id", nullable = false)
    private Long recipeItemId;

    @Column(name = "material_type_id", length = 191)
    private String materialTypeId;

    @PriceSensitive
    @Column(name = "before_unit_price", precision = 15, scale = 4)
    private BigDecimal beforeUnitPrice;

    @PriceSensitive
    @Column(name = "after_unit_price", precision = 15, scale = 4)
    private BigDecimal afterUnitPrice;

    @Column(name = "approved_by", nullable = false)
    private Long approvedBy;

    @Column(name = "approved_at", nullable = false)
    private LocalDateTime approvedAt;

    @Column(name = "approval_comment", columnDefinition = "TEXT")
    private String approvalComment;
}
