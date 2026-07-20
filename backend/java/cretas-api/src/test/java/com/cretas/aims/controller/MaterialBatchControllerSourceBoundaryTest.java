package com.cretas.aims.controller;

import com.cretas.aims.dto.material.CreateMaterialBatchRequest;
import com.cretas.aims.dto.material.ReplenishMaterialBatchRequest;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.security.PriceMaskResolver;
import com.cretas.aims.service.MaterialBatchService;
import com.cretas.aims.service.MobileService;
import com.cretas.aims.service.inventory.OpeningInventoryService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.verifyNoInteractions;

@ExtendWith(MockitoExtension.class)
class MaterialBatchControllerSourceBoundaryTest {

    @Mock MaterialBatchService materialBatchService;
    @Mock MobileService mobileService;
    @Mock PriceMaskResolver priceMaskResolver;
    @Mock OpeningInventoryService openingInventoryService;

    private MaterialBatchController controller;

    @BeforeEach
    void setUp() {
        controller = new MaterialBatchController(
                materialBatchService, mobileService, priceMaskResolver, openingInventoryService);
    }

    @Test
    void directSingleBatchCreationIsClosedBeforeAnyWriteServiceRuns() {
        BusinessException error = assertThrows(BusinessException.class,
                () -> controller.createMaterialBatch("F006", "Bearer ignored", new CreateMaterialBatchRequest()));

        assertEquals(409, error.getCode());
        assertEquals("sourceTaskId", error.getHintTarget());
        verifyNoInteractions(materialBatchService, mobileService);
    }

    @Test
    void directBatchCreationIsClosedBeforeAnyWriteServiceRuns() {
        BusinessException error = assertThrows(BusinessException.class,
                () -> controller.batchCreateMaterialBatches(
                        "F006", "Bearer ignored", List.of(new CreateMaterialBatchRequest())));

        assertEquals(409, error.getCode());
        verifyNoInteractions(materialBatchService, mobileService);
    }

    @Test
    void directReplenishIsClosedBeforeAnyWriteServiceRuns() {
        BusinessException error = assertThrows(BusinessException.class,
                () -> controller.replenishExistingBatch(
                        "F006", "batch-1", "Bearer ignored", new ReplenishMaterialBatchRequest()));

        assertEquals(409, error.getCode());
        verifyNoInteractions(materialBatchService, mobileService);
    }
}
