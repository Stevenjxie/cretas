package com.cretas.aims.service.bom;

import com.cretas.aims.dto.ProductProcessWorkflowDTO;
import com.cretas.aims.dto.bom.BomWorkflowRevisionPinRequest;
import com.cretas.aims.dto.workflow.WorkflowRevisionCandidateDTO;
import com.cretas.aims.entity.ProductProcessWorkflow;
import com.cretas.aims.entity.ProductProcessWorkflowActivation;
import com.cretas.aims.entity.ProductProcessWorkflowRevision;
import com.cretas.aims.entity.bom.BomRecipe;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.repository.ProductProcessWorkflowActivationRepository;
import com.cretas.aims.repository.ProductProcessWorkflowRepository;
import com.cretas.aims.repository.ProductProcessWorkflowRevisionRepository;
import com.cretas.aims.repository.bom.BomRecipeRepository;
import com.cretas.aims.service.validation.ProductProcessWorkflowCatalogValidator;
import com.cretas.aims.service.validation.ProductProcessWorkflowValidator;
import com.cretas.aims.service.workflow.PinnedWorkflowGraph;
import com.cretas.aims.service.workflow.WorkflowRevisionSnapshotService;
import com.cretas.aims.service.workflow.WorkflowActualIoSemantics;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/** Stable Workflow revision selection and exact target-SKU graph slicing for BOM drafts. */
@Service
@RequiredArgsConstructor
public class BomWorkflowRevisionService {

    private static final Set<String> OUTPUT_ROLES = Set.of("MAIN", "CO_PRODUCT", "BY_PRODUCT");

    private final BomRecipeRepository recipeRepository;
    private final ProductProcessWorkflowRepository workflowRepository;
    private final ProductProcessWorkflowRevisionRepository revisionRepository;
    private final ProductProcessWorkflowActivationRepository activationRepository;
    private final ProductProcessWorkflowValidator validator;
    private final ProductProcessWorkflowCatalogValidator catalogValidator;
    private final WorkflowRevisionSnapshotService revisionSnapshotService;
    private final ObjectMapper objectMapper;

    @Transactional(readOnly = true)
    public List<WorkflowRevisionCandidateDTO> listCompatible(String factoryId, String recipeId) {
        BomRecipe recipe = requireRecipe(factoryId, recipeId);
        Optional<ProductProcessWorkflowActivation> activation = activationRepository
                .findByFactoryIdAndProductTypeId(factoryId, recipe.getProductTypeId())
                .filter(row -> Boolean.TRUE.equals(row.getEnabled()));

        List<WorkflowRevisionCandidateDTO> result = new ArrayList<>();
        Set<String> represented = new HashSet<>();
        for (ProductProcessWorkflowRevision revision : revisionRepository.findCurrentFactoryDraftRevisions(factoryId)) {
            represented.add(revision.getWorkflowId() + ":" + revision.getRevisionHash());
            result.add(toCandidate(revision, recipe.getProductTypeId(), activation));
        }
        if (recipe.getWorkflowRevisionId() != null) {
            revisionRepository.findByIdAndFactoryId(recipe.getWorkflowRevisionId(), factoryId)
                    .filter(revision -> represented.add(revision.getWorkflowId() + ":" + revision.getRevisionHash()))
                    .ifPresent(revision -> result.add(toCandidate(revision, recipe.getProductTypeId(), activation)));
        }

        // Existing deployments have published/draft rows created before the revision table. Expose them
        // read-only without a data backfill; an explicit user pin captures the immutable revision atomically.
        for (ProductProcessWorkflow workflow : workflowRepository
                .findByFactoryIdAndProductTypeIdOrderByDefinitionVersionDesc(factoryId, recipe.getProductTypeId())) {
            String hash = revisionSnapshotService.hash(workflow);
            if (represented.add(workflow.getId() + ":" + hash)) {
                result.add(toLegacyCandidate(workflow, hash, recipe.getProductTypeId(), activation));
            }
        }

        result.sort(Comparator
                .comparing(WorkflowRevisionCandidateDTO::isCompatible).reversed()
                .thenComparing(Comparator.comparing(
                        (WorkflowRevisionCandidateDTO row) -> "DRAFT".equals(row.getStatus())).reversed())
                .thenComparing(WorkflowRevisionCandidateDTO::getSavedAt,
                        Comparator.nullsLast(Comparator.reverseOrder()))
                .thenComparing(WorkflowRevisionCandidateDTO::getDefinitionVersion,
                        Comparator.nullsLast(Comparator.reverseOrder())));
        result.stream().filter(WorkflowRevisionCandidateDTO::isCompatible).findFirst()
                .ifPresent(candidate -> candidate.setRecommended(true));
        return result;
    }

    /**
     * Resolve exactly one current, saved, structurally complete Workflow DRAFT for a new BOM.
     * The factory-wide search is intentional: a Workflow owner can be an upstream material while
     * the target BOM belongs to any terminal output SKU.
     */
    @Transactional
    public WorkflowBinding autoBindUniqueDraft(String factoryId, BomRecipe recipe) {
        if (!factoryId.equals(recipe.getFactoryId()) || recipe.getStatus() != BomRecipe.Status.DRAFT) {
            throw invalid(409, "只有当前工厂的 BOM 草稿可以自动关联工艺", "BOM_WORKFLOW_AUTO_BIND_READ_ONLY");
        }
        List<WorkflowBinding> matches = new ArrayList<>();
        List<BusinessException> targetFailures = new ArrayList<>();
        for (ProductProcessWorkflowRevision storedRevision :
                revisionRepository.findCurrentFactoryDraftRevisions(factoryId)) {
            ProductProcessWorkflowRevision revision =
                    repairCurrentDraftRevisionIfNeeded(factoryId, storedRevision);
            try {
                matches.add(binding(revision, recipe.getProductTypeId()));
            } catch (BusinessException error) {
                // Incompatible revisions are not candidates; ambiguity is evaluated after the full scan.
                if (containsFinishedSku(revision, recipe.getProductTypeId())) {
                    targetFailures.add(error);
                }
            }
        }
        if (matches.isEmpty()) {
            if (targetFailures.size() == 1) {
                throw targetFailures.getFirst();
            }
            throw invalid(409,
                    "未找到唯一且结构完整、包含当前 SKU 终端产出的 Workflow 草稿",
                    "BOM_WORKFLOW_DRAFT_NOT_FOUND");
        }
        Long activeBomWorkflowId = recipeRepository
                .findByFactoryIdAndProductTypeIdAndIsCurrentTrueAndStatus(
                        factoryId, recipe.getProductTypeId(), BomRecipe.Status.ACTIVE)
                .map(BomRecipe::getWorkflowId)
                .orElse(null);
        Optional<ProductProcessWorkflowActivation> enabledActivation = activationRepository
                .findByFactoryIdAndProductTypeId(factoryId, recipe.getProductTypeId())
                .filter(row -> Boolean.TRUE.equals(row.getEnabled()));
        WorkflowBinding authoritative = uniqueLineageBinding(
                matches, activeBomWorkflowId, "当前生效 BOM");
        if (authoritative == null) {
            authoritative = uniqueLineageBinding(
                    matches,
                    enabledActivation.map(ProductProcessWorkflowActivation::getActiveWorkflowId)
                            .orElse(null),
                    "当前启用工艺");
        }
        if (authoritative != null) {
            applyBinding(recipe, authoritative);
            recipeRepository.saveAndFlush(recipe);
            return authoritative;
        }
        if (matches.size() > 1) {
            String candidates = matches.stream()
                    .map(match -> "workflowId=" + match.revision().getWorkflowId()
                            + ", revisionId=" + match.revision().getId()
                            + ", version=" + match.revision().getDefinitionVersion())
                    .collect(Collectors.joining("；"));
            throw invalid(409,
                    "找到多个可用于当前 SKU 的 Workflow 草稿，系统不能替您猜测",
                    "BOM_WORKFLOW_DRAFT_AMBIGUOUS")
                    .withHint("冲突候选：" + candidates)
                    .withHintTarget("workflow");
        }
        WorkflowBinding match = matches.getFirst();
        applyBinding(recipe, match);
        recipeRepository.saveAndFlush(recipe);
        return match;
    }

    private WorkflowBinding uniqueLineageBinding(
            List<WorkflowBinding> matches,
            Long workflowId,
            String authority) {
        if (workflowId == null) return null;
        List<WorkflowBinding> lineageMatches = matches.stream()
                .filter(match -> Objects.equals(workflowId, match.revision().getWorkflowId()))
                .toList();
        if (lineageMatches.size() > 1) {
            String revisions = lineageMatches.stream()
                    .map(match -> String.valueOf(match.revision().getId()))
                    .collect(Collectors.joining(","));
            throw invalid(409,
                    authority + " 对应多个当前 Workflow 修订",
                    "BOM_WORKFLOW_AUTHORITY_CONFLICT")
                    .withHint("workflowId=" + workflowId + "，revisionIds=" + revisions)
                    .withHintTarget("workflow");
        }
        return lineageMatches.stream().findFirst().orElse(null);
    }

    /**
     * A historical rollout could leave a mutable draft pointing at a stale/corrupt
     * revision identity. Never rewrite that immutable row: recapture the current
     * draft content and move only the draft pointer to the new valid revision.
     */
    private ProductProcessWorkflowRevision repairCurrentDraftRevisionIfNeeded(
            String factoryId, ProductProcessWorkflowRevision revision) {
        if (Objects.equals(revision.getRevisionHash(), revisionSnapshotService.hash(revision))) {
            return revision;
        }
        ProductProcessWorkflow workflow = workflowRepository
                .findByIdAndFactoryId(revision.getWorkflowId(), factoryId)
                .filter(candidate -> candidate.getStatus() == ProductProcessWorkflow.Status.DRAFT)
                .filter(candidate -> Objects.equals(candidate.getCurrentRevisionId(), revision.getId()))
                .orElseThrow(() -> invalid(
                        409,
                        "Workflow 修订内容哈希不一致，且该修订已不是当前可修复草稿",
                        "BOM_WORKFLOW_REVISION_HASH_INVALID"));
        ProductProcessWorkflowRevision repaired;
        String workflowHash = revisionSnapshotService.hash(workflow);
        if (Objects.equals(workflowHash, revision.getRevisionHash())) {
            boolean referenced = java.util.Arrays.stream(BomRecipe.Status.values())
                    .anyMatch(status -> !recipeRepository
                            .findByFactoryIdAndWorkflowRevisionIdAndStatusOrderByProductTypeIdAsc(
                                    factoryId, revision.getId(), status)
                            .isEmpty());
            if (referenced) {
                throw invalid(409,
                        "Workflow 修订内容哈希不一致，且已有 BOM 固定该修订，不能自动修复",
                        "BOM_WORKFLOW_REVISION_HASH_INVALID");
            }
            // This row never represented its advertised hash and has no consumer.
            // Repair the invalid DRAFT snapshot in place without touching history.
            revision.setProductTypeId(workflow.getProductTypeId());
            revision.setDefinitionVersion(workflow.getDefinitionVersion());
            revision.setSchemaVersion(workflow.getSchemaVersion());
            revision.setNodesJson(workflow.getNodesJson());
            revision.setEdgesJson(workflow.getEdgesJson());
            revision.setViewportJson(workflow.getViewportJson());
            ProductProcessWorkflowDTO repairedDefinition =
                    revisionSnapshotService.definition(revision);
            revision.setProcessCount((int) repairedDefinition.getNodes().stream()
                    .filter(node -> "PROCESS".equals(node.getKind()))
                    .count());
            try {
                validator.validateStructureComplete(repairedDefinition);
                revision.setStructurallyComplete(true);
                revision.setValidationMessage(null);
            } catch (BusinessException error) {
                revision.setStructurallyComplete(false);
                revision.setValidationMessage(error.getMessage());
            }
            repaired = revisionRepository.saveAndFlush(revision);
        } else {
            repaired = revisionSnapshotService.capture(workflow);
        }
        if (!Objects.equals(repaired.getRevisionHash(), revisionSnapshotService.hash(repaired))) {
            throw invalid(409, "Workflow 修订重新捕获后仍无法验证",
                    "BOM_WORKFLOW_REVISION_HASH_INVALID");
        }
        workflow.setCurrentRevisionId(repaired.getId());
        workflow.setCurrentRevisionHash(repaired.getRevisionHash());
        workflowRepository.saveAndFlush(workflow);
        return repaired;
    }

    private boolean containsFinishedSku(
            ProductProcessWorkflowRevision revision,
            String productTypeId) {
        try {
            return revisionSnapshotService.definition(revision).getNodes().stream()
                    .filter(node -> "FINISHED_GOOD".equals(node.getKind()))
                    .anyMatch(node -> productTypeId.equals(string(node.getData(), "skuId")));
        } catch (BusinessException invalidRevision) {
            return false;
        }
    }

    @Transactional
    public WorkflowBinding bindExactRevision(String factoryId, BomRecipe recipe, Long revisionId) {
        ProductProcessWorkflowRevision revision = revisionRepository.findByIdAndFactoryId(revisionId, factoryId)
                .orElseThrow(() -> invalid(409, "固定的 Workflow 修订不存在于当前工厂",
                        "BOM_WORKFLOW_REVISION_NOT_FOUND"));
        WorkflowBinding match = binding(revision, recipe.getProductTypeId());
        applyBinding(recipe, match);
        recipeRepository.saveAndFlush(recipe);
        return match;
    }

    @Transactional(readOnly = true)
    public WorkflowBinding resolveExactBinding(
            String factoryId, BomRecipe recipe, Long revisionId) {
        if (!factoryId.equals(recipe.getFactoryId())) {
            throw invalid(404, "BOM 不存在于当前工厂", "BOM_WORKFLOW_RECIPE_NOT_FOUND");
        }
        ProductProcessWorkflowRevision revision = revisionRepository
                .findByIdAndFactoryId(revisionId, factoryId)
                .orElseThrow(() -> invalid(409,
                        "目标 Workflow 修订不存在于当前工厂",
                        "BOM_WORKFLOW_REVISION_NOT_FOUND"));
        return binding(revision, recipe.getProductTypeId());
    }

    @Transactional(readOnly = true)
    public Optional<ProductProcessWorkflowRevision> findNewerCompatibleDraft(
            String factoryId, BomRecipe recipe) {
        if (recipe.getWorkflowId() == null || recipe.getWorkflowRevisionId() == null) return Optional.empty();
        List<ProductProcessWorkflowRevision> candidates = revisionRepository.findCurrentFactoryDraftRevisions(factoryId)
                .stream()
                .filter(revision -> Objects.equals(recipe.getWorkflowId(), revision.getWorkflowId()))
                .filter(revision -> !Objects.equals(recipe.getWorkflowRevisionId(), revision.getId()))
                .filter(revision -> incompatibility(revision, recipe.getProductTypeId()) == null)
                .toList();
        if (candidates.size() > 1) {
            throw invalid(409, "同一 Workflow 存在多个当前草稿修订，无法确定升级目标",
                    "BOM_WORKFLOW_UPGRADE_AMBIGUOUS");
        }
        return candidates.stream().findFirst();
    }

    /**
     * Explicit upgrade only succeeds when every persisted identity in the old target slice still
     * exists with the same stable node/port/edge identity and unit contract.
     */
    @Transactional
    public WorkflowBinding upgradeToLatestCompatibleDraft(String factoryId, BomRecipe recipe) {
        ProductProcessWorkflowRevision target = findNewerCompatibleDraft(factoryId, recipe)
                .orElseThrow(() -> invalid(409, "当前没有可升级的兼容工艺修订",
                        "BOM_WORKFLOW_UPGRADE_NOT_AVAILABLE"));
        ProductProcessWorkflowDTO oldDefinition = definitionFromRecipe(recipe);
        PinnedWorkflowGraph oldGraph = resolveTargetGraph(
                recipe.getWorkflowRevisionId(), oldDefinition, recipe.getProductTypeId());
        WorkflowBinding targetBinding = binding(target, recipe.getProductTypeId());
        validateStableUpgrade(oldDefinition, oldGraph, targetBinding.definition(), targetBinding.graph());
        applyBinding(recipe, targetBinding);
        recipeRepository.saveAndFlush(recipe);
        return targetBinding;
    }

    @Transactional
    public BomRecipe pin(String factoryId, String recipeId, BomWorkflowRevisionPinRequest request) {
        BomRecipe recipe = recipeRepository.lockByIdAndFactoryId(recipeId, factoryId)
                .orElseThrow(() -> invalid(404, "BOM 不存在于当前工厂", "BOM_WORKFLOW_RECIPE_NOT_FOUND"));
        if (recipe.getStatus() != BomRecipe.Status.DRAFT) {
            throw invalid(409, "只有 BOM 草稿可以选择 Workflow 修订", "BOM_WORKFLOW_PIN_READ_ONLY");
        }
        ProductProcessWorkflowRevision revision = resolveRequestedRevision(factoryId, recipe, request);
        WorkflowBinding match = binding(revision, recipe.getProductTypeId());
        PinnedWorkflowGraph graph = match.graph();
        if (graph.processes().isEmpty()) {
            throw invalid(409, "所选 Workflow 修订没有可配置的工序", "BOM_WORKFLOW_REVISION_HAS_NO_PROCESS");
        }

        applyBinding(recipe, match);
        return recipeRepository.saveAndFlush(recipe);
    }

    @Transactional(readOnly = true)
    public PinnedWorkflowGraph resolvePinnedGraph(String factoryId, BomRecipe recipe) {
        if (!factoryId.equals(recipe.getFactoryId())) {
            throw invalid(404, "BOM 不存在于当前工厂", "BOM_WORKFLOW_RECIPE_NOT_FOUND");
        }
        if (recipe.getWorkflowRevisionHash() == null
                || recipe.getWorkflowNodesSnapshotJson() == null
                || recipe.getWorkflowEdgesSnapshotJson() == null) {
            throw invalid(409, "BOM 尚未选择已保存的 Workflow 修订", "BOM_WORKFLOW_REVISION_REQUIRED");
        }
        ProductProcessWorkflowDTO definition = definitionFromRecipe(recipe);
        validateStructureForBom(definition);
        catalogValidator.validateForBomConfiguration(factoryId, recipe.getProductTypeId(), definition);
        return resolveTargetGraph(recipe.getWorkflowRevisionId(), definition, recipe.getProductTypeId());
    }

    @Transactional(readOnly = true)
    public List<TerminalOutput> resolvePinnedTerminalOutputs(String factoryId, BomRecipe recipe) {
        resolvePinnedGraph(factoryId, recipe);
        return resolveTerminalOutputs(definitionFromRecipe(recipe));
    }

    /**
     * Resolve the exact terminal membership of every process from stable node identities.
     * A process may belong to all outputs, one output, or a strict subset of three-plus outputs.
     */
    @Transactional(readOnly = true)
    public Map<String, CostScopeProfile> resolveProcessCostProfiles(String factoryId, BomRecipe recipe) {
        PinnedWorkflowGraph targetGraph = resolvePinnedGraph(factoryId, recipe);
        ProductProcessWorkflowDTO definition = definitionFromRecipe(recipe);
        List<TerminalOutput> outputs = resolveTerminalOutputs(definition);
        Map<String, LinkedHashSet<TerminalOutput>> memberships = new HashMap<>();
        for (TerminalOutput output : outputs) {
            resolveTargetGraph(recipe.getWorkflowRevisionId(), definition, output.productTypeId())
                    .processes().stream()
                    .map(PinnedWorkflowGraph.ProcessStep::processNodeId)
                    .distinct()
                    .forEach(processNodeId -> memberships
                            .computeIfAbsent(processNodeId, ignored -> new LinkedHashSet<>())
                            .add(output));
        }
        LinkedHashMap<String, CostScopeProfile> profiles = new LinkedHashMap<>();
        for (PinnedWorkflowGraph.ProcessStep process : targetGraph.processes()) {
            List<TerminalOutput> members = memberships
                    .getOrDefault(process.processNodeId(), new LinkedHashSet<>()).stream()
                    .sorted(Comparator.comparing(TerminalOutput::terminalNodeId))
                    .toList();
            String scope = members.size() == outputs.size()
                    ? "SHARED"
                    : members.size() == 1 ? "OUTPUT_EXCLUSIVE" : "OUTPUT_GROUP";
            profiles.put(process.processNodeId(), new CostScopeProfile(
                    scope,
                    members.stream().map(TerminalOutput::terminalNodeId).toList(),
                    members.stream().map(TerminalOutput::productTypeId).toList()));
        }
        return Map.copyOf(profiles);
    }

    @Transactional(readOnly = true)
    public Map<String, String> resolveProcessCostScopes(String factoryId, BomRecipe recipe) {
        return resolveProcessCostProfiles(factoryId, recipe).entrySet().stream()
                .collect(Collectors.toUnmodifiableMap(
                        Map.Entry::getKey,
                        entry -> entry.getValue().costScope()));
    }

    public static String canonicalCostScopeKey(List<String> terminalNodeIds) {
        return terminalNodeIds.stream()
                .filter(Objects::nonNull)
                .distinct()
                .sorted()
                .collect(Collectors.joining(","));
    }

    @Transactional(readOnly = true)
    public ProductProcessWorkflowRevision requireExactPublishedRevisionForActiveBom(
            String factoryId, Long workflowId, String productTypeId) {
        ProductProcessWorkflow workflow = workflowRepository.findByIdAndFactoryId(workflowId, factoryId)
                .filter(row -> productTypeId.equals(row.getProductTypeId()))
                .filter(row -> row.getStatus() == ProductProcessWorkflow.Status.PUBLISHED)
                .orElseThrow(() -> invalid(409, "只能启用当前工厂已发布的 Workflow", "WORKFLOW_NOT_PUBLISHED"));
        Long revisionId = workflow.getCurrentRevisionId();
        if (revisionId == null || workflow.getCurrentRevisionHash() == null) {
            throw invalid(409, "已发布 Workflow 缺少保存修订，请另存草稿并重新发布",
                    "WORKFLOW_PUBLISHED_REVISION_MISSING");
        }
        ProductProcessWorkflowRevision revision = revisionRepository.findByIdAndFactoryId(revisionId, factoryId)
                .filter(row -> workflowId.equals(row.getWorkflowId()))
                .filter(row -> productTypeId.equals(row.getProductTypeId()))
                .filter(row -> workflow.getCurrentRevisionHash().equals(row.getRevisionHash()))
                .orElseThrow(() -> invalid(409, "Workflow 当前修订身份不一致", "WORKFLOW_REVISION_IDENTITY_MISMATCH"));
        requireCompleteActiveFamily(factoryId, revision);
        return revision;
    }

    /**
     * Publication gate: the active BOM must already pin the exact immutable revision
     * that is about to be published. The revision is still DRAFT at this point, so
     * this check deliberately does not require a published workflow status.
     */
    @Transactional(readOnly = true)
    public BomRecipe requireActiveBomPinsRevision(
            String factoryId,
            String productTypeId,
            ProductProcessWorkflowRevision revision) {
        if (revision == null
                || !factoryId.equals(revision.getFactoryId())
                || !productTypeId.equals(revision.getProductTypeId())) {
            throw invalid(409, "Workflow 修订不属于当前工厂或 SKU",
                    "WORKFLOW_REVISION_SCOPE_INVALID");
        }
        return requireCompleteActiveFamily(factoryId, revision);
    }

    @Transactional(readOnly = true)
    public String resolveMainOutputProductTypeId(
            String factoryId,
            ProductProcessWorkflowRevision revision) {
        if (revision == null || !factoryId.equals(revision.getFactoryId())) {
            throw invalid(409, "Workflow 修订不属于当前工厂",
                    "WORKFLOW_REVISION_SCOPE_INVALID");
        }
        return resolveTerminalOutputs(revisionSnapshotService.definition(revision)).stream()
                .filter(output -> output.outputRole() == BomRecipe.OutputRole.MAIN)
                .map(TerminalOutput::productTypeId)
                .findFirst()
                .orElseThrow(() -> invalid(409, "Workflow 缺少唯一主产出",
                        "BOM_WORKFLOW_MAIN_OUTPUT_REQUIRED"));
    }

    @Transactional(readOnly = true)
    public List<TerminalOutput> resolveTerminalOutputsForRevision(
            String factoryId,
            ProductProcessWorkflowRevision revision) {
        if (revision == null || !factoryId.equals(revision.getFactoryId())) {
            throw invalid(409, "Workflow 修订不属于当前工厂",
                    "WORKFLOW_REVISION_SCOPE_INVALID");
        }
        return resolveTerminalOutputs(revisionSnapshotService.definition(revision));
    }

    private BomRecipe requireCompleteActiveFamily(
            String factoryId, ProductProcessWorkflowRevision revision) {
        ProductProcessWorkflowDTO definition = requireCompatible(
                revision, resolveTerminalOutputs(revisionSnapshotService.definition(revision)).stream()
                        .filter(output -> output.outputRole() == BomRecipe.OutputRole.MAIN)
                        .map(TerminalOutput::productTypeId)
                        .findFirst()
                        .orElseThrow());
        List<TerminalOutput> requiredOutputs = resolveTerminalOutputs(definition);
        List<BomRecipe> activeRows = recipeRepository
                .findByFactoryIdAndWorkflowRevisionIdAndStatusOrderByProductTypeIdAsc(
                        factoryId, revision.getId(), BomRecipe.Status.ACTIVE)
                .stream()
                .filter(recipe -> Boolean.TRUE.equals(recipe.getIsCurrent()))
                .filter(recipe -> Objects.equals(revision.getWorkflowId(), recipe.getWorkflowId()))
                .filter(recipe -> Objects.equals(revision.getRevisionHash(), recipe.getWorkflowRevisionHash()))
                .toList();
        Map<String, BomRecipe> activeByProduct = activeRows.stream().collect(Collectors.toMap(
                BomRecipe::getProductTypeId, Function.identity(), (left, right) -> left));
        for (TerminalOutput output : requiredOutputs) {
            BomRecipe active = activeByProduct.get(output.productTypeId());
            if (active == null
                    || !Objects.equals(output.terminalNodeId(), active.getTargetTerminalNodeId())
                    || output.outputRole() != active.getOutputRole()
                    || output.costAllocationRatio().compareTo(active.getCostAllocationRatio()) != 0) {
                throw invalid(409, "终端 SKU " + output.productTypeId()
                                + " 没有固定当前 Workflow 修订的完整 ACTIVE BOM",
                        "WORKFLOW_ACTIVE_BOM_REVISION_MISMATCH")
                        .withHint("请在当前页面右侧打开 BOM，升级到最新工艺并激活新版本后重试")
                        .withHintTarget("bom");
            }
            resolvePinnedGraph(factoryId, active);
        }
        Set<String> familyIds = activeRows.stream().map(BomRecipe::getBomFamilyId)
                .filter(Objects::nonNull).collect(Collectors.toSet());
        if (requiredOutputs.size() > 1 && (familyIds.size() != 1 || activeRows.size() != requiredOutputs.size())) {
            throw invalid(409, "多产出 Workflow 的 ACTIVE BOM 不属于同一个完整 BOM Family",
                    "WORKFLOW_ACTIVE_BOM_FAMILY_INCOMPLETE")
                    .withHint("请在当前页面右侧补齐所有产出 SKU 的 BOM，并激活同一完整版本后重试")
                    .withHintTarget("bom");
        }
        return activeByProduct.get(requiredOutputs.stream()
                .filter(output -> output.outputRole() == BomRecipe.OutputRole.MAIN)
                .findFirst().orElseThrow().productTypeId());
    }

    private ProductProcessWorkflowRevision resolveRequestedRevision(
            String factoryId, BomRecipe recipe, BomWorkflowRevisionPinRequest request) {
        if (request == null) {
            throw invalid(400, "请选择 Workflow 修订", "BOM_WORKFLOW_REVISION_REQUIRED");
        }
        if (request.getRevisionId() != null) {
            ProductProcessWorkflowRevision revision = revisionRepository
                    .findByIdAndFactoryId(request.getRevisionId(), factoryId)
                    .orElseThrow(() -> invalid(400, "Workflow 修订不属于当前工厂",
                            "BOM_WORKFLOW_REVISION_SCOPE_INVALID"));
            if (request.getRevisionHash() != null
                    && !request.getRevisionHash().equals(revision.getRevisionHash())) {
                throw invalid(409, "Workflow 修订已变化，请刷新后重选", "BOM_WORKFLOW_REVISION_STALE");
            }
            return revision;
        }
        if (request.getWorkflowId() == null || request.getRevisionHash() == null) {
            throw invalid(400, "请选择带稳定修订标识的 Workflow", "BOM_WORKFLOW_REVISION_REQUIRED");
        }
        ProductProcessWorkflow workflow = workflowRepository.findByIdAndFactoryId(request.getWorkflowId(), factoryId)
                .orElseThrow(() -> invalid(400, "Workflow 不属于当前工厂",
                        "BOM_WORKFLOW_REVISION_SCOPE_INVALID"));
        if (!request.getRevisionHash().equals(revisionSnapshotService.hash(workflow))) {
            throw invalid(409, "Workflow 草稿已变化，请刷新后选择最新保存修订", "BOM_WORKFLOW_REVISION_STALE");
        }
        ProductProcessWorkflowRevision revision = revisionSnapshotService.capture(workflow);
        workflow.setCurrentRevisionId(revision.getId());
        workflow.setCurrentRevisionHash(revision.getRevisionHash());
        workflowRepository.save(workflow);
        return revision;
    }

    private WorkflowRevisionCandidateDTO toCandidate(
            ProductProcessWorkflowRevision revision,
            String targetProductTypeId,
            Optional<ProductProcessWorkflowActivation> activation) {
        String reason = incompatibility(revision, targetProductTypeId);
        return WorkflowRevisionCandidateDTO.builder()
                .revisionId(revision.getId())
                .workflowId(revision.getWorkflowId())
                .definitionVersion(revision.getDefinitionVersion())
                .revisionNumber(revision.getRevisionNumber())
                .revisionHash(revision.getRevisionHash())
                .status(revision.getStatus().name())
                .savedAt(revision.getCreatedAt())
                .processCount(revision.getProcessCount())
                .enabled(isEnabled(activation, revision.getWorkflowId()))
                .compatible(reason == null)
                .incompatibilityReason(reason)
                .build();
    }

    private WorkflowRevisionCandidateDTO toLegacyCandidate(
            ProductProcessWorkflow workflow,
            String hash,
            String targetProductTypeId,
            Optional<ProductProcessWorkflowActivation> activation) {
        ProductProcessWorkflowRevision transientRevision = new ProductProcessWorkflowRevision();
        transientRevision.setFactoryId(workflow.getFactoryId());
        transientRevision.setProductTypeId(workflow.getProductTypeId());
        transientRevision.setWorkflowId(workflow.getId());
        transientRevision.setDefinitionVersion(workflow.getDefinitionVersion());
        transientRevision.setRevisionHash(hash);
        transientRevision.setSchemaVersion(workflow.getSchemaVersion());
        transientRevision.setNodesJson(workflow.getNodesJson());
        transientRevision.setEdgesJson(workflow.getEdgesJson());
        transientRevision.setViewportJson(workflow.getViewportJson());
        transientRevision.setStatus(workflow.getStatus() == ProductProcessWorkflow.Status.PUBLISHED
                ? ProductProcessWorkflowRevision.Status.PUBLISHED
                : ProductProcessWorkflowRevision.Status.DRAFT);
        ProductProcessWorkflowDTO definition = revisionSnapshotService.definition(transientRevision);
        String reason = incompatibility(transientRevision, targetProductTypeId);
        int processCount = (int) definition.getNodes().stream()
                .filter(node -> "PROCESS".equals(node.getKind())).count();
        return WorkflowRevisionCandidateDTO.builder()
                .workflowId(workflow.getId())
                .definitionVersion(workflow.getDefinitionVersion())
                .revisionHash(hash)
                .status(workflow.getStatus().name())
                .savedAt(workflow.getUpdatedAt())
                .processCount(processCount)
                .enabled(isEnabled(activation, workflow.getId()))
                .compatible(reason == null)
                .incompatibilityReason(reason)
                .build();
    }

    private String incompatibility(ProductProcessWorkflowRevision revision, String targetProductTypeId) {
        try {
            requireCompatible(revision, targetProductTypeId);
            return null;
        } catch (BusinessException error) {
            return error.getMessage();
        }
    }

    private ProductProcessWorkflowDTO requireCompatible(
            ProductProcessWorkflowRevision revision, String targetProductTypeId) {
        if (!Objects.equals(revision.getRevisionHash(), revisionSnapshotService.hash(revision))) {
            throw invalid(409, "Workflow 修订内容哈希不一致", "BOM_WORKFLOW_REVISION_HASH_INVALID");
        }
        ProductProcessWorkflowDTO definition = revisionSnapshotService.definition(revision);
        validateStructureForBom(definition);
        catalogValidator.validateForBomConfiguration(revision.getFactoryId(), targetProductTypeId, definition);
        resolveTargetGraph(revision.getId(), definition, targetProductTypeId);
        return definition;
    }

    private void validateStructureForBom(ProductProcessWorkflowDTO definition) {
        try {
            validator.validateStructureComplete(definition);
        } catch (BusinessException error) {
            if ("PRODUCT_PROCESS_WORKFLOW_OUTPUT_CONTRACT_REQUIRED".equals(error.getErrorCode())) {
                throw invalid(409, error.getMessage(), "BOM_WORKFLOW_MULTI_OUTPUT_CONTRACT_REQUIRED");
            }
            if ("PRODUCT_PROCESS_WORKFLOW_OUTPUT_CONTRACT_INVALID".equals(error.getErrorCode())) {
                throw invalid(409, error.getMessage(), "BOM_WORKFLOW_MULTI_OUTPUT_CONTRACT_INVALID");
            }
            throw error;
        }
    }

    private WorkflowBinding binding(
            ProductProcessWorkflowRevision revision, String targetProductTypeId) {
        ProductProcessWorkflowDTO definition = requireCompatible(revision, targetProductTypeId);
        PinnedWorkflowGraph graph = resolveTargetGraph(revision.getId(), definition, targetProductTypeId);
        List<TerminalOutput> outputs = resolveTerminalOutputs(definition);
        TerminalOutput target = outputs.stream()
                .filter(output -> targetProductTypeId.equals(output.productTypeId()))
                .findFirst()
                .orElseThrow(() -> invalid(409, "Workflow 修订没有当前 SKU 的终端产出",
                        "BOM_WORKFLOW_TARGET_TERMINAL_INVALID"));
        return new WorkflowBinding(revision, definition, graph, outputs, target);
    }

    private void applyBinding(BomRecipe recipe, WorkflowBinding binding) {
        ProductProcessWorkflowRevision revision = binding.revision();
        recipe.setWorkflowRevisionId(revision.getId());
        recipe.setWorkflowId(revision.getWorkflowId());
        recipe.setWorkflowDefinitionVersion(revision.getDefinitionVersion());
        recipe.setWorkflowRevisionHash(revision.getRevisionHash());
        recipe.setWorkflowSchemaVersion(revision.getSchemaVersion());
        recipe.setWorkflowNodesSnapshotJson(revision.getNodesJson());
        recipe.setWorkflowEdgesSnapshotJson(revision.getEdgesJson());
        recipe.setTargetTerminalNodeId(binding.target().terminalNodeId());
        recipe.setOutputRole(binding.target().outputRole());
        recipe.setCostAllocationRatio(binding.target().costAllocationRatio());
    }

    private ProductProcessWorkflowDTO definitionFromRecipe(BomRecipe recipe) {
        ProductProcessWorkflowDTO dto = new ProductProcessWorkflowDTO();
        dto.setId(recipe.getWorkflowId());
        dto.setFactoryId(recipe.getFactoryId());
        dto.setProductTypeId(recipe.getProductTypeId());
        dto.setSchemaVersion(recipe.getWorkflowSchemaVersion());
        dto.setStatus("PINNED");
        dto.setVersion(recipe.getWorkflowDefinitionVersion());
        dto.setRevisionId(recipe.getWorkflowRevisionId());
        dto.setRevisionHash(recipe.getWorkflowRevisionHash());
        dto.setNodes(read(recipe.getWorkflowNodesSnapshotJson(), new TypeReference<>() {}, "nodes"));
        dto.setEdges(read(recipe.getWorkflowEdgesSnapshotJson(), new TypeReference<>() {}, "edges"));
        dto.setViewport(new ProductProcessWorkflowDTO.Viewport(0D, 0D, 1D));
        return dto;
    }

    /** Shared exact target-SKU reverse-DAG resolver used by BOM draft and ACTIVE runtime paths. */
    public static PinnedWorkflowGraph resolveTargetGraph(
            Long revisionId, ProductProcessWorkflowDTO definition, String targetProductTypeId) {
        Map<String, ProductProcessWorkflowDTO.Node> nodeById = definition.getNodes().stream()
                .collect(Collectors.toMap(ProductProcessWorkflowDTO.Node::getId, Function.identity(),
                        (left, right) -> left, LinkedHashMap::new));
        Map<String, List<ProductProcessWorkflowDTO.Edge>> incoming = new HashMap<>();
        Map<String, List<ProductProcessWorkflowDTO.Edge>> outgoing = new HashMap<>();
        for (ProductProcessWorkflowDTO.Edge edge : definition.getEdges()) {
            incoming.computeIfAbsent(edge.getTarget(), ignored -> new ArrayList<>()).add(edge);
            outgoing.computeIfAbsent(edge.getSource(), ignored -> new ArrayList<>()).add(edge);
        }
        List<ProductProcessWorkflowDTO.Node> terminals = definition.getNodes().stream()
                .filter(node -> "FINISHED_GOOD".equals(node.getKind()))
                .filter(node -> targetProductTypeId.equals(string(node.getData(), "skuId")))
                .filter(node -> outgoing.getOrDefault(node.getId(), List.of()).isEmpty())
                .toList();
        if (terminals.size() != 1) {
            throw invalid(409, terminals.isEmpty()
                            ? "Workflow 修订没有目标 SKU 的终端成品 Cell"
                            : "Workflow 修订重复绑定目标 SKU 终端 Cell",
                    "BOM_WORKFLOW_TARGET_TERMINAL_INVALID");
        }

        Set<String> ancestors = new LinkedHashSet<>();
        ArrayDeque<String> pending = new ArrayDeque<>();
        pending.add(terminals.getFirst().getId());
        while (!pending.isEmpty()) {
            String nodeId = pending.removeFirst();
            if (!ancestors.add(nodeId)) continue;
            for (ProductProcessWorkflowDTO.Edge edge : incoming.getOrDefault(nodeId, List.of())) {
                pending.addLast(edge.getSource());
            }
        }
        List<ProductProcessWorkflowDTO.Edge> sliceEdges = definition.getEdges().stream()
                .filter(edge -> ancestors.contains(edge.getSource()) && ancestors.contains(edge.getTarget()))
                .toList();
        List<ProductProcessWorkflowDTO.Node> rawRoots = ancestors.stream()
                .map(nodeById::get)
                .filter(Objects::nonNull)
                .filter(node -> "RAW_MATERIAL".equals(node.getKind()))
                .filter(node -> incoming.getOrDefault(node.getId(), List.of()).stream()
                        .noneMatch(edge -> ancestors.contains(edge.getSource())))
                .toList();
        List<String> rootMaterialTypeIds = rawRoots.stream()
                .map(node -> string(node.getData(), "skuId"))
                .filter(Objects::nonNull)
                .distinct()
                .toList();
        if (rawRoots.isEmpty() || rootMaterialTypeIds.size() != rawRoots.size()) {
            throw invalid(409, "目标 SKU 路径必须回溯到可识别的原料入口",
                    "BOM_WORKFLOW_ROOT_INPUT_INVALID");
        }

        validateMultiOutputContracts(ancestors, nodeById);

        Map<String, Integer> indegree = new LinkedHashMap<>();
        Map<String, List<String>> sliceOutgoing = new HashMap<>();
        ancestors.forEach(id -> indegree.put(id, 0));
        for (ProductProcessWorkflowDTO.Edge edge : sliceEdges) {
            sliceOutgoing.computeIfAbsent(edge.getSource(), ignored -> new ArrayList<>()).add(edge.getTarget());
            indegree.merge(edge.getTarget(), 1, Integer::sum);
        }
        Comparator<String> nodeOrder = Comparator
                .comparingDouble((String id) -> coordinate(nodeById.get(id), true))
                .thenComparingDouble(id -> coordinate(nodeById.get(id), false))
                .thenComparing(Function.identity());
        ArrayDeque<String> ready = indegree.entrySet().stream()
                .filter(entry -> entry.getValue() == 0)
                .map(Map.Entry::getKey)
                .sorted(nodeOrder)
                .collect(Collectors.toCollection(ArrayDeque::new));
        List<ProductProcessWorkflowDTO.Node> orderedNodes = new ArrayList<>();
        List<PinnedWorkflowGraph.ProcessStep> processes = new ArrayList<>();
        while (!ready.isEmpty()) {
            String current = ready.removeFirst();
            ProductProcessWorkflowDTO.Node node = nodeById.get(current);
            orderedNodes.add(node);
            if ("PROCESS".equals(node.getKind())) {
                String workProcessId = string(node.getData(), "workProcessId");
                if (workProcessId == null) {
                    throw invalid(409, "Workflow 工序 Cell 缺少工序主数据绑定",
                            "BOM_WORKFLOW_PROCESS_IDENTITY_MISSING");
                }
                processes.add(new PinnedWorkflowGraph.ProcessStep(
                        node.getId(), workProcessId, processes.size() + 1));
            }
            sliceOutgoing.getOrDefault(current, List.of()).stream().sorted(nodeOrder).forEach(target -> {
                int remaining = indegree.merge(target, -1, Integer::sum);
                if (remaining == 0) ready.addLast(target);
            });
        }
        if (orderedNodes.size() != ancestors.size()) {
            throw invalid(409, "Workflow 目标路径包含回路", "BOM_WORKFLOW_TARGET_SLICE_CYCLE");
        }
        return new PinnedWorkflowGraph(revisionId, definition.getId(), definition.getVersion(),
                definition.getRevisionHash(), targetProductTypeId, terminals.getFirst().getId(),
                rootMaterialTypeIds, List.copyOf(processes), List.copyOf(orderedNodes), List.copyOf(sliceEdges));
    }

    private static void validateMultiOutputContracts(
            Set<String> ancestors, Map<String, ProductProcessWorkflowDTO.Node> nodeById) {
        for (String nodeId : ancestors) {
            ProductProcessWorkflowDTO.Node node = nodeById.get(nodeId);
            if (node == null || !"PROCESS".equals(node.getKind()) || node.getData() == null) continue;
            if (WorkflowActualIoSemantics.enabled(node)) continue;
            Object rawPorts = node.getData().get("ports");
            if (!(rawPorts instanceof List<?> ports)) continue;
            List<Map<?, ?>> outputs = ports.stream()
                    .filter(Map.class::isInstance).<Map<?, ?>>map(value -> (Map<?, ?>) value)
                    .filter(port -> "OUTPUT".equals(String.valueOf(port.get("direction"))))
                    .toList();
            if (outputs.size() <= 1) continue;
            BigDecimal total = BigDecimal.ZERO;
            int mainCount = 0;
            for (Map<?, ?> output : outputs) {
                String role = value(output.get("outputRole"));
                BigDecimal ratio = decimal(output.get("costAllocationRatio"));
                if (role == null || !OUTPUT_ROLES.contains(role) || ratio == null
                        || ("BY_PRODUCT".equals(role) ? ratio.signum() != 0 : ratio.signum() <= 0)) {
                    throw invalid(409, "多产出工序必须为每个产出配置角色和成本分摊比例",
                            "BOM_WORKFLOW_MULTI_OUTPUT_CONTRACT_REQUIRED");
                }
                if ("MAIN".equals(role)) mainCount++;
                total = total.add(ratio);
            }
            if (mainCount != 1 || total.compareTo(new BigDecimal("100")) != 0) {
                throw invalid(409, "多产出工序必须有且仅有一个主产出，成本分摊合计必须为100%",
                        "BOM_WORKFLOW_MULTI_OUTPUT_CONTRACT_INVALID");
            }
        }
    }

    /** Exact terminal output contract of one immutable Workflow revision. */
    public static List<TerminalOutput> resolveTerminalOutputs(ProductProcessWorkflowDTO definition) {
        Map<String, ProductProcessWorkflowDTO.Node> nodesById = definition.getNodes().stream()
                .collect(Collectors.toMap(ProductProcessWorkflowDTO.Node::getId, Function.identity(),
                        (left, right) -> left, LinkedHashMap::new));
        Map<String, List<ProductProcessWorkflowDTO.Edge>> outgoing = definition.getEdges().stream()
                .collect(Collectors.groupingBy(ProductProcessWorkflowDTO.Edge::getSource));
        Map<String, List<ProductProcessWorkflowDTO.Edge>> incoming = definition.getEdges().stream()
                .collect(Collectors.groupingBy(ProductProcessWorkflowDTO.Edge::getTarget));
        List<ProductProcessWorkflowDTO.Node> terminals = definition.getNodes().stream()
                .filter(node -> "FINISHED_GOOD".equals(node.getKind()))
                .filter(node -> outgoing.getOrDefault(node.getId(), List.of()).isEmpty())
                .sorted(Comparator.comparing(ProductProcessWorkflowDTO.Node::getId))
                .toList();
        if (terminals.isEmpty()) {
            throw invalid(409, "Workflow 没有终端成品产出", "BOM_WORKFLOW_TERMINAL_OUTPUT_REQUIRED");
        }

        List<TerminalOutput> outputs = new ArrayList<>();
        for (int terminalIndex = 0; terminalIndex < terminals.size(); terminalIndex++) {
            ProductProcessWorkflowDTO.Node terminal = terminals.get(terminalIndex);
            String productTypeId = string(terminal.getData(), "skuId");
            List<ProductProcessWorkflowDTO.Edge> producerEdges = incoming.getOrDefault(terminal.getId(), List.of());
            if (productTypeId == null || producerEdges.size() != 1) {
                throw invalid(409, "每个终端成品必须绑定 SKU 且只有一个生产来源",
                        "BOM_WORKFLOW_TERMINAL_OUTPUT_INVALID");
            }
            ProductProcessWorkflowDTO.Edge producerEdge = producerEdges.getFirst();
            ProductProcessWorkflowDTO.Node process = nodesById.get(producerEdge.getSource());
            Map<?, ?> outputPort = processPort(process, producerEdge.getSourceHandle(), "OUTPUT");
            String roleValue = value(outputPort.get("outputRole"));
            BigDecimal ratio = decimal(outputPort.get("costAllocationRatio"));
            if (WorkflowActualIoSemantics.enabled(process)) {
                // Compatibility-only storage metadata. It is not authored or shown to
                // users and does not control which outputs may be reported.
                roleValue = terminalIndex == 0 ? "MAIN" : "BY_PRODUCT";
                ratio = terminalIndex == 0 ? new BigDecimal("100") : BigDecimal.ZERO;
            } else if (terminals.size() == 1) {
                roleValue = roleValue == null ? "MAIN" : roleValue;
                ratio = ratio == null ? new BigDecimal("100") : ratio;
            }
            if (roleValue == null || !OUTPUT_ROLES.contains(roleValue)
                    || ratio == null
                    || ("BY_PRODUCT".equals(roleValue) ? ratio.signum() != 0 : ratio.signum() <= 0)) {
                throw invalid(409, "多产出 Workflow 必须为每个终端配置角色和成本分摊比例",
                        "BOM_WORKFLOW_MULTI_OUTPUT_CONTRACT_REQUIRED");
            }
            String unit = value(outputPort.get("unit"));
            if (unit == null) unit = string(terminal.getData(), "baseUnit");
            if (unit == null) {
                throw invalid(409, "终端产出缺少单位契约: " + productTypeId,
                        "BOM_WORKFLOW_TERMINAL_UNIT_REQUIRED");
            }
            outputs.add(new TerminalOutput(
                    terminal.getId(),
                    productTypeId,
                    producerEdge.getSource(),
                    producerEdge.getSourceHandle(),
                    BomRecipe.OutputRole.valueOf(roleValue),
                    ratio,
                    unit));
        }
        long mainCount = outputs.stream().filter(output -> output.outputRole() == BomRecipe.OutputRole.MAIN).count();
        BigDecimal total = outputs.stream().map(TerminalOutput::costAllocationRatio)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        if (mainCount != 1 || total.compareTo(new BigDecimal("100")) != 0) {
            throw invalid(409, "多产出必须有且仅有一个主产出，成本分摊合计必须为100%",
                    "BOM_WORKFLOW_MULTI_OUTPUT_CONTRACT_INVALID");
        }
        return List.copyOf(outputs);
    }

    /** Logical raw input slots generated from the exact target-SKU reverse slice. */
    public static List<InputSlot> resolveInputSlots(PinnedWorkflowGraph graph) {
        Map<String, ProductProcessWorkflowDTO.Node> nodesById = graph.nodes().stream()
                .collect(Collectors.toMap(ProductProcessWorkflowDTO.Node::getId, Function.identity(),
                        (left, right) -> left, LinkedHashMap::new));
        List<InputSlot> slots = new ArrayList<>();
        int order = 0;
        for (ProductProcessWorkflowDTO.Edge edge : graph.edges()) {
            ProductProcessWorkflowDTO.Node source = nodesById.get(edge.getSource());
            ProductProcessWorkflowDTO.Node target = nodesById.get(edge.getTarget());
            if (source == null || target == null
                    || !"RAW_MATERIAL".equals(source.getKind())
                    || !"PROCESS".equals(target.getKind())) {
                continue;
            }
            Map<?, ?> port = processPort(target, edge.getTargetHandle(), "INPUT");
            slots.add(new InputSlot(
                    source.getId(),
                    string(source.getData(), "skuId"),
                    target.getId(),
                    edge.getTargetHandle(),
                    edge.getId(),
                    value(port.get("unit")),
                    ++order));
        }
        if (slots.isEmpty()) {
            throw invalid(409, "目标 SKU 路径没有可配置的原料投入槽",
                    "BOM_WORKFLOW_INPUT_SLOT_REQUIRED");
        }
        return List.copyOf(slots);
    }

    private void validateStableUpgrade(
            ProductProcessWorkflowDTO oldDefinition,
            PinnedWorkflowGraph oldGraph,
            ProductProcessWorkflowDTO newDefinition,
            PinnedWorkflowGraph newGraph) {
        List<String> conflicts = new ArrayList<>();
        List<InputSlot> oldSlots = resolveInputSlots(oldGraph);
        List<InputSlot> newSlots = resolveInputSlots(newGraph);
        Map<String, List<InputSlot>> newByMaterial = newSlots.stream()
                .collect(Collectors.groupingBy(
                        InputSlot::materialTypeId,
                        LinkedHashMap::new,
                        Collectors.toList()));
        for (Map.Entry<String, List<InputSlot>> entry : newByMaterial.entrySet()) {
            if (entry.getKey() == null || entry.getValue().size() != 1) {
                conflicts.add("原料 " + entry.getKey() + " 在目标工艺中对应多个投入槽");
            }
        }
        for (InputSlot oldSlot : oldSlots) {
            List<InputSlot> candidates = newByMaterial.getOrDefault(oldSlot.materialTypeId(), List.of());
            if (candidates.size() != 1) {
                conflicts.add("原料 " + oldSlot.materialTypeId() + " 无法唯一迁移到目标工艺");
                continue;
            }
            if (!sameUnit(oldSlot.unit(), candidates.getFirst().unit())) {
                conflicts.add("原料 " + oldSlot.materialTypeId() + " 的投入单位不兼容");
            }
        }
        if (!conflicts.isEmpty()) {
            String detail = conflicts.stream().limit(6).collect(Collectors.joining("；"));
            throw invalid(409, "无法自动升级工艺：" + detail, "BOM_WORKFLOW_UPGRADE_CONFLICT");
        }
    }

    public static boolean unitsCompatible(String left, String right) {
        return sameUnit(left, right);
    }

    private static Map<String, Map<?, ?>> processPorts(ProductProcessWorkflowDTO.Node process) {
        if (process == null || process.getData() == null
                || !(process.getData().get("ports") instanceof List<?> ports)) {
            return Map.of();
        }
        LinkedHashMap<String, Map<?, ?>> result = new LinkedHashMap<>();
        for (Object raw : ports) {
            if (raw instanceof Map<?, ?> port && value(port.get("id")) != null) {
                result.put(value(port.get("id")), port);
            }
        }
        return result;
    }

    private static Map<?, ?> processPort(
            ProductProcessWorkflowDTO.Node process, String portId, String direction) {
        Map<?, ?> port = processPorts(process).get(portId);
        if (port == null || !direction.equals(value(port.get("direction")))) {
            throw invalid(409, "Workflow 连线与稳定端口声明不一致: " + portId,
                    "BOM_WORKFLOW_PORT_BINDING_INVALID");
        }
        return port;
    }

    private static boolean sameUnit(String left, String right) {
        if (left == null || right == null) return false;
        return canonicalUnit(left).equals(canonicalUnit(right));
    }

    private static String canonicalUnit(String value) {
        return switch (value.trim().toLowerCase()) {
            case "公斤", "千克", "kg" -> "kg";
            case "克", "g" -> "g";
            case "毫升", "ml" -> "ml";
            case "升", "l" -> "l";
            case "盒", "box" -> "box";
            case "箱", "case" -> "case";
            default -> value.trim().toLowerCase();
        };
    }

    private static BigDecimal decimal(Object value) {
        if (value instanceof Number number) return new BigDecimal(number.toString());
        if (value instanceof String text) {
            try { return new BigDecimal(text); } catch (NumberFormatException ignored) { return null; }
        }
        return null;
    }

    private static double coordinate(ProductProcessWorkflowDTO.Node node, boolean x) {
        if (node == null || node.getPosition() == null) return Double.MAX_VALUE;
        Double value = x ? node.getPosition().getX() : node.getPosition().getY();
        return value == null ? Double.MAX_VALUE : value;
    }

    private static String string(Map<String, Object> data, String key) {
        return data == null ? null : value(data.get(key));
    }

    private static String value(Object value) {
        if (value == null) return null;
        String text = String.valueOf(value).trim();
        return text.isEmpty() || "null".equalsIgnoreCase(text) ? null : text;
    }

    private boolean isEnabled(Optional<ProductProcessWorkflowActivation> activation, Long workflowId) {
        return activation.map(row -> workflowId.equals(row.getActiveWorkflowId())).orElse(false);
    }

    private BomRecipe requireRecipe(String factoryId, String recipeId) {
        return recipeRepository.findById(recipeId)
                .filter(recipe -> factoryId.equals(recipe.getFactoryId()))
                .orElseThrow(() -> invalid(404, "BOM 不存在于当前工厂", "BOM_WORKFLOW_RECIPE_NOT_FOUND"));
    }

    private <T> T read(String json, TypeReference<T> type, String field) {
        try {
            return objectMapper.readValue(json, type);
        } catch (JsonProcessingException error) {
            throw new BusinessException(500, "BOM 固定的 Workflow " + field + " 快照损坏", error)
                    .withCode("BOM_WORKFLOW_SNAPSHOT_INVALID");
        }
    }

    private static BusinessException invalid(int status, String message, String code) {
        return new BusinessException(status, message).withCode(code).withSeverity("warning");
    }

    public record TerminalOutput(
            String terminalNodeId,
            String productTypeId,
            String producerProcessNodeId,
            String outputPortId,
            BomRecipe.OutputRole outputRole,
            BigDecimal costAllocationRatio,
            String outputUnit) { }

    public record CostScopeProfile(
            String costScope,
            List<String> terminalNodeIds,
            List<String> productTypeIds) {
        public String costScopeKey() {
            return canonicalCostScopeKey(terminalNodeIds);
        }
    }

    public record InputSlot(
            String materialNodeId,
            String materialTypeId,
            String processNodeId,
            String inputPortId,
            String edgeId,
            String unit,
            int order) { }

    public record WorkflowBinding(
            ProductProcessWorkflowRevision revision,
            ProductProcessWorkflowDTO definition,
            PinnedWorkflowGraph graph,
            List<TerminalOutput> terminalOutputs,
            TerminalOutput target) { }
}
