package com.cretas.aims.entity.bom;

import com.cretas.aims.entity.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.Where;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@EqualsAndHashCode(callSuper = true)
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "bom_yield_suggestions",
        indexes = {
                @Index(name = "idx_bom_yield_suggestion_factory_status", columnList = "factory_id, status"),
                @Index(name = "idx_bom_yield_suggestion_product", columnList = "factory_id, product_type_id")
        },
        uniqueConstraints = {
                @UniqueConstraint(name = "uk_bom_yield_suggestion_source",
                        columnNames = {"factory_id", "product_type_id", "source_event_type", "source_event_id"})
        })
@Where(clause = "deleted_at IS NULL")
public class BomYieldSuggestion extends BaseEntity {

    public enum Status {
        PENDING,
        APPLIED,
        REJECTED
    }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "factory_id", nullable = false, length = 50)
    private String factoryId;

    @Column(name = "product_type_id", nullable = false, length = 100)
    private String productTypeId;

    @Column(name = "product_name", length = 100)
    private String productName;

    @Column(name = "bom_recipe_id", length = 191)
    private String bomRecipeId;

    @Column(name = "previous_yield_rate", precision = 6, scale = 2)
    private BigDecimal previousYieldRate;

    @Column(name = "suggested_yield_rate", nullable = false, precision = 6, scale = 2)
    private BigDecimal suggestedYieldRate;

    @Column(name = "sample_count", nullable = false)
    private Integer sampleCount;

    @Column(name = "excluded_sample_count", nullable = false)
    private Integer excludedSampleCount = 0;

    @Column(name = "guard_max_offset_percent", nullable = false, precision = 6, scale = 2)
    private BigDecimal guardMaxOffsetPercent;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private Status status = Status.PENDING;

    @Column(name = "source_event_type", nullable = false, length = 64)
    private String sourceEventType;

    @Column(name = "source_event_id", nullable = false, length = 191)
    private String sourceEventId;

    @Column(name = "generated_by", nullable = false, length = 64)
    private String generatedBy;

    @Column(name = "generated_at", nullable = false)
    private LocalDateTime generatedAt;

    @Column(name = "applied_by", length = 64)
    private String appliedBy;

    @Column(name = "applied_at")
    private LocalDateTime appliedAt;
}
