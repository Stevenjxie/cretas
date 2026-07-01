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
 *       次 name) 去匹配 {@code raw_material_types} 中的<b>主材类名称</b>, 取<b>最长匹配</b>
 *       (最具体) 的原料 id 作族键。处理无 BOM 的导入产品。
 *       <p><b>主材类 = 排除法</b> (不硬编码单一 category): 真实工厂 category 命名各异 ——
 *       F006 主料肉是 {@code 肉类} (如 冻猪蹄), LIUSHANMEN 是 {@code 主材} (62 行, 根本没有 {@code 原料} 类)。
 *       故字典 = 该工厂全部 raw_material_types <b>剔除明确的辅料类</b>
 *       ({@code 调味料/调味品/添加剂/包材/包辅材}); 其余 (原料/主材/肉类/未分类 …) 均作主材候选。
 *       这样 F006 的 肉类 与 LIUSHANMEN 的 主材 均可匹配, 且未来新增主材 category 自动生效。</li>
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

    /**
     * 名称字典的<b>排除法</b> — 明确的辅料/包材类 category, 这些<b>不</b>作为产品族信号。
     * 其余全部 category (原料/主材/肉类/未分类 …) 均作主材候选, 从而 F006(肉类) + LIUSHANMEN(主材) 都可匹配。
     * (若硬编码单一 "原料" 则真实工厂全部落空 → 回退失效, 见 class javadoc。)
     */
    private static final Set<String> AUXILIARY_CATEGORIES = Set.of(
            "调味料", "调味品", "添加剂", "包材", "包辅材");
    /**
     * 名称匹配前剥离的前导存储/状态限定词 (真实主材名带 冻/鲜 前缀, 成品名不带)。
     * <b>顺序敏感</b>: 长前缀在前 (速冻/冷冻/冷鲜 先于 冻/鲜), 命中即返回不继续。
     */
    private static final List<String> STORAGE_QUALIFIER_PREFIXES = List.of(
            "速冻", "冷冻", "冷鲜", "冻", "鲜", "生", "熟");
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

        // 主材字典延迟加载 (仅当有产品走名称兜底时才查一次)。
        List<RawMaterialType> rawDict = null;
        boolean rawDictLoaded = false;

        for (String ptId : ids) {
            String family = deriveFromBom(factoryId, ptId);
            if (family == null) {
                if (!rawDictLoaded) {
                    rawDict = loadMainMaterialDict(factoryId);
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
     * 加载该工厂的<b>主材字典</b> — 全部 raw_material_types 剔除明确辅料类 ({@link #AUXILIARY_CATEGORIES})。
     * category 为 null/未分类的行<b>保留</b>作主材候选 (宁可匹配, 不因缺分类而误排)。
     */
    private List<RawMaterialType> loadMainMaterialDict(String factoryId) {
        return rawMaterialTypeRepo.findByFactoryId(factoryId).stream()
                .filter(r -> !isAuxiliaryCategory(r.getCategory()))
                .collect(Collectors.toList());
    }

    /** 是否明确的辅料/包材类 category (null/未列出 → 视为主材候选, 返 false)。 */
    private boolean isAuxiliaryCategory(String category) {
        return category != null && AUXILIARY_CATEGORIES.contains(category.trim());
    }

    /**
     * 兜底信号: 产品名 (优先 baseProductName) 匹配主材字典, 取最长匹配的原料 id。
     *
     * <p>匹配用原料名的<b>核心词</b> (剥离前导存储/状态限定词, 见 {@link #coreName}):
     * 真实数据里主材常带存储前缀 (F006 主料是 {@code 冻猪蹄}), 而成品名是 {@code 卤猪蹄} —— 直接
     * substring 会漏 ({@code "卤猪蹄".contains("冻猪蹄") == false})。剥前缀后 {@code 冻猪蹄→猪蹄},
     * {@code "卤猪蹄".contains("猪蹄") == true}。最长匹配按<b>核心词</b>长度比较 (牛肉 胜 牛)。
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
            if (raw.getId() == null) {
                continue;
            }
            String core = coreName(raw.getName());
            if (core.isEmpty()) {
                continue;
            }
            if (productName.contains(core) && core.length() > bestLen) {
                best = raw;
                bestLen = core.length();
            }
        }
        return best == null ? null : FAMILY_PREFIX + best.getId();
    }

    /**
     * 原料名 → 匹配核心词: 剥离前导存储/状态限定词 (冻/速冻/冷冻/冷鲜/鲜/生/熟)。
     * 仅在剥离后仍剩 ≥2 字符时才剥 (避免把 "生鱼"→"鱼" 之类过度剥成噪音, 且防单字误配)。
     * 无前缀 / 剥后过短 → 原样返回。
     */
    private String coreName(String rawName) {
        if (rawName == null) {
            return "";
        }
        String s = rawName.trim();
        for (String q : STORAGE_QUALIFIER_PREFIXES) {
            if (s.length() > q.length() + 1 && s.startsWith(q)) {
                return s.substring(q.length());
            }
        }
        return s;
    }
}
