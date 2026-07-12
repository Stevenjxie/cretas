package com.cretas.aims.service;

import com.cretas.aims.dto.ProductProcessWorkflowDTO;
import com.cretas.aims.dto.ProductProcessWorkflowVersionSummaryDTO;

import java.util.List;
import java.util.Optional;

public interface ProductProcessWorkflowService {

    Optional<ProductProcessWorkflowDTO> getEditorDefinition(String factoryId, String productTypeId);

    ProductProcessWorkflowDTO saveDraft(
            String factoryId,
            String productTypeId,
            ProductProcessWorkflowDTO definition);

    ProductProcessWorkflowDTO publish(String factoryId, String productTypeId, Long lockVersion);

    /** 只读: 某产品的全部 Workflow 版本行(DRAFT + PUBLISHED), 按 definitionVersion 倒序。 */
    List<ProductProcessWorkflowVersionSummaryDTO> listVersions(String factoryId, String productTypeId);

    /** 只读: 某产品指定 definitionVersion 的完整图定义。 */
    ProductProcessWorkflowDTO getVersion(String factoryId, String productTypeId, Integer definitionVersion);
}
