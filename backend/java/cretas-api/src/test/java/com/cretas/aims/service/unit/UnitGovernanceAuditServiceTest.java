package com.cretas.aims.service.unit;

import com.cretas.aims.dto.unit.UnitGovernanceConflictDTO;
import com.cretas.aims.entity.ProductProcessWorkflow;
import com.cretas.aims.entity.ProductType;
import com.cretas.aims.entity.RawMaterialType;
import com.cretas.aims.entity.unit.ProductUnitConversion;
import com.cretas.aims.repository.ProductProcessWorkflowRepository;
import com.cretas.aims.repository.ProductTypeRepository;
import com.cretas.aims.repository.RawMaterialTypeRepository;
import com.cretas.aims.repository.unit.ProductUnitConversionRepository;
import com.cretas.aims.service.unit.impl.UnitGovernanceAuditServiceImpl;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class UnitGovernanceAuditServiceTest {

    @Mock private ProductTypeRepository productTypeRepository;
    @Mock private RawMaterialTypeRepository rawMaterialTypeRepository;
    @Mock private ProductProcessWorkflowRepository workflowRepository;
    @Mock private ProductUnitConversionRepository conversionRepository;
    @Mock private UnitContractService unitContractService;

    private UnitGovernanceAuditService service;

    @BeforeEach
    void setUp() {
        service = new UnitGovernanceAuditServiceImpl(
                productTypeRepository,
                rawMaterialTypeRepository,
                workflowRepository,
                conversionRepository,
                unitContractService,
                new ObjectMapper());
        when(unitContractService.normalize(anyString(), anyString())).thenAnswer(invocation -> {
            String raw = invocation.getArgument(1, String.class);
            String code = switch (raw) {
                case "g", "克" -> "g";
                case "kg", "千克" -> "kg";
                case "件", "pcs" -> "pcs";
                default -> null;
            };
            return code == null
                    ? new UnitNormalizationResult(raw, null, null)
                    : new UnitNormalizationResult(raw, code,
                    new CanonicalUnit(code, "g".equals(code) || "kg".equals(code)
                            ? UnitDimension.MASS : UnitDimension.COUNT,
                            "kg".equals(code) ? "g" : code,
                            "kg".equals(code) ? new BigDecimal("1000")
                                    : "g".equals(code) ? BigDecimal.ONE : null,
                            code,
                            3));
        });
    }

    @Test
    void scansMasterDataAndEveryWorkflowVersionWithoutMutatingThem() throws Exception {
        ProductType product = product("P1", "g", new BigDecimal("200"));
        RawMaterialType raw = raw("R1", "神秘单位");
        ProductProcessWorkflow workflow = workflow(7, """
                [
                  {"id":"material:finished","kind":"FINISHED_GOOD","data":{"skuId":"P1","baseUnit":"kg"}},
                  {"id":"process:freeze","kind":"PROCESS","data":{"inputUnit":"g","outputUnit":"件","ports":[
                    {"id":"in","direction":"INPUT","ordinal":0,"materialNodeId":"material:finished","unit":"kg"},
                    {"id":"out","direction":"OUTPUT","ordinal":0,"materialNodeId":"material:finished","unit":"件","conversionRefId":"C1","conversionVersion":2}
                  ]}}
                ]
                """);
        ProductUnitConversion stale = conversion("C1", 1L, "pcs", "g",
                LocalDateTime.now().minusDays(10), LocalDateTime.now().minusDays(1));

        when(productTypeRepository.findByFactoryId("F006")).thenReturn(List.of(product));
        when(rawMaterialTypeRepository.findByFactoryId("F006")).thenReturn(List.of(raw));
        when(workflowRepository.findByFactoryIdOrderByProductTypeIdAscDefinitionVersionDesc("F006"))
                .thenReturn(List.of(workflow));
        when(conversionRepository.findByFactoryIdOrderByProductTypeIdAscCreatedAtAsc("F006"))
                .thenReturn(List.of(stale));

        List<UnitGovernanceConflictDTO> conflicts = service.scan("F006");

        assertThat(conflicts).extracting(UnitGovernanceConflictDTO::errorCode)
                .contains(
                        "UNKNOWN_UNIT_ALIAS",
                        "LEGACY_GRAMS_PER_UNIT_AMBIGUOUS",
                        "MATERIAL_SKU_UNIT_MISMATCH",
                        "PROCESS_PRIMARY_PORT_UNIT_MISMATCH",
                        "PORT_CONVERSION_REQUIRED",
                        "PORT_CONVERSION_STALE");
        assertThat(conflicts).anySatisfy(conflict -> {
            assertThat(conflict.factoryId()).isEqualTo("F006");
            assertThat(conflict.productTypeId()).isEqualTo("P1");
            assertThat(conflict.workflowVersion()).isEqualTo(7);
            assertThat(conflict.nodeId()).isEqualTo("process:freeze");
            assertThat(conflict.portId()).isEqualTo("out");
            assertThat(conflict.current()).isEqualTo("pcs");
            assertThat(conflict.expected()).isEqualTo("g");
            assertThat(conflict.errorCode()).isEqualTo("PORT_CONVERSION_STALE");
        });
        assertThat(workflow.getNodesJson()).contains("\"baseUnit\":\"kg\"");
        assertThat(workflow.getUnitReviewRequired()).isFalse();
    }

    @Test
    void acceptsAnEffectiveExactConversionAndExplicitNetContentSemantics() throws Exception {
        ProductType product = product("P1", "件", new BigDecimal("200"));
        ProductUnitConversion conversion = conversion("C1", 3L, "pcs", "g",
                LocalDateTime.now().minusDays(1), null);
        conversion.setSourceType(ProductUnitConversion.SourceType.NET_CONTENT);
        conversion.setFactor(new BigDecimal("200"));
        ProductProcessWorkflow workflow = workflow(2, """
                [
                  {"id":"material:finished","kind":"FINISHED_GOOD","data":{"skuId":"P1","baseUnit":"件"}},
                  {"id":"process:pack","kind":"PROCESS","data":{"outputUnit":"g","ports":[
                    {"id":"out","direction":"OUTPUT","ordinal":0,"materialNodeId":"material:finished","unit":"g","conversionRefId":"C1","conversionVersion":3}
                  ]}}
                ]
                """);

        when(productTypeRepository.findByFactoryId("F006")).thenReturn(List.of(product));
        when(rawMaterialTypeRepository.findByFactoryId("F006")).thenReturn(List.of());
        when(workflowRepository.findByFactoryIdOrderByProductTypeIdAscDefinitionVersionDesc("F006"))
                .thenReturn(List.of(workflow));
        when(conversionRepository.findByFactoryIdOrderByProductTypeIdAscCreatedAtAsc("F006"))
                .thenReturn(List.of(conversion));

        assertThat(service.scan("F006")).isEmpty();
    }

    private ProductType product(String id, String unit, BigDecimal gramsPerUnit) {
        ProductType product = new ProductType();
        product.setId(id);
        product.setFactoryId("F006");
        product.setUnit(unit);
        product.setGramsPerUnit(gramsPerUnit);
        return product;
    }

    private RawMaterialType raw(String id, String unit) {
        RawMaterialType raw = new RawMaterialType();
        raw.setId(id);
        raw.setFactoryId("F006");
        raw.setUnit(unit);
        return raw;
    }

    private ProductProcessWorkflow workflow(int version, String nodesJson) throws Exception {
        ProductProcessWorkflow workflow = new ProductProcessWorkflow();
        workflow.setFactoryId("F006");
        workflow.setProductTypeId("P1");
        workflow.setDefinitionVersion(version);
        workflow.setNodesJson(new ObjectMapper().readTree(nodesJson).toString());
        workflow.setUnitReviewRequired(false);
        return workflow;
    }

    private ProductUnitConversion conversion(
            String id,
            long version,
            String from,
            String to,
            LocalDateTime effectiveFrom,
            LocalDateTime effectiveTo) {
        ProductUnitConversion conversion = new ProductUnitConversion();
        conversion.setId(id);
        conversion.setFactoryId("F006");
        conversion.setProductTypeId("P1");
        conversion.setFromUnitCode(from);
        conversion.setToUnitCode(to);
        conversion.setFactor(BigDecimal.ONE);
        conversion.setSourceType(ProductUnitConversion.SourceType.MANUAL);
        conversion.setEffectiveFrom(effectiveFrom);
        conversion.setEffectiveTo(effectiveTo);
        conversion.setVersion(version);
        return conversion;
    }
}
