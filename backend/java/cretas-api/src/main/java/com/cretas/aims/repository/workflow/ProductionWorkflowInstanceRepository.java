package com.cretas.aims.repository.workflow;

import com.cretas.aims.entity.workflow.ProductionWorkflowInstance;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface ProductionWorkflowInstanceRepository
        extends JpaRepository<ProductionWorkflowInstance, Long> {

    Optional<ProductionWorkflowInstance> findByFactoryIdAndProductionBatchId(
            String factoryId,
            Long productionBatchId);
}
