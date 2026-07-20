package com.cretas.aims.repository;

import com.cretas.aims.entity.WorkProcessGovernanceAudit;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface WorkProcessGovernanceAuditRepository
        extends JpaRepository<WorkProcessGovernanceAudit, String> {

    Optional<WorkProcessGovernanceAudit> findByFactoryIdAndIdempotencyKey(
            String factoryId,
            String idempotencyKey);
}
