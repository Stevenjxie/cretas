package com.cretas.aims.service.inventory.impl;

import com.cretas.aims.dto.inventory.CreateSalesOrderRequest;
import com.cretas.aims.entity.inventory.SalesOrderItem;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;

class SalesServiceImplPackagingSnapshotTest {

    @Test
    void structuredPackagingSelection_survivesNullLegacyDisplayFields() {
        CreateSalesOrderRequest.SalesOrderItemDTO request = new CreateSalesOrderRequest.SalesOrderItemDTO();
        SalesOrderItem item = new SalesOrderItem();
        item.setPackagingSpecName("12盒/箱");
        item.setBoxQuantity(new BigDecimal("8"));

        SalesServiceImpl.applyLineDisplaySnapshots(request, item);

        assertEquals("12盒/箱", item.getSpecification());
        assertEquals(new BigDecimal("8"), item.getBoxQuantity());
    }

    @Test
    void explicitLegacyFields_overrideDerivedPackagingDisplay() {
        CreateSalesOrderRequest.SalesOrderItemDTO request = new CreateSalesOrderRequest.SalesOrderItemDTO();
        request.setSpecification("客户指定 24盒/箱");
        request.setBoxQuantity(new BigDecimal("3"));
        SalesOrderItem item = new SalesOrderItem();
        item.setPackagingSpecName("12盒/箱");
        item.setBoxQuantity(new BigDecimal("8"));

        SalesServiceImpl.applyLineDisplaySnapshots(request, item);

        assertEquals("客户指定 24盒/箱", item.getSpecification());
        assertEquals(new BigDecimal("3"), item.getBoxQuantity());
    }
}
