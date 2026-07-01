package com.cretas.aims.service.wip;

import com.cretas.aims.entity.ProductType;
import com.cretas.aims.entity.RawMaterialType;
import com.cretas.aims.entity.bom.BomRecipe;
import com.cretas.aims.entity.bom.BomRecipeItem;
import com.cretas.aims.repository.ProductTypeRepository;
import com.cretas.aims.repository.RawMaterialTypeRepository;
import com.cretas.aims.repository.bom.BomRecipeItemRepository;
import com.cretas.aims.repository.bom.BomRecipeRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 产品族 (product family) 自动识别器 — SFI 半成品防呆过滤的家族信号来源。
 *
 * <p><b>为什么要"产品族"而不是"同产品"</b> (07-01 客户澄清, transcript 59-61):
 * 「版成品都是通用的…只是版成品转成品的时候才会分…这三个品是的」——
 * <b>熟制前的半成品在同一产品族内通用</b> (例: 卤猪蹄 / 椒麻猪蹄 / 猪蹄冠 共用同一"猪蹄"半成品,
 * 只在最后熟制道才分化成不同成品)。按 productTypeId 精确过滤会把这个通用"猪蹄"半成品对
 * 兄弟"猪蹄"成品计划隐藏 —— 恰恰破坏客户想要的复用。故过滤维度必须是<b>产品族</b>
 * (猪蹄 vs 牛肉), 而非产品本身。
 *
 * <p><b>客户模型</b>: 版产品「以原料为主, 以原料为名」→ 族 = 产品的主原料。故族键 = 主原料的
 * {@code raw_material_types.id}, 前缀 {@code "RM:"}。
 *
 * <p><b>零手填 — 自动识别 (双信号, 就地识别不入库)</b>:
 * <ol>
 *   <li><b>主信号 BOM主料 (以原料为主)</b>: 取产品当前 BOM ({@code is_current=TRUE}),
 *       在其 {@code materialCategory=RAW} 明细里取<b>用量最大</b>的一项 (并列取 sortOrder 最小),
 *       其 {@code materialTypeId} 即族键。</li>
 *   <li><b>兜底 名称字典 (以原料为名)</b>: BOM 缺失/无 RAW 项时, 用产品名 (优先 baseProductName,
 *       次 name) 去匹配 {@code raw_material_types} 中 {@code category=原料} 的名称, 取<b>最长匹配</b>
 *       (最具体) 的原料 id 作族键。处理无 BOM 的导入产品。</li>
 *   <li><b>都识别不出</b> → 返回族键缺失 (map 中无此 key)。调用方据此<b>宽松放行</b>
 *       (不隐藏可能合法复用的项), 见 {@code WipInventoryServiceImpl} 过滤逻辑。</li>
 * </ol>
 *
 * <p><b>为什么就地识别 (derive-on-read) 而非落库列</b>:
 * <ul>
 *   <li><b>永新鲜</b>: BOM主料改动 (换主料) 后族键自动跟随, 无需重跑 backfill/迁移。</li>
 *   <li><b>零 schema 变更</b>: 不新增列 → 无 prod/test schema drift, 不碰真客户 337 产品的表结构。</li>
 *   <li><b>零手填铁律</b>: 从不作为表单字段暴露; 每次读时就地算, 天然覆盖存量产品
 *       ("同一识别逻辑跑遍现有 product_types" 的 backfill 等价物)。</li>
 * </ul>
 * 过滤只在有界集合 (工厂级 WIP 行 + 当前计划 productType) 上运行, 批量去重后逐 id 识别, 无 N+1 隐患。
 *
 * @since 2026-07-01 (feat/sfi-picker-all-steps — 同类过滤改产品族)
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ProductFamilyResolver {

    private final BomRecipeRepository bomRecipeRepo;
    private final BomRecipeItemRepository bomRecipeItemRepo;
    private final ProductTypeRepository productTypeRepo;
    private final RawMaterialTypeRepository rawMaterialTypeRepo;

    /** {@code raw_material_types.category} 中"原料"档 (vs 调味品/包材) — 名称字典只匹配真原料。 */
    private static final String RAW_MATERIAL_CATEGORY = "原料";
    /** BOM 明细 {@code materialCategory} 的原料档 (vs AUXILIARY/PACKAGING)。 */
    private static final String BOM_RAW_CATEGORY = "RAW";
    /** 族键前缀 — 键空间 = raw_material_types.id (BOM主料 与 名称字典 落同一 id 空间, 故可比较)。 */
    private static final String FAMILY_PREFIX = "RM:";

    /**
     * 批量识别一组产品的族键。
     *
     * @param factoryId      工厂 ID
     * @param productTypeIds 待识别的产品类型 id (可含 null/空白/重复, 内部去重清洗)
     * @return {@code productTypeId → familyKey} 映射; <b>识别不出族的 productType 不会出现在 map 里</b>
     *         (调用方据 map 缺失 = "族未知" 做宽松放行, 不是空串也不是 null 值)。
     */
    public Map<String, String> resolveFamilies(String factoryId, Collection<String> productTypeIds) {
        Map<String, String> result = new HashMap<>();
        if (factoryId == null || factoryId.isBlank() || productTypeIds == null || productTypeIds.isEmpty()) {
            return result;
        }
        Set<String> ids = productTypeIds.stream()
                .filter(id -> id != null && !id.isBlank())
                .collect(Collectors.toSet());
        if (ids.isEmpty()) {
            return result;
        }

        // 名称兜底需要的产品名 (一次批量取, 避免 N+1)。
        Map<String, ProductType> ptMap = new HashMap<>();
        productTypeRepo.findByIdIn(ids).forEach(pt -> ptMap.put(pt.getId(), pt));

        // 原料字典延迟加载 (仅当有产品走名称兜底时才查一次)。
        List<RawMaterialType> rawDict = null;
        boolean rawDictLoaded = false;

        for (String ptId : ids) {
            String family = deriveFromBom(factoryId, ptId);
            if (family == null) {
                if (!rawDictLoaded) {
                    rawDict = rawMaterialTypeRepo.findByFactoryIdAndCategory(factoryId, RAW_MATERIAL_CATEGORY);
                    rawDictLoaded = true;
                }
                family = deriveFromName(ptMap.get(ptId), rawDict);
            }
            if (family != null) {
                result.put(ptId, family);
            }
        }
        return result;
    }

    /**
     * 便捷单产品识别 (内部委托 {@link #resolveFamilies})。
     *
     * @return 族键, 识别不出返 {@code null}。
     */
    public String resolveFamily(String factoryId, String productTypeId) {
        return resolveFamilies(factoryId, List.of(productTypeId == null ? "" : productTypeId)).get(productTypeId);
    }

    /**
     * 主信号: 从产品当前 BOM 的主料 (用量最大的 RAW 明细) 取族键。
     *
     * @return {@code "RM:" + materialTypeId}, 无当前 BOM / 无 RAW 明细时返 {@code null}。
     */
    private String deriveFromBom(String factoryId, String productTypeId) {
        BomRecipe recipe = bomRecipeRepo
                .findByFactoryIdAndProductTypeIdAndIsCurrentTrue(factoryId, productTypeId)
                .orElse(null);
        if (recipe == null) {
            return null;
        }
        List<BomRecipeItem> items = bomRecipeItemRepo.findByRecipeIdOrderBySortOrderAsc(recipe.getId());
        if (items == null || items.isEmpty()) {
            return null;
        }
        // 主料 = 用量 (standardQuantity) 最大的 RAW 项; 并列取 sortOrder 最小; 再并列取 materialTypeId 字典序 (确定性)。
        BomRecipeItem primary = items.stream()
                .filter(i -> BOM_RAW_CATEGORY.equalsIgnoreCase(i.getMaterialCategory()))
                .filter(i -> i.getMaterialTypeId() != null && !i.getMaterialTypeId().isBlank())
                .min(Comparator
                        .comparing((BomRecipeItem i) -> i.getStandardQuantity() == null
                                ? BigDecimal.ZERO : i.getStandardQuantity(), Comparator.reverseOrder())
                        .thenComparing(i -> i.getSortOrder() == null ? Integer.MAX_VALUE : i.getSortOrder())
                        .thenComparing(BomRecipeItem::getMaterialTypeId))
                .orElse(null);
        return primary == null ? null : FAMILY_PREFIX + primary.getMaterialTypeId();
    }

    /**
     * 兜底信号: 产品名 (优先 baseProductName) 匹配原料字典, 取最长匹配的原料 id。
     *
     * @return {@code "RM:" + rawMaterialTypeId}, 无匹配返 {@code null}。
     */
    private String deriveFromName(ProductType pt, List<RawMaterialType> rawDict) {
        if (pt == null || rawDict == null || rawDict.isEmpty()) {
            return null;
        }
        String productName = pt.getBaseProductName() != null && !pt.getBaseProductName().isBlank()
                ? pt.getBaseProductName() : pt.getName();
        if (productName == null || productName.isBlank()) {
            return null;
        }
        RawMaterialType best = null;
        int bestLen = 0;
        for (RawMaterialType raw : rawDict) {
            String rn = raw.getName();
            if (rn == null || rn.isBlank() || raw.getId() == null) {
                continue;
            }
            if (productName.contains(rn) && rn.length() > bestLen) {
                best = raw;
                bestLen = rn.length();
            }
        }
        return best == null ? null : FAMILY_PREFIX + best.getId();
    }
}
