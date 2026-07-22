package com.cretas.aims.controller;

import com.cretas.aims.entity.enums.PurchaseReceiveStatus;
import com.cretas.aims.entity.inventory.PurchaseReceiveItem;
import com.cretas.aims.entity.inventory.PurchaseReceiveRecord;
import com.cretas.aims.security.PriceMaskResolver;
import com.cretas.aims.service.inventory.PurchaseService;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.client.RestTemplate;

import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class PrintControllerPurchaseReceiptTest {

    @Test
    @SuppressWarnings("unchecked")
    void receiptPrintUsesRealWarehouseReceiptIdentityAndLines() throws Exception {
        PurchaseService purchaseService = mock(PurchaseService.class);
        PurchaseReceiveRecord receipt = new PurchaseReceiveRecord();
        receipt.setId("RCV-1");
        receipt.setReceiveNumber("RCV-20260722-0001");
        receipt.setPurchaseOrderId("PO-ID");
        receipt.setPurchaseOrderNumber("PO-20260722-0001");
        receipt.setSupplierName("测试供应商");
        receipt.setReceiveDate(LocalDate.of(2026, 7, 22));
        receipt.setWarehouseId("WH-RM");
        receipt.setReceivedBy(1309L);
        receipt.setStatus(PurchaseReceiveStatus.DRAFT);
        PurchaseReceiveItem item = new PurchaseReceiveItem();
        item.setMaterialName("原料A");
        item.setReceivedQuantity(new BigDecimal("10"));
        item.setUnit("kg");
        receipt.setItems(List.of(item));
        when(purchaseService.getReceiveRecordById("F006", "RCV-1")).thenReturn(receipt);

        PrintController controller = new PrintController(
                mock(RestTemplate.class), "http://python", mock(PriceMaskResolver.class));
        ReflectionTestUtils.setField(controller, "purchaseService", purchaseService);
        Method method = PrintController.class.getDeclaredMethod(
                "buildPurchaseReceiptPayload", String.class, String.class, Map.class);
        method.setAccessible(true);

        Map<String, Object> payload = (Map<String, Object>) method.invoke(
                controller, "F006", "RCV-1", Map.of());

        assertThat(payload).containsEntry("movementNumber", "RCV-20260722-0001")
                .containsEntry("sourceRef", "PO-20260722-0001")
                .containsEntry("supplierName", "测试供应商")
                .containsEntry("warehouseName", "WH-RM");
        assertThat((List<Map<String, Object>>) payload.get("items"))
                .singleElement()
                .satisfies(row -> assertThat(row)
                        .containsEntry("materialName", "原料A")
                        .containsEntry("quantity", new BigDecimal("10"))
                        .containsEntry("unit", "kg"));
    }
}
