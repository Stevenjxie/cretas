package com.cretas.aims.service.impl;

import com.cretas.aims.dto.bom.BomCostSummaryDTO;
import com.cretas.aims.entity.RawMaterialType;
import com.cretas.aims.entity.bom.BomItem;
import com.cretas.aims.repository.RawMaterialTypeRepository;
import com.cretas.aims.repository.bom.BomItemRepository;
import com.cretas.aims.repository.bom.LaborCostConfigRepository;
import com.cretas.aims.repository.bom.OverheadCostConfigRepository;
import com.cretas.aims.service.UnitConversionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

/**
 * R13 (2026-06-22): BOM 成本计算的计量单位口径对齐.
 *
 * <p>核心红线: BOM 行 unitPrice auto-fill 自物料主数据 (按主数据单位计价, 通常 kg),
 * 但行用量可能按"斤"录入. 计价用量须先折算到主数据单位 (斤→kg), 否则 斤量×kg价 失真.
 *
 * <p>这些 case 用 {@link MockitoExtension} (单元级, 不加载 DB), 经反射注入
 * {@code unitConversionService} + {@code rawMaterialTypeRepository} 两个
 * {@code @Autowired(required=false)} 可选依赖.
 */
@DisplayName("R13 BOM 成本计量单位口径对齐")
@ExtendWith(MockitoExtension.class)
class BomServiceImplUomCostReconciliationTest {

    @Mock private BomItemRepository bomItemRepository;
    @Mock private LaborCostConfigRepository laborCostConfigRepository;
    @Mock private OverheadCostConfigRepository overheadCostConfigRepository;
    @Mock private RawMaterialTypeRepository rawMaterialTypeRepository;

    private BomServiceImpl service;

    private static final String F = "F006";

    @BeforeEach
    void setUp() {
        service = new BomServiceImpl(bomItemRepository, laborCostConfigRepository, overheadCostConfigRepository);
        // 注入两个可选依赖 (真实 UnitConversionService, mock repository)
        ReflectionTestUtils.setField(service, "unitConversionService", new UnitConversionService());
        ReflectionTestUtils.setField(service, "rawMaterialTypeRepository", rawMaterialTypeRepository);

        // 无人工/均摊 (聚焦原料成本)
        lenient().when(laborCostConfigRepository
                .findByFactoryIdAndProductTypeIdAndIsActiveTrueAndDeletedAtIsNullOrderBySortOrderAsc(anyString(), anyString()))
                .thenReturn(List.of());
        lenient().when(laborCostConfigRepository
                .findByFactoryIdAndProductTypeIdIsNullAndDeletedAtIsNullOrderBySortOrderAsc(anyString()))
                .thenReturn(List.of());
        lenient().when(overheadCostConfigRepository
                .findByFactoryIdAndIsActiveTrueAndDeletedAtIsNullOrderBySortOrderAsc(anyString()))
                .thenReturn(List.of());
    }

    private RawMaterialType material(String id, String unit) {
        RawMaterialType m = new RawMaterialType();
        m.setId(id);
        m.setUnit(unit);
        return m;
    }

    private BomItem item(String productTypeId, String materialTypeId, String qty, String unit, String price) {
        return BomItem.builder()
                .factoryId(F)
                .productTypeId(productTypeId)
                .materialTypeId(materialTypeId)
                .materialName(materialTypeId)
                .standardQuantity(new BigDecimal(qty))
                .yieldRate(new BigDecimal("100.00"))  // actualQuantity == standardQuantity
                .unit(unit)
                .unitPrice(new BigDecimal(price))
                .taxRate(new BigDecimal("13.00"))
                .build();
    }

    @Test
    @DisplayName("斤行 + kg主数据价: 100斤×¥10/kg 须按 50kg 计 = ¥500 (不是裸 ¥1000)")
    void jinLine_kgMasterPrice_normalizedToKg() {
        String p = "P-JIN";
        // 100 斤, 主数据按 kg 计价 ¥10/kg → 50kg × 10 = 500
        BomItem it = item(p, "RM-BEEF", "100", "斤", "10");
        when(bomItemRepository.findByFactoryIdAndProductTypeIdAndDeletedAtIsNullOrderBySortOrderAsc(F, p))
                .thenReturn(List.of(it));
        when(rawMaterialTypeRepository.findById("RM-BEEF"))
                .thenReturn(Optional.of(material("RM-BEEF", "kg")));

        BomCostSummaryDTO result = service.calculateProductCost(F, p);

        assertThat(result.getMaterialCostTotal()).isEqualByComparingTo("500");
        assertThat(result.getMaterialCosts()).singleElement().satisfies(ci -> {
            // 展示仍保留行原始单位/单价 (不改存储语义), 仅 subtotal 用归一后的量
            assertThat(ci.getUnit()).isEqualTo("斤");
            assertThat(ci.getUnitPrice()).isEqualByComparingTo("10");
            assertThat(ci.getSubtotal()).isEqualByComparingTo("500");
        });
    }

    @Test
    @DisplayName("kg行 + kg主数据: 无归一, 10kg×¥10 = ¥100 (回归, 旧行为不变)")
    void kgLine_kgMaster_unchanged() {
        String p = "P-KG";
        BomItem it = item(p, "RM-KG", "10", "kg", "10");
        when(bomItemRepository.findByFactoryIdAndProductTypeIdAndDeletedAtIsNullOrderBySortOrderAsc(F, p))
                .thenReturn(List.of(it));
        when(rawMaterialTypeRepository.findById("RM-KG"))
                .thenReturn(Optional.of(material("RM-KG", "kg")));

        BomCostSummaryDTO result = service.calculateProductCost(F, p);

        assertThat(result.getMaterialCostTotal()).isEqualByComparingTo("100");
    }

    @Test
    @DisplayName("个行 + 个主数据 (非重量): 不归一, 5个×¥3 = ¥15 (绝不当 kg)")
    void countLine_notNormalized() {
        String p = "P-COUNT";
        BomItem it = item(p, "RM-BOX", "5", "个", "3");
        when(bomItemRepository.findByFactoryIdAndProductTypeIdAndDeletedAtIsNullOrderBySortOrderAsc(F, p))
                .thenReturn(List.of(it));
        // 个 非重量单位, reconcile 提前 return, 不会查 repository — lenient 避免 unnecessary stub
        lenient().when(rawMaterialTypeRepository.findById("RM-BOX"))
                .thenReturn(Optional.of(material("RM-BOX", "个")));

        BomCostSummaryDTO result = service.calculateProductCost(F, p);

        assertThat(result.getMaterialCostTotal()).isEqualByComparingTo("15");
    }

    @Test
    @DisplayName("g行 + kg主数据价: 1500g×¥2/kg = 1.5kg×2 = ¥3 (混重量单位)")
    void gramLine_kgMasterPrice_normalized() {
        String p = "P-GRAM";
        BomItem it = item(p, "RM-SALT", "1500", "g", "2");
        when(bomItemRepository.findByFactoryIdAndProductTypeIdAndDeletedAtIsNullOrderBySortOrderAsc(F, p))
                .thenReturn(List.of(it));
        when(rawMaterialTypeRepository.findById("RM-SALT"))
                .thenReturn(Optional.of(material("RM-SALT", "kg")));

        BomCostSummaryDTO result = service.calculateProductCost(F, p);

        assertThat(result.getMaterialCostTotal()).isEqualByComparingTo("3");
    }

    @Test
    @DisplayName("混单位多行: 斤行(归一) + kg行(不变) 累加正确")
    void mixedUnits_multipleLines() {
        String p = "P-MIX";
        // 斤行: 100斤 × ¥10/kg = 50kg × 10 = 500
        BomItem jin = item(p, "RM-BEEF", "100", "斤", "10");
        // kg行: 2kg × ¥20/kg = 40
        BomItem kg = item(p, "RM-OIL", "2", "kg", "20");
        when(bomItemRepository.findByFactoryIdAndProductTypeIdAndDeletedAtIsNullOrderBySortOrderAsc(F, p))
                .thenReturn(List.of(jin, kg));
        when(rawMaterialTypeRepository.findById("RM-BEEF"))
                .thenReturn(Optional.of(material("RM-BEEF", "kg")));
        when(rawMaterialTypeRepository.findById("RM-OIL"))
                .thenReturn(Optional.of(material("RM-OIL", "kg")));

        BomCostSummaryDTO result = service.calculateProductCost(F, p);

        assertThat(result.getMaterialCostTotal()).isEqualByComparingTo("540");  // 500 + 40
    }

    @Test
    @DisplayName("斤行 + 斤主数据价 (主数据也按斤计价): 无归一, 100斤×¥5/斤 = ¥500 口径自洽")
    void jinLine_jinMaster_unchanged() {
        String p = "P-JINJIN";
        BomItem it = item(p, "RM-JIN", "100", "斤", "5");
        when(bomItemRepository.findByFactoryIdAndProductTypeIdAndDeletedAtIsNullOrderBySortOrderAsc(F, p))
                .thenReturn(List.of(it));
        when(rawMaterialTypeRepository.findById("RM-JIN"))
                .thenReturn(Optional.of(material("RM-JIN", "斤")));

        BomCostSummaryDTO result = service.calculateProductCost(F, p);

        // 同单位 (斤==斤) convert 透传 → 100 × 5 = 500
        assertThat(result.getMaterialCostTotal()).isEqualByComparingTo("500");
    }

    @Test
    @DisplayName("缺主数据: 斤行但 material 不存在 → 不归一 fail-open, 100斤×¥10 = ¥1000 (诚实不猜)")
    void missingMaster_failOpen() {
        String p = "P-NOMAT";
        BomItem it = item(p, "RM-GONE", "100", "斤", "10");
        when(bomItemRepository.findByFactoryIdAndProductTypeIdAndDeletedAtIsNullOrderBySortOrderAsc(F, p))
                .thenReturn(List.of(it));
        when(rawMaterialTypeRepository.findById("RM-GONE")).thenReturn(Optional.empty());

        BomCostSummaryDTO result = service.calculateProductCost(F, p);

        assertThat(result.getMaterialCostTotal()).isEqualByComparingTo("1000");
    }
}
