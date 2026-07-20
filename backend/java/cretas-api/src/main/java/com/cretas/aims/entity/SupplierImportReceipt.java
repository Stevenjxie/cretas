package com.cretas.aims.entity;

import jakarta.persistence.*;
import lombok.*;

import java.util.UUID;

@Data
@EqualsAndHashCode(callSuper = true)
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Entity
@Table(name = "supplier_import_receipts",
        uniqueConstraints = @UniqueConstraint(name = "uk_supplier_import_factory_key",
                columnNames = {"factory_id", "idempotency_key"}),
        indexes = @Index(name = "idx_supplier_import_factory_digest",
                columnList = "factory_id,file_digest"))
public class SupplierImportReceipt extends BaseEntity {
    @Id
    @Column(name = "id", nullable = false, length = 64)
    private String id;

    @PrePersist
    void assignId() { if (id == null) id = UUID.randomUUID().toString(); }

    @Column(name = "factory_id", nullable = false, length = 64)
    private String factoryId;
    @Column(name = "idempotency_key", nullable = false, length = 100)
    private String idempotencyKey;
    @Column(name = "file_digest", nullable = false, length = 64)
    private String fileDigest;
    @Column(name = "created_by", nullable = false)
    private Long createdBy;
    @Column(name = "created_count", nullable = false)
    private Integer createdCount;
    @Column(name = "supplier_ids", columnDefinition = "TEXT")
    private String supplierIds;
}
