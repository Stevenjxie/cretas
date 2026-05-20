package com.cretas.aims.controller;

import com.cretas.aims.security.PriceMaskResolver;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.client.RestTemplate;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.when;

/**
 * RBAC unit tests for {@link PrintController} — Sprint1-Fix-K2.
 *
 * <p>PR #659 shipped 5 print endpoints with zero price masking. Roles without
 * {@code procurement:price:view} (warehouse_manager / quality_inspector / operator)
 * could call {@code GET /print/sales-order/{id}} and receive a PDF containing real
 * 单价 / 小计 / 合计 — bypassing PR #423 PriceFieldResponseAdvice which only walks
 * JSON. This test class verifies the payload-side mask runs before Python rendering.
 */
@DisplayName("PrintController RBAC 单测")
@ExtendWith(MockitoExtension.class)
class PrintControllerRBACTest {

    @Mock RestTemplate pythonRestTemplate;
    @Mock PriceMaskResolver priceMaskResolver;

    private PrintController controller;

    @BeforeEach
    void setUp() {
        controller = new PrintController(pythonRestTemplate, "http://localhost:8083", priceMaskResolver);
    }

    private Map<String, Object> samplePayloadWithPrices() {
        Map<String, Object> p = new HashMap<>();
        p.put("orderNumber", "SO-001");
        p.put("customerName", "测试客户");
        p.put("totalAmount", "10000.00");
        p.put("subtotal", "9500.00");
        p.put("taxAmount", "500.00");
        List<Map<String, Object>> items = new ArrayList<>();
        Map<String, Object> item1 = new HashMap<>();
        item1.put("productName", "猪肉");
        item1.put("qty", 100);
        item1.put("unitPrice", "50.00");
        item1.put("subtotal", "5000.00");
        items.add(item1);
        Map<String, Object> item2 = new HashMap<>();
        item2.put("productName", "牛肉");
        item2.put("qty", 50);
        item2.put("unitPrice", "90.00");
        item2.put("subtotal", "4500.00");
        items.add(item2);
        p.put("items", items);
        return p;
    }

    @Test
    @DisplayName("❌ 仓库管理员 (无 procurement:price:view) — totalAmount / subtotal / items.unitPrice 全 mask 为 '—'")
    @SuppressWarnings("unchecked")
    void warehouseManager_priceFieldsMasked() {
        when(priceMaskResolver.shouldMaskPrice("Bearer fake-warehouse-token")).thenReturn(true);

        Map<String, Object> payload = samplePayloadWithPrices();
        controller.applyPriceMask(payload, "Bearer fake-warehouse-token", "sales-order", true);

        assertEquals("—", payload.get("totalAmount"), "totalAmount 必须脱敏");
        assertEquals("—", payload.get("subtotal"), "subtotal 必须脱敏");
        assertEquals("—", payload.get("taxAmount"), "taxAmount 必须脱敏");

        List<Map<String, Object>> items = (List<Map<String, Object>>) payload.get("items");
        for (Map<String, Object> item : items) {
            assertEquals("—", item.get("unitPrice"), "items[].unitPrice 必须脱敏");
            assertEquals("—", item.get("subtotal"), "items[].subtotal 必须脱敏");
            // 非金额字段保留
            assertNotEquals("—", item.get("productName"));
            assertNotEquals("—", item.get("qty"));
        }
        // 非金额顶层字段保留
        assertEquals("SO-001", payload.get("orderNumber"));
        assertEquals("测试客户", payload.get("customerName"));
    }

    @Test
    @DisplayName("✅ finance_manager (有 procurement:price:view) — 金额字段完整保留")
    @SuppressWarnings("unchecked")
    void financeManager_priceFieldsIntact() {
        when(priceMaskResolver.shouldMaskPrice("Bearer fake-finance-token")).thenReturn(false);

        Map<String, Object> payload = samplePayloadWithPrices();
        controller.applyPriceMask(payload, "Bearer fake-finance-token", "purchase-order", true);

        assertEquals("10000.00", payload.get("totalAmount"));
        assertEquals("9500.00", payload.get("subtotal"));
        assertEquals("500.00", payload.get("taxAmount"));
        List<Map<String, Object>> items = (List<Map<String, Object>>) payload.get("items");
        assertEquals("50.00", items.get(0).get("unitPrice"));
        assertEquals("4500.00", items.get(1).get("subtotal"));
    }

    @Test
    @DisplayName("✅ production-task / material-requisition 单据不含金额, mask hook skip")
    void nonMonetaryDocs_skipMaskingEntirely() {
        Map<String, Object> payload = new HashMap<>();
        payload.put("taskNumber", "PT-001");
        payload.put("plannedQuantity", "100");

        // hasMonetaryFields = false 时, 即便 resolver 会返 mask=true, 也不该 mutate
        controller.applyPriceMask(payload, "Bearer any-token", "production-task", false);

        assertEquals("PT-001", payload.get("taskNumber"));
        assertEquals("100", payload.get("plannedQuantity"));
        // resolver 不该被调用 (短路在 hasMonetaryFields=false)
        org.mockito.Mockito.verifyNoInteractions(priceMaskResolver);
    }

    @Test
    @DisplayName("❌ 缺失 Authorization (null) → resolver shouldMaskPrice 默认 true (closed-by-default) → 脱敏")
    void missingAuthorization_defaultsToMask() {
        when(priceMaskResolver.shouldMaskPrice(null)).thenReturn(true);

        Map<String, Object> payload = samplePayloadWithPrices();
        controller.applyPriceMask(payload, null, "sales-order", true);

        assertEquals("—", payload.get("totalAmount"));
    }

    // ==================== Sprint 6 W3-C — 3 P1 templates (Round 13 §13) ====================

    @Test
    @DisplayName("✅ financial-invoice (Sprint 6 W3-C) — 仓库管理员 mask: subtotal/taxAmount/totalAmount + items unitPrice/taxAmount/subtotal 全 '—'")
    @SuppressWarnings("unchecked")
    void financialInvoice_warehouseManager_priceFieldsMasked() {
        when(priceMaskResolver.shouldMaskPrice("Bearer fake-warehouse-token")).thenReturn(true);

        Map<String, Object> payload = new HashMap<>();
        payload.put("invoiceNumber", "INV-001");
        payload.put("partyName", "测试客户");
        payload.put("subtotal", "9500.00");
        payload.put("taxAmount", "500.00");
        payload.put("totalAmount", "10000.00");
        List<Map<String, Object>> items = new ArrayList<>();
        Map<String, Object> line1 = new HashMap<>();
        line1.put("name", "冷冻肉");
        line1.put("qty", 100);
        line1.put("unitPrice", "50.00");
        line1.put("taxAmount", "250.00");
        line1.put("subtotal", "5000.00");
        items.add(line1);
        payload.put("items", items);

        controller.applyPriceMask(payload, "Bearer fake-warehouse-token", "financial-invoice", true);

        assertEquals("—", payload.get("subtotal"));
        assertEquals("—", payload.get("taxAmount"));
        assertEquals("—", payload.get("totalAmount"));
        List<Map<String, Object>> maskedItems = (List<Map<String, Object>>) payload.get("items");
        assertEquals("—", maskedItems.get(0).get("unitPrice"));
        assertEquals("—", maskedItems.get(0).get("taxAmount"));
        assertEquals("—", maskedItems.get(0).get("subtotal"));
        // 非金额字段保留
        assertEquals("INV-001", payload.get("invoiceNumber"));
        assertEquals("测试客户", payload.get("partyName"));
        assertEquals("冷冻肉", maskedItems.get(0).get("name"));
    }

    @Test
    @DisplayName("✅ stock-movement (Sprint 6 W3-C) — 无金额字段, mask 不影响 qty/batch/location")
    @SuppressWarnings("unchecked")
    void stockMovement_nonMonetaryDoc_qtyFieldsUntouched() {
        Map<String, Object> payload = new HashMap<>();
        payload.put("movementNumber", "STK-IN-001");
        payload.put("movementType", "IN");
        payload.put("warehouseName", "1号冷库");
        payload.put("totalQty", "1000");
        List<Map<String, Object>> items = new ArrayList<>();
        Map<String, Object> line1 = new HashMap<>();
        line1.put("name", "猪肉");
        line1.put("batch", "B202605190001");
        line1.put("qty", 500);
        line1.put("locationCode", "A-01-03");
        items.add(line1);
        payload.put("items", items);

        // hasMonetaryFields = false → resolver 不调用, 全字段保留
        controller.applyPriceMask(payload, "Bearer any-token", "stock-movement", false);

        assertEquals("STK-IN-001", payload.get("movementNumber"));
        assertEquals("IN", payload.get("movementType"));
        assertEquals("1000", payload.get("totalQty"));
        List<Map<String, Object>> rows = (List<Map<String, Object>>) payload.get("items");
        assertEquals(500, rows.get(0).get("qty"));
        assertEquals("B202605190001", rows.get(0).get("batch"));
        org.mockito.Mockito.verifyNoInteractions(priceMaskResolver);
    }

    @Test
    @DisplayName("✅ packing-list (Sprint 6 W3-C) — 无金额字段, 抄码 / 净重 / 毛重 全保留 (跟 N13 W-ABA-1 抄码品)")
    @SuppressWarnings("unchecked")
    void packingList_nonMonetaryDoc_weightAndAbacaUntouched() {
        Map<String, Object> payload = new HashMap<>();
        payload.put("packingNumber", "PK-001");
        payload.put("totalCartons", "10");
        payload.put("totalNetWeight", "850.50");
        payload.put("totalGrossWeight", "950.00");
        payload.put("weightUnit", "kg");
        List<Map<String, Object>> cartons = new ArrayList<>();
        Map<String, Object> c1 = new HashMap<>();
        c1.put("cartonNo", "1");
        c1.put("productName", "卤猪蹄");
        c1.put("netWeight", "85.5");
        c1.put("grossWeight", "95.0");
        c1.put("abacaCode", "ABACA-202605190001");  // 抄码 (not '超码')
        cartons.add(c1);
        payload.put("cartons", cartons);

        controller.applyPriceMask(payload, "Bearer any-token", "packing-list", false);

        assertEquals("PK-001", payload.get("packingNumber"));
        assertEquals("850.50", payload.get("totalNetWeight"));
        assertEquals("950.00", payload.get("totalGrossWeight"));
        List<Map<String, Object>> rows = (List<Map<String, Object>>) payload.get("cartons");
        assertEquals("85.5", rows.get(0).get("netWeight"));
        assertEquals("ABACA-202605190001", rows.get(0).get("abacaCode"));
        org.mockito.Mockito.verifyNoInteractions(priceMaskResolver);
    }
}
