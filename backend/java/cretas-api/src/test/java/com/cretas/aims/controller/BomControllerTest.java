package com.cretas.aims.controller;

import com.cretas.aims.dto.bom.CreateBomItemRequest;
import com.cretas.aims.dto.bom.CreateLaborCostRequest;
import com.cretas.aims.dto.bom.UpdateBomItemRequest;
import com.cretas.aims.dto.bom.UpdateLaborCostRequest;
import com.cretas.aims.dto.common.ApiResponse;
import com.cretas.aims.entity.bom.BomItem;
import com.cretas.aims.entity.bom.LaborCostConfig;
import com.cretas.aims.service.BomService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * Rule 17.1 cleanup verification for BomController (PR #370 audit).
 *
 * <p>These tests assert that the Controller correctly:
 * <ol>
 *   <li>Binds the dedicated Request DTOs (not the Entity directly)</li>
 *   <li>Maps the DTO to the Entity at the boundary, auto-filling factoryId
 *       from the path variable</li>
 *   <li>Accepts the "minimum body" (Rule 10) — wire-side caller does not
 *       need to send factoryId / id / audit fields</li>
 *   <li>Applies the legacy entity-default values when optional fields are
 *       omitted (yieldRate=100, taxRate=0, materialCategory=RAW, etc.)</li>
 * </ol>
 *
 * <p>The W-01 anti-pattern that this refactor unblocks: previously the
 * entity-level {@code @NotNull} on factoryId would 400-reject any FE body
 * that omitted factoryId — because the controller's {@code
 * setFactoryId(factoryId)} runs AFTER entity validation. With dedicated
 * DTOs, factoryId is not part of the wire contract at all.
 */
@ExtendWith(MockitoExtension.class)
class BomControllerTest {

    @Mock BomService bomService;
    @InjectMocks BomController controller;

    // ===== BomItem: minimum body (Rule 10) =====

    @Test
    void addBomItem_minimumBody_filledFactoryIdAndDefaults() {
        // Minimum wire body: only the truly required fields per CreateBomItemRequest.
        // No factoryId — that comes from the path. No id — DB-generated.
        // No yieldRate / taxRate / materialCategory / sortOrder — entity defaults apply.
        CreateBomItemRequest req = new CreateBomItemRequest();
        req.setProductTypeId("PT-001");
        req.setMaterialTypeId("MT-001");
        req.setStandardQuantity(new BigDecimal("10.5"));

        when(bomService.saveBomItem(any(BomItem.class))).thenAnswer(inv -> inv.getArgument(0));

        ApiResponse<BomItem> resp = controller.addBomItem("F001", req);

        ArgumentCaptor<BomItem> captor = ArgumentCaptor.forClass(BomItem.class);
        org.mockito.Mockito.verify(bomService).saveBomItem(captor.capture());
        BomItem persisted = captor.getValue();

        assertNotNull(resp);
        assertEquals("F001", persisted.getFactoryId(), "factoryId must be auto-filled from path");
        assertEquals("PT-001", persisted.getProductTypeId());
        assertEquals("MT-001", persisted.getMaterialTypeId());
        assertEquals(new BigDecimal("10.5"), persisted.getStandardQuantity());
        // B1 (PR #370 / BomController.toBomItem): yieldRate=null is now passed through as-is
        // (待评估 — old sentinel default 100.00 removed; BomServiceImpl may apply canvas default).
        assertNull(persisted.getYieldRate(), "B1: yieldRate null passes through — service applies canvas default");
        assertEquals(BigDecimal.ZERO, persisted.getTaxRate(), "taxRate default");
        assertEquals("RAW", persisted.getMaterialCategory(), "materialCategory default");
        assertEquals(0, persisted.getSortOrder(), "sortOrder default");
        assertNull(persisted.getId(), "id must remain null on create — DB IDENTITY assigns");
    }

    // ===== BomItem: happy path with all fields =====

    @Test
    void addBomItem_happyPath_allFieldsPropagated() {
        CreateBomItemRequest req = new CreateBomItemRequest();
        req.setProductTypeId("PT-100");
        req.setProductName("黑椒牛肉粒");
        req.setMaterialTypeId("MT-100");
        req.setMaterialName("牛肉粒");
        req.setStandardQuantity(new BigDecimal("0.8000"));
        req.setYieldRate(new BigDecimal("95.00"));
        req.setUnit("kg");
        req.setUnitPrice(new BigDecimal("85.0000"));
        req.setTaxRate(new BigDecimal("13.00"));
        req.setMaterialCategory("RAW");
        req.setSortOrder(5);
        req.setRemark("冷冻保存");

        when(bomService.saveBomItem(any(BomItem.class))).thenAnswer(inv -> inv.getArgument(0));

        controller.addBomItem("F006", req);

        ArgumentCaptor<BomItem> captor = ArgumentCaptor.forClass(BomItem.class);
        org.mockito.Mockito.verify(bomService).saveBomItem(captor.capture());
        BomItem persisted = captor.getValue();

        assertEquals("F006", persisted.getFactoryId());
        assertEquals("黑椒牛肉粒", persisted.getProductName());
        assertEquals("牛肉粒", persisted.getMaterialName());
        assertEquals(new BigDecimal("0.8000"), persisted.getStandardQuantity());
        assertEquals(new BigDecimal("95.00"), persisted.getYieldRate(), "yieldRate not overwritten by default");
        assertEquals(new BigDecimal("13.00"), persisted.getTaxRate(), "taxRate not overwritten by default");
        assertEquals(5, persisted.getSortOrder());
        assertEquals("冷冻保存", persisted.getRemark());
    }

    @Test
    void updateBomItem_setsIdAndFactoryIdFromPath() {
        UpdateBomItemRequest req = new UpdateBomItemRequest();
        req.setProductTypeId("PT-001");
        req.setMaterialTypeId("MT-001");
        req.setStandardQuantity(new BigDecimal("12.0"));

        when(bomService.saveBomItem(any(BomItem.class))).thenAnswer(inv -> inv.getArgument(0));

        controller.updateBomItem("F001", 42L, req);

        ArgumentCaptor<BomItem> captor = ArgumentCaptor.forClass(BomItem.class);
        org.mockito.Mockito.verify(bomService).saveBomItem(captor.capture());
        BomItem persisted = captor.getValue();

        assertEquals(42L, persisted.getId(), "id must be set from path on update");
        assertEquals("F001", persisted.getFactoryId(), "factoryId must be auto-filled from path");
    }

    // ===== LaborCostConfig: minimum body =====

    @Test
    void addLaborCost_minimumBody_filledFactoryIdAndDefaults() {
        CreateLaborCostRequest req = new CreateLaborCostRequest();
        req.setProcessName("切割");
        req.setUnitPrice(new BigDecimal("5.0000"));

        when(bomService.saveLaborCost(any(LaborCostConfig.class))).thenAnswer(inv -> inv.getArgument(0));

        controller.addLaborCost("F001", req);

        ArgumentCaptor<LaborCostConfig> captor = ArgumentCaptor.forClass(LaborCostConfig.class);
        org.mockito.Mockito.verify(bomService).saveLaborCost(captor.capture());
        LaborCostConfig persisted = captor.getValue();

        assertEquals("F001", persisted.getFactoryId());
        assertEquals("切割", persisted.getProcessName());
        assertEquals(new BigDecimal("5.0000"), persisted.getUnitPrice());
        // Defaults
        assertEquals(BigDecimal.ONE, persisted.getDefaultQuantity(), "defaultQuantity default");
        assertTrue(persisted.getIsActive(), "isActive default true");
        assertEquals(0, persisted.getSortOrder(), "sortOrder default");
        assertNull(persisted.getProductTypeId(), "global config — productTypeId null is valid");
        assertNull(persisted.getId(), "id must remain null on create");
    }

    @Test
    void addLaborCost_happyPath_productScopedConfig() {
        CreateLaborCostRequest req = new CreateLaborCostRequest();
        req.setProductTypeId("PT-100");
        req.setProcessName("包装");
        req.setProcessCategory("后处理");
        req.setUnitPrice(new BigDecimal("3.5000"));
        req.setPriceUnit("元/件");
        req.setDefaultQuantity(new BigDecimal("1.0000"));
        req.setIsActive(true);
        req.setSortOrder(10);
        req.setRemark("普通包装线");

        when(bomService.saveLaborCost(any(LaborCostConfig.class))).thenAnswer(inv -> inv.getArgument(0));

        controller.addLaborCost("F006", req);

        ArgumentCaptor<LaborCostConfig> captor = ArgumentCaptor.forClass(LaborCostConfig.class);
        org.mockito.Mockito.verify(bomService).saveLaborCost(captor.capture());
        LaborCostConfig persisted = captor.getValue();

        assertEquals("F006", persisted.getFactoryId());
        assertEquals("PT-100", persisted.getProductTypeId(), "product-scoped config");
        assertEquals("包装", persisted.getProcessName());
        assertEquals("后处理", persisted.getProcessCategory());
        assertEquals("元/件", persisted.getPriceUnit());
        assertEquals(10, persisted.getSortOrder());
        assertEquals("普通包装线", persisted.getRemark());
    }

    @Test
    void updateLaborCost_setsIdAndFactoryIdFromPath() {
        UpdateLaborCostRequest req = new UpdateLaborCostRequest();
        req.setProcessName("切割");
        req.setUnitPrice(new BigDecimal("6.0000"));

        when(bomService.saveLaborCost(any(LaborCostConfig.class))).thenAnswer(inv -> inv.getArgument(0));

        controller.updateLaborCost("F001", 7L, req);

        ArgumentCaptor<LaborCostConfig> captor = ArgumentCaptor.forClass(LaborCostConfig.class);
        org.mockito.Mockito.verify(bomService).saveLaborCost(captor.capture());
        LaborCostConfig persisted = captor.getValue();

        assertEquals(7L, persisted.getId());
        assertEquals("F001", persisted.getFactoryId());
        assertEquals(new BigDecimal("6.0000"), persisted.getUnitPrice());
    }
}
