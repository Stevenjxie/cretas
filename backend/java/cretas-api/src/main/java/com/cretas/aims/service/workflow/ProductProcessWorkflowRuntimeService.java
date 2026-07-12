package com.cretas.aims.service.workflow;

import com.cretas.aims.dto.WorkProcessTaskDTO;
import com.cretas.aims.dto.workflow.ProductionWorkflowRuntimeDTO;

import java.util.List;
import java.util.Optional;

public interface ProductProcessWorkflowRuntimeService {

    Optional<List<WorkProcessTaskDTO>> materializeIfActive(
            String factoryId,
            Long productionBatchId,
            String productTypeId);

    ProductionWorkflowRuntimeDTO getRuntime(String factoryId, Long productionBatchId);
}
