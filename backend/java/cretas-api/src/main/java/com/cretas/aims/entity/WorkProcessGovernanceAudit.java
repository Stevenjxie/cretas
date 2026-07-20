package com.cretas.aims.entity;

import com.cretas.aims.dto.WorkProcessDTO;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.Where;

/** Immutable audit record for future-selection-only duplicate governance. */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "work_process_governance_audits", indexes = {
        @Index(name = "idx_wp_governance_factory_created", columnList = "factory_id, created_at")
})
@Where(clause = "deleted_at IS NULL")
public class WorkProcessGovernanceAudit extends BaseEntity {

    @Id
    @Column(name = "id", nullable = false, length = 50)
    private String id;

    @Column(name = "factory_id", nullable = false, length = 50)
    private String factoryId;

    @Column(name = "idempotency_key", nullable = false, length = 100)
    private String idempotencyKey;

    @Enumerated(EnumType.STRING)
    @Column(name = "mode", nullable = false, length = 32)
    private WorkProcessDTO.GovernanceMode mode;

    @Column(name = "master_process_id", nullable = false, length = 50)
    private String masterProcessId;

    @Column(name = "governed_process_ids", nullable = false, columnDefinition = "text")
    private String governedProcessIds;

    @Column(name = "operator", nullable = false, length = 100)
    private String operator;

    @Column(name = "reason", length = 500)
    private String reason;
}
