package com.cretas.aims.service.workflow.impl;

import com.cretas.aims.dto.ProductProcessWorkflowDTO;
import com.cretas.aims.dto.workflow.ProductProcessWorkflowActivationDTO;
import com.cretas.aims.entity.ProductProcessWorkflow;
import com.cretas.aims.entity.ProductProcessWorkflowActivation;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.repository.ProductProcessWorkflowActivationRepository;
import com.cretas.aims.repository.ProductProcessWorkflowRepository;
import com.cretas.aims.service.validation.ProductProcessWorkflowCatalogValidator;
import com.cretas.aims.service.validation.ProductProcessWorkflowValidator;
import com.cretas.aims.service.validation.ProductProcessWorkflowUnitValidator;
import com.cretas.aims.service.workflow.CompiledProductProcessWorkflow;
import com.cretas.aims.service.workflow.ProductProcessWorkflowActivationService;
import com.cretas.aims.service.workflow.ProductProcessWorkflowRuntimeCompiler;
import com.cretas.aims.service.bom.BomWorkflowRevisionService;
import com.cretas.aims.service.workflow.WorkflowTopology;
import com.cretas.aims.service.workflow.WorkflowTopologyClassifier;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.OptimisticLockException;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;

@Service
@RequiredArgsConstructor
public class ProductProcessWorkflowActivationServiceImpl
        implements ProductProcessWorkflowActivationService {

    private final ProductProcessWorkflowActivationRepository activationRepository;
    private final ProductProcessWorkflowRepository workflowRepository;
    private final ProductProcessWorkflowValidator validator;
    private final ProductProcessWorkflowCatalogValidator catalogValidator;
    private final ProductProcessWorkflowUnitValidator unitValidator;
    private final ProductProcessWorkflowRuntimeCompiler compiler;
    private final BomWorkflowRevisionService bomWorkflowRevisionService;
    private final ObjectMapper objectMapper;

    @Override
    @Transactional
    public ProductProcessWorkflowActivationDTO activate(
            String factoryId,
            Long workflowId,
            Long operatorId) {
        ProductProcessWorkflow workflow = workflowRepository.findByIdAndFactoryId(workflowId, factoryId)
                .orElseThrow(this::productMismatch);
        if (workflow.getStatus() != ProductProcessWorkflow.Status.PUBLISHED) {
            throw notPublished();
        }
        ProductProcessWorkflowDTO definition = toDefinition(workflow);
        validator.validateForPublish(definition);
        catalogValidator.validateForPublish(factoryId, workflow.getProductTypeId(), definition);
        unitValidator.validateForPublish(factoryId, definition);
        WorkflowTopology topology = WorkflowTopologyClassifier.classify(definition);

        // 2B.2: 多产出已放开 (原 B1 single-output guard 移除)。仍编译一次校验发布版本可编译,
        // 编译失败即拒绝激活 (禁止降级处理)。
        compiler.compile(definition);
        // Runtime may only enable the exact revision frozen by the ACTIVE BOM.
        bomWorkflowRevisionService.requireExactPublishedRevisionForActiveBom(
                factoryId, workflow.getId(), workflow.getProductTypeId());

        // Serialize activation decisions inside one factory. The legacy activation table is
        // unique by anchor, so cross-anchor single-output uniqueness must be enforced here.
        workflowRepository.lockByFactoryId(factoryId);
        assertSingleOutputActivationUnique(factoryId, workflow, topology);

        ProductProcessWorkflowActivation activation = activationRepository
                .findByFactoryIdAndProductTypeId(factoryId, workflow.getProductTypeId())
                .orElseGet(ProductProcessWorkflowActivation::new);
        if (isAlreadyActive(activation, workflow)) {
            return toDTO(activation, topology);
        }

        activation.setFactoryId(factoryId);
        activation.setProductTypeId(workflow.getProductTypeId());
        activation.setActiveWorkflowId(workflow.getId());
        activation.setActiveDefinitionVersion(workflow.getDefinitionVersion());
        activation.setEnabled(true);
        activation.setActivatedBy(operatorId);
        activation.setActivatedAt(LocalDateTime.now());
        return toDTO(save(activation), topology);
    }

    @Override
    @Transactional
    public ProductProcessWorkflowActivationDTO deactivate(
            String factoryId,
            String productTypeId,
            Long expectedLockVersion) {
        ProductProcessWorkflowActivation activation = findActivation(factoryId, productTypeId);
        if (expectedLockVersion == null || !expectedLockVersion.equals(activation.getLockVersion())) {
            throw conflict();
        }
        activation.setEnabled(false);
        return toDTO(save(activation));
    }

    @Override
    @Transactional(readOnly = true)
    public ProductProcessWorkflowActivationDTO get(String factoryId, String productTypeId) {
        return activationRepository.findByFactoryIdAndProductTypeId(factoryId, productTypeId)
                .map(this::toDTO)
                .orElse(null);
    }

    private ProductProcessWorkflowActivation findActivation(String factoryId, String productTypeId) {
        return activationRepository.findByFactoryIdAndProductTypeId(factoryId, productTypeId)
                .orElseThrow(this::productMismatch);
    }

    private ProductProcessWorkflowActivation save(ProductProcessWorkflowActivation activation) {
        try {
            return activationRepository.saveAndFlush(activation);
        } catch (ObjectOptimisticLockingFailureException | OptimisticLockException
                 | DataIntegrityViolationException error) {
            throw conflict(error);
        }
    }

    private boolean isAlreadyActive(
            ProductProcessWorkflowActivation activation,
            ProductProcessWorkflow workflow) {
        return activation.getId() != null
                && Boolean.TRUE.equals(activation.getEnabled())
                && workflow.getId().equals(activation.getActiveWorkflowId())
                && workflow.getDefinitionVersion().equals(activation.getActiveDefinitionVersion());
    }

    private void assertSingleOutputActivationUnique(
            String factoryId,
            ProductProcessWorkflow requestedWorkflow,
            WorkflowTopology requestedTopology) {
        if (!requestedTopology.isSingleOutput()) return;
        String terminalSku = requestedTopology.terminalOutputSkuIds().getFirst();
        for (ProductProcessWorkflowActivation active
                : activationRepository.findByFactoryIdAndEnabledTrue(factoryId)) {
            if (Objects.equals(active.getProductTypeId(), requestedWorkflow.getProductTypeId())) {
                continue; // same internal family: explicit version switching remains valid
            }
            ProductProcessWorkflow competing = workflowRepository
                    .findByIdAndFactoryId(active.getActiveWorkflowId(), factoryId)
                    .orElse(null);
            if (competing == null
                    || competing.getStatus() != ProductProcessWorkflow.Status.PUBLISHED
                    || !Objects.equals(active.getActiveDefinitionVersion(), competing.getDefinitionVersion())) {
                continue;
            }
            ProductProcessWorkflowDTO competingDefinition = toDefinition(competing);
            if (Boolean.TRUE.equals(competing.getUnitReviewRequired())) {
                try {
                    unitValidator.validateForPublish(factoryId, competingDefinition);
                } catch (BusinessException invalidUnitContract) {
                    continue;
                }
            }
            WorkflowTopology competingTopology = WorkflowTopologyClassifier.classify(competingDefinition);
            if (competingTopology.isSingleOutput()
                    && competingTopology.terminalOutputSkuIds().contains(terminalSku)) {
                throw new BusinessException(409,
                        "该成品已存在启用的单产出 Workflow，请保存为同一 Workflow 的新版本或更换产品 SKU")
                        .withCode("WORKFLOW_SINGLE_OUTPUT_ALREADY_ACTIVE")
                        .withHint("停用冲突 Workflow，或在同一 Workflow 内发布并激活新版本")
                        .withHintTarget("terminalOutputProductTypeId")
                        .withSeverity("warning");
            }
        }
    }

    private ProductProcessWorkflowDTO toDefinition(ProductProcessWorkflow workflow) {
        ProductProcessWorkflowDTO dto = new ProductProcessWorkflowDTO();
        dto.setId(workflow.getId());
        dto.setFactoryId(workflow.getFactoryId());
        dto.setProductTypeId(workflow.getProductTypeId());
        dto.setSchemaVersion(workflow.getSchemaVersion());
        dto.setStatus(workflow.getStatus().name());
        dto.setVersion(workflow.getDefinitionVersion());
        dto.setLockVersion(workflow.getLockVersion());
        dto.setUnitReviewRequired(workflow.getUnitReviewRequired());
        dto.setNodes(readJson(workflow.getNodesJson(),
                new TypeReference<List<ProductProcessWorkflowDTO.Node>>() {}, "nodes"));
        dto.setEdges(readJson(workflow.getEdgesJson(),
                new TypeReference<List<ProductProcessWorkflowDTO.Edge>>() {}, "edges"));
        dto.setViewport(readJson(workflow.getViewportJson(),
                new TypeReference<ProductProcessWorkflowDTO.Viewport>() {}, "viewport"));
        return dto;
    }

    private <T> T readJson(String json, TypeReference<T> type, String field) {
        try {
            return objectMapper.readValue(json, type);
        } catch (JsonProcessingException error) {
            throw new BusinessException(500, "Workflow " + field + " data is invalid", error)
                    .withCode("PRODUCT_PROCESS_WORKFLOW_DATA_INVALID")
                    .withHint("Reload the workflow definition and publish a corrected version before activation")
                    .withSeverity("error");
        }
    }

    private ProductProcessWorkflowActivationDTO toDTO(ProductProcessWorkflowActivation activation) {
        ProductProcessWorkflowActivationDTO dto = new ProductProcessWorkflowActivationDTO();
        dto.setId(activation.getId());
        dto.setFactoryId(activation.getFactoryId());
        dto.setProductTypeId(activation.getProductTypeId());
        dto.setActiveWorkflowId(activation.getActiveWorkflowId());
        dto.setActiveDefinitionVersion(activation.getActiveDefinitionVersion());
        dto.setEnabled(activation.getEnabled());
        dto.setActivatedBy(activation.getActivatedBy());
        dto.setActivatedAt(activation.getActivatedAt());
        dto.setLockVersion(activation.getLockVersion());
        return dto;
    }

    private ProductProcessWorkflowActivationDTO toDTO(
            ProductProcessWorkflowActivation activation, WorkflowTopology topology) {
        ProductProcessWorkflowActivationDTO dto = toDTO(activation);
        dto.setWorkflowType(topology.type().name());
        dto.setTerminalOutputProductTypeIds(topology.terminalOutputSkuIds());
        dto.setRootInputProductTypeIds(topology.rootInputSkuIds());
        return dto;
    }

    private BusinessException notPublished() {
        return new BusinessException(409, "Only a published workflow can be activated")
                .withCode("WORKFLOW_NOT_PUBLISHED")
                .withHint("Publish this exact workflow version, then activate it again")
                .withSeverity("warning");
    }

    private BusinessException productMismatch() {
        return new BusinessException(400, "Workflow activation does not belong to this factory and product")
                .withCode("WORKFLOW_ACTIVATION_PRODUCT_MISMATCH")
                .withHint("Reload workflows for the current factory and product, then retry")
                .withSeverity("warning");
    }

    private BusinessException conflict() {
        return conflict(null);
    }

    private BusinessException conflict(Throwable cause) {
        return new BusinessException(409, "Workflow activation was updated by another operator", cause)
                .withCode("WORKFLOW_ACTIVATION_CONFLICT")
                .withHint("Reload the current activation and retry with its latest lockVersion")
                .withSeverity("warning");
    }
}
