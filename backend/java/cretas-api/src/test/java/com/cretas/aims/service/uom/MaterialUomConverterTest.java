package com.cretas.aims.service.uom;

import com.cretas.aims.entity.MaterialPackagingHierarchy;
import com.cretas.aims.entity.RawMaterialType;
import com.cretas.aims.repository.MaterialPackagingHierarchyRepository;
import com.cretas.aims.repository.RawMaterialTypeRepository;
import com.cretas.aims.service.UnitConversionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * T143 — MaterialUomConverter 单元测试.
 *
 * <p>覆盖 5 个核心分支:
 * <ul>
 *   <li>同单位透传 (无换算)</li>
 *   <li>同维度 g↔kg (via UnitConversionService)</li>
 *   <li>跨 箱→kg via level1PerLevel2 (装箱规格)</li>
 *   <li>抄码料 → ABACA_SKIP (绝不阻断)</li>
 *   <li>无装箱规格 → UNCONVERTIBLE (绝不静默 default 成 1.0)</li>
 * </ul>
 */
@DisplayName("T143: MaterialUomConverter — 箱↔kg + 抄码 + fail-loud")
class MaterialUomConverterTest {

    private MaterialPackagingHierarchyRepository packagingRepo;
    private RawMaterialTypeRepository materialRepo;
    private MaterialUomConverter converter;

    private static final String MAT_ZHUSHE = "MT-PORK-TONGUE-001";   // 冷冻猪舌
    private static final String MAT_BEEF = "MT-BEEF-001";            // 牛肉 (抄码)

    @BeforeEach
    void setUp() {
        packagingRepo = mock(MaterialPackagingHierarchyRepository.class);
        materialRepo = mock(RawMaterialTypeRepository.class);
        // UnitConversionService 无状态, 用真实实例 (验证真实 g↔kg 算术)
        converter = new MaterialUomConverter(packagingRepo, materialRepo,
                com.cretas.aims.service.unit.TestUnitContractFactory.legacyFacade());

        // 默认: 物料查不到 (各 case 覆写)
        when(materialRepo.findById(anyString())).thenReturn(Optional.empty());
        when(packagingRepo.findByMaterialTypeId(anyString())).thenReturn(Optional.empty());
    }

    @Test
    @DisplayName("同单位透传: 箱→箱 不换算")
    void sameUnit_passthrough() {
        MaterialUomConverter.ConversionResult r =
                converter.toComparableQuantity(MAT_ZHUSHE, new BigDecimal("200"), "箱", "箱");
        assertThat(r.isConverted()).isTrue();
        assertThat(r.getQuantity()).isEqualByComparingTo("200");
        assertThat(r.getUnit()).isEqualTo("箱");
    }

    @Test
    @DisplayName("同维度: 2000g → 2kg (via UnitConversionService)")
    void sameDimension_gToKg() {
        MaterialUomConverter.ConversionResult r =
                converter.toComparableQuantity(MAT_ZHUSHE, new BigDecimal("2000"), "g", "kg");
        assertThat(r.isConverted()).isTrue();
        assertThat(r.getQuantity()).isEqualByComparingTo("2");
    }

    @Test
    @DisplayName("跨 箱→kg: 库存单位 kg, BOM 单位 箱, level1PerLevel2=10 → 5箱=50kg")
    void crossDimension_boxToKg_viaHierarchy() {
        // hierarchy: level1=kg, level2=箱, 10 kg/箱
        when(packagingRepo.findByMaterialTypeId(MAT_ZHUSHE))
                .thenReturn(Optional.of(hierarchy("kg", new BigDecimal("10"), "箱")));

        // from=箱, stock=kg → 5箱 × 10 = 50kg
        MaterialUomConverter.ConversionResult r =
                converter.toComparableQuantity(MAT_ZHUSHE, new BigDecimal("5"), "箱", "kg");
        assertThat(r.isConverted()).isTrue();
        assertThat(r.getQuantity()).isEqualByComparingTo("50");
    }

    @Test
    @DisplayName("跨 g→箱: BOM 需求 217000g, level1PerLevel2=10(kg/箱) → 217g→0.217kg... 21700g=2.17箱")
    void crossDimension_gToBox_viaHierarchy() {
        // hierarchy: level1=kg, level2=箱, 10 kg/箱
        when(packagingRepo.findByMaterialTypeId(MAT_ZHUSHE))
                .thenReturn(Optional.of(hierarchy("kg", new BigDecimal("10"), "箱")));

        // from=g, stock=箱: 50000g → 50kg → ÷10 = 5箱
        MaterialUomConverter.ConversionResult r =
                converter.toComparableQuantity(MAT_ZHUSHE, new BigDecimal("50000"), "g", "箱");
        assertThat(r.isConverted()).isTrue();
        assertThat(r.getQuantity()).isEqualByComparingTo("5");
    }

    @Test
    @DisplayName("抄码料 → ABACA_SKIP (绝不阻断), 即便单位不同")
    void abaca_returnsSkip() {
        RawMaterialType beef = new RawMaterialType();
        beef.setName("牛肉");
        beef.setUnit("箱");
        beef.setIsAbacaPackaging(true);
        when(materialRepo.findById(MAT_BEEF)).thenReturn(Optional.of(beef));

        MaterialUomConverter.ConversionResult r =
                converter.toComparableQuantity(MAT_BEEF, new BigDecimal("100000"), "g", "箱");
        assertThat(r.isAbacaSkip()).isTrue();
        assertThat(r.getMaterialName()).isEqualTo("牛肉");
    }

    @Test
    @DisplayName("无装箱规格 (非抄码, 单位不同) → UNCONVERTIBLE (绝不静默 default 1.0)")
    void missingHierarchy_returnsUnconvertible() {
        RawMaterialType m = new RawMaterialType();
        m.setName("冷冻猪舌");
        m.setUnit("箱");
        m.setIsAbacaPackaging(false);
        when(materialRepo.findById(MAT_ZHUSHE)).thenReturn(Optional.of(m));
        // packagingRepo 默认返 empty → 无装箱规格

        MaterialUomConverter.ConversionResult r =
                converter.toComparableQuantity(MAT_ZHUSHE, new BigDecimal("217"), "g", "箱");
        assertThat(r.isUnconvertible()).isTrue();
        assertThat(r.getQuantity()).isNull();   // 绝不静默 default 成数值
        assertThat(r.getReason()).contains("冷冻猪舌");
    }

    // ================================================================
    // T156 — 单位别名归一化 (pcs ≡ 个 ≡ 件): 等同单位不再误判 UNCONVERTIBLE.
    // 背景: 气调盒 BOM 单位 pcs, 库存单位 个, 本是同一计数单位, 旧 normalize
    // 只 trim+lowercase → pcs ≠ 个 → 误报 409 阻断开工.
    // ================================================================

    @Test
    @DisplayName("T156: pcs vs 个 → CONVERTED 1:1 (气调盒, 不再误判 UNCONVERTIBLE)")
    void aliasPcsVsGe_converted1to1() {
        // 气调盒: BOM 单位 pcs, 库存单位 个 → 同一计数单位.
        MaterialUomConverter.ConversionResult r =
                converter.toComparableQuantity(MAT_ZHUSHE, new BigDecimal("100"), "pcs", "个");
        assertThat(r.isConverted()).isTrue();
        assertThat(r.getQuantity()).isEqualByComparingTo("100");   // 1:1, 无系数
        assertThat(r.getUnit()).isEqualTo("个");                    // 用库存单位
        assertThat(r.isUnconvertible()).isFalse();
    }

    @Test
    @DisplayName("T156: 件 vs 个 → CONVERTED 1:1 (同计数单位)")
    void aliasJianVsGe_converted1to1() {
        MaterialUomConverter.ConversionResult r =
                converter.toComparableQuantity(MAT_ZHUSHE, new BigDecimal("50"), "件", "个");
        assertThat(r.isConverted()).isTrue();
        assertThat(r.getQuantity()).isEqualByComparingTo("50");
    }

    @Test
    @DisplayName("T156: PCS / Pcs / ' pcs ' 大小写+空格不敏感 → CONVERTED 1:1")
    void aliasPcsCaseInsensitive_converted() {
        for (String bomUnit : new String[]{"PCS", "Pcs", " pcs ", "pieces", "pc"}) {
            MaterialUomConverter.ConversionResult r =
                    converter.toComparableQuantity(MAT_ZHUSHE, new BigDecimal("7"), bomUnit, "个");
            assertThat(r.isConverted())
                    .as("BOM 单位 '%s' vs 个 应可换算", bomUnit)
                    .isTrue();
            assertThat(r.getQuantity()).isEqualByComparingTo("7");
        }
    }

    @Test
    @DisplayName("T156: g vs kg 仍走系数换算 (别名不破坏 UnitConversionService g↔kg)")
    void aliasDoesNotRegress_gToKg() {
        // 2000g → 2kg, 经 UnitConversionService 维度换算 (非 1:1)
        MaterialUomConverter.ConversionResult r =
                converter.toComparableQuantity(MAT_ZHUSHE, new BigDecimal("2000"), "g", "kg");
        assertThat(r.isConverted()).isTrue();
        assertThat(r.getQuantity()).isEqualByComparingTo("2");
    }

    @Test
    @DisplayName("T156: 克 vs kg 仍正确换算 (中文重量别名归一到 g → 走 g↔kg 系数)")
    void aliasChineseWeight_keToKg() {
        // 克 → g 规范, 2000克 → 2kg
        MaterialUomConverter.ConversionResult r =
                converter.toComparableQuantity(MAT_ZHUSHE, new BigDecimal("2000"), "克", "kg");
        assertThat(r.isConverted()).isTrue();
        assertThat(r.getQuantity()).isEqualByComparingTo("2");
    }

    @Test
    @DisplayName("T156: 个 vs kg (无关维度, 无装箱规格) → 仍 UNCONVERTIBLE (别名不让其假换算)")
    void aliasGeVsKg_stillUnconvertible() {
        RawMaterialType m = new RawMaterialType();
        m.setName("气调盒");
        m.setUnit("kg");
        m.setIsAbacaPackaging(false);
        when(materialRepo.findById(MAT_ZHUSHE)).thenReturn(Optional.of(m));
        // packagingRepo 默认 empty → 无 个↔kg 装箱规格

        MaterialUomConverter.ConversionResult r =
                converter.toComparableQuantity(MAT_ZHUSHE, new BigDecimal("100"), "个", "kg");
        assertThat(r.isUnconvertible()).isTrue();   // 个 与 kg 无关, 不别名硬换
        assertThat(r.getQuantity()).isNull();
    }

    @Test
    @DisplayName("null 数量 → UNCONVERTIBLE (不静默)")
    void nullQty_unconvertible() {
        MaterialUomConverter.ConversionResult r =
                converter.toComparableQuantity(MAT_ZHUSHE, null, "g", "箱");
        assertThat(r.isUnconvertible()).isTrue();
    }

    @Test
    @DisplayName("装箱规格不完整 (level2/perBox 缺失) + 非抄码 → UNCONVERTIBLE")
    void incompleteHierarchy_unconvertible() {
        RawMaterialType m = new RawMaterialType();
        m.setName("某原料");
        m.setUnit("箱");
        m.setIsAbacaPackaging(false);
        when(materialRepo.findById(MAT_ZHUSHE)).thenReturn(Optional.of(m));
        // hierarchy 缺 level2Unit / perBox
        MaterialPackagingHierarchy h = new MaterialPackagingHierarchy();
        h.setLevel1Unit("kg");
        // level2Unit null, level1PerLevel2 null
        when(packagingRepo.findByMaterialTypeId(MAT_ZHUSHE)).thenReturn(Optional.of(h));

        MaterialUomConverter.ConversionResult r =
                converter.toComparableQuantity(MAT_ZHUSHE, new BigDecimal("217"), "g", "箱");
        assertThat(r.isUnconvertible()).isTrue();
    }

    private static MaterialPackagingHierarchy hierarchy(String level1Unit, BigDecimal perBox, String level2Unit) {
        MaterialPackagingHierarchy h = new MaterialPackagingHierarchy();
        h.setLevel1Unit(level1Unit);
        h.setLevel1PerLevel2(perBox);
        h.setLevel2Unit(level2Unit);
        return h;
    }
}
