package com.cretas.aims.logistics.entity;

import com.cretas.aims.entity.BaseEntity;
import com.cretas.aims.logistics.entity.enums.OwnershipType;
import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;
import org.hibernate.annotations.Where;

import java.util.UUID;

/**
 * 独立司机资料。一车可绑 2-3 司机 (白班/晚班), 绑定关系见 {@link LogisticsVehicleDriver}。
 *
 * <p>Maps to table {@code logistics_drivers} (V20261028_01)。
 */
@Entity
@Table(name = "logistics_drivers",
        indexes = {
                @Index(name = "idx_ld_factory", columnList = "factory_id")
        })
@Where(clause = "deleted_at IS NULL")
@Data
@EqualsAndHashCode(callSuper = true)
@SuperBuilder
@NoArgsConstructor
@AllArgsConstructor
public class LogisticsDriver extends BaseEntity {

    @Id
    @Column(name = "id", length = 36, nullable = false, updatable = false)
    private String id;

    @PrePersist
    protected void assignDefaults() {
        if (id == null) {
            id = UUID.randomUUID().toString();
        }
        if (employmentType == null) {
            employmentType = OwnershipType.OWNED;
        }
        if (active == null) {
            active = true;
        }
    }

    @Column(name = "factory_id", length = 64, nullable = false)
    private String factoryId;

    @Column(name = "name", length = 128, nullable = false)
    private String name;

    @Column(name = "phone", length = 32)
    private String phone;

    @Enumerated(EnumType.STRING)
    @Column(name = "employment_type", length = 16, nullable = false)
    private OwnershipType employmentType;

    @Column(name = "service_areas", length = 512)
    private String serviceAreas;

    @Column(name = "available_from", length = 8)
    private String availableFrom;

    @Column(name = "available_to", length = 8)
    private String availableTo;

    @Column(name = "active", nullable = false)
    private Boolean active;

    @Version
    @Column(name = "version", nullable = false)
    private Long version;
}
