package com.cretas.aims.service.workflow;

import com.cretas.aims.dto.workflow.ProductProcessWorkflowActivationDTO;

public interface ProductProcessWorkflowActivationService {

    ProductProcessWorkflowActivationDTO activate(String factoryId, Long workflowId, Long operatorId);

    ProductProcessWorkflowActivationDTO deactivate(
            String factoryId,
            String productTypeId,
            Long expectedLockVersion);

    ProductProcessWorkflowActivationDTO get(String factoryId, String productTypeId);
}
