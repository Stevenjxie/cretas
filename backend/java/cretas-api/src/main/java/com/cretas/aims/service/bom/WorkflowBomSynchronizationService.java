package com.cretas.aims.service.bom;

import com.cretas.aims.dto.workflow.WorkflowBomSyncPreflightResponse;
import com.cretas.aims.entity.ProductProcessWorkflowRevision;
import com.cretas.aims.entity.bom.BomRecipe;
import com.cretas.aims.entity.bom.BomRecipeItem;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.repository.bom.BomRecipeItemRepository;
import com.cretas.aims.repository.bom.BomRecipeRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class WorkflowBomSynchronizationService {

    private final BomRecipeRepository recipeRepository;
    private final BomRecipeItemRepository itemRepository;
    private final BomWorkflowRevisionService revisionService;
    private final BomRecipeService recipeService;

    @Transactional(readOnly = true)
    public WorkflowBomSyncPreflightResponse preflight(String factoryId, String productTypeId) {
        return preflight(factoryId, productTypeId, null);
    }

    @Transactional(readOnly = true)
    public WorkflowBomSyncPreflightResponse preflight(
            String factoryId,
            String productTypeId,
            ProductProcessWorkflowRevision requestedTarget) {
        String mainOutputProductTypeId = requestedTarget == null
                ? productTypeId
                : revisionService.resolveMainOutputProductTypeId(factoryId, requestedTarget);
        BomRecipe active = recipeRepository
                .findByFactoryIdAndProductTypeIdAndIsCurrentTrueAndStatus(
                        factoryId, mainOutputProductTypeId, BomRecipe.Status.ACTIVE)
                .orElse(null);
        if (active == null) {
            return WorkflowBomSyncPreflightResponse.builder()
                    .classification(WorkflowBomSyncPreflightResponse.Classification.USER_INPUT_REQUIRED)
                    .missingItems(List.of(issue(
                            "WORKFLOW_ACTIVE_BOM_REQUIRED", null, null, null,
                            "bom", "当前产品没有生效 BOM", "请先完成 BOM 配置")))
                    .canCompleteAutomatically(false)
                    .build();
        }

        ProductProcessWorkflowRevision target = requestedTarget == null
                ? revisionService.findNewerCompatibleDraft(factoryId, active).orElse(null)
                : requestedTarget;
        if (target == null) {
            revisionService.resolvePinnedGraph(factoryId, active);
            return WorkflowBomSyncPreflightResponse.builder()
                    .classification(WorkflowBomSyncPreflightResponse.Classification.READY)
                    .activeBomVersion(active.getVersion())
                    .activeBomWorkflowRevisionId(active.getWorkflowRevisionId())
                    .targetWorkflowRevisionId(active.getWorkflowRevisionId())
                    .preservedItems(familyItems(factoryId, active).stream()
                            .map(BomRecipeItem::getMaterialName)
                            .filter(Objects::nonNull).distinct().toList())
                    .canCompleteAutomatically(true)
                    .build();
        }
        if (!Objects.equals(active.getWorkflowId(), target.getWorkflowId())) {
            return WorkflowBomSyncPreflightResponse.builder()
                    .classification(WorkflowBomSyncPreflightResponse.Classification.CONFLICT)
                    .activeBomVersion(active.getVersion())
                    .activeBomWorkflowRevisionId(active.getWorkflowRevisionId())
                    .targetWorkflowRevisionId(target.getId())
                    .conflicts(List.of(issue(
                            "BOM_WORKFLOW_LINEAGE_CONFLICT",
                            null, null, null, "workflowId",
                            "当前生效 BOM 与待发布 Workflow 不属于同一工艺版本线",
                            "请明确选择正确的 BOM 或 Workflow 版本")))
                    .canCompleteAutomatically(false)
                    .build();
        }
        if (Objects.equals(active.getWorkflowRevisionId(), target.getId())
                && Objects.equals(active.getWorkflowRevisionHash(), target.getRevisionHash())) {
            try {
                revisionService.requireActiveBomPinsRevision(
                        factoryId, productTypeId, target);
            } catch (BusinessException error) {
                return WorkflowBomSyncPreflightResponse.builder()
                        .classification(WorkflowBomSyncPreflightResponse.Classification.CONFLICT)
                        .activeBomVersion(active.getVersion())
                        .activeBomWorkflowRevisionId(active.getWorkflowRevisionId())
                        .targetWorkflowRevisionId(target.getId())
                        .conflicts(List.of(issue(
                                error.getErrorCode() == null
                                        ? "WORKFLOW_ACTIVE_BOM_FAMILY_INCOMPLETE"
                                        : error.getErrorCode(),
                                null, null, null, "bomFamily",
                                error.getMessage(),
                                error.getActionHint() == null
                                        ? "请补齐并激活完整 BOM Family"
                                        : error.getActionHint())))
                        .canCompleteAutomatically(false)
                        .build();
            }
            return WorkflowBomSyncPreflightResponse.builder()
                    .classification(WorkflowBomSyncPreflightResponse.Classification.READY)
                    .activeBomVersion(active.getVersion())
                    .activeBomWorkflowRevisionId(active.getWorkflowRevisionId())
                    .targetWorkflowRevisionId(target.getId())
                    .preservedItems(familyItems(factoryId, active).stream()
                            .map(BomRecipeItem::getMaterialName)
                            .filter(Objects::nonNull).distinct().toList())
                    .canCompleteAutomatically(true)
                    .build();
        }

        List<BomRecipe> family = familyRecipes(factoryId, active);
        List<BomRecipeItem> items = familyItems(family);
        List<WorkflowBomSyncPreflightResponse.AutomaticMapping> mappings = new ArrayList<>();
        List<WorkflowBomSyncPreflightResponse.SyncIssue> missing = new ArrayList<>();
        List<WorkflowBomSyncPreflightResponse.SyncIssue> conflicts = new ArrayList<>();
        List<BomWorkflowRevisionService.TerminalOutput> outputs =
                revisionService.resolveTerminalOutputsForRevision(factoryId, target);
        Map<String, BomRecipe> familyByProduct = new LinkedHashMap<>();
        for (BomRecipe member : family) {
            familyByProduct.put(member.getProductTypeId(), member);
        }
        Set<String> targetOutputProductIds = outputs.stream()
                .map(BomWorkflowRevisionService.TerminalOutput::productTypeId)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        for (BomRecipe member : family) {
            if (!targetOutputProductIds.contains(member.getProductTypeId())) {
                conflicts.add(issue(
                        "BOM_FAMILY_OUTPUT_REMOVED",
                        null, member.getProductTypeId(), null,
                        "bomFamily",
                        "生效 BOM Family 中的产出 " + member.getProductTypeId()
                                + " 已从目标 Workflow 删除",
                        "请先停用或调整该产出的 BOM，再重新发布 Workflow"));
            }
        }
        Map<String, String> targetTerminalByRecipeId = new LinkedHashMap<>();
        Map<String, BomWorkflowRevisionService.InputSlot> representativeSlots =
                new LinkedHashMap<>();
        Map<String, LinkedHashSet<BomRecipe>> targetsBySlot = new LinkedHashMap<>();
        for (BomWorkflowRevisionService.TerminalOutput output : outputs) {
            BomRecipe member = familyByProduct.get(output.productTypeId());
            if (member == null) {
                missing.add(issue(
                        "BOM_FAMILY_OUTPUTS_INCOMPLETE",
                        null, output.productTypeId(), output.producerProcessNodeId(),
                        "bomFamily",
                        "终端产出 " + output.productTypeId() + " 缺少生效 BOM",
                        "请补齐并激活同一 BOM Family"));
                continue;
            }
            targetTerminalByRecipeId.put(member.getId(), output.terminalNodeId());
            BomWorkflowRevisionService.WorkflowBinding binding =
                    revisionService.resolveExactBinding(factoryId, member, target.getId());
            for (BomWorkflowRevisionService.InputSlot slot :
                    BomWorkflowRevisionService.resolveInputSlots(binding.graph())) {
                String slotKey = slotKey(slot);
                representativeSlots.putIfAbsent(slotKey, slot);
                targetsBySlot.computeIfAbsent(slotKey, ignored -> new LinkedHashSet<>())
                        .add(member);
            }
        }
        BomRecipe main = outputs.stream()
                .filter(output -> output.outputRole() == BomRecipe.OutputRole.MAIN)
                .map(output -> familyByProduct.get(output.productTypeId()))
                .filter(Objects::nonNull)
                .findFirst()
                .orElse(active);
        List<TargetSlotProfile> profiles = new ArrayList<>();
        for (Map.Entry<String, BomWorkflowRevisionService.InputSlot> entry :
                representativeSlots.entrySet()) {
            List<BomRecipe> targets = targetsBySlot.get(entry.getKey()).stream()
                    .sorted(Comparator.comparing(
                            member -> targetTerminalByRecipeId.get(member.getId())))
                    .toList();
            String costScope = targets.size() == outputs.size()
                    ? "SHARED"
                    : targets.size() == 1 ? "OUTPUT_EXCLUSIVE" : "OUTPUT_GROUP";
            BomRecipe owner = "SHARED".equals(costScope) ? main : targets.getFirst();
            String costScopeKey = BomWorkflowRevisionService.canonicalCostScopeKey(
                    targets.stream()
                            .map(member -> targetTerminalByRecipeId.get(member.getId()))
                            .toList());
            profiles.add(new TargetSlotProfile(
                    entry.getValue(), owner, costScope, costScopeKey));
        }
        Map<String, Long> profileMultiplicity = profiles.stream()
                .collect(Collectors.groupingBy(
                        profile -> profile.owner().getId() + "\u0000"
                                + profile.slot().materialTypeId(),
                        LinkedHashMap::new,
                        Collectors.counting()));
        for (TargetSlotProfile profile : profiles) {
            BomWorkflowRevisionService.InputSlot slot = profile.slot();
            String profileMaterialKey = profile.owner().getId() + "\u0000"
                    + slot.materialTypeId();
            if (slot.materialTypeId() == null
                    || profileMultiplicity.getOrDefault(profileMaterialKey, 0L) != 1L) {
                conflicts.add(issue(
                        "BOM_WORKFLOW_UPGRADE_SLOT_AMBIGUOUS",
                        slot.materialTypeId(), null, slot.processNodeId(),
                        "workflowInputPortId",
                        "同一产出范围内的原料对应多个投入槽",
                        "请在 Workflow 中保留唯一投入槽"));
                continue;
            }
            List<BomRecipeItem> matches = items.stream()
                    .filter(item -> !"PACKAGING".equals(item.getMaterialCategory()))
                    .filter(item -> Objects.equals(item.getRecipeId(), profile.owner().getId()))
                    .filter(item -> Objects.equals(item.getMaterialTypeId(), slot.materialTypeId()))
                    .toList();
            if (matches.size() > 1) {
                conflicts.add(issue(
                        "BOM_WORKFLOW_UPGRADE_MATERIAL_AMBIGUOUS",
                        slot.materialTypeId(), null, slot.processNodeId(), "materialTypeId",
                        "BOM 中存在多条同原料主规则",
                        "请保留唯一一条主料规则"));
                continue;
            }
            if (matches.isEmpty()) {
                missing.add(issue(
                        "BOM_WORKFLOW_INPUT_ITEM_MISSING",
                        slot.materialTypeId(), null, slot.processNodeId(), "materialTypeId",
                        "目标工艺新增了尚未配置的原料",
                        "请补充该原料的 BOM 配置"));
                continue;
            }
            BomRecipeItem item = matches.getFirst();
            if (!BomWorkflowRevisionService.unitsCompatible(item.getUnit(), slot.unit())) {
                conflicts.add(issue(
                        "BOM_WORKFLOW_UPGRADE_UNIT_INCOMPATIBLE",
                        item.getMaterialTypeId(), item.getMaterialName(), slot.processNodeId(), "unit",
                        "BOM 单位与目标工艺投入单位不兼容",
                        "请统一计量单位"));
                continue;
            }
            mappings.add(WorkflowBomSyncPreflightResponse.AutomaticMapping.builder()
                    .materialTypeId(item.getMaterialTypeId())
                    .materialName(item.getMaterialName())
                    .fromNodeId(item.getWorkflowMaterialNodeId())
                    .toNodeId(slot.materialNodeId())
                    .toProcessNodeId(slot.processNodeId())
                    .toInputPortId(slot.inputPortId())
                    .toEdgeId(slot.edgeId())
                    .ownerRecipeId(profile.owner().getId())
                    .costScope(profile.costScope())
                    .costScopeKey(profile.costScopeKey())
                    .build());
        }

        WorkflowBomSyncPreflightResponse.Classification classification =
                !conflicts.isEmpty()
                        ? WorkflowBomSyncPreflightResponse.Classification.CONFLICT
                        : !missing.isEmpty()
                        ? WorkflowBomSyncPreflightResponse.Classification.USER_INPUT_REQUIRED
                        : WorkflowBomSyncPreflightResponse.Classification.AUTO_MIGRATABLE;
        return WorkflowBomSyncPreflightResponse.builder()
                .classification(classification)
                .activeBomVersion(active.getVersion())
                .syncDraftVersion(recipeRepository.findMaxVersion(
                        factoryId, mainOutputProductTypeId) + 1)
                .activeBomWorkflowRevisionId(active.getWorkflowRevisionId())
                .targetWorkflowRevisionId(target.getId())
                .preservedItems(items.stream().map(BomRecipeItem::getMaterialName)
                        .filter(Objects::nonNull).toList())
                .automaticMappings(mappings)
                .missingItems(missing)
                .conflicts(conflicts)
                .canCompleteAutomatically(classification
                        == WorkflowBomSyncPreflightResponse.Classification.AUTO_MIGRATABLE)
                .build();
    }

    private List<BomRecipe> familyRecipes(String factoryId, BomRecipe active) {
        return active.getBomFamilyId() == null
                ? List.of(active)
                : recipeRepository.findByFactoryIdAndBomFamilyIdAndStatusOrderByProductTypeIdAsc(
                        factoryId, active.getBomFamilyId(), BomRecipe.Status.ACTIVE);
    }

    private List<BomRecipeItem> familyItems(String factoryId, BomRecipe active) {
        return familyItems(familyRecipes(factoryId, active));
    }

    private List<BomRecipeItem> familyItems(List<BomRecipe> family) {
        return family.stream()
                .flatMap(member -> itemRepository
                        .findByRecipeIdOrderBySortOrderAsc(member.getId()).stream())
                .toList();
    }

    private String slotKey(BomWorkflowRevisionService.InputSlot slot) {
        return slot.materialNodeId() + "\u0000"
                + slot.inputPortId() + "\u0000" + slot.edgeId();
    }

    private record TargetSlotProfile(
            BomWorkflowRevisionService.InputSlot slot,
            BomRecipe owner,
            String costScope,
            String costScopeKey) {
    }

    @Transactional
    public BomRecipe synchronizeForPublish(
            String factoryId,
            String productTypeId,
            ProductProcessWorkflowRevision targetRevision,
            Long operatorId) {
        return recipeService.synchronizeActiveBomToWorkflowRevision(
                factoryId, productTypeId, targetRevision, operatorId);
    }

    private static WorkflowBomSyncPreflightResponse.SyncIssue issue(
            String code,
            String materialTypeId,
            String materialName,
            String processNodeId,
            String field,
            String message,
            String action) {
        return WorkflowBomSyncPreflightResponse.SyncIssue.builder()
                .code(code)
                .materialTypeId(materialTypeId)
                .materialName(materialName)
                .processNodeId(processNodeId)
                .field(field)
                .message(message)
                .action(action)
                .build();
    }
}
