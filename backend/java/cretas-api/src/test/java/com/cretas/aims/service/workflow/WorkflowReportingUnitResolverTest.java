package com.cretas.aims.service.workflow;

import com.cretas.aims.entity.ProductType;
import com.cretas.aims.entity.RawMaterialType;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.repository.ProductTypeRepository;
import com.cretas.aims.repository.RawMaterialTypeRepository;
import com.cretas.aims.service.unit.CanonicalUnit;
import com.cretas.aims.service.unit.UnitContractService;
import com.cretas.aims.service.unit.UnitDimension;
import com.cretas.aims.service.unit.UnitNormalizationResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class WorkflowReportingUnitResolverTest {

    private final ProductTypeRepository productTypeRepository = mock(ProductTypeRepository.class);
    private final RawMaterialTypeRepository rawMaterialTypeRepository =
            mock(RawMaterialTypeRepository.class);
    private final UnitContractService unitContractService = mock(UnitContractService.class);
    private WorkflowReportingUnitResolver resolver;

    private static UnitDimension dimensionOf(String code) {
        return switch (code) {
            case "kg", "g", "千克", "公斤" -> UnitDimension.MASS;
            case "只", "个" -> UnitDimension.COUNT;
            default -> UnitDimension.PACKAGE;
        };
    }

    @BeforeEach
    void setUp() {
        when(unitContractService.normalize(anyString(), anyString())).thenAnswer(invocation -> {
            String raw = invocation.getArgument(1);
            // 契约的真实行为: 只/个/件 同归 pcs; 千克/公斤 同归 kg
            String code = switch (raw.trim()) {
                case "只", "个", "件", "pcs" -> "pcs";
                case "千克", "公斤", "kg" -> "kg";
                default -> raw;
            };
            return new UnitNormalizationResult(raw, code, mock(CanonicalUnit.class));
        });
        when(unitContractService.describe(anyString(), anyString())).thenAnswer(invocation -> {
            String code = invocation.getArgument(1);
            String canonical = switch (code) {
                case "千克", "公斤" -> "kg";
                case "克" -> "g";
                default -> code;
            };
            return Optional.of(new CanonicalUnit(
                    canonical, dimensionOf(code), canonical, java.math.BigDecimal.ONE, canonical, 3));
        });
        resolver = new WorkflowReportingUnitResolver(
                productTypeRepository, rawMaterialTypeRepository, unitContractService);
    }

    private void rawMaterial(String id, String unit) {
        RawMaterialType material = new RawMaterialType();
        material.setId(id);
        material.setFactoryId("F006");
        material.setUnit(unit);
        when(rawMaterialTypeRepository.findByIdAndFactoryId(id, "F006"))
                .thenReturn(Optional.of(material));
    }

    private void semiFinished(String id, String unit) {
        ProductType product = new ProductType();
        product.setId(id);
        product.setFactoryId("F006");
        product.setUnit(unit);
        when(productTypeRepository.findByIdAndFactoryId(id, "F006"))
                .thenReturn(Optional.of(product));
    }

    @Test
    void massMastersStillCollapseToKgSoTheChainNeverMixesGramsAndKilos() {
        rawMaterial("RM-1", "g");
        semiFinished("PT-SEMI", "千克");

        assertEquals("kg", resolver.resolve("F006", "RAW_MATERIAL", "RM-1", "g"));
        assertEquals("kg", resolver.resolve("F006", "SEMI_FINISHED", "PT-SEMI", "g"));
    }

    @Test
    void countedMastersKeepTheirOwnUnitInsteadOfBeingRewrittenToKg() {
        // 整鸡按只计 —— 强写 kg 会把端口和物料主单位同时改掉,
        // 连换算都不会记录(两边都是 kg、系数 1.0), 报工页于是显示 kg。
        rawMaterial("RM-CHICKEN", "只");
        semiFinished("PT-WIP", "只");

        assertEquals("只", resolver.resolve("F006", "RAW_MATERIAL", "RM-CHICKEN", "只"));
        assertEquals("只", resolver.resolve("F006", "SEMI_FINISHED", "PT-WIP", "只"));
        // 等价码只对有真实换算系数的科学单位成立。只/件 之间没有普适换算 ——
        // 一只不等于一件, 硬编一个共同等价码等于让系统替工厂断定两个不同的东西相同,
        // 工厂新建单位时也无从判断该挂进哪个族。非科学单位的等价码就是它自己。
        assertEquals("只", resolver.canonicalCode("F006", "只"));
        assertEquals("件", resolver.canonicalCode("F006", "件"));
        // 科学单位照常归一: 千克/公斤 都是 kg
        assertEquals("kg", resolver.canonicalCode("F006", "千克"));
        // 契约认不出的写法折大小写后再比 —— Box 与 box 是同一个单位, 不能算两样
        assertEquals("箱子", resolver.canonicalCode("F006", "箱子"));
        assertEquals("crate", resolver.canonicalCode("F006", "CRATE"));
    }

    @Test
    void unknownRawMaterialFallsBackToTheDeclaredPortUnitNotKg() {
        when(rawMaterialTypeRepository.findByIdAndFactoryId("RM-GHOST", "F006"))
                .thenReturn(Optional.empty());

        assertEquals("只", resolver.resolve("F006", "RAW_MATERIAL", "RM-GHOST", "只"));
    }

    @Test
    void finishedGoodsReportingInheritsTheSkuBaseUnit() {
        ProductType finished = new ProductType();
        finished.setId("PT-FG");
        finished.setFactoryId("F006");
        finished.setUnit("盒");
        when(productTypeRepository.findByIdAndFactoryId("PT-FG", "F006"))
                .thenReturn(Optional.of(finished));

        assertEquals("盒", resolver.resolve("F006", "FINISHED_GOOD", "PT-FG", "g"));
    }

    @Test
    void missingFinishedSkuFailsClosedInsteadOfGuessingFromLegacyPort() {
        when(productTypeRepository.findByIdAndFactoryId("PT-MISSING", "F006"))
                .thenReturn(Optional.empty());

        BusinessException error = assertThrows(BusinessException.class,
                () -> resolver.resolve("F006", "FINISHED_GOOD", "PT-MISSING", "g"));

        assertEquals("WORKFLOW_REPORTING_UNIT_UNRESOLVED", error.getErrorCode());
    }
}
