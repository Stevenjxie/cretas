package com.cretas.aims.service.validation;

import com.cretas.aims.dto.ProductProcessWorkflowDTO;
import com.cretas.aims.entity.ProductType;
import com.cretas.aims.entity.unit.ProductUnitConversion;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.repository.ProductTypeRepository;
import com.cretas.aims.repository.RawMaterialTypeRepository;
import com.cretas.aims.repository.unit.ProductUnitConversionRepository;
import com.cretas.aims.service.unit.CanonicalUnit;
import com.cretas.aims.service.unit.UnitContractService;
import com.cretas.aims.service.unit.UnitDimension;
import com.cretas.aims.service.unit.UnitNormalizationResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ProductProcessWorkflowUnitValidatorTest {

    @Mock ProductTypeRepository productTypeRepository;
    @Mock RawMaterialTypeRepository rawMaterialTypeRepository;
    @Mock ProductUnitConversionRepository conversionRepository;
    @Mock UnitContractService unitContractService;

    private ProductProcessWorkflowUnitValidator validator;

    @BeforeEach
    void setUp() {
        validator = new ProductProcessWorkflowUnitValidator(
                productTypeRepository, rawMaterialTypeRepository, conversionRepository, unitContractService);
        ProductType product = new ProductType();
        product.setId("SKU-1");
        product.setFactoryId("F006");
        product.setUnit("g");
        when(productTypeRepository.findByIdIn(any())).thenReturn(List.of(product));
        when(rawMaterialTypeRepository.findAllById(any())).thenReturn(List.of());
        when(unitContractService.validateConversionGraph(eq("F006"), eq("SKU-1"), any()))
                .thenReturn(List.of());
        when(unitContractService.normalize(eq("F006"), any())).thenAnswer(invocation -> {
            String raw = invocation.getArgument(1);
            String code = switch (raw == null ? "" : raw) {
                case "g", "克" -> "g";
                case "pcs", "件" -> "pcs";
                default -> null;
            };
            CanonicalUnit unit = code == null ? null : new CanonicalUnit(
                    code,
                    "g".equals(code) ? UnitDimension.MASS : UnitDimension.COUNT,
                    code,
                    BigDecimal.ONE,
                    code,
                    4);
            return new UnitNormalizationResult(raw, code, unit);
        });
    }

    @Test
    void acceptsCanonicalAliasWithoutConversion() {
        var result = validator.validate("F006", workflow("克", "g", "g", null, null));
        assertThat(result.errors()).isEmpty();
    }

    @Test
    void rejectsCrossDimensionPortWithoutExactConversion() {
        assertThatThrownBy(() -> validator.validateForPublish(
                "F006", workflow("g", "pcs", "pcs", null, null)))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("必须绑定精确换算版本");
    }

    @Test
    void acceptsExactCurrentConversionReference() {
        ProductUnitConversion conversion = conversion(7L);
        when(conversionRepository.findAllById(any())).thenReturn(List.of(conversion));

        var result = validator.validate("F006", workflow("g", "pcs", "pcs", "conv-1", 7L));

        assertThat(result.errors()).isEmpty();
    }

    @Test
    void rejectsStaleConversionVersion() {
        ProductUnitConversion conversion = conversion(8L);
        when(conversionRepository.findAllById(any())).thenReturn(List.of(conversion));

        var result = validator.validate("F006", workflow("g", "pcs", "pcs", "conv-1", 7L));

        assertThat(result.errors()).extracting("code").contains("WORKFLOW_CONVERSION_STALE");
    }

    @Test
    void rejectsProcessHintDifferentFromPrimaryPort() {
        var result = validator.validate("F006", workflow("g", "g", "pcs", null, null));
        assertThat(result.errors()).extracting("code").contains("WORKFLOW_PROCESS_UNIT_STALE");
    }

    private ProductUnitConversion conversion(Long version) {
        ProductUnitConversion row = new ProductUnitConversion();
        row.setId("conv-1");
        row.setFactoryId("F006");
        row.setProductTypeId("SKU-1");
        row.setFromUnitCode("pcs");
        row.setToUnitCode("g");
        row.setFactor(new BigDecimal("200"));
        row.setVersion(version);
        row.setEffectiveFrom(LocalDateTime.now().minusDays(1));
        return row;
    }

    private ProductProcessWorkflowDTO workflow(
            String materialUnit,
            String portUnit,
            String processOutputUnit,
            String conversionRefId,
            Long conversionVersion) {
        Map<String, Object> materialData = new LinkedHashMap<>();
        materialData.put("name", "成品");
        materialData.put("skuId", "SKU-1");
        materialData.put("baseUnit", materialUnit);
        materialData.put("bound", true);

        Map<String, Object> input = port("in-1", "INPUT", "mat-1", portUnit, conversionRefId, conversionVersion, 0);
        Map<String, Object> output = port("out-1", "OUTPUT", "mat-1", portUnit, conversionRefId, conversionVersion, 0);
        Map<String, Object> processData = new LinkedHashMap<>();
        processData.put("processName", "包装");
        processData.put("inputUnit", portUnit);
        processData.put("outputUnit", processOutputUnit);
        processData.put("ports", List.of(input, output));

        ProductProcessWorkflowDTO definition = new ProductProcessWorkflowDTO();
        definition.setNodes(List.of(
                new ProductProcessWorkflowDTO.Node("mat-1", "FINISHED_GOOD",
                        new ProductProcessWorkflowDTO.Position(0D, 0D), materialData),
                new ProductProcessWorkflowDTO.Node("proc-1", "PROCESS",
                        new ProductProcessWorkflowDTO.Position(1D, 1D), processData)));
        definition.setEdges(List.of());
        return definition;
    }

    private Map<String, Object> port(
            String id, String direction, String materialNodeId, String unit,
            String conversionRefId, Long conversionVersion, int ordinal) {
        Map<String, Object> port = new LinkedHashMap<>();
        port.put("id", id);
        port.put("direction", direction);
        port.put("materialNodeId", materialNodeId);
        port.put("unit", unit);
        port.put("ordinal", ordinal);
        if (conversionRefId != null) port.put("conversionRefId", conversionRefId);
        if (conversionVersion != null) port.put("conversionVersion", conversionVersion);
        return port;
    }
}
