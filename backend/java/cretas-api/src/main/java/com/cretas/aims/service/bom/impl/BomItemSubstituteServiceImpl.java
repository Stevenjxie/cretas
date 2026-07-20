package com.cretas.aims.service.bom.impl;

import com.cretas.aims.dto.bom.BomItemSubstituteDTO;
import com.cretas.aims.dto.bom.BomSubstituteInput;
import com.cretas.aims.entity.RawMaterialType;
import com.cretas.aims.entity.bom.BomItemSubstitute;
import com.cretas.aims.entity.bom.BomRecipe;
import com.cretas.aims.entity.bom.BomRecipeItem;
import com.cretas.aims.entity.bom.BomSeasoningItem;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.repository.RawMaterialTypeRepository;
import com.cretas.aims.repository.bom.BomItemSubstituteRepository;
import com.cretas.aims.repository.bom.BomRecipeItemRepository;
import com.cretas.aims.repository.bom.BomRecipeRepository;
import com.cretas.aims.repository.bom.BomSeasoningItemRepository;
import com.cretas.aims.service.bom.BomItemSubstituteService;
import com.cretas.aims.service.unit.UnitContractService;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.*;

/**
 * Transactional source of truth for parent-to-substitute BOM relations.
 *
 * <p>All write operations lock the recipe head. This makes whole-parent replacement and cycle
 * validation atomic even when two users edit different parent rows in the same recipe. Existing
 * rows are soft deleted so DRAFT edits remain auditable; an unchanged payload is a true no-op.
 */
@Service
@RequiredArgsConstructor
public class BomItemSubstituteServiceImpl implements BomItemSubstituteService {

    private static final BigDecimal ONE = BigDecimal.ONE;

    private final BomItemSubstituteRepository repository;
    private final BomRecipeRepository recipeRepository;
    private final BomRecipeItemRepository recipeItemRepository;
    private final BomSeasoningItemRepository seasoningItemRepository;
    private final RawMaterialTypeRepository materialRepository;
    private final UnitContractService unitContractService;
    private final EntityManager entityManager;

    @Override
    @Transactional(readOnly = true)
    public List<BomItemSubstituteDTO> listByRecipe(String factoryId, String recipeId) {
        ownedRecipe(factoryId, recipeId);
        return repository.findByFactoryIdAndRecipeIdOrderByCreatedAtAsc(factoryId, recipeId)
                .stream().map(this::toDto).toList();
    }

    @Override
    @Transactional(readOnly = true)
    public List<BomItemSubstituteDTO> listForRecipeItem(
            String factoryId, String recipeId, Long parentRecipeItemId) {
        ownedRecipe(factoryId, recipeId);
        ownedRecipeItem(factoryId, recipeId, parentRecipeItemId, false);
        return recipeRelations(factoryId, recipeId, parentRecipeItemId).stream()
                .map(this::toDto).toList();
    }

    @Override
    @Transactional(readOnly = true)
    public List<BomItemSubstituteDTO> listForSeasoningItem(
            String factoryId, String recipeId, Long parentSeasoningItemId) {
        ownedRecipe(factoryId, recipeId);
        ownedSeasoningItem(factoryId, recipeId, parentSeasoningItemId, false);
        return seasoningRelations(factoryId, recipeId, parentSeasoningItemId).stream()
                .map(this::toDto).toList();
    }

    @Override
    @Transactional
    public List<BomItemSubstituteDTO> replaceForRecipeItem(
            String factoryId,
            String recipeId,
            Long parentRecipeItemId,
            List<BomSubstituteInput> substitutes) {
        lockDraftRecipe(factoryId, recipeId);
        ParentContext parent = ownedRecipeItem(factoryId, recipeId, parentRecipeItemId, true);
        return replace(parent, substitutes);
    }

    @Override
    @Transactional
    public List<BomItemSubstituteDTO> replaceForSeasoningItem(
            String factoryId,
            String recipeId,
            Long parentSeasoningItemId,
            List<BomSubstituteInput> substitutes) {
        lockDraftRecipe(factoryId, recipeId);
        ParentContext parent = ownedSeasoningItem(factoryId, recipeId, parentSeasoningItemId, true);
        return replace(parent, substitutes);
    }

    @Override
    @Transactional
    public List<BomItemSubstituteDTO> cloneRelations(
            String factoryId,
            String sourceRecipeId,
            String targetRecipeId,
            Map<Long, Long> recipeItemIdMap,
            Map<Long, Long> seasoningItemIdMap) {
        if (Objects.equals(sourceRecipeId, targetRecipeId)) {
            throw error(400, "源 BOM 与目标 BOM 不能相同", "BOM_SUBSTITUTE_CLONE_SAME_RECIPE");
        }
        lockCloneRecipes(factoryId, sourceRecipeId, targetRecipeId);

        Map<Long, Long> safeRecipeMap = recipeItemIdMap == null ? Map.of() : Map.copyOf(recipeItemIdMap);
        Map<Long, Long> safeSeasoningMap = seasoningItemIdMap == null ? Map.of() : Map.copyOf(seasoningItemIdMap);
        List<BomItemSubstitute> source = repository
                .findByFactoryIdAndRecipeIdOrderByCreatedAtAsc(factoryId, sourceRecipeId);
        if (source.isEmpty()) {
            return List.of();
        }

        List<BomItemSubstitute> proposed = new ArrayList<>(source.size());
        for (BomItemSubstitute relation : source) {
            ParentContext targetParent = mappedTargetParent(
                    factoryId, targetRecipeId, relation, safeRecipeMap, safeSeasoningMap);
            assertSameParentContract(relation, targetParent);
            proposed.add(copyForTarget(relation, targetParent));
        }
        rejectCycles(factoryId, targetRecipeId, null, proposed);

        List<BomItemSubstitute> existing = repository
                .findByFactoryIdAndRecipeIdOrderByCreatedAtAsc(factoryId, targetRecipeId);
        if (!existing.isEmpty()) {
            if (sameCloneSet(existing, proposed)) {
                return existing.stream().map(this::toDto).toList();
            }
            throw error(409,
                    "目标 BOM 已存在不同的替代关系，不能重复克隆覆盖",
                    "BOM_SUBSTITUTE_CLONE_TARGET_CONFLICT");
        }
        return saveAll(proposed);
    }

    private List<BomItemSubstituteDTO> replace(
            ParentContext parent, List<BomSubstituteInput> requested) {
        List<BomSubstituteInput> inputs = requested == null ? List.of() : requested;
        List<BomItemSubstitute> proposed = buildRelations(parent, inputs);
        List<BomItemSubstitute> existing = parent.kind() == BomItemSubstitute.ParentKind.RECIPE_ITEM
                ? recipeRelations(parent.factoryId(), parent.recipeId(), parent.parentRecipeItemId())
                : seasoningRelations(parent.factoryId(), parent.recipeId(), parent.parentSeasoningItemId());

        if (sameParentSet(existing, proposed)) {
            return existing.stream().map(this::toDto).toList();
        }
        rejectCycles(parent.factoryId(), parent.recipeId(), parent, proposed);

        if (!existing.isEmpty()) {
            existing.forEach(BomItemSubstitute::softDelete);
            repository.saveAllAndFlush(existing);
        }
        return saveAll(proposed);
    }

    private List<BomItemSubstituteDTO> saveAll(List<BomItemSubstitute> proposed) {
        if (proposed.isEmpty()) {
            return List.of();
        }
        try {
            return repository.saveAllAndFlush(proposed).stream().map(this::toDto).toList();
        } catch (DataIntegrityViolationException ex) {
            throw error(409,
                    "替代关系已被其他操作更新，请刷新后重试",
                    "BOM_SUBSTITUTE_CONCURRENT_CONFLICT", ex);
        }
    }

    private List<BomItemSubstitute> buildRelations(
            ParentContext parent, List<BomSubstituteInput> inputs) {
        Set<String> seen = new HashSet<>();
        List<BomItemSubstitute> result = new ArrayList<>(inputs.size());
        for (BomSubstituteInput input : inputs) {
            String substituteId = input == null ? null : trimToNull(input.getMaterialTypeId());
            if (substituteId == null) {
                throw error(400, "替代物料不能为空", "BOM_SUBSTITUTE_MATERIAL_REQUIRED");
            }
            if (!seen.add(substituteId)) {
                throw error(409, "同一主项不能重复添加同一替代物料", "BOM_SUBSTITUTE_DUPLICATE");
            }
            if (substituteId.equals(parent.materialTypeId())) {
                throw error(400, "物料不能替代自身", "BOM_SUBSTITUTE_SELF_REFERENCE");
            }
            RawMaterialType substitute = ownedActiveMaterial(parent.factoryId(), substituteId);
            ensureCompatibleMaterialFamily(parent, substitute);

            String parentUnit = canonicalUnit(parent.factoryId(), parent.unit(), "主项");
            String substituteUnit = canonicalUnit(parent.factoryId(), substitute.getUnit(), "替代物料");
            BigDecimal requestedFactor = input.getConversionFactor();
            boolean explicit = requestedFactor != null;
            BigDecimal factor;
            if (!explicit) {
                if (!parentUnit.equals(substituteUnit)) {
                    throw error(400,
                            "主项与替代物料单位不同，必须明确填写替代换算系数",
                            "BOM_SUBSTITUTE_CONVERSION_REQUIRED");
                }
                factor = ONE;
            } else {
                if (requestedFactor.compareTo(BigDecimal.ZERO) <= 0) {
                    throw error(400, "替代换算系数必须大于0", "BOM_SUBSTITUTE_FACTOR_INVALID");
                }
                factor = requestedFactor.stripTrailingZeros();
            }

            result.add(newRelation(parent, substitute, parentUnit, substituteUnit, factor, explicit));
        }
        return result;
    }

    private BomItemSubstitute newRelation(
            ParentContext parent,
            RawMaterialType substitute,
            String parentUnit,
            String substituteUnit,
            BigDecimal factor,
            boolean explicit) {
        return BomItemSubstitute.builder()
                .factoryId(parent.factoryId())
                .recipeId(parent.recipeId())
                .parentKind(parent.kind())
                .parentRecipeItemId(parent.parentRecipeItemId())
                .parentSeasoningItemId(parent.parentSeasoningItemId())
                .parentMaterialTypeIdSnapshot(parent.materialTypeId())
                .parentMaterialNameSnapshot(parent.materialName())
                .materialCategorySnapshot(parent.materialCategory())
                .workProcessIdSnapshot(parent.workProcessId())
                .workflowProcessNodeIdSnapshot(parent.workflowProcessNodeId())
                .packagingSpecIdSnapshot(parent.packagingSpecId())
                .packagingRoleSnapshot(parent.packagingRole())
                .substituteMaterialTypeId(substitute.getId())
                .substituteMaterialCodeSnapshot(substitute.getDisplayCode())
                .substituteMaterialNameSnapshot(substitute.getName())
                .parentUnitSnapshot(parentUnit)
                .substituteUnitSnapshot(substituteUnit)
                .conversionFactor(factor)
                .conversionExplicit(explicit)
                .build();
    }

    private ParentContext ownedRecipeItem(
            String factoryId, String recipeId, Long itemId, boolean lock) {
        if (itemId == null) {
            throw error(400, "BOM 主项不能为空", "BOM_SUBSTITUTE_PARENT_REQUIRED");
        }
        BomRecipeItem item = lock
                ? entityManager.find(BomRecipeItem.class, itemId, LockModeType.PESSIMISTIC_WRITE)
                : recipeItemRepository.findById(itemId).orElse(null);
        if (item == null) {
            throw error(404, "BOM 主项不存在", "BOM_SUBSTITUTE_PARENT_NOT_FOUND");
        }
        validateParentOwnership(factoryId, recipeId, item.getFactoryId(), item.getRecipeId());
        String category = trimToNull(item.getMaterialCategory());
        if (!Set.of("RAW", "AUXILIARY", "PACKAGING").contains(category)) {
            throw error(400, "BOM 主项类型不支持替代关系", "BOM_SUBSTITUTE_PARENT_CATEGORY_INVALID");
        }
        if ("AUXILIARY".equals(category)) {
            throw error(409,
                    "工序辅料替代必须在具体工序辅料明细中配置",
                    "BOM_SUBSTITUTE_AUXILIARY_PROCESS_REQUIRED");
        }
        if ("PACKAGING".equals(category) && trimToNull(item.getPackagingRole()) == null) {
            throw error(409,
                    "包材主项缺少包装角色，不能配置替代物料",
                    "BOM_SUBSTITUTE_PACKAGING_ROLE_REQUIRED");
        }
        RawMaterialType material = ownedMaterial(factoryId, item.getMaterialTypeId());
        return new ParentContext(
                factoryId, recipeId, BomItemSubstitute.ParentKind.RECIPE_ITEM,
                item.getId(), null, material.getId(),
                valueOrFallback(item.getMaterialName(), material.getName()), category,
                null, null, item.getPackagingSpecId(), item.getPackagingRole(), item.getUnit(), material);
    }

    private ParentContext ownedSeasoningItem(
            String factoryId, String recipeId, Long itemId, boolean lock) {
        if (itemId == null) {
            throw error(400, "工序辅料主项不能为空", "BOM_SUBSTITUTE_PARENT_REQUIRED");
        }
        BomSeasoningItem item = lock
                ? entityManager.find(BomSeasoningItem.class, itemId, LockModeType.PESSIMISTIC_WRITE)
                : seasoningItemRepository.findById(itemId).orElse(null);
        if (item == null) {
            throw error(404, "工序辅料主项不存在", "BOM_SUBSTITUTE_PARENT_NOT_FOUND");
        }
        validateParentOwnership(factoryId, recipeId, item.getFactoryId(), item.getRecipeId());
        if (trimToNull(item.getWorkProcessId()) == null) {
            throw error(409,
                    "工序辅料尚未绑定工序，不能配置替代物料",
                    "BOM_SUBSTITUTE_AUXILIARY_PROCESS_REQUIRED");
        }
        if (trimToNull(item.getWorkflowProcessNodeId()) == null) {
            throw error(409,
                    "工序辅料缺少 Workflow 工序节点快照，不能配置替代物料",
                    "BOM_SUBSTITUTE_AUXILIARY_NODE_REQUIRED");
        }
        if (trimToNull(item.getMaterialTypeId()) == null) {
            throw error(409,
                    "历史工序辅料缺少物料身份，请先补齐后再配置替代物料",
                    "BOM_SUBSTITUTE_PARENT_MATERIAL_REQUIRED");
        }
        RawMaterialType material = ownedMaterial(factoryId, item.getMaterialTypeId());
        return new ParentContext(
                factoryId, recipeId, BomItemSubstitute.ParentKind.SEASONING_ITEM,
                null, item.getId(), material.getId(),
                valueOrFallback(item.getName(), material.getName()), "AUXILIARY",
                item.getWorkProcessId(), item.getWorkflowProcessNodeId(),
                null, null, material.getUnit(), material);
    }

    private ParentContext mappedTargetParent(
            String factoryId,
            String targetRecipeId,
            BomItemSubstitute source,
            Map<Long, Long> recipeItemIdMap,
            Map<Long, Long> seasoningItemIdMap) {
        if (source.getParentKind() == BomItemSubstitute.ParentKind.RECIPE_ITEM) {
            Long targetId = recipeItemIdMap.get(source.getParentRecipeItemId());
            if (targetId == null) {
                throw error(400,
                        "克隆替代关系时缺少 BOM 明细 ID 映射",
                        "BOM_SUBSTITUTE_CLONE_PARENT_MAP_REQUIRED");
            }
            return ownedRecipeItem(factoryId, targetRecipeId, targetId, true);
        }
        Long targetId = seasoningItemIdMap.get(source.getParentSeasoningItemId());
        if (targetId == null) {
            throw error(400,
                    "克隆替代关系时缺少工序辅料 ID 映射",
                    "BOM_SUBSTITUTE_CLONE_PARENT_MAP_REQUIRED");
        }
        return ownedSeasoningItem(factoryId, targetRecipeId, targetId, true);
    }

    private BomItemSubstitute copyForTarget(BomItemSubstitute source, ParentContext target) {
        return BomItemSubstitute.builder()
                .factoryId(target.factoryId())
                .recipeId(target.recipeId())
                .parentKind(target.kind())
                .parentRecipeItemId(target.parentRecipeItemId())
                .parentSeasoningItemId(target.parentSeasoningItemId())
                .parentMaterialTypeIdSnapshot(target.materialTypeId())
                .parentMaterialNameSnapshot(target.materialName())
                .materialCategorySnapshot(target.materialCategory())
                .workProcessIdSnapshot(target.workProcessId())
                .workflowProcessNodeIdSnapshot(target.workflowProcessNodeId())
                .packagingSpecIdSnapshot(target.packagingSpecId())
                .packagingRoleSnapshot(target.packagingRole())
                .substituteMaterialTypeId(source.getSubstituteMaterialTypeId())
                .substituteMaterialCodeSnapshot(source.getSubstituteMaterialCodeSnapshot())
                .substituteMaterialNameSnapshot(source.getSubstituteMaterialNameSnapshot())
                .parentUnitSnapshot(source.getParentUnitSnapshot())
                .substituteUnitSnapshot(source.getSubstituteUnitSnapshot())
                .conversionFactor(source.getConversionFactor())
                .conversionExplicit(source.getConversionExplicit())
                .build();
    }

    private void assertSameParentContract(BomItemSubstitute source, ParentContext target) {
        if (!Objects.equals(source.getParentMaterialTypeIdSnapshot(), target.materialTypeId())
                || !Objects.equals(source.getMaterialCategorySnapshot(), target.materialCategory())
                || !Objects.equals(source.getWorkProcessIdSnapshot(), target.workProcessId())
                || !Objects.equals(source.getWorkflowProcessNodeIdSnapshot(), target.workflowProcessNodeId())
                || !Objects.equals(source.getPackagingSpecIdSnapshot(), target.packagingSpecId())
                || !Objects.equals(source.getPackagingRoleSnapshot(), target.packagingRole())) {
            throw error(409,
                    "目标 BOM 主项与源版本作用域不一致，不能克隆替代关系",
                    "BOM_SUBSTITUTE_CLONE_SCOPE_MISMATCH");
        }
    }

    private void rejectCycles(
            String factoryId,
            String recipeId,
            ParentContext replacedParent,
            List<BomItemSubstitute> proposed) {
        List<BomItemSubstitute> all = new ArrayList<>(
                repository.findByFactoryIdAndRecipeIdOrderByCreatedAtAsc(factoryId, recipeId));
        if (replacedParent != null) {
            all.removeIf(relation -> sameParent(relation, replacedParent));
        }
        all.addAll(proposed);

        Map<String, Set<String>> graph = new HashMap<>();
        for (BomItemSubstitute relation : all) {
            graph.computeIfAbsent(relation.getParentMaterialTypeIdSnapshot(), ignored -> new HashSet<>())
                    .add(relation.getSubstituteMaterialTypeId());
        }
        Map<String, Integer> state = new HashMap<>();
        for (String node : graph.keySet()) {
            if (hasCycle(node, graph, state)) {
                throw error(409,
                        "替代关系形成循环，请调整主项与替代物料",
                        "BOM_SUBSTITUTE_CYCLE");
            }
        }
    }

    private boolean hasCycle(String node, Map<String, Set<String>> graph, Map<String, Integer> state) {
        int current = state.getOrDefault(node, 0);
        if (current == 1) return true;
        if (current == 2) return false;
        state.put(node, 1);
        for (String next : graph.getOrDefault(node, Set.of())) {
            if (hasCycle(next, graph, state)) return true;
        }
        state.put(node, 2);
        return false;
    }

    private BomRecipe ownedRecipe(String factoryId, String recipeId) {
        BomRecipe recipe = recipeRepository.findById(recipeId)
                .orElseThrow(() -> error(404, "BOM 不存在", "BOM_NOT_FOUND"));
        if (!factoryId.equals(recipe.getFactoryId())) {
            throw error(403, "BOM 不属于当前工厂", "BOM_FACTORY_MISMATCH");
        }
        return recipe;
    }

    private BomRecipe lockDraftRecipe(String factoryId, String recipeId) {
        BomRecipe recipe = entityManager.find(BomRecipe.class, recipeId, LockModeType.PESSIMISTIC_WRITE);
        if (recipe == null) {
            throw error(404, "BOM 不存在", "BOM_NOT_FOUND");
        }
        if (!factoryId.equals(recipe.getFactoryId())) {
            throw error(403, "BOM 不属于当前工厂", "BOM_FACTORY_MISMATCH");
        }
        if (recipe.getStatus() != BomRecipe.Status.DRAFT) {
            throw error(409, "只有 BOM 草稿可以修改替代关系", "BOM_SUBSTITUTE_DRAFT_REQUIRED");
        }
        return recipe;
    }

    /** Lock both heads in a stable order so a cloned source revision cannot drift mid-copy. */
    private void lockCloneRecipes(String factoryId, String sourceRecipeId, String targetRecipeId) {
        List<String> orderedIds = new ArrayList<>(List.of(sourceRecipeId, targetRecipeId));
        orderedIds.sort(String::compareTo);
        Map<String, BomRecipe> locked = new HashMap<>();
        for (String recipeId : orderedIds) {
            BomRecipe recipe = entityManager.find(
                    BomRecipe.class, recipeId, LockModeType.PESSIMISTIC_WRITE);
            if (recipe == null) {
                throw error(404, "BOM 不存在", "BOM_NOT_FOUND");
            }
            if (!factoryId.equals(recipe.getFactoryId())) {
                throw error(403, "BOM 不属于当前工厂", "BOM_FACTORY_MISMATCH");
            }
            locked.put(recipeId, recipe);
        }
        if (locked.get(targetRecipeId).getStatus() != BomRecipe.Status.DRAFT) {
            throw error(409, "替代关系只能克隆到 BOM 草稿", "BOM_SUBSTITUTE_DRAFT_REQUIRED");
        }
    }

    private RawMaterialType ownedMaterial(String factoryId, String materialTypeId) {
        RawMaterialType material = materialRepository.findById(materialTypeId)
                .orElseThrow(() -> error(404, "物料不存在", "BOM_SUBSTITUTE_MATERIAL_NOT_FOUND"));
        if (!factoryId.equals(material.getFactoryId())) {
            throw error(403, "物料不属于当前工厂", "BOM_SUBSTITUTE_MATERIAL_FACTORY_MISMATCH");
        }
        return material;
    }

    private RawMaterialType ownedActiveMaterial(String factoryId, String materialTypeId) {
        RawMaterialType material = ownedMaterial(factoryId, materialTypeId);
        if (!Boolean.TRUE.equals(material.getIsActive())) {
            throw error(409, "已停用物料不能新增为替代物料", "BOM_SUBSTITUTE_MATERIAL_INACTIVE");
        }
        return material;
    }

    private void ensureCompatibleMaterialFamily(ParentContext context, RawMaterialType substitute) {
        RawMaterialType parent = context.parentMaterial();
        if ("PACKAGING".equals(context.materialCategory())) {
            String parentFamily = packagingClassificationFamily(parent);
            String substituteFamily = packagingClassificationFamily(substitute);
            if (parentFamily == null || substituteFamily == null) {
                throw error(409,
                        "包材主项或替代物料缺少可验证的包装分类，不能确认包装角色兼容性",
                        "BOM_SUBSTITUTE_PACKAGING_CLASSIFICATION_REQUIRED");
            }
            if (!parentFamily.equals(substituteFamily)) {
                throw error(400,
                        "替代包材必须与主包材属于同一包装分类和角色",
                        "BOM_SUBSTITUTE_PACKAGING_ROLE_MISMATCH");
            }
            return;
        }
        String parentFamily = trimToNull(parent.getPrimaryCode());
        String substituteFamily = trimToNull(substitute.getPrimaryCode());
        if (parentFamily != null && substituteFamily != null && !parentFamily.equals(substituteFamily)) {
            throw error(400,
                    "替代物料与主项业务类别不兼容",
                    "BOM_SUBSTITUTE_MATERIAL_CATEGORY_MISMATCH");
        }
    }

    /**
     * Returns a fail-closed packaging family. The stable 16-digit classification code carries the
     * L1/L2/L3 path in its first ten digits. Older descriptive categories are accepted only when
     * they are more specific than the broad PACKAGING bucket.
     */
    private String packagingClassificationFamily(RawMaterialType material) {
        String code = trimToNull(material.getCode());
        if (code != null && code.matches("\\d{16}")) {
            return "code:" + code.substring(0, 10);
        }
        String category = trimToNull(material.getCategory());
        if (category == null) return null;
        String normalized = category.toUpperCase(Locale.ROOT);
        if (Set.of("PACKAGING", "PACKAGE", "包材", "包装").contains(normalized)) {
            return null;
        }
        return "category:" + normalized;
    }

    private String canonicalUnit(String factoryId, String rawUnit, String label) {
        var normalized = unitContractService.normalize(factoryId, rawUnit);
        if (!normalized.recognized()) {
            throw error(400,
                    label + "单位未登记，不能配置替代换算",
                    "BOM_SUBSTITUTE_UNIT_UNRECOGNIZED");
        }
        return normalized.code();
    }

    private void validateParentOwnership(
            String factoryId, String recipeId, String actualFactoryId, String actualRecipeId) {
        if (!factoryId.equals(actualFactoryId)) {
            throw error(403, "BOM 主项不属于当前工厂", "BOM_SUBSTITUTE_PARENT_FACTORY_MISMATCH");
        }
        if (!recipeId.equals(actualRecipeId)) {
            throw error(409, "BOM 主项不属于当前版本", "BOM_SUBSTITUTE_PARENT_RECIPE_MISMATCH");
        }
    }

    private List<BomItemSubstitute> recipeRelations(
            String factoryId, String recipeId, Long parentRecipeItemId) {
        return repository
                .findByFactoryIdAndRecipeIdAndParentKindAndParentRecipeItemIdOrderByCreatedAtAsc(
                        factoryId, recipeId, BomItemSubstitute.ParentKind.RECIPE_ITEM,
                        parentRecipeItemId);
    }

    private List<BomItemSubstitute> seasoningRelations(
            String factoryId, String recipeId, Long parentSeasoningItemId) {
        return repository
                .findByFactoryIdAndRecipeIdAndParentKindAndParentSeasoningItemIdOrderByCreatedAtAsc(
                        factoryId, recipeId, BomItemSubstitute.ParentKind.SEASONING_ITEM,
                        parentSeasoningItemId);
    }

    private boolean sameParentSet(List<BomItemSubstitute> existing, List<BomItemSubstitute> proposed) {
        return relationKeys(existing, false).equals(relationKeys(proposed, false));
    }

    private boolean sameCloneSet(List<BomItemSubstitute> existing, List<BomItemSubstitute> proposed) {
        return relationKeys(existing, true).equals(relationKeys(proposed, true));
    }

    private Set<String> relationKeys(List<BomItemSubstitute> relations, boolean includeParent) {
        Set<String> keys = new HashSet<>();
        for (BomItemSubstitute relation : relations) {
            String parent = includeParent
                    ? relation.getParentKind() + ":"
                    + Objects.toString(relation.getParentRecipeItemId(), "") + ":"
                    + Objects.toString(relation.getParentSeasoningItemId(), "") + ":"
                    : "";
            keys.add(parent + relation.getSubstituteMaterialTypeId() + ":"
                    + relation.getConversionFactor().stripTrailingZeros().toPlainString() + ":"
                    + Boolean.TRUE.equals(relation.getConversionExplicit()));
        }
        return keys;
    }

    private boolean sameParent(BomItemSubstitute relation, ParentContext parent) {
        if (relation.getParentKind() != parent.kind()) return false;
        return parent.kind() == BomItemSubstitute.ParentKind.RECIPE_ITEM
                ? Objects.equals(relation.getParentRecipeItemId(), parent.parentRecipeItemId())
                : Objects.equals(relation.getParentSeasoningItemId(), parent.parentSeasoningItemId());
    }

    private BomItemSubstituteDTO toDto(BomItemSubstitute relation) {
        return BomItemSubstituteDTO.builder()
                .id(relation.getId())
                .factoryId(relation.getFactoryId())
                .recipeId(relation.getRecipeId())
                .parentKind(relation.getParentKind())
                .parentRecipeItemId(relation.getParentRecipeItemId())
                .parentSeasoningItemId(relation.getParentSeasoningItemId())
                .parentMaterialTypeId(relation.getParentMaterialTypeIdSnapshot())
                .parentMaterialName(relation.getParentMaterialNameSnapshot())
                .materialCategory(relation.getMaterialCategorySnapshot())
                .workProcessId(relation.getWorkProcessIdSnapshot())
                .workflowProcessNodeId(relation.getWorkflowProcessNodeIdSnapshot())
                .packagingSpecId(relation.getPackagingSpecIdSnapshot())
                .packagingRole(relation.getPackagingRoleSnapshot())
                .substituteMaterialTypeId(relation.getSubstituteMaterialTypeId())
                .substituteMaterialCode(relation.getSubstituteMaterialCodeSnapshot())
                .substituteMaterialName(relation.getSubstituteMaterialNameSnapshot())
                .parentUnit(relation.getParentUnitSnapshot())
                .substituteUnit(relation.getSubstituteUnitSnapshot())
                .conversionFactor(relation.getConversionFactor())
                .conversionExplicit(Boolean.TRUE.equals(relation.getConversionExplicit()))
                .version(relation.getVersion())
                .createdAt(relation.getCreatedAt())
                .updatedAt(relation.getUpdatedAt())
                .build();
    }

    private String valueOrFallback(String value, String fallback) {
        String trimmed = trimToNull(value);
        return trimmed == null ? fallback : trimmed;
    }

    private String trimToNull(String value) {
        if (value == null) return null;
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private BusinessException error(int status, String message, String code) {
        return new BusinessException(status, message).withCode(code);
    }

    private BusinessException error(int status, String message, String code, Throwable cause) {
        return new BusinessException(status, message, cause).withCode(code);
    }

    private record ParentContext(
            String factoryId,
            String recipeId,
            BomItemSubstitute.ParentKind kind,
            Long parentRecipeItemId,
            Long parentSeasoningItemId,
            String materialTypeId,
            String materialName,
            String materialCategory,
            String workProcessId,
            String workflowProcessNodeId,
            String packagingSpecId,
            String packagingRole,
            String unit,
            RawMaterialType parentMaterial) {
    }
}
