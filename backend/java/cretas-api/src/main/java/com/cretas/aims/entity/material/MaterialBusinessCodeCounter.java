package com.cretas.aims.entity.material;

import com.cretas.aims.entity.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import jakarta.persistence.Version;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.Where;

/** Factory-and-prefix scoped sequence state for material business code allocation. */
@Entity
@Table(name = "material_business_code_counters",
        uniqueConstraints = @UniqueConstraint(name = "uk_mbc_counter_factory_prefix",
                columnNames = {"factory_id", "code_prefix"}))
@Where(clause = "deleted_at IS NULL")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode(callSuper = true)
public class MaterialBusinessCodeCounter extends BaseEntity {

    public static final long MAX_SEQUENCE = 999_999L;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "factory_id", nullable = false, length = 50)
    private String factoryId;

    @Column(name = "code_prefix", nullable = false, length = 8)
    private String codePrefix;

    @Min(0)
    @Max(MAX_SEQUENCE)
    @Column(name = "last_allocated", nullable = false)
    @Builder.Default
    private Long lastAllocated = 0L;

    @Version
    @Column(name = "version", nullable = false)
    @Builder.Default
    private Long version = 0L;
}
