package com.cretas.aims.service.impl;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.cretas.aims.dto.ProductProcessWorkflowDTO;
import com.cretas.aims.dto.ProductProcessWorkflowVersionSummaryDTO;
import com.cretas.aims.entity.ProductProcessWorkflow;
import com.cretas.aims.entity.ProductProcessWorkflowActivation;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.repository.ProductProcessWorkflowActivationRepository;
import com.cretas.aims.repository.ProductProcessWorkflowRepository;
import com.cretas.aims.repository.ProductTypeRepository;
import com.cretas.aims.service.ProductProcessWorkflowService;
import com.cretas.aims.service.validation.ProductProcessWorkflowCatalogValidator;
import com.cretas.aims.service.validation.ProductProcessWorkflowValidator;
import com.cretas.aims.service.validation.ProductProcessWorkflowUnitValidator;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class ProductProcessWorkflowServiceImpl implements ProductProcessWorkflowService {

    private final ProductProcessWorkflowRepository repository;
    private final ProductProcessWorkflowActivationRepository activationRepository;
    private final ObjectMapper objectMapper;
    private final ProductProcessWorkflowValidator validator;
    private final ProductProcessWorkflowCatalogValidator catalogValidator;
    private final ProductProcessWorkflowUnitValidator unitValidator;
    private final ProductTypeRepository productTypeRepository;

    @Override
    @Transactional(readOnly = true)
    public Optional<ProductProcessWorkflowDTO> getEditorDefinition(
            String factoryId,
            String productTypeId) {
        Optional<ProductProcessWorkflow> draft = find(factoryId, productTypeId, ProductProcessWorkflow.Status.DRAFT);
        if (draft.isPresent()) {
            return draft.map(this::toDTOWithUnitWarnings);
        }
        return find(factoryId, productTypeId, ProductProcessWorkflow.Status.PUBLISHED).map(this::toDTOWithUnitWarnings);
    }

    @Override
    @Transactional
    public ProductProcessWorkflowDTO saveDraft(
            String factoryId,
            String productTypeId,
            ProductProcessWorkflowDTO definition) {
        requireOwningProduct(factoryId, productTypeId);
        validator.validateForDraft(definition);
        var unitValidation = unitValidator.validate(factoryId, definition);
        definition.setUnitWarnings(java.util.stream.Stream.concat(
                        unitValidation.errors().stream(), unitValidation.warnings().stream())
                .toList());
        Optional<ProductProcessWorkflow> existingDraft = find(
                factoryId, productTypeId, ProductProcessWorkflow.Status.DRAFT);

        ProductProcessWorkflow entity;
        if (existingDraft.isPresent()) {
            entity = existingDraft.get();
            assertCurrentVersion(definition.getLockVersion(), entity);
        } else {
            entity = new ProductProcessWorkflow();
            entity.setFactoryId(factoryId);
            entity.setProductTypeId(productTypeId);
            entity.setStatus(ProductProcessWorkflow.Status.DRAFT);
            int nextVersion = find(factoryId, productTypeId, ProductProcessWorkflow.Status.PUBLISHED)
                    .map(ProductProcessWorkflow::getDefinitionVersion)
                    .orElse(0) + 1;
            entity.setDefinitionVersion(nextVersion);
        }

        entity.setSchemaVersion(definition.getSchemaVersion());
        entity.setNodesJson(writeJson(definition.getNodes()));
        entity.setEdgesJson(writeJson(definition.getEdges()));
        entity.setViewportJson(writeJson(definition.getViewport()));
        ProductProcessWorkflowDTO saved = toDTO(repository.saveAndFlush(entity));
        saved.setUnitWarnings(definition.getUnitWarnings());
        return saved;
    }

    @Override
    @Transactional
    public ProductProcessWorkflowDTO publish(String factoryId, String productTypeId, Long lockVersion) {
        requireOwningProduct(factoryId, productTypeId);
        ProductProcessWorkflow draft = find(factoryId, productTypeId, ProductProcessWorkflow.Status.DRAFT)
                .orElseThrow(() -> new BusinessException(409, "没有可发布的 Workflow 草稿")
                        .withCode("PRODUCT_PROCESS_WORKFLOW_DRAFT_MISSING")
                        .withHint("请先保存草稿，再发布版本")
                        .withSeverity("warning"));
        assertCurrentVersion(lockVersion, draft);
        ProductProcessWorkflowDTO definition = toDTO(draft);
        validator.validateForPublish(definition);
        catalogValidator.validateForPublish(factoryId, productTypeId, definition);
        unitValidator.validateForPublish(factoryId, definition);
        draft.setStatus(ProductProcessWorkflow.Status.PUBLISHED);
        return toDTO(repository.saveAndFlush(draft));
    }

    @Override
    @Transactional(readOnly = true)
    public List<ProductProcessWorkflowVersionSummaryDTO> listVersions(String factoryId, String productTypeId) {
        requireOwningProduct(factoryId, productTypeId);
        Optional<ProductProcessWorkflowActivation> activation = activationRepository
                .findByFactoryIdAndProductTypeId(factoryId, productTypeId);
        List<ProductProcessWorkflow> rows = repository
                .findByFactoryIdAndProductTypeIdOrderByDefinitionVersionDesc(factoryId, productTypeId);
        return rows.stream()
                .map(row -> toVersionSummaryDTO(row, activation))
                .collect(Collectors.toList());
    }

    @Override
    @Transactional(readOnly = true)
    public ProductProcessWorkflowDTO getVersion(String factoryId, String productTypeId, Integer definitionVersion) {
        requireOwningProduct(factoryId, productTypeId);
        ProductProcessWorkflow row = repository
                .findFirstByFactoryIdAndProductTypeIdAndDefinitionVersion(factoryId, productTypeId, definitionVersion)
                .orElseThrow(() -> new BusinessException(
                                404, "该产品不存在 Workflow 版本: " + definitionVersion)
                        .withCode("PRODUCT_PROCESS_WORKFLOW_VERSION_NOT_FOUND")
                        .withHint("请从版本历史列表中选择一个存在的版本号")
                        .withSeverity("warning"));
        return toDTO(row);
    }

    private ProductProcessWorkflowVersionSummaryDTO toVersionSummaryDTO(
            ProductProcessWorkflow row,
            Optional<ProductProcessWorkflowActivation> activation) {
        boolean active = activation
                .filter(a -> Boolean.TRUE.equals(a.getEnabled()))
                .map(a -> row.getId().equals(a.getActiveWorkflowId()))
                .orElse(false);
        return new ProductProcessWorkflowVersionSummaryDTO(
                row.getDefinitionVersion(),
                row.getStatus().name(),
                row.getUpdatedAt(),
                active);
    }

    private Optional<ProductProcessWorkflow> find(
            String factoryId,
            String productTypeId,
            ProductProcessWorkflow.Status status) {
        return repository.findFirstByFactoryIdAndProductTypeIdAndStatusOrderByDefinitionVersionDesc(
                factoryId, productTypeId, status);
    }

    private void requireOwningProduct(String factoryId, String productTypeId) {
        if (productTypeRepository.findByIdAndFactoryId(productTypeId, factoryId).isEmpty()) {
            throw new BusinessException(400, "Workflow 所属产品不存在或不属于当前工厂")
                    .withCode("PRODUCT_PROCESS_WORKFLOW_PRODUCT_INVALID")
                    .withHint("请重新选择当前工厂内的有效产品后再保存或发布 Workflow")
                    .withSeverity("warning");
        }
    }

    private void assertCurrentVersion(Long requestVersion, ProductProcessWorkflow entity) {
        if (requestVersion == null || !requestVersion.equals(entity.getLockVersion())) {
            throw new BusinessException(409, "该 Workflow 已被其他人更新")
                    .withCode("PRODUCT_PROCESS_WORKFLOW_CONFLICT")
                    .withHint("请重新加载最新版本；如需保留当前内容，可复制当前草稿 JSON")
                    .withSeverity("warning");
        }
    }

    private ProductProcessWorkflowDTO toDTO(ProductProcessWorkflow entity) {
        ProductProcessWorkflowDTO dto = new ProductProcessWorkflowDTO();
        dto.setId(entity.getId());
        dto.setFactoryId(entity.getFactoryId());
        dto.setProductTypeId(entity.getProductTypeId());
        dto.setSchemaVersion(entity.getSchemaVersion());
        dto.setStatus(entity.getStatus().name());
        dto.setVersion(entity.getDefinitionVersion());
        dto.setLockVersion(entity.getLockVersion());
        dto.setUnitReviewRequired(entity.getUnitReviewRequired());
        dto.setNodes(readJson(
                entity.getNodesJson(),
                new TypeReference<List<ProductProcessWorkflowDTO.Node>>() {},
                "nodes"));
        dto.setEdges(readJson(
                entity.getEdgesJson(),
                new TypeReference<List<ProductProcessWorkflowDTO.Edge>>() {},
                "edges"));
        dto.setViewport(readJson(
                entity.getViewportJson(),
                new TypeReference<ProductProcessWorkflowDTO.Viewport>() {},
                "viewport"));
        return dto;
    }

    private ProductProcessWorkflowDTO toDTOWithUnitWarnings(ProductProcessWorkflow entity) {
        ProductProcessWorkflowDTO dto = toDTO(entity);
        dto.setUnitWarnings(unitValidator.validate(entity.getFactoryId(), dto).errors());
        return dto;
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException error) {
            throw invalidStoredJson("Workflow 数据无法序列化", error);
        }
    }

    private <T> T readJson(String json, TypeReference<T> type, String field) {
        try {
            return objectMapper.readValue(json, type);
        } catch (JsonProcessingException error) {
            throw invalidStoredJson("Workflow " + field + " 数据损坏", error);
        }
    }

    private BusinessException invalidStoredJson(String message, JsonProcessingException cause) {
        return (BusinessException) new BusinessException(500, message, cause)
                .withCode("PRODUCT_PROCESS_WORKFLOW_DATA_INVALID")
                .withHint("请联系管理员检查该产品的 Workflow 图定义")
                .withSeverity("error");
    }
}
