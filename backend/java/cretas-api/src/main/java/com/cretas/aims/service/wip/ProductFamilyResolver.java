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
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 产品族 (product family) 自动识别器 — SFI 半成品 / 成品复用防呆过滤的家族信号来源。
 *
 * <p><b>为什么要"产品族"而不是"同产品"</b> (07-01 客户澄清, transcript 59-61):
 * 「版成品都是通用的…只是版成品转成品的时候才会分…这三个品是的」——
 * <b>熟制前的半成品在同一产品族内通用</b> (例: 卤猪蹄 / 椒麻猪蹄 / 猪蹄冠 共用同一"猪蹄"半成品,
 * 只在最后熟制道才分化成不同成品)。按 productTypeId 精确过滤会把这个通用"猪蹄"半成品对
 * 兄弟"猪蹄"成品计划隐藏 —— 恰恰破坏客户想要的复用。故过滤维度必须是<b>产品族</b>
 * (猪蹄 vs 牛肉), 而非产品本身。
 *
 * <p><b>为什么族键必须是"核心料名"而非 raw_material_types.id</b> (07-01 真实数据复盘):
 * 早期实现族键 = {@code "RM:" + 主料 id}。在付费客户 六膳门(LIUSHANMEN) 上近乎失效 ——
 * 他们有<b>两行不同的猪蹄原料</b> ({@code YL-叮咚-猪蹄18-22} 与 {@code 二级猪蹄}, id 不同),
 * 一旦 BOM 补齐, 两个兄弟"猪蹄"成品的主料指向<b>不同 id</b> → 落<b>不同族</b> → 合法复用被隐藏,
 * 恰好破坏防呆。故族键改为<b>归一化核心料名</b> ({@code 猪蹄}): 两行猪蹄原料 + 无 BOM 的猪蹄成品
 * 都归到<b>同一"猪蹄"族</b>, 而 牛肉 仍独立。粒度从"具体原料行"粗化到"核心料"。
 *
 * <p><b>归一化 (core-name normalization)</b> — 从原料/产品名剥噪音, 留核心料词:
 * <ul>
 *   <li>剥前导存储/状态限定词: 速冻/冷冻/冷鲜/冻/鲜/生/熟 ({@code 冻猪蹄→猪蹄})。</li>
 *   <li>剥前导等级标记: 特级/优级/一~五级/甲乙丙级 ({@code 二级猪蹄→猪蹄})。</li>
 *   <li><b>最小核心保护 (min-core guard)</b>: 仅当剥后仍 ≥2 个汉字才剥 —— 防 {@code 生姜→姜} /
 *       {@code 鲜鱼→鱼} 之类过度剥成单字噪音, 也防 {@code 猪蹄}/{@code 猪舌} 被剥成 {@code 猪} 而<b>过度合并</b>。</li>
 * </ul>
 *
 * <p><b>锚定真实原料核心名 (anchoring, 防臆造子串 + 避免脆弱品牌黑名单)</b>:
 * 供应商码/品牌前缀 ({@code YL-}/{@code 叮咚-}) 与 规格后缀 ({@code 18-22}) 千奇百怪, 硬编码剥离列表
 * 必然遗漏。故不强行解析脏名, 而是<b>锚定该工厂真实原料的"干净核心名"</b>: 先从全部原料里抽出
 * 能干净归一 (剥噪后是纯汉字 ≥2 字) 的核心词集合 (锚集, 如 {@code {猪蹄, 猪舌, 牛肉, 膝软骨…}}),
 * 再让脏名 (原料名或产品名) 去<b>匹配锚集里最长的、作为子串出现的核心词</b> ——
 * {@code YL-叮咚-猪蹄18-22} 含 {@code 猪蹄} → 归 {@code 猪蹄}; {@code 二级猪蹄} 亦归 {@code 猪蹄} → 同族。
 * 核心必对应该工厂<b>真实存在</b>的原料核心 (不是任意子串), 且并列取最长 (牛肉 胜 牛)。
 *
 * <p><b>识别不出 → 宽松放行 (over-permissive, 铁律: 绝不藏合法复用)</b>:
 * 归一化剥不出可信核心 (剥后为空/过短/无锚命中) → 族键<b>缺失</b> (map 中无此 key)。
 * 调用方据 map 缺失 = "族未知" 做宽松放行 —— 宁可多显, 不隐藏可能合法的复用。
 *
 * <p><b>零手填 — 双信号自动识别 (就地识别不入库)</b>:
 * <ol>
 *   <li><b>主信号 BOM主料</b>: 取产品当前 BOM ({@code is_current=TRUE}) 中 {@code materialCategory=RAW}
 *       用量最大的一项 (并列取 sortOrder 最小), 用其原料<b>真实名</b> (按 materialTypeId 反查
 *       raw_material_types, 反查不到则退回 BOM 明细 materialName) 归一化 + 锚定 → 核心料名。</li>
 *   <li><b>兜底 名称字典</b>: BOM 缺失/无 RAW 项/主料名锚不出时, 用产品名 (优先 baseProductName, 次 name)
 *       去锚集里匹配最长核心 → 核心料名。处理无 BOM 的导入产品 (客户 141 产品仅 2 有 BOM)。
 *       <p><b>主材字典 = 排除法</b> (不硬编码单一 category): 真实工厂 category 命名各异 ——
 *       F006 主料肉是 {@code 肉类}/{@code 原料}, LIUSHANMEN 是 {@code 主材} (根本没有 {@code 原料} 类)。
 *       故锚集 = 该工厂全部 raw_material_types <b>剔除明确辅料类</b>
 *       ({@code 调味料/调味品/添加剂/包材/包辅材}); 其余 (原料/主材/肉类/未分类 …) 均作主材候选。</li>
 *   <li><b>都识别不出</b> → 族键缺失 → 宽松放行。</li>
 * </ol>
 *
 * <p><b>为什么就地识别 (derive-on-read) 而非落库列</b>: 永新鲜 (BOM/原料改动自动跟随)、零 schema 变更
 * (不碰真客户 337 产品表结构)、零手填 (每次读时就地算, 天然覆盖存量产品)。
 * 过滤只在有界集合 (工厂级 WIP 行 + 当前计划 productType) 上运行, 每工厂原料字典只查一次, 无 N+1。
 *
 * @since 2026-07-01 (feat/sfi-picker-all-steps — 同类过滤改产品族)
 * @since 2026-07-01 (feat/family-coarse-grain — 族键从 raw-id 粗化到核心料名, 两行猪蹄原料同族)
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
     */
    private static final Set<String> AUXILIARY_CATEGORIES = Set.of(
            "调味料", "调味品", "添加剂", "包材", "包辅材");
    /**
     * 归一化前剥离的前导限定词 (存储/状态 + 等级)。<b>顺序敏感</b>: 长前缀在前 (速冻/冷冻/冷鲜 先于 冻/鲜),
     * 命中即剥一次并从头重试 (支持叠加, 如 假想 {@code 冻二级X})。等级如 二级 (真实 {@code 二级猪蹄})。
     * 每次剥离受 min-core guard 约束 (剥后须仍 ≥2 汉字), 见 {@link #coreName}。
     */
    private static final List<String> STRIP_PREFIXES = List.of(
            "速冻", "冷冻", "冷鲜",
            "特级", "优级", "一级", "二级", "三级", "四级", "五级", "甲级", "乙级", "丙级",
            "冻", "鲜", "生", "熟");
    /** 核心料名最小汉字数 —— 防单字过度合并 (猪蹄/猪舌 不可塌成 猪)。 */
    private static final int MIN_CORE_CJK = 2;
    /** BOM 明细 {@code materialCategory} 的原料档 (vs AUXILIARY/PACKAGING)。 */
    private static final String BOM_RAW_CATEGORY = "RAW";
    /** 族键前缀 — 键空间 = 归一化核心料名 (BOM主料 与 名称字典 落同一核心名空间, 故可比较)。 */
    private static final String FAMILY_PREFIX = "CORE:";

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

        // 该工厂原料字典 (一次查) — 锚定必需: 从中抽 (a) 全部原料 id→name 反查表 (BOM主料名),
        // (b) 主材"干净核心名"锚集 (剔辅料类)。即便产品全走 BOM主料, 锚集仍是归一化前提, 故必加载一次。
        List<RawMaterialType> rawList = rawMaterialTypeRepo.findByFactoryId(factoryId);
        Map<String, String> rawIdToName = new HashMap<>();
        for (RawMaterialType r : rawList) {
            if (r.getId() != null) {
                rawIdToName.put(r.getId(), r.getName());
            }
        }
        Set<String> anchors = buildAnchorCores(rawList);

        for (String ptId : ids) {
            String family = deriveFromBom(factoryId, ptId, rawIdToName, anchors);
            if (family == null) {
                family = deriveFromName(ptMap.get(ptId), anchors);
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
     * 主信号: 从产品当前 BOM 的主料 (用量最大的 RAW 明细) 取核心料名。
     *
     * <p>用主料原料的<b>真实名</b> (按 materialTypeId 反查 raw_material_types) 归一化 + 锚定;
     * 反查不到 (id 不在字典 / 被剔为辅料) 则退回 BOM 明细 {@code materialName}。
     * 主料名锚不出核心 → 返 {@code null} → 交由名称兜底 (产品名可能仍能锚出)。
     *
     * @return {@code "CORE:" + 核心料名}, 无当前 BOM / 无 RAW 明细 / 主料名锚不出时返 {@code null}。
     */
    private String deriveFromBom(String factoryId, String productTypeId,
                                 Map<String, String> rawIdToName, Set<String> anchors) {
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
        if (primary == null) {
            return null;
        }
        String rawName = rawIdToName.getOrDefault(primary.getMaterialTypeId(), primary.getMaterialName());
        String core = matchCore(rawName, anchors);
        return core == null ? null : FAMILY_PREFIX + core;
    }

    /**
     * 兜底信号: 产品名 (优先 baseProductName) 锚定主材核心名, 取最长匹配。
     *
     * @return {@code "CORE:" + 核心料名}, 无匹配返 {@code null}。
     */
    private String deriveFromName(ProductType pt, Set<String> anchors) {
        if (pt == null || anchors.isEmpty()) {
            return null;
        }
        String productName = pt.getBaseProductName() != null && !pt.getBaseProductName().isBlank()
                ? pt.getBaseProductName() : pt.getName();
        String core = matchCore(productName, anchors);
        return core == null ? null : FAMILY_PREFIX + core;
    }

    /**
     * 从该工厂原料构建"干净核心名"锚集 —— 剔明确辅料类后, 每个原料归一化 ({@link #coreName}),
     * 保留归一后<b>纯汉字且 ≥{@value #MIN_CORE_CJK} 字</b>的核心词。脏名 (供应商码/规格/括号规格)
     * 归一后含 ASCII/数字/括号 → 非纯汉字 → 不作锚 (宁少锚, 不臆造)。
     * category 为 null/未分类的行保留作候选 (宁可匹配, 不因缺分类误排)。
     */
    private Set<String> buildAnchorCores(List<RawMaterialType> rawList) {
        Set<String> anchors = new LinkedHashSet<>();
        for (RawMaterialType r : rawList) {
            if (isAuxiliaryCategory(r.getCategory())) {
                continue;
            }
            String core = coreName(r.getName());
            if (isCleanCore(core)) {
                anchors.add(core);
            }
        }
        return anchors;
    }

    /** 是否明确的辅料/包材类 category (null/未列出 → 视为主材候选, 返 false)。 */
    private boolean isAuxiliaryCategory(String category) {
        return category != null && AUXILIARY_CATEGORIES.contains(category.trim());
    }

    /**
     * 在 {@code text} 中匹配锚集里<b>最长的、作为子串出现的核心词</b>。
     * 并列 (等长两锚都命中) 取字典序较小者 (确定性)。无命中返 {@code null}。
     */
    private String matchCore(String text, Set<String> anchors) {
        if (text == null || text.isBlank() || anchors.isEmpty()) {
            return null;
        }
        String best = null;
        int bestLen = 0;
        for (String a : anchors) {
            if (text.contains(a)) {
                int len = a.codePointCount(0, a.length());
                if (len > bestLen || (len == bestLen && (best == null || a.compareTo(best) < 0))) {
                    best = a;
                    bestLen = len;
                }
            }
        }
        return best;
    }

    /**
     * 原料名 → 归一化核心词: 剥离前导存储/状态/等级限定词 ({@link #STRIP_PREFIXES})。
     * <b>min-core guard</b>: 仅当剥后仍 ≥{@value #MIN_CORE_CJK} 汉字时才剥 (防 生姜→姜 / 猪蹄→猪 之类过度剥)。
     * 支持叠加剥离 (剥一次后从头重试)。无前缀 / 剥后过短 → 原样返回 (trim 后)。
     */
    private String coreName(String rawName) {
        if (rawName == null) {
            return "";
        }
        String s = rawName.trim();
        boolean stripped = true;
        while (stripped) {
            stripped = false;
            for (String q : STRIP_PREFIXES) {
                if (s.startsWith(q)) {
                    String rest = s.substring(q.length());
                    if (rest.codePointCount(0, rest.length()) >= MIN_CORE_CJK) {
                        s = rest;
                        stripped = true;
                        break;
                    }
                }
            }
        }
        return s;
    }

    /** 归一化结果是否可作锚: 全汉字 (CJK Unified Ideographs) 且 ≥{@value #MIN_CORE_CJK} 字。 */
    private boolean isCleanCore(String s) {
        if (s == null || s.isEmpty()) {
            return false;
        }
        int cjk = 0;
        int i = 0;
        while (i < s.length()) {
            int cp = s.codePointAt(i);
            if (Character.UnicodeScript.of(cp) != Character.UnicodeScript.HAN) {
                return false;
            }
            cjk++;
            i += Character.charCount(cp);
        }
        return cjk >= MIN_CORE_CJK;
    }
}
