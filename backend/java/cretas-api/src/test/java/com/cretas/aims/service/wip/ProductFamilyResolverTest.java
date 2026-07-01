package com.cretas.aims.service.wip;

import com.cretas.aims.entity.ProductType;
import com.cretas.aims.entity.RawMaterialType;
import com.cretas.aims.entity.bom.BomRecipe;
import com.cretas.aims.entity.bom.BomRecipeItem;
import com.cretas.aims.repository.ProductTypeRepository;
import com.cretas.aims.repository.RawMaterialTypeRepository;
import com.cretas.aims.repository.bom.BomRecipeItemRepository;
import com.cretas.aims.repository.bom.BomRecipeRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link ProductFamilyResolver} 单元测试 — 产品族自动识别 (核心料名归族, 粗粒度)。
 *
 * <p><b>核心行为</b> (07-01 客户澄清 + 真实数据复盘):
 * <ul>
 *   <li>族键 = 归一化<b>核心料名</b> ({@code CORE:猪蹄}), 不是 raw_material_types.id。</li>
 *   <li><b>多行不同 id 的猪蹄原料</b> ({@code YL-叮咚-猪蹄18-22} / {@code 二级猪蹄}) + 无 BOM 的猪蹄成品
 *       → <b>同一"猪蹄"族</b> (复用的根本; 六膳门真实场景)。</li>
 *   <li>牛肉/牛腱 → 独立族 (不塌进猪蹄); 猪蹄 vs 猪舌 <b>不过度合并</b> (不塌成"猪")。</li>
 *   <li>剥噪 (存储/等级/供应商码/规格) 后锚定真实原料核心名; 锚不出 → 族键缺失 → <b>宽松放行</b>。</li>
 *   <li>BOM主料优先, 无 BOM → 名称字典兜底; 都无 → 族键缺失。</li>
 * </ul>
 *
 * <p>测试用<b>真实数据形态</b>的名字 (源自 prod cretas_prod_db LIUSHANMEN/F006)。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("ProductFamilyResolver — 产品族自动识别 (核心料名归族, 粗粒度)")
class ProductFamilyResolverTest {

    private static final String FACTORY_ID = "F006";

    @InjectMocks
    private ProductFamilyResolver resolver;

    @Mock private BomRecipeRepository bomRecipeRepo;
    @Mock private BomRecipeItemRepository bomRecipeItemRepo;
    @Mock private ProductTypeRepository productTypeRepo;
    @Mock private RawMaterialTypeRepository rawMaterialTypeRepo;

    // ==================== Helpers ====================

    private ProductType pt(String id, String name, String baseName) {
        ProductType p = new ProductType();
        p.setId(id);
        p.setName(name);
        p.setBaseProductName(baseName);
        p.setFactoryId(FACTORY_ID);
        p.setCode("C-" + id);
        p.setUnit("kg");
        return p;
    }

    private RawMaterialType raw(String id, String name) {
        return raw(id, name, "原料");
    }

    private RawMaterialType raw(String id, String name, String category) {
        RawMaterialType r = new RawMaterialType();
        r.setId(id);
        r.setName(name);
        r.setCategory(category);
        r.setFactoryId(FACTORY_ID);
        r.setCode("RC-" + id);
        r.setUnit("kg");
        return r;
    }

    private BomRecipe recipe(String recipeId, String productTypeId) {
        BomRecipe br = new BomRecipe();
        br.setId(recipeId);
        br.setFactoryId(FACTORY_ID);
        br.setProductTypeId(productTypeId);
        br.setProductName("N-" + productTypeId);
        br.setRecipeCode("BOM-" + recipeId);
        br.setOutputQuantityPerUnit(new BigDecimal("200"));
        return br;
    }

    /** BOM 明细 — materialName 传原料真实名 (但归族用 materialTypeId 反查字典的名, 更权威)。 */
    private BomRecipeItem item(Long id, String materialTypeId, String materialName, String category,
                               BigDecimal stdQty, Integer sortOrder) {
        BomRecipeItem i = new BomRecipeItem();
        i.setId(id);
        i.setFactoryId(FACTORY_ID);
        i.setMaterialTypeId(materialTypeId);
        i.setMaterialName(materialName);
        i.setMaterialCategory(category);
        i.setStandardQuantity(stdQty);
        i.setUnit("g");
        i.setSortOrder(sortOrder);
        return i;
    }

    /** 给某 productType 装一份 BOM (含明细)。 */
    private void mockBom(String productTypeId, String recipeId, BomRecipeItem... items) {
        when(bomRecipeRepo.findByFactoryIdAndProductTypeIdAndIsCurrentTrue(FACTORY_ID, productTypeId))
                .thenReturn(Optional.of(recipe(recipeId, productTypeId)));
        when(bomRecipeItemRepo.findByRecipeIdOrderBySortOrderAsc(recipeId)).thenReturn(List.of(items));
    }

    private void mockNoBom(String productTypeId) {
        when(bomRecipeRepo.findByFactoryIdAndProductTypeIdAndIsCurrentTrue(FACTORY_ID, productTypeId))
                .thenReturn(Optional.empty());
    }

    /** 装该工厂原料字典 (锚集来源, 每次 resolveFamilies 必查一次)。 */
    private void mockRawDict(RawMaterialType... raws) {
        when(rawMaterialTypeRepo.findByFactoryId(FACTORY_ID)).thenReturn(List.of(raws));
    }

    // ==================== ★ 粗粒度归族 — 真实数据 (headline) ====================

    @Nested
    @DisplayName("★ 核心料名粗粒度归族 (真实数据 LIUSHANMEN/F006)")
    class CoarseGrainRealData {

        @Test
        @DisplayName("★(a) 两行不同 id 的猪蹄原料 (YL-叮咚-猪蹄18-22 / 二级猪蹄) 经 BOM主料 → 同一猪蹄族")
        void twoZhutiRawRows_differentIds_coFamilyViaBom() {
            // 六膳门真实: RMT_...4546 = YL-叮咚-猪蹄18-22, RMT_...4144 = 二级猪蹄 (不同 id)。
            // 一旦 BOM 补齐, 兄弟成品主料指向不同 id —— 旧 id 键会分裂成两族 (隐藏复用)。核心名归族 → 同族。
            mockRawDict(
                    raw("RM-ZHUTI-DD", "YL-叮咚-猪蹄18-22", "主材"),   // 脏名: 供应商码+规格, 不作锚
                    raw("RM-ZHUTI-2J", "二级猪蹄", "主材"),           // 干净: 剥等级 → 锚 猪蹄
                    raw("RM-NIU", "牛前键", "主材"));
            mockBom("PT-LU", "R-LU",
                    item(1L, "RM-ZHUTI-DD", "YL-叮咚-猪蹄18-22", "RAW", new BigDecimal("300"), 1));
            mockBom("PT-JIAO", "R-JIAO",
                    item(2L, "RM-ZHUTI-2J", "二级猪蹄", "RAW", new BigDecimal("280"), 1));
            when(productTypeRepo.findByIdIn(anyCollection())).thenReturn(List.of(
                    pt("PT-LU", "叮咚好食光红烧猪蹄 250g", "红烧猪蹄"),
                    pt("PT-JIAO", "叮咚好食光泰式酸辣猪蹄 225g", "泰式酸辣猪蹄")));

            Map<String, String> fam = resolver.resolveFamilies(FACTORY_ID, List.of("PT-LU", "PT-JIAO"));

            assertThat(fam.get("PT-LU")).isEqualTo("CORE:猪蹄");
            assertThat(fam.get("PT-JIAO")).isEqualTo("CORE:猪蹄");
            assertThat(fam.get("PT-LU")).isEqualTo(fam.get("PT-JIAO"));   // ← 复用的根本
        }

        @Test
        @DisplayName("★(b) F006 冻猪蹄 (肉类) 剥存储前缀 → 猪蹄族; 与 LIUSHANMEN 二级猪蹄 同核心名")
        void f006FrozenZhuti_sameCoreAsGrader() {
            // F006 真实原料 冻猪蹄(肉类); LIUSHANMEN 二级猪蹄(主材) —— 归一后都是 猪蹄, 族键字符串一致。
            mockRawDict(
                    raw("RM-F006", "冻猪蹄", "肉类"),
                    raw("RM-LSM", "二级猪蹄", "主材"));
            mockBom("PT-F", "R-F",
                    item(1L, "RM-F006", "冻猪蹄", "RAW", new BigDecimal("100"), 1));
            mockBom("PT-L", "R-L",
                    item(2L, "RM-LSM", "二级猪蹄", "RAW", new BigDecimal("100"), 1));
            when(productTypeRepo.findByIdIn(anyCollection())).thenReturn(List.of(
                    pt("PT-F", "叮咚好食光卤猪蹄(去大骨) 200g", "卤猪蹄"),
                    pt("PT-L", "叮咚好食光红烧猪蹄 250g", "红烧猪蹄")));

            Map<String, String> fam = resolver.resolveFamilies(FACTORY_ID, List.of("PT-F", "PT-L"));

            assertThat(fam.get("PT-F")).isEqualTo("CORE:猪蹄");
            assertThat(fam.get("PT-L")).isEqualTo("CORE:猪蹄");
        }

        @Test
        @DisplayName("★(c) 无 BOM 的猪蹄成品 (叮咚好食光卤猪蹄) → 名称锚定 → 猪蹄族")
        void noBomZhutiProduct_matchesByName() {
            // 六膳门真实: 141 产品仅 2 有 BOM; 3 个猪蹄成品无 BOM → 靠产品名锚定 猪蹄。
            mockRawDict(
                    raw("RM-ZHUTI-2J", "二级猪蹄", "主材"),          // 锚 猪蹄
                    raw("RM-ZHUTI-DD", "YL-叮咚-猪蹄18-22", "主材"), // 脏名不作锚
                    raw("RM-NIU", "牛前键", "主材"));
            mockNoBom("PT-C1");
            mockNoBom("PT-C2");
            mockNoBom("PT-C3");
            when(productTypeRepo.findByIdIn(anyCollection())).thenReturn(List.of(
                    pt("PT-C1", "叮咚好食光卤猪蹄(去大骨) 200g", ""),   // baseProductName 空 → 用 name
                    pt("PT-C2", "叮咚好食光泰式酸辣猪蹄 225g", ""),
                    pt("PT-C3", "叮咚好食光红烧猪蹄 250g", "")));

            Map<String, String> fam = resolver.resolveFamilies(FACTORY_ID,
                    List.of("PT-C1", "PT-C2", "PT-C3"));

            assertThat(fam.get("PT-C1")).isEqualTo("CORE:猪蹄");
            assertThat(fam.get("PT-C2")).isEqualTo("CORE:猪蹄");
            assertThat(fam.get("PT-C3")).isEqualTo("CORE:猪蹄");
        }

        @Test
        @DisplayName("★(d) 牛肉/牛腱 独立族 (不塌进猪蹄); 猪蹄 vs 猪舌 不过度合并 (不塌成 猪)")
        void niuAndZhu_notOverCollapsed() {
            mockRawDict(
                    raw("RM-ZHUTI", "猪蹄", "主材"),
                    raw("RM-ZHUSHE", "猪舌", "主材"),
                    raw("RM-NIUROU", "牛肉", "主材"),
                    raw("RM-NIUJIAN", "牛腱", "主材"));
            mockNoBom("PT-ZT");
            mockNoBom("PT-ZS");
            mockNoBom("PT-NR");
            mockNoBom("PT-NJ");
            when(productTypeRepo.findByIdIn(anyCollection())).thenReturn(List.of(
                    pt("PT-ZT", "卤猪蹄", "卤猪蹄"),
                    pt("PT-ZS", "轻卤门腔猪舌", "猪舌"),
                    pt("PT-NR", "冷吃麻辣牛肉", "麻辣牛肉"),
                    pt("PT-NJ", "松茸牛腱子", "牛腱")));

            Map<String, String> fam = resolver.resolveFamilies(FACTORY_ID,
                    List.of("PT-ZT", "PT-ZS", "PT-NR", "PT-NJ"));

            assertThat(fam.get("PT-ZT")).isEqualTo("CORE:猪蹄");
            assertThat(fam.get("PT-ZS")).isEqualTo("CORE:猪舌");    // 不塌成 猪
            assertThat(fam.get("PT-NR")).isEqualTo("CORE:牛肉");    // 不塌进 猪蹄
            assertThat(fam.get("PT-NJ")).isEqualTo("CORE:牛腱");
            // 四者互不相同
            assertThat(Set.of(fam.get("PT-ZT"), fam.get("PT-ZS"),
                    fam.get("PT-NR"), fam.get("PT-NJ"))).hasSize(4);
        }

        @Test
        @DisplayName("★(d2) 猪舌 vs 猪蹄: 一方名字含另一核心的子片段也不误配 (猪舌 不含 猪蹄)")
        void zhusheNotMatchZhuti() {
            mockRawDict(
                    raw("RM-ZHUTI", "冻猪蹄", "肉类"),      // 锚 猪蹄
                    raw("RM-ZHUSHE", "冷冻猪舌", "原料"));   // 锚 猪舌
            mockNoBom("PT-ZS");
            when(productTypeRepo.findByIdIn(anyCollection()))
                    .thenReturn(List.of(pt("PT-ZS", "叮咚好食光轻卤门腔（猪舌）120g", "猪舌")));

            assertThat(resolver.resolveFamily(FACTORY_ID, "PT-ZS")).isEqualTo("CORE:猪舌");
        }

        @Test
        @DisplayName("★(e) 脏名锚不出核心 (YL-王伟-猪肉青, 无 猪肉 锚) → 族键缺失 → 宽松放行")
        void unanchorableDirtyName_absent() {
            // 六膳门真实 猪肉青/猪二号肉 等: 无干净"猪肉"原料 → 无锚 → 识别不出 → over-permissive。
            mockRawDict(
                    raw("RM-ZHUTI", "二级猪蹄", "主材"),           // 锚 猪蹄 (但产品名不含 猪蹄)
                    raw("RM-DIRTY", "YL-王伟-猪肉青", "主材"));     // 脏名, 不作锚
            mockNoBom("PT-UNK");
            when(productTypeRepo.findByIdIn(anyCollection()))
                    .thenReturn(List.of(pt("PT-UNK", "王伟猪肉青加工", "猪肉青")));

            Map<String, String> fam = resolver.resolveFamilies(FACTORY_ID, List.of("PT-UNK"));

            assertThat(fam).doesNotContainKey("PT-UNK");
            assertThat(resolver.resolveFamily(FACTORY_ID, "PT-UNK")).isNull();
        }
    }

    // ==================== BOM主料 主信号 ====================

    @Nested
    @DisplayName("BOM主料 (以原料为主) — 主信号")
    class BomPrimary {

        @Test
        @DisplayName("★(f) BOM主料优先: 主料反查真实原料名 → 核心名 (即便产品名指向别的核心)")
        void bomPrimary_usesRawName_precedesProductName() {
            // BOM主料 = 牛肉(RM-NIU); 但产品名"卤猪蹄"含 猪蹄。BOM主料优先 → 取 牛肉, 不看产品名。
            mockRawDict(
                    raw("RM-NIU", "牛肉", "主材"),
                    raw("RM-ZHUTI", "猪蹄", "主材"));
            mockBom("PT-P", "R-P",
                    item(1L, "RM-NIU", "牛肉", "RAW", new BigDecimal("300"), 1));
            when(productTypeRepo.findByIdIn(anyCollection()))
                    .thenReturn(List.of(pt("PT-P", "卤猪蹄", "卤猪蹄")));  // 名字像猪蹄, 但 BOM主料是牛肉

            assertThat(resolver.resolveFamily(FACTORY_ID, "PT-P")).isEqualTo("CORE:牛肉");
        }

        @Test
        @DisplayName("牛肉成品 → 不同族键 (猪蹄计划不显牛肉)")
        void niurou_differentFamily() {
            mockRawDict(
                    raw("RM-ZHUTI", "猪蹄", "主材"),
                    raw("RM-NIUROU", "牛肉", "主材"));
            mockBom("PT-ZHUTI", "R-Z",
                    item(1L, "RM-ZHUTI", "猪蹄", "RAW", new BigDecimal("300"), 1));
            mockBom("PT-NIUROU", "R-N",
                    item(2L, "RM-NIUROU", "牛肉", "RAW", new BigDecimal("300"), 1));
            when(productTypeRepo.findByIdIn(anyCollection())).thenReturn(List.of(
                    pt("PT-ZHUTI", "卤猪蹄", "卤猪蹄"),
                    pt("PT-NIUROU", "卤牛肉", "卤牛肉")));

            Map<String, String> fam = resolver.resolveFamilies(FACTORY_ID, List.of("PT-ZHUTI", "PT-NIUROU"));

            assertThat(fam.get("PT-ZHUTI")).isEqualTo("CORE:猪蹄");
            assertThat(fam.get("PT-NIUROU")).isEqualTo("CORE:牛肉");
            assertThat(fam.get("PT-ZHUTI")).isNotEqualTo(fam.get("PT-NIUROU"));
        }

        @Test
        @DisplayName("主料 = 用量最大的 RAW 项 (忽略 AUXILIARY/PACKAGING, 忽略较小 RAW)")
        void primary_isDominantRawByQuantity() {
            mockRawDict(
                    raw("RM-SMALL", "生姜", "主材"),   // 小用量 RAW
                    raw("RM-BIG", "猪蹄", "主材"));     // 大用量 RAW → 主料
            mockBom("PT-X", "R-X",
                    item(1L, "RM-SMALL", "生姜", "RAW", new BigDecimal("50"), 1),
                    item(2L, "RM-BIG", "猪蹄", "RAW", new BigDecimal("400"), 2),
                    item(3L, "RM-PACK", "包装盒", "PACKAGING", new BigDecimal("999"), 3));
            when(productTypeRepo.findByIdIn(anyCollection())).thenReturn(List.of(pt("PT-X", "X", "X")));

            assertThat(resolver.resolveFamily(FACTORY_ID, "PT-X")).isEqualTo("CORE:猪蹄");
        }

        @Test
        @DisplayName("BOM 存在但无 RAW 明细 → 落名称字典兜底")
        void bomWithoutRaw_fallsBackToName() {
            mockRawDict(raw("RM-ZHUTI", "冻猪蹄", "肉类"));
            mockBom("PT-Y", "R-Y",
                    item(1L, "RM-SALT", "食盐", "AUXILIARY", new BigDecimal("5"), 1));
            when(productTypeRepo.findByIdIn(anyCollection())).thenReturn(List.of(pt("PT-Y", "卤猪蹄", "卤猪蹄")));

            assertThat(resolver.resolveFamily(FACTORY_ID, "PT-Y")).isEqualTo("CORE:猪蹄");
        }

        @Test
        @DisplayName("BOM主料反查不到 id (孤儿主料) → 退回 BOM 明细 materialName 归一化")
        void bomPrimary_orphanId_fallsBackToItemMaterialName() {
            // BOM 明细 materialTypeId 指向字典没有的 id (被剔为辅料/已删)。退回 item.materialName='冻猪蹄'。
            mockRawDict(raw("RM-ZHUTI-CANON", "二级猪蹄", "主材"));  // 锚 猪蹄
            mockBom("PT-ORPH", "R-ORPH",
                    item(1L, "RM-GONE", "冻猪蹄", "RAW", new BigDecimal("100"), 1));
            when(productTypeRepo.findByIdIn(anyCollection()))
                    .thenReturn(List.of(pt("PT-ORPH", "卤猪蹄", "卤猪蹄")));

            assertThat(resolver.resolveFamily(FACTORY_ID, "PT-ORPH")).isEqualTo("CORE:猪蹄");
        }
    }

    // ==================== 名称字典 兜底信号 ====================

    @Nested
    @DisplayName("名称字典 (以原料为名) — 兜底信号 (无 BOM 的导入产品)")
    class NameDictionary {

        @Test
        @DisplayName("★真实 F006: 主料肉 category=肉类 (非'原料') 仍可锚 (冻猪蹄 → 猪蹄)")
        void f006_matchesRouLeiCategory() {
            mockNoBom("PT-F006");
            when(productTypeRepo.findByIdIn(anyCollection()))
                    .thenReturn(List.of(pt("PT-F006", "卤猪蹄", "卤猪蹄")));
            mockRawDict(
                    raw("RM-ZHUTI", "冻猪蹄", "肉类"),
                    raw("RM-SALT", "食盐", "调味料"));

            assertThat(resolver.resolveFamily(FACTORY_ID, "PT-F006")).isEqualTo("CORE:猪蹄");
        }

        @Test
        @DisplayName("★真实 LIUSHANMEN: 主材 category=主材 (根本无'原料'类) 仍可锚")
        void liushanmen_matchesZhuCaiCategory() {
            mockNoBom("PT-LSM");
            when(productTypeRepo.findByIdIn(anyCollection()))
                    .thenReturn(List.of(pt("PT-LSM", "叮咚好食光卤猪蹄", "卤猪蹄")));
            mockRawDict(
                    raw("RM-ZHUTI", "二级猪蹄", "主材"),
                    raw("RM-JIANG", "生抽", "调味品"));

            assertThat(resolver.resolveFamily(FACTORY_ID, "PT-LSM")).isEqualTo("CORE:猪蹄");
        }

        @Test
        @DisplayName("辅料类 (调味料/添加剂/包材) 不作锚 — 即使产品名含调料名也只锚主材")
        void auxiliaryCategories_notFamilySignal() {
            mockNoBom("PT-AUX");
            when(productTypeRepo.findByIdIn(anyCollection()))
                    .thenReturn(List.of(pt("PT-AUX", "香辣猪蹄", "香辣猪蹄")));
            mockRawDict(
                    raw("RM-CHILI", "辣椒", "调味料"),
                    raw("RM-ADD", "防腐剂", "添加剂"),
                    raw("RM-BOX", "包装盒", "包材"),
                    raw("RM-ZHUTI", "冻猪蹄", "肉类"));

            assertThat(resolver.resolveFamily(FACTORY_ID, "PT-AUX")).isEqualTo("CORE:猪蹄");
        }

        @Test
        @DisplayName("纯辅料匹配 (无主材命中) → 族键缺失 (调料名不构成族)")
        void onlyAuxiliaryMatch_noFamily() {
            mockNoBom("PT-ONLYAUX");
            when(productTypeRepo.findByIdIn(anyCollection()))
                    .thenReturn(List.of(pt("PT-ONLYAUX", "五香辣椒", "五香辣椒")));
            mockRawDict(raw("RM-CHILI", "辣椒", "调味料"));

            assertThat(resolver.resolveFamily(FACTORY_ID, "PT-ONLYAUX")).isNull();
        }

        @Test
        @DisplayName("category 为 null (未分类) 的主材仍作锚候选")
        void nullCategoryRaw_stillCandidate() {
            mockNoBom("PT-NULLCAT");
            when(productTypeRepo.findByIdIn(anyCollection()))
                    .thenReturn(List.of(pt("PT-NULLCAT", "卤猪蹄", "卤猪蹄")));
            mockRawDict(raw("RM-ZHUTI", "猪蹄", null));

            assertThat(resolver.resolveFamily(FACTORY_ID, "PT-NULLCAT")).isEqualTo("CORE:猪蹄");
        }

        @Test
        @DisplayName("最长匹配优先 (牛肉丸 胜 牛肉)")
        void longestMatchWins() {
            mockNoBom("PT-N");
            when(productTypeRepo.findByIdIn(anyCollection()))
                    .thenReturn(List.of(pt("PT-N", "手工牛肉丸", "牛肉丸")));
            mockRawDict(raw("RM-NIUROU", "牛肉"), raw("RM-WAN", "牛肉丸"));

            assertThat(resolver.resolveFamily(FACTORY_ID, "PT-N")).isEqualTo("CORE:牛肉丸");
        }

        @Test
        @DisplayName("无 baseProductName → 回退 name 匹配")
        void fallsBackToNameField() {
            mockNoBom("PT-Z");
            when(productTypeRepo.findByIdIn(anyCollection()))
                    .thenReturn(List.of(pt("PT-Z", "卤牛肉", null)));  // baseProductName null
            mockRawDict(raw("RM-NIUROU", "牛肉"));

            assertThat(resolver.resolveFamily(FACTORY_ID, "PT-Z")).isEqualTo("CORE:牛肉");
        }

        @Test
        @DisplayName("BOM主料 与 名称字典 落同一核心名空间 → 兄弟(一走BOM一走名称)仍同族")
        void bomAndName_sameCoreSpace_stillMatch() {
            mockRawDict(raw("RM-ZHUTI", "冻猪蹄", "肉类"));
            mockBom("PT-BOM", "R-BOM",
                    item(1L, "RM-ZHUTI", "冻猪蹄", "RAW", new BigDecimal("300"), 1));
            mockNoBom("PT-NAME");
            when(productTypeRepo.findByIdIn(anyCollection())).thenReturn(List.of(
                    pt("PT-BOM", "卤猪蹄", "卤猪蹄"),
                    pt("PT-NAME", "椒麻猪蹄", "椒麻猪蹄")));

            Map<String, String> fam = resolver.resolveFamilies(FACTORY_ID, List.of("PT-BOM", "PT-NAME"));

            assertThat(fam.get("PT-BOM")).isEqualTo("CORE:猪蹄");
            assertThat(fam.get("PT-NAME")).isEqualTo("CORE:猪蹄");
            assertThat(fam.get("PT-BOM")).isEqualTo(fam.get("PT-NAME"));
        }
    }

    // ==================== 归一化 (剥噪 + min-core guard) ====================

    @Nested
    @DisplayName("归一化 — 剥存储/等级前缀 + 最小核心保护")
    class Normalization {

        @Test
        @DisplayName("存储前缀剥离: 冻/速冻/冷冻/冷鲜猪蹄 全归 猪蹄")
        void stripStoragePrefixes() {
            mockRawDict(
                    raw("R1", "冻猪蹄"), raw("R2", "速冻猪蹄"),
                    raw("R3", "冷冻猪蹄"), raw("R4", "冷鲜猪蹄"));
            mockNoBom("PT-1"); mockNoBom("PT-2"); mockNoBom("PT-3"); mockNoBom("PT-4");
            when(productTypeRepo.findByIdIn(anyCollection())).thenReturn(List.of(
                    pt("PT-1", "卤猪蹄", "卤猪蹄"), pt("PT-2", "红烧猪蹄", "红烧猪蹄"),
                    pt("PT-3", "椒麻猪蹄", "椒麻猪蹄"), pt("PT-4", "酸辣猪蹄", "酸辣猪蹄")));

            Map<String, String> fam = resolver.resolveFamilies(FACTORY_ID,
                    List.of("PT-1", "PT-2", "PT-3", "PT-4"));

            assertThat(fam.values()).containsOnly("CORE:猪蹄");
        }

        @Test
        @DisplayName("等级前缀剥离: 二级/三级/特级猪蹄 全归 猪蹄")
        void stripGradePrefixes() {
            mockRawDict(raw("R1", "二级猪蹄", "主材"), raw("R2", "三级猪蹄", "主材"),
                    raw("R3", "特级猪蹄", "主材"));
            mockNoBom("PT-1");
            when(productTypeRepo.findByIdIn(anyCollection()))
                    .thenReturn(List.of(pt("PT-1", "卤猪蹄", "卤猪蹄")));

            assertThat(resolver.resolveFamily(FACTORY_ID, "PT-1")).isEqualTo("CORE:猪蹄");
        }

        @Test
        @DisplayName("min-core guard: 生姜 不被剥成 姜 (剥后仅 1 汉字 → 不剥)")
        void minCoreGuard_shengJiangNotStripped() {
            // 若把 生姜 误剥成 姜, 则 生姜 与含姜产品会误配。剥后<2字 → 不剥 → 锚=生姜。
            mockRawDict(raw("RM-JIANG", "生姜", "主材"));
            mockNoBom("PT-J");
            when(productTypeRepo.findByIdIn(anyCollection()))
                    .thenReturn(List.of(pt("PT-J", "姜汁调味", "姜汁")));  // 含 姜 但不含 生姜

            // 生姜 未被剥成 姜 → 产品名"姜汁"不含"生姜" → 无匹配 → 族键缺失
            assertThat(resolver.resolveFamily(FACTORY_ID, "PT-J")).isNull();
        }

        @Test
        @DisplayName("脏名 (含 ASCII/数字/括号) 不作锚: 西装鸡（16只） / YL-叮咚-猪蹄18-22 不污染锚集")
        void dirtyNames_notAnchors() {
            // 仅 二级猪蹄 是干净锚(猪蹄); 脏名不作锚。产品"西装鸡"名无干净锚命中 → 缺失。
            mockRawDict(
                    raw("RM-DIRTY1", "西装鸡（16只）", "主材"),
                    raw("RM-DIRTY2", "YL-叮咚-猪蹄18-22", "主材"),
                    raw("RM-CLEAN", "二级猪蹄", "主材"));
            mockNoBom("PT-CHICKEN");
            when(productTypeRepo.findByIdIn(anyCollection()))
                    .thenReturn(List.of(pt("PT-CHICKEN", "西装鸡整只", "西装鸡")));

            // 西装鸡（16只） 非纯汉字 → 不作锚 → 产品"西装鸡"无锚命中 → 缺失 (over-permissive)
            assertThat(resolver.resolveFamily(FACTORY_ID, "PT-CHICKEN")).isNull();
        }
    }

    // ==================== 识别不出 + 边界 ====================

    @Nested
    @DisplayName("识别不出族 + 边界")
    class Unresolvable {

        @Test
        @DisplayName("无 BOM 且名称无锚命中 → 族键缺失 (map 无此 key, 宽松放行)")
        void noBomNoNameMatch_absentFromMap() {
            mockNoBom("PT-UNK");
            when(productTypeRepo.findByIdIn(anyCollection()))
                    .thenReturn(List.of(pt("PT-UNK", "神秘产品", "神秘产品")));
            mockRawDict(raw("RM-ZHUTI", "猪蹄"));

            Map<String, String> fam = resolver.resolveFamilies(FACTORY_ID, List.of("PT-UNK"));

            assertThat(fam).doesNotContainKey("PT-UNK");
            assertThat(resolver.resolveFamily(FACTORY_ID, "PT-UNK")).isNull();
        }

        @Test
        @DisplayName("空原料字典 → 无锚 → 全部识别不出 (宽松放行)")
        void emptyRawDict_allUnresolvable() {
            mockNoBom("PT-A");
            when(productTypeRepo.findByIdIn(anyCollection()))
                    .thenReturn(List.of(pt("PT-A", "卤猪蹄", "卤猪蹄")));
            mockRawDict();  // 空字典

            assertThat(resolver.resolveFamily(FACTORY_ID, "PT-A")).isNull();
        }

        @Test
        @DisplayName("空/ null 输入 → 空 map, 不查任何 repo")
        void emptyInput_noQueries() {
            assertThat(resolver.resolveFamilies(FACTORY_ID, List.of())).isEmpty();
            assertThat(resolver.resolveFamilies(FACTORY_ID, null)).isEmpty();
            assertThat(resolver.resolveFamilies(null, List.of("PT-A"))).isEmpty();
            verify(productTypeRepo, never()).findByIdIn(anyCollection());
            verify(bomRecipeRepo, never())
                    .findByFactoryIdAndProductTypeIdAndIsCurrentTrue(any(), any());
            verify(rawMaterialTypeRepo, never()).findByFactoryId(any());
        }

        @Test
        @DisplayName("原料字典每次 resolveFamilies 只查一次 (锚集构建, 无 N+1)")
        void rawDictLoadedOncePerCall() {
            mockBom("PT-A", "R-A", item(1L, "RM-ZHUTI", "猪蹄", "RAW", new BigDecimal("300"), 1));
            mockBom("PT-B", "R-B", item(2L, "RM-ZHUTI", "猪蹄", "RAW", new BigDecimal("300"), 1));
            mockRawDict(raw("RM-ZHUTI", "猪蹄", "主材"));
            when(productTypeRepo.findByIdIn(anyCollection())).thenReturn(List.of(
                    pt("PT-A", "卤猪蹄", "卤猪蹄"), pt("PT-B", "红烧猪蹄", "红烧猪蹄")));

            resolver.resolveFamilies(FACTORY_ID, List.of("PT-A", "PT-B"));

            verify(rawMaterialTypeRepo).findByFactoryId(FACTORY_ID);  // 恰好 1 次 (两产品共享)
        }

        @Test
        @DisplayName("存量产品批量识别 = backfill 等价物 (就地识别覆盖现有 product_types, 零手填)")
        void backfillEquivalent_batchOverExisting() {
            mockRawDict(raw("RM-ZHUTI", "冻猪蹄", "肉类"), raw("RM-NIUROU", "牛肉", "主材"));
            mockBom("PT-A", "R-A", item(1L, "RM-ZHUTI", "冻猪蹄", "RAW", new BigDecimal("300"), 1));
            mockBom("PT-B", "R-B", item(2L, "RM-ZHUTI", "冻猪蹄", "RAW", new BigDecimal("290"), 1));
            mockBom("PT-C", "R-C", item(3L, "RM-NIUROU", "牛肉", "RAW", new BigDecimal("300"), 1));
            when(productTypeRepo.findByIdIn(anyCollection())).thenReturn(List.of(
                    pt("PT-A", "卤猪蹄", "卤猪蹄"),
                    pt("PT-B", "椒麻猪蹄", "椒麻猪蹄"),
                    pt("PT-C", "卤牛肉", "卤牛肉")));

            Map<String, String> fam = resolver.resolveFamilies(
                    FACTORY_ID, Set.of("PT-A", "PT-B", "PT-C"));

            assertThat(fam).hasSize(3);
            assertThat(fam.get("PT-A")).isEqualTo(fam.get("PT-B"));            // 兄弟同族 (核心 猪蹄)
            assertThat(fam.get("PT-C")).isNotEqualTo(fam.get("PT-A"));         // 牛肉异族
        }
    }
}
