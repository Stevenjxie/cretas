package com.cretas.aims.controller;

import com.cretas.aims.dto.unit.UnitConversionRequest;
import com.cretas.aims.service.unit.ProductUnitConversionService;
import com.cretas.aims.service.unit.UnitContractService;
import com.cretas.aims.service.unit.UnitConversionContext;
import com.cretas.aims.service.unit.UnitConversionResult;
import com.cretas.aims.service.unit.UnitConversionStatus;
import com.cretas.aims.service.unit.UnitGovernanceAuditService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class UnitContractControllerTest {

    @Mock UnitContractService unitContractService;
    @Mock ProductUnitConversionService productUnitConversionService;
    @Mock UnitGovernanceAuditService unitGovernanceAuditService;

    @Test
    void convertPassesFactoryAndProductContextToAuthority() {
        UnitContractController controller = new UnitContractController(
                unitContractService, productUnitConversionService, unitGovernanceAuditService);
        UnitConversionResult converted = new UnitConversionResult(
                UnitConversionStatus.CONVERTED, new BigDecimal("0.2"), "pcs", "kg",
                List.of("pcs", "g", "kg"), null, null, null, List.of());
        ArgumentCaptor<UnitConversionContext> context = ArgumentCaptor.forClass(UnitConversionContext.class);
        when(unitContractService.convert(eq(BigDecimal.ONE), context.capture())).thenReturn(converted);

        var response = controller.convert("F1", new UnitConversionRequest(
                BigDecimal.ONE, "P1", "件", "kg", null, null, null, null));

        assertSame(converted, response.getBody().getData());
        assertEquals("F1", context.getValue().factoryId());
        assertEquals("P1", context.getValue().productTypeId());
        assertNotNull(context.getValue().at());
    }

    @Test
    void listIsStrictlyFactoryAndProductScoped() {
        UnitContractController controller = new UnitContractController(
                unitContractService, productUnitConversionService, unitGovernanceAuditService);
        when(productUnitConversionService.list("F1", "P1")).thenReturn(List.of());

        var response = controller.list("F1", "P1");

        assertTrue(response.getBody().getData().isEmpty());
        verify(productUnitConversionService).list("F1", "P1");
    }

    @Test
    void conflictsIsStrictlyFactoryScoped() {
        UnitContractController controller = new UnitContractController(
                unitContractService, productUnitConversionService, unitGovernanceAuditService);
        when(unitGovernanceAuditService.scan("F1")).thenReturn(List.of());

        var response = controller.conflicts("F1");

        assertTrue(response.getBody().getData().isEmpty());
        verify(unitGovernanceAuditService).scan("F1");
    }
}
