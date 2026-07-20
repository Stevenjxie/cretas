package com.cretas.aims.service.validation;

import com.cretas.aims.dto.ProductProcessWorkflowDTO;
import com.cretas.aims.dto.workflow.ProductConfigurationCompletenessReport;
import com.cretas.aims.entity.ProductProcessWorkflow;
import com.cretas.aims.entity.ProductProcessWorkflowActivation;
import com.cretas.aims.entity.ProductType;
import com.cretas.aims.entity.bom.BomRecipe;
import com.cretas.aims.entity.bom.BomRecipeItem;
import com.cretas.aims.entity.bom.BomSeasoningItem;
import com.cretas.aims.entity.product.ProductPackagingSpec;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.repository.ProductProcessWorkflowActivationRepository;
import com.cretas.aims.repository.ProductProcessWorkflowRepository;
import com.cretas.aims.repository.ProductTypeRepository;
import com.cretas.aims.repository.bom.BomRecipeItemRepository;
import com.cretas.aims.repository.bom.BomRecipeRepository;
import com.cretas.aims.repository.bom.BomSeasoningItemRepository;
import com.cretas.aims.repository.product.ProductPackagingSpecRepository;
import com.cretas.aims.service.bom.BomWorkflowRevisionService;
import com.cretas.aims.service.unit.UnitContractService;
import com.cretas.aims.service.unit.UnitDimension;
import com.cretas.aims.service.workflow.PinnedWorkflowGraph;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * One evaluator for BOM configuration, activation, Workflow publication/activation and plan admission.
 * DRAFT Workflow is read only by this configuration service; runtime resolution remains ACTIVE-only.
 */
@Service
@RequiredArgsConstructor
public class ProductConfigurationReadinessService {

    private static final Set<String> AUXILIARY_POLICIES = Set.of("REQUIRED", "NOT_REQUIRED");

    private final ProductProcessWorkflowRepository workflowRepository;
    private final ProductProcessWorkflowActivationRepository activationRepository;
    private final ProductProcessWorkflowValidator workflowValidator;
    private final ProductProcessWorkflowCatalogValidator catalogValidator;
    private final ProductProcessWorkflowUnitValidator unitValidator;
    private final BomWorkflowRevisionService bomWorkflowRevisionService;
    private final BomRecipeRepository recipeRepository;
    private final BomRecipeItemRepository itemRepository;
    private final BomSeasoningItemRepository seasoningRepository;
    private final ProductPackagingSpecRepository packagingSpecRepository;
    private final ProductTypeRepository productTypeRepository;
    private final UnitContractService unitContractService;
    private final ObjectMapper objectMapper;

    @Transactional(readOnly = true)
    public WorkflowDraftContext requireWorkflowDraftCompleteForBomWrite(
            String factoryId,
            String productTypeId) {
        ProductProcessWorkflow draft = workflowRepository
                .findFirstByFactoryIdAndProductTypeIdAndStatusOrderByDefinitionVersionDesc(
                        factoryId, productTypeId, ProductProcessWorkflow.Status.DRAFT)
                .orElseThrow(() -> workflowFirstError("尚未创建 Workflow 草稿"));
        ProductProcessWorkflowDTO definition = toDefinition(draft);
        try {
            workflowValidator.validateStructureComplete(definition);
            catalogValidator.validateForBomConfiguration(factoryId, productTypeId, definition);
            unitValidator.validateForPublish(factoryId, definition);
        } catch (BusinessException error) {
            throw workflowFirstError(error.getMessage());
        }
        return new WorkflowDraftContext(draft.getId(), draft.getDefinitionVersion(), definition);
    }

    @Transactional(readOnly = true)
    public ProductConfigurationCompletenessReport evaluate(
            String factoryId,
            String productTypeId,
            String requestedRecipeId) {
        List<ProductConfigurationCompletenessReport.Issue> issues = new ArrayList<>();
        List<BomRecipe> versions = recipeRepository
                .findByFactoryIdAndProductTypeIdOrderByVersionDesc(factoryId, productTypeId);
        BomRecipe recipe = selectRecipe(factoryId, productTypeId, requestedRecipeId, versions);
        BomRecipe active = versions.stream()
                .filter(row -> row.getStatus() == BomRecipe.Status.ACTIVE && Boolean.TRUE.equals(row.getIsCurrent()))
                .findFirst().orElse(null);
        BomRecipe editableDraft = versions.stream()
                .filter(row -> row.getStatus() == BomRecipe.Status.DRAFT)
                .findFirst().orElse(null);
        String bomState = active != null && editableDraft != null ? "ACTIVE_WITH_DRAFT"
                : active != null ? "ACTIVE"
                : editableDraft != null ? "DRAFT_ONLY" : "NO_VERSION";

        ProductProcessWorkflow draft = hasPinnedWorkflow(recipe)
                ? null
                : workflowRepository
                        .findFirstByFactoryIdAndProductTypeIdAndStatusOrderByDefinitionVersionDesc(
                                factoryId, productTypeId, ProductProcessWorkflow.Status.DRAFT)
                        .orElse(null);
        ProductProcessWorkflowDTO workflowDefinition = null;
        PinnedWorkflowGraph pinnedGraph = null;
        boolean workflowComplete = false;
        if (hasPinnedWorkflow(recipe)) {
            try {
                pinnedGraph = bomWorkflowRevisionService.resolvePinnedGraph(factoryId, recipe);
                workflowDefinition = toDefinition(recipe, pinnedGraph);
                unitValidator.validateForPublish(factoryId, workflowDefinition);
                workflowComplete = true;
            } catch (BusinessException error) {
                issues.add(issue("WORKFLOW_PINNED_REVISION_INCOMPLETE", error.getMessage(), "workflow"));
            }
        } else if (draft == null) {
            issues.add(issue("WORKFLOW_DRAFT_MISSING", "请先创建并完成 Workflow 工序草稿", "workflow"));
        } else {
            try {
                workflowDefinition = toDefinition(draft);
                workflowValidator.validateStructureComplete(workflowDefinition);
                catalogValidator.validateForBomConfiguration(factoryId, productTypeId, workflowDefinition);
                unitValidator.validateForPublish(factoryId, workflowDefinition);
                workflowComplete = true;
            } catch (BusinessException error) {
                issues.add(issue("WORKFLOW_DRAFT_INCOMPLETE", error.getMessage(), "workflow"));
            }
        }

        List<ProductConfigurationCompletenessReport.ProcessAuxiliaryStatus> processStatuses = new ArrayList<>();
        List<ProductConfigurationCompletenessReport.PackagingLevelStatus> packagingStatuses = new ArrayList<>();
        boolean bomComplete = false;
        if (recipe == null) {
            issues.add(issue("BOM_DRAFT_MISSING", "Workflow 完成后请创建同一产品的 BOM 草稿", "bom"));
        } else if (!workflowComplete || workflowDefinition == null) {
            issues.add(issue("BOM_BLOCKED_BY_WORKFLOW", "BOM 配置尚未开放：请先完成 Workflow 工序配置", "workflow"));
        } else {
            bomComplete = evaluateBom(factoryId, productTypeId, recipe, workflowDefinition, pinnedGraph,
                    issues, processStatuses, packagingStatuses);
        }

        boolean bomActive = recipe != null
                && recipe.getStatus() == BomRecipe.Status.ACTIVE
                && Boolean.TRUE.equals(recipe.getIsCurrent())
                && bomComplete;
        Optional<ProductProcessWorkflowActivation> activation = activationRepository
                .findByFactoryIdAndProductTypeId(factoryId, productTypeId)
                .filter(row -> Boolean.TRUE.equals(row.getEnabled()));
        boolean workflowEnabled = activation.isPresent() && bomActive;
        String stage = workflowEnabled ? "WORKFLOW_ENABLED"
                : bomActive ? "BOM_ACTIVE"
                : bomComplete ? "BOM_COMPLETE"
                : workflowComplete ? "BOM_DRAFT_CONFIGURABLE"
                : "SKU_CREATED";

        return ProductConfigurationCompletenessReport.builder()
                .factoryId(factoryId)
                .productTypeId(productTypeId)
                .stage(stage)
                .bomState(bomState)
                .workflowDraftId(draft == null ? null : draft.getId())
                .workflowDraftVersion(draft == null ? null : draft.getDefinitionVersion())
                .bomRecipeId(recipe == null ? null : recipe.getId())
                .bomVersion(recipe == null ? null : recipe.getVersion())
                .workflowDraftComplete(workflowComplete)
                .bomConfigurable(workflowComplete)
                .bomComplete(bomComplete)
                .bomActive(bomActive)
                .workflowEnabled(workflowEnabled)
                .workflowPublishAllowed(bomActive)
                .workflowEnableAllowed(bomActive)
                .productionPlanAllowed(workflowEnabled)
                .issues(issues)
                .processAuxiliaryStatuses(processStatuses)
                .packagingLevels(packagingStatuses)
                .build();
    }

    @Transactional(readOnly = true)
    public ProductConfigurationCompletenessReport requireBomCompleteForActivation(
            String factoryId,
            BomRecipe recipe) {
        ProductConfigurationCompletenessReport report = evaluate(factoryId, recipe.getProductTypeId(), recipe.getId());
        if (!report.isBomComplete()) {
            String detail = report.getIssues().stream().map(ProductConfigurationCompletenessReport.Issue::getMessage)
                    .reduce((left, right) -> left + "；" + right).orElse("BOM 配置不完整");
            throw new BusinessException(409, detail)
                    .withCode("BOM_COMPLETENESS_REQUIRED")
                    .withHint("请按缺失项补齐原料关系、工序辅料确认和各包装层包材后再激活")
                    .withHintTarget("bomCompleteness")
                    .withSeverity("warning");
        }
        return report;
    }

    @Transactional(readOnly = true)
    public ProductConfigurationCompletenessReport requireActiveBomComplete(
            String factoryId,
            String productTypeId) {
        BomRecipe active = recipeRepository.findByFactoryIdAndProductTypeIdAndIsCurrentTrueAndStatus(
                        factoryId, productTypeId, BomRecipe.Status.ACTIVE)
                .orElseThrow(() -> new BusinessException(409, "当前产品没有已激活的 BOM")
                        .withCode("ACTIVE_BOM_REQUIRED")
                        .withHint("请先完成并激活 BOM，再发布或启用 Workflow")
                        .withSeverity("warning"));
        ProductConfigurationCompletenessReport report = evaluate(factoryId, productTypeId, active.getId());
        if (!report.isBomActive()) {
            throw new BusinessException(409, "当前已激活 BOM 未通过完整性校验")
                    .withCode("ACTIVE_BOM_INCOMPLETE")
                    .withHint("请创建新 BOM 版本补齐缺失项并激活；历史版本不会被改写")
                    .withSeverity("warning");
        }
        return report;
    }

    private boolean evaluateBom(
            String factoryId,
            String productTypeId,
            BomRecipe recipe,
            ProductProcessWorkflowDTO workflow,
            PinnedWorkflowGraph pinnedGraph,
            List<ProductConfigurationCompletenessReport.Issue> issues,
            List<ProductConfigurationCompletenessReport.ProcessAuxiliaryStatus> processStatuses,
            List<ProductConfigurationCompletenessReport.PackagingLevelStatus> packagingStatuses) {
        List<BomRecipeItem> items = itemRepository.findByRecipeIdOrderBySortOrderAsc(recipe.getId());
        long rawCount = items.stream().filter(item -> "RAW".equalsIgnoreCase(item.getMaterialCategory())).count();
        if (rawCount == 0) {
            issues.add(issue("BOM_RAW_REQUIRED", "尚未配置任何主原料关系", "rawMaterials"));
        }

        List<BomSeasoningItem> seasonings = seasoningRepository.findByRecipeIdOrderBySeqAsc(recipe.getId());
        for (WorkflowProcessContext process : processContexts(workflow, pinnedGraph)) {
            ProductProcessWorkflowDTO.Node node = process.node();
            Map<String, Object> data = node.getData() == null ? Map.of() : node.getData();
            String processId = process.workProcessId();
            String processNodeId = process.workflowProcessNodeId();
            String processName = string(data.get("processName"));
            String policy = string(data.get("auxiliaryPolicy"));
            long bindingCount = seasonings.stream()
                    .filter(item -> matchesProcess(item, processNodeId, processId, pinnedGraph != null))
                    .count();
            boolean validPolicy = AUXILIARY_POLICIES.contains(policy);
            boolean complete = validPolicy && ("NOT_REQUIRED".equals(policy) || bindingCount > 0);
            processStatuses.add(ProductConfigurationCompletenessReport.ProcessAuxiliaryStatus.builder()
                    .workflowProcessNodeId(processNodeId).workProcessId(processId)
                    .processName(processName).auxiliaryPolicy(policy)
                    .bindingCount(bindingCount).complete(complete).build());
            if (!validPolicy) {
                issues.add(issue("BOM_AUXILIARY_DECISION_REQUIRED",
                        "工序「" + display(processName, processId) + "」尚未确认是否需要辅料",
                        processNodeId));
            } else if ("REQUIRED".equals(policy) && bindingCount == 0) {
                issues.add(issue("BOM_AUXILIARY_REQUIRED",
                        "工序「" + display(processName, processId) + "」需要辅料但尚未配置",
                        processNodeId));
            }
        }

        List<BomRecipeItem> packagingItems = items.stream()
                .filter(item -> "PACKAGING".equalsIgnoreCase(item.getMaterialCategory())).toList();
        ProductType product = productTypeRepository.findByIdAndFactoryId(productTypeId, factoryId).orElse(null);
        boolean basePackagingRequired = product != null && unitContractService.describe(factoryId, product.getUnit())
                .map(unit -> unit.dimension() == UnitDimension.PACKAGE || unit.dimension() == UnitDimension.COUNT)
                .orElse(false);
        long baseCount = packagingItems.stream().filter(item -> item.getPackagingSpecId() == null).count();
        if (basePackagingRequired) {
            packagingStatuses.add(ProductConfigurationCompletenessReport.PackagingLevelStatus.builder()
                    .packagingSpecId(null).name("基本规格").packageUnit(product.getUnit())
                    .baseUnit(product.getUnit()).materialCount(baseCount).complete(baseCount > 0).build());
            if (baseCount == 0) {
                issues.add(issue("BOM_BASE_PACKAGING_REQUIRED", "基本销售规格尚未配置包材", "packaging:base"));
            }
        }
        for (ProductPackagingSpec spec : packagingSpecRepository
                .findByFactoryIdAndProductTypeIdAndActiveTrueOrderBySortOrderAscCreatedAtAsc(factoryId, productTypeId)) {
            long count = packagingItems.stream().filter(item -> spec.getId().equals(item.getPackagingSpecId())).count();
            packagingStatuses.add(ProductConfigurationCompletenessReport.PackagingLevelStatus.builder()
                    .packagingSpecId(spec.getId()).name(spec.getName()).packageUnit(spec.getPackageUnit())
                    .baseUnit(spec.getBaseUnit()).materialCount(count).complete(count > 0).build());
            if (count == 0) {
                issues.add(issue("BOM_PACKAGING_LEVEL_REQUIRED",
                        "包装规格「" + spec.getName() + "」尚未配置该层新增包材",
                        "packaging:" + spec.getId()));
            }
        }
        return issues.stream().noneMatch(issue -> issue.getCode().startsWith("BOM_"));
    }

    private List<WorkflowProcessContext> processContexts(
            ProductProcessWorkflowDTO workflow,
            PinnedWorkflowGraph pinnedGraph) {
        if (pinnedGraph == null) {
            return workflow.getNodes().stream()
                    .filter(node -> "PROCESS".equals(node.getKind()))
                    .map(node -> new WorkflowProcessContext(
                            node.getId(),
                            string(node.getData() == null ? null : node.getData().get("workProcessId")),
                            node))
                    .toList();
        }
        Map<String, ProductProcessWorkflowDTO.Node> nodesById = new LinkedHashMap<>();
        for (ProductProcessWorkflowDTO.Node node : pinnedGraph.nodes()) {
            nodesById.putIfAbsent(node.getId(), node);
        }
        return pinnedGraph.processes().stream()
                .map(step -> new WorkflowProcessContext(
                        step.processNodeId(), step.workProcessId(), nodesById.get(step.processNodeId())))
                .filter(process -> process.node() != null)
                .toList();
    }

    private boolean matchesProcess(
            BomSeasoningItem item,
            String workflowProcessNodeId,
            String workProcessId,
            boolean pinned) {
        if (workflowProcessNodeId != null && workflowProcessNodeId.equals(item.getWorkflowProcessNodeId())) {
            return true;
        }
        return !pinned
                && item.getWorkflowProcessNodeId() == null
                && workProcessId != null
                && workProcessId.equals(item.getWorkProcessId());
    }

    private boolean hasPinnedWorkflow(BomRecipe recipe) {
        return recipe != null
                && (recipe.getWorkflowRevisionId() != null
                || recipe.getWorkflowRevisionHash() != null
                || recipe.getWorkflowNodesSnapshotJson() != null
                || recipe.getWorkflowEdgesSnapshotJson() != null);
    }

    private BomRecipe selectRecipe(
            String factoryId,
            String productTypeId,
            String requestedRecipeId,
            List<BomRecipe> versions) {
        if (requestedRecipeId == null || requestedRecipeId.isBlank()) {
            return versions.stream().filter(row -> row.getStatus() == BomRecipe.Status.DRAFT).findFirst()
                    .orElseGet(() -> versions.stream()
                            .filter(row -> row.getStatus() == BomRecipe.Status.ACTIVE
                                    && Boolean.TRUE.equals(row.getIsCurrent()))
                            .findFirst().orElse(null));
        }
        return versions.stream().filter(row -> requestedRecipeId.equals(row.getId())).findFirst()
                .orElseThrow(() -> new BusinessException(404, "BOM 不属于当前工厂或产品")
                        .withCode("BOM_RECIPE_SCOPE_MISMATCH"));
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
        dto.setNodes(read(workflow.getNodesJson(), new TypeReference<List<ProductProcessWorkflowDTO.Node>>() {}));
        dto.setEdges(read(workflow.getEdgesJson(), new TypeReference<List<ProductProcessWorkflowDTO.Edge>>() {}));
        dto.setViewport(read(workflow.getViewportJson(), new TypeReference<ProductProcessWorkflowDTO.Viewport>() {}));
        return dto;
    }

    private ProductProcessWorkflowDTO toDefinition(BomRecipe recipe, PinnedWorkflowGraph graph) {
        ProductProcessWorkflowDTO dto = new ProductProcessWorkflowDTO();
        dto.setId(graph.workflowId());
        dto.setFactoryId(recipe.getFactoryId());
        dto.setProductTypeId(recipe.getProductTypeId());
        dto.setSchemaVersion(recipe.getWorkflowSchemaVersion());
        dto.setStatus("PINNED");
        dto.setVersion(graph.definitionVersion());
        dto.setRevisionId(graph.workflowRevisionId());
        dto.setRevisionHash(graph.revisionHash());
        dto.setNodes(graph.nodes());
        dto.setEdges(graph.edges());
        dto.setViewport(new ProductProcessWorkflowDTO.Viewport(0D, 0D, 1D));
        return dto;
    }

    private <T> T read(String json, TypeReference<T> type) {
        try {
            return objectMapper.readValue(json, type);
        } catch (JsonProcessingException error) {
            throw new BusinessException(500, "Workflow 草稿数据损坏", error)
                    .withCode("PRODUCT_PROCESS_WORKFLOW_DATA_INVALID");
        }
    }

    private BusinessException workflowFirstError(String detail) {
        return new BusinessException(409, "请先完成 Workflow 工序配置：" + detail)
                .withCode("WORKFLOW_DRAFT_COMPLETE_REQUIRED")
                .withHint("请返回产品-工序配置，完成起点、终点、工序与连线后再配置 BOM")
                .withHintTarget("workflowConfiguration")
                .withSeverity("warning");
    }

    private ProductConfigurationCompletenessReport.Issue issue(String code, String message, String target) {
        return ProductConfigurationCompletenessReport.Issue.builder()
                .code(code).message(message).target(target).build();
    }

    private String string(Object value) {
        return value == null ? null : String.valueOf(value).trim();
    }

    private String display(String name, String fallback) {
        return name == null || name.isBlank() ? fallback : name;
    }

    private record WorkflowProcessContext(
            String workflowProcessNodeId,
            String workProcessId,
            ProductProcessWorkflowDTO.Node node) { }

    public record WorkflowDraftContext(
            Long workflowId,
            Integer definitionVersion,
            ProductProcessWorkflowDTO definition) {
        public Set<String> workProcessIds() {
            LinkedHashSet<String> ids = new LinkedHashSet<>();
            for (ProductProcessWorkflowDTO.Node node : definition.getNodes()) {
                if (!"PROCESS".equals(node.getKind()) || node.getData() == null) continue;
                Object id = node.getData().get("workProcessId");
                if (id != null && !String.valueOf(id).isBlank()) ids.add(String.valueOf(id));
            }
            return ids;
        }
    }
}
