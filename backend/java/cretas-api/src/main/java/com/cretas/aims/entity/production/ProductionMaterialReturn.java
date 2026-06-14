package com.cretas.aims.entity.production;

import com.cretas.aims.entity.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import lombok.ToString;
import org.hibernate.annotations.Where;

import java.math.BigDecimal;
import java.util.UUID;

@Data
@EqualsAndHashCode(callSuper = true)
@ToString(callSuper = true)
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "production_material_returns",
        indexes = {
                @Index(name = "idx_pmr_factory_status", columnList = "factory_id,return_status"),
                @Index(name = "idx_pmr_requisition", columnList = "requisition_id"),
                @Index(name = "idx_pmr_batch", columnList = "material_batch_id"),
                @Index(name = "idx_pmr_material_type", columnList = "material_type_id")
        }
)
@Where(clause = "deleted_at IS NULL")
public class ProductionMaterialReturn extends BaseEntity {

    @Id
    @Column(name = "id", nullable = false, length = 64)
    private String id;

    @Column(name = "factory_id", nullable = false, length = 64)
    private String factoryId;

    @Column(name = "requisition_id", nullable = false, length = 64)
    private String requisitionId;

    @Column(name = "requisition_item_id", nullable = false, length = 64)
    private String requisitionItemId;

    @Column(name = "material_type_id", nullable = false, length = 64)
    private String materialTypeId;

    @Column(name = "material_batch_id", nullable = false, length = 191)
    private String materialBatchId;

    @Column(name = "return_quantity", nullable = false, precision = 15, scale = 3)
    private BigDecimal returnQuantity;

    @Enumerated(EnumType.STRING)
    @Column(name = "return_status", nullable = false, length = 20)
    private ReturnStatus returnStatus = ReturnStatus.PENDING;

    @PrePersist
    void assignUUID() {
        if (id == null) {
            id = UUID.randomUUID().toString();
        }
    }

    public enum ReturnStatus {
        PENDING,
        EXECUTED
    }
}
