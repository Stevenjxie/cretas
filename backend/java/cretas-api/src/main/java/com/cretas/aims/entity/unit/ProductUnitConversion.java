package com.cretas.aims.entity.unit;

import com.cretas.aims.entity.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.experimental.SuperBuilder;
import org.hibernate.annotations.GenericGenerator;
import org.hibernate.annotations.Where;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/** Product-specific authority for conversions that cannot be inferred from canonical units. */
@Entity
@Table(name = "product_unit_conversions")
@Getter
@Setter
@SuperBuilder
@NoArgsConstructor
@EqualsAndHashCode(callSuper = true)
@Where(clause = "deleted_at IS NULL")
public class ProductUnitConversion extends BaseEntity {

    public enum SourceType {
        NET_CONTENT,
        PACKAGING,
        MANUAL
    }

    @Id
    @GeneratedValue(generator = "uuid2")
    @GenericGenerator(name = "uuid2", strategy = "uuid2")
    @Column(length = 36)
    private String id;

    @Column(name = "factory_id", nullable = false, length = 50)
    private String factoryId;

    @Column(name = "product_type_id", nullable = false, length = 100)
    private String productTypeId;

    @Column(name = "from_unit_code", nullable = false, length = 20)
    private String fromUnitCode;

    @Column(name = "to_unit_code", nullable = false, length = 20)
    private String toUnitCode;

    @Column(nullable = false, precision = 20, scale = 8)
    private BigDecimal factor;

    @Enumerated(EnumType.STRING)
    @Column(name = "source_type", nullable = false, length = 20)
    private SourceType sourceType;

    @Column(name = "is_primary_sales_conversion", nullable = false)
    private Boolean primarySalesConversion = false;

    @Column(name = "effective_from", nullable = false)
    private LocalDateTime effectiveFrom;

    @Column(name = "effective_to")
    private LocalDateTime effectiveTo;

    @Version
    @Column(nullable = false)
    private Long version = 0L;
}
