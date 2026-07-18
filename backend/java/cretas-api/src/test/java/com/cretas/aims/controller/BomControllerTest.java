package com.cretas.aims.controller;

import com.cretas.aims.dto.bom.CreateLaborCostRequest;
import com.cretas.aims.dto.bom.UpdateLaborCostRequest;
import com.cretas.aims.entity.bom.LaborCostConfig;
import com.cretas.aims.service.BomService;
import com.cretas.aims.service.bom.BomYieldEstimateService;
import com.cretas.aims.service.orchestration.RecursiveBomExpansionService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class BomControllerTest {

    @Mock BomService bomService;
    @Mock RecursiveBomExpansionService recursiveBomExpansionService;
    @Mock BomYieldEstimateService bomYieldEstimateService;
    @InjectMocks BomController controller;

    @Test
    void addLaborCost_minimumBody_fillsFactoryAndDefaults() {
        CreateLaborCostRequest request = new CreateLaborCostRequest();
        request.setProcessName("切割");
        request.setUnitPrice(new BigDecimal("5.0000"));
        when(bomService.saveLaborCost(any(LaborCostConfig.class))).thenAnswer(invocation -> invocation.getArgument(0));

        controller.addLaborCost("F001", request);

        ArgumentCaptor<LaborCostConfig> captor = ArgumentCaptor.forClass(LaborCostConfig.class);
        verify(bomService).saveLaborCost(captor.capture());
        LaborCostConfig saved = captor.getValue();
        assertEquals("F001", saved.getFactoryId());
        assertEquals("切割", saved.getProcessName());
        assertEquals(new BigDecimal("5.0000"), saved.getUnitPrice());
        assertEquals(BigDecimal.ONE, saved.getDefaultQuantity());
        assertTrue(saved.getIsActive());
        assertEquals(0, saved.getSortOrder());
        assertNull(saved.getProductTypeId());
        assertNull(saved.getId());
    }

    @Test
    void addLaborCost_supportsProductScopedConfig() {
        CreateLaborCostRequest request = new CreateLaborCostRequest();
        request.setProductTypeId("PT-100");
        request.setProcessName("包装");
        request.setProcessCategory("后处理");
        request.setUnitPrice(new BigDecimal("3.5000"));
        request.setPriceUnit("元/件");
        request.setDefaultQuantity(BigDecimal.ONE);
        request.setIsActive(true);
        request.setSortOrder(10);
        request.setRemark("普通包装线");
        when(bomService.saveLaborCost(any(LaborCostConfig.class))).thenAnswer(invocation -> invocation.getArgument(0));

        controller.addLaborCost("F006", request);

        ArgumentCaptor<LaborCostConfig> captor = ArgumentCaptor.forClass(LaborCostConfig.class);
        verify(bomService).saveLaborCost(captor.capture());
        LaborCostConfig saved = captor.getValue();
        assertEquals("F006", saved.getFactoryId());
        assertEquals("PT-100", saved.getProductTypeId());
        assertEquals("包装", saved.getProcessName());
        assertEquals("后处理", saved.getProcessCategory());
        assertEquals("元/件", saved.getPriceUnit());
        assertEquals(10, saved.getSortOrder());
    }

    @Test
    void updateLaborCost_setsIdAndFactoryFromPath() {
        UpdateLaborCostRequest request = new UpdateLaborCostRequest();
        request.setProcessName("切割");
        request.setUnitPrice(new BigDecimal("6.0000"));
        when(bomService.saveLaborCost(any(LaborCostConfig.class))).thenAnswer(invocation -> invocation.getArgument(0));

        controller.updateLaborCost("F001", 7L, request);

        ArgumentCaptor<LaborCostConfig> captor = ArgumentCaptor.forClass(LaborCostConfig.class);
        verify(bomService).saveLaborCost(captor.capture());
        assertEquals(7L, captor.getValue().getId());
        assertEquals("F001", captor.getValue().getFactoryId());
    }
}
