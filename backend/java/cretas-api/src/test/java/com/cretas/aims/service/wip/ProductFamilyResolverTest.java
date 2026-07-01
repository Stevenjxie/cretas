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
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link ProductFamilyResolver} 单元测试 — 产品族自动识别 (以原料为主自动识别, 零手填)。
 *
 * <p>核心断言 (07-01 客户澄清后的正确行为):
 * <ul>
 *   <li>两个兄弟"猪蹄"成品 (不同 productTypeId, 同猪蹄主料) → 落<b>同一族键</b> (复用的根本)。</li>
 *   <li>"牛肉"成品 → 不同族键 (猪蹄计划不显牛肉)。</li>
 *   <li>BOM主料优先, 无 BOM → 名称字典兜底; 都无 → 族键缺失 (宽松放行)。</li>
 *   <li>存量产品批量识别 = backfill 等价物 (就地识别覆盖现有 product_types, 零手填)。</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("ProductFamilyResolver — 产品族自动识别 (以原料为主)")
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
        RawMaterialType r = new RawMaterialType();
        r.setId(id);
        r.setName(name);
        r.setCategory("原料");
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

    private BomRecipeItem item(Long id, String materialTypeId, String category,
                               BigDecimal stdQty, Integer sortOrder) {
        BomRecipeItem i = new BomRecipeItem();
        i.setId(id);
        i.setFactoryId(FACTORY_ID);
        i.setMaterialTypeId(materialTypeId);
        i.setMaterialName("M-" + materialTypeId);
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

    // ==================== BOM主料 主信号 ====================

    @Nested
    @DisplayName("BOM主料 (以原料为主) — 主信号")
    class BomPrimary {

        @Test
        @DisplayName("两兄弟猪蹄成品 (不同 productTypeId, 同猪蹄主料) → 同一族键 (复用根本)")
        void twoSiblingZhuti_sameFamily() {
            // PT-A 卤猪蹄, PT-B 椒麻猪蹄 — 各自 BOM主料都指向同一"猪蹄"原料 RM-ZHUTI。
            mockBom("PT-A", "R-A",
                    item(1L, "RM-ZHUTI", "RAW", new BigDecimal("300"), 1),
                    item(2L, "RM-SALT", "AUXILIARY", new BigDecimal("5"), 2));
            mockBom("PT-B", "R-B",
                    item(3L, "RM-ZHUTI", "RAW", new BigDecimal("280"), 1),
                    item(4L, "RM-PEPPER", "AUXILIARY", new BigDecimal("8"), 2));
            when(productTypeRepo.findByIdIn(anyCollection())).thenReturn(List.of(
                    pt("PT-A", "好食光卤猪蹄", "卤猪蹄"),
                    pt("PT-B", "好食光椒麻猪蹄", "椒麻猪蹄")));

            Map<String, String> fam = resolver.resolveFamilies(FACTORY_ID, List.of("PT-A", "PT-B"));

            assertThat(fam.get("PT-A")).isEqualTo("RM:RM-ZHUTI");
            assertThat(fam.get("PT-B")).isEqualTo("RM:RM-ZHUTI");
            assertThat(fam.get("PT-A")).isEqualTo(fam.get("PT-B"));
        }

        @Test
        @DisplayName("牛肉成品 → 不同族键 (猪蹄计划不显牛肉)")
        void niurou_differentFamily() {
            mockBom("PT-ZHUTI", "R-Z",
                    item(1L, "RM-ZHUTI", "RAW", new BigDecimal("300"), 1));
            mockBom("PT-NIUROU", "R-N",
                    item(2L, "RM-NIUROU", "RAW", new BigDecimal("300"), 1));
            when(productTypeRepo.findByIdIn(anyCollection())).thenReturn(List.of(
                    pt("PT-ZHUTI", "卤猪蹄", "卤猪蹄"),
                    pt("PT-NIUROU", "卤牛肉", "卤牛肉")));

            Map<String, String> fam = resolver.resolveFamilies(FACTORY_ID, List.of("PT-ZHUTI", "PT-NIUROU"));

            assertThat(fam.get("PT-ZHUTI")).isEqualTo("RM:RM-ZHUTI");
            assertThat(fam.get("PT-NIUROU")).isEqualTo("RM:RM-NIUROU");
            assertThat(fam.get("PT-ZHUTI")).isNotEqualTo(fam.get("PT-NIUROU"));
        }

        @Test
        @DisplayName("主料 = 用量最大的 RAW 项 (忽略 AUXILIARY/PACKAGING, 忽略较小 RAW)")
        void primary_isDominantRawByQuantity() {
            mockBom("PT-X", "R-X",
                    item(1L, "RM-SMALL", "RAW", new BigDecimal("50"), 1),   // RAW 但用量小
                    item(2L, "RM-BIG", "RAW", new BigDecimal("400"), 2),    // RAW 用量最大 → 主料
                    item(3L, "RM-PACK", "PACKAGING", new BigDecimal("999"), 3)); // 包材忽略
            when(productTypeRepo.findByIdIn(anyCollection())).thenReturn(List.of(pt("PT-X", "X", "X")));

            assertThat(resolver.resolveFamily(FACTORY_ID, "PT-X")).isEqualTo("RM:RM-BIG");
        }

        @Test
        @DisplayName("BOM 存在但无 RAW 明细 → 落名称字典兜底")
        void bomWithoutRaw_fallsBackToName() {
            mockBom("PT-Y", "R-Y",
                    item(1L, "RM-SALT", "AUXILIARY", new BigDecimal("5"), 1));
            when(productTypeRepo.findByIdIn(anyCollection())).thenReturn(List.of(pt("PT-Y", "卤猪蹄", "卤猪蹄")));
            when(rawMaterialTypeRepo.findByFactoryIdAndCategory(FACTORY_ID, "原料"))
                    .thenReturn(List.of(raw("RM-ZHUTI", "猪蹄")));

            assertThat(resolver.resolveFamily(FACTORY_ID, "PT-Y")).isEqualTo("RM:RM-ZHUTI");
        }
    }

    // ==================== 名称字典 兜底信号 ====================

    @Nested
    @DisplayName("名称字典 (以原料为名) — 兜底信号 (无 BOM 的导入产品)")
    class NameDictionary {

        @Test
        @DisplayName("无 BOM → 产品名匹配原料字典 (卤猪蹄 → 猪蹄)")
        void noBom_matchByName() {
            mockNoBom("PT-IMP");
            when(productTypeRepo.findByIdIn(anyCollection()))
                    .thenReturn(List.of(pt("PT-IMP", "好食光卤猪蹄200g", "好食光卤猪蹄")));
            when(rawMaterialTypeRepo.findByFactoryIdAndCategory(FACTORY_ID, "原料"))
                    .thenReturn(List.of(raw("RM-ZHUTI", "猪蹄"), raw("RM-NIUROU", "牛肉")));

            assertThat(resolver.resolveFamily(FACTORY_ID, "PT-IMP")).isEqualTo("RM:RM-ZHUTI");
        }

        @Test
        @DisplayName("最长匹配优先 (牛肉 胜 牛)")
        void longestMatchWins() {
            mockNoBom("PT-N");
            when(productTypeRepo.findByIdIn(anyCollection()))
                    .thenReturn(List.of(pt("PT-N", "酱牛肉", "酱牛肉")));
            when(rawMaterialTypeRepo.findByFactoryIdAndCategory(FACTORY_ID, "原料"))
                    .thenReturn(List.of(raw("RM-NIU", "牛"), raw("RM-NIUROU", "牛肉")));

            assertThat(resolver.resolveFamily(FACTORY_ID, "PT-N")).isEqualTo("RM:RM-NIUROU");
        }

        @Test
        @DisplayName("无 baseProductName → 回退 name 匹配")
        void fallsBackToNameField() {
            mockNoBom("PT-Z");
            ProductType p = pt("PT-Z", "卤牛肉", null); // baseProductName null
            when(productTypeRepo.findByIdIn(anyCollection())).thenReturn(List.of(p));
            when(rawMaterialTypeRepo.findByFactoryIdAndCategory(FACTORY_ID, "原料"))
                    .thenReturn(List.of(raw("RM-NIUROU", "牛肉")));

            assertThat(resolver.resolveFamily(FACTORY_ID, "PT-Z")).isEqualTo("RM:RM-NIUROU");
        }

        @Test
        @DisplayName("BOM主料 与 名称字典 落同一 id 空间 → 兄弟(一走BOM一走名称)仍同族")
        void bomAndName_sameIdSpace_stillMatch() {
            // PT-BOM 走 BOM主料(RM-ZHUTI); PT-NAME 无 BOM 走名称匹配 → 同一 RM-ZHUTI。
            mockBom("PT-BOM", "R-BOM",
                    item(1L, "RM-ZHUTI", "RAW", new BigDecimal("300"), 1));
            mockNoBom("PT-NAME");
            when(productTypeRepo.findByIdIn(anyCollection())).thenReturn(List.of(
                    pt("PT-BOM", "卤猪蹄", "卤猪蹄"),
                    pt("PT-NAME", "椒麻猪蹄", "椒麻猪蹄")));
            when(rawMaterialTypeRepo.findByFactoryIdAndCategory(FACTORY_ID, "原料"))
                    .thenReturn(List.of(raw("RM-ZHUTI", "猪蹄")));

            Map<String, String> fam = resolver.resolveFamilies(FACTORY_ID, List.of("PT-BOM", "PT-NAME"));

            assertThat(fam.get("PT-BOM")).isEqualTo("RM:RM-ZHUTI");
            assertThat(fam.get("PT-NAME")).isEqualTo("RM:RM-ZHUTI");
            assertThat(fam.get("PT-BOM")).isEqualTo(fam.get("PT-NAME"));
        }
    }

    // ==================== 识别不出 + 边界 ====================

    @Nested
    @DisplayName("识别不出族 + 边界")
    class Unresolvable {

        @Test
        @DisplayName("无 BOM 且名称无匹配 → 族键缺失 (map 无此 key, 宽松放行)")
        void noBomNoNameMatch_absentFromMap() {
            mockNoBom("PT-UNK");
            when(productTypeRepo.findByIdIn(anyCollection()))
                    .thenReturn(List.of(pt("PT-UNK", "神秘产品", "神秘产品")));
            when(rawMaterialTypeRepo.findByFactoryIdAndCategory(FACTORY_ID, "原料"))
                    .thenReturn(List.of(raw("RM-ZHUTI", "猪蹄")));

            Map<String, String> fam = resolver.resolveFamilies(FACTORY_ID, List.of("PT-UNK"));

            assertThat(fam).doesNotContainKey("PT-UNK");
            assertThat(resolver.resolveFamily(FACTORY_ID, "PT-UNK")).isNull();
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
        }

        @Test
        @DisplayName("全走 BOM主料 → 不加载原料字典 (名称字典延迟加载)")
        void allBom_noRawDictLoad() {
            mockBom("PT-A", "R-A", item(1L, "RM-ZHUTI", "RAW", new BigDecimal("300"), 1));
            when(productTypeRepo.findByIdIn(anyCollection())).thenReturn(List.of(pt("PT-A", "卤猪蹄", "卤猪蹄")));

            resolver.resolveFamilies(FACTORY_ID, List.of("PT-A"));

            verify(rawMaterialTypeRepo, never()).findByFactoryIdAndCategory(any(), any());
        }

        @Test
        @DisplayName("存量产品批量识别 = backfill 等价物 (就地识别覆盖现有 product_types, 零手填)")
        void backfillEquivalent_batchOverExisting() {
            // 现有 3 个产品 (2 猪蹄兄弟 + 1 牛肉), 无任何手填族字段 → 就地全识别。
            mockBom("PT-A", "R-A", item(1L, "RM-ZHUTI", "RAW", new BigDecimal("300"), 1));
            mockBom("PT-B", "R-B", item(2L, "RM-ZHUTI", "RAW", new BigDecimal("290"), 1));
            mockBom("PT-C", "R-C", item(3L, "RM-NIUROU", "RAW", new BigDecimal("300"), 1));
            when(productTypeRepo.findByIdIn(anyCollection())).thenReturn(List.of(
                    pt("PT-A", "卤猪蹄", "卤猪蹄"),
                    pt("PT-B", "椒麻猪蹄", "椒麻猪蹄"),
                    pt("PT-C", "卤牛肉", "卤牛肉")));

            Map<String, String> fam = resolver.resolveFamilies(
                    FACTORY_ID, Set.of("PT-A", "PT-B", "PT-C"));

            assertThat(fam).hasSize(3);
            assertThat(fam.get("PT-A")).isEqualTo(fam.get("PT-B"));            // 兄弟同族
            assertThat(fam.get("PT-C")).isNotEqualTo(fam.get("PT-A"));         // 牛肉异族
        }
    }
}
