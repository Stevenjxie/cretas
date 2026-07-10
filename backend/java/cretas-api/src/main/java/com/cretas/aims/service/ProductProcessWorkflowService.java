package com.cretas.aims.service;

import com.cretas.aims.dto.ProductProcessWorkflowDTO;

import java.util.Optional;

public interface ProductProcessWorkflowService {

    Optional<ProductProcessWorkflowDTO> getEditorDefinition(String factoryId, String productTypeId);

    ProductProcessWorkflowDTO saveDraft(
            String factoryId,
            String productTypeId,
            ProductProcessWorkflowDTO definition);

    ProductProcessWorkflowDTO publish(String factoryId, String productTypeId, Long lockVersion);
}
