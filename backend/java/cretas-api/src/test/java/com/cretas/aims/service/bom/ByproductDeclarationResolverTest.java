package com.cretas.aims.service.bom;

import com.cretas.aims.entity.RawMaterialType;
import com.cretas.aims.entity.bom.BomRecipeItem;
import com.cretas.aims.repository.RawMaterialTypeRepository;
import com.cretas.aims.repository.bom.BomRecipeItemRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

/**
 * 副产声明两个来源的收敛优先级。
 *
 * <p>线上并存两处: {@code work_processes.expected_byproducts}(自由文本 name, 每道工序, 4 条,
 * 其中 2 条在真实工厂 F006) 与 BOM 配方内容第四类(原料字典 SKU, 每个 BOM 版本, 2026-07-31 上线)。
 * 没有优先级规则的话两边迟早各说各话且不报错 —— 本仓 07-31 一天连修五处
 * 「同一件事多套实现」, 这条规则就是不让它变成第六处。</p>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ByproductDeclarationResolverTest {

    private static final String FACTORY = "F006";
    private static final String PRODUCT = "PT-ZHUSHE";

    @Mock private BomRecipeItemRepository bomRecipeItemRepository;
    @Mock private RawMaterialTypeRepository rawMaterialTypeRepository;

    @InjectMocks private ByproductDeclarationResolver resolver;

    @Test
    void bomDeclarationWinsOverLegacyProcessDeclaration() {
        when(bomRecipeItemRepository.findCurrentByProduct(FACTORY, PRODUCT))
                .thenReturn(List.of(rawItem(), byproductItem("MT-FEIYOU", "kg", "36")));
        when(rawMaterialTypeRepository.findById("MT-FEIYOU"))
                .thenReturn(Optional.of(material("肥油")));

        List<Map<String, Object>> result = resolver.resolve(FACTORY, PRODUCT, legacy("舌苔碎肉", "kg"));

        assertThat(result).hasSize(1); // 只取副产行, 原料行不混进来
        Map<String, Object> row = result.get(0);
        assertThat(row.get("name")).isEqualTo("肥油");
        assertThat(row.get("materialTypeId")).isEqualTo("MT-FEIYOU");
        assertThat(row.get("expectedQuantity")).isEqualTo(new BigDecimal("36"));
        assertThat(row.get("source")).isEqualTo(ByproductDeclarationResolver.SOURCE_BOM);
    }

    /**
     * 🔴 刻意<b>不合并</b>两边: 合并会产生「一半有 SKU 一半没有」的行, 下游没法判断哪些能落
     * material_batches。要么整份走 BOM, 要么整份走历史声明。
     */
    @Test
    void bomDeclarationDoesNotGetMergedWithLegacyOnes() {
        when(bomRecipeItemRepository.findCurrentByProduct(FACTORY, PRODUCT))
                .thenReturn(List.of(byproductItem("MT-FEIYOU", "kg", "36")));
        when(rawMaterialTypeRepository.findById("MT-FEIYOU"))
                .thenReturn(Optional.of(material("肥油")));

        List<Map<String, Object>> result = resolver.resolve(FACTORY, PRODUCT, legacy("舌苔碎肉", "kg"));

        assertThat(result).extracting(row -> row.get("name")).containsExactly("肥油");
        assertThat(result).extracting(row -> row.get("name")).doesNotContain("舌苔碎肉");
    }

    /** 那 4 条历史声明不能因为新机制上线就失效 —— 该产品没有 BOM 副产行时照旧回落。 */
    @Test
    void legacyDeclarationSurvivesWhenProductHasNoBomByproductRow() {
        when(bomRecipeItemRepository.findCurrentByProduct(FACTORY, PRODUCT))
                .thenReturn(List.of(rawItem())); // 有 BOM 但没有副产行

        List<Map<String, Object>> result = resolver.resolve(FACTORY, PRODUCT, legacy("舌苔碎肉", "kg"));

        assertThat(result).hasSize(1);
        assertThat(result.get(0).get("name")).isEqualTo("舌苔碎肉");
        assertThat(result.get(0).get("source"))
                .isEqualTo(ByproductDeclarationResolver.SOURCE_LEGACY_PROCESS);
        // 历史声明没有 SKU —— 如实标 null, 下游据此知道这份不能直接落库
        assertThat(result.get(0)).containsEntry("materialTypeId", null);
        assertThat(result.get(0)).containsEntry("expectedQuantity", null);
    }

    @Test
    void bothEmptyYieldsEmptyListNotNull() {
        when(bomRecipeItemRepository.findCurrentByProduct(FACTORY, PRODUCT)).thenReturn(List.of());

        assertThat(resolver.resolve(FACTORY, PRODUCT, null)).isEmpty();
        assertThat(resolver.resolve(FACTORY, PRODUCT, List.of())).isEmpty();
    }

    /** 没有 productTypeId 时无从查 BOM(如免工序报工哨兵任务), 回落历史声明而不是报错。 */
    @Test
    void missingProductTypeFallsBackInsteadOfFailing() {
        assertThat(resolver.resolve(FACTORY, null, legacy("肥油", "kg")))
                .singleElement()
                .extracting(row -> row.get("source"))
                .isEqualTo(ByproductDeclarationResolver.SOURCE_LEGACY_PROCESS);
    }

    /** SKU 档案查不到名字时留 null —— 不拿 materialTypeId 冒充名称(禁降级)。 */
    @Test
    void missingSkuNameStaysNullInsteadOfBorrowingTheId() {
        when(bomRecipeItemRepository.findCurrentByProduct(FACTORY, PRODUCT))
                .thenReturn(List.of(byproductItem("MT-GONE", "kg", "5")));
        when(rawMaterialTypeRepository.findById("MT-GONE")).thenReturn(Optional.empty());

        Map<String, Object> row = resolver.resolve(FACTORY, PRODUCT, List.of()).get(0);

        assertThat(row).containsEntry("name", null);
        assertThat(row.get("materialTypeId")).isEqualTo("MT-GONE");
    }

    // ---------- helpers ----------

    private List<Map<String, Object>> legacy(String name, String unit) {
        Map<String, Object> declaration = new LinkedHashMap<>();
        declaration.put("name", name);
        declaration.put("unit", unit);
        declaration.put("defaultEnabled", true);
        return List.of(declaration);
    }

    private BomRecipeItem byproductItem(String materialTypeId, String unit, String qty) {
        BomRecipeItem item = new BomRecipeItem();
        item.setMaterialCategory(BomRecipeItem.CATEGORY_BYPRODUCT);
        item.setMaterialTypeId(materialTypeId);
        item.setUnit(unit);
        item.setStandardQuantity(new BigDecimal(qty));
        return item;
    }

    private BomRecipeItem rawItem() {
        BomRecipeItem item = new BomRecipeItem();
        item.setMaterialCategory("RAW");
        item.setMaterialTypeId("MT-RAW");
        item.setUnit("kg");
        return item;
    }

    private RawMaterialType material(String name) {
        RawMaterialType type = new RawMaterialType();
        type.setName(name);
        return type;
    }
}
