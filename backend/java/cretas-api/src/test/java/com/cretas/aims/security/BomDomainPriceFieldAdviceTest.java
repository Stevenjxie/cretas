package com.cretas.aims.security;

import com.cretas.aims.dto.bom.BomCostSummaryDTO;
import com.cretas.aims.dto.bom.BomCostSummaryDTO.LaborCostItem;
import com.cretas.aims.dto.bom.BomCostSummaryDTO.MaterialCostItem;
import com.cretas.aims.dto.bom.BomCostSummaryDTO.OverheadCostItem;
import com.cretas.aims.dto.bom.BomFamilyOutputCostingResponse;
import com.cretas.aims.dto.common.ApiResponse;
import com.cretas.aims.entity.BatchWorkSession;
import com.cretas.aims.entity.EmployeeWorkSession;
import com.cretas.aims.entity.RawMaterialType;
import com.cretas.aims.entity.User;
import com.cretas.aims.entity.bom.BomRecipeItem;
import com.cretas.aims.entity.bom.BomRecipe;
import com.cretas.aims.entity.bom.LaborCostConfig;
import com.cretas.aims.entity.bom.OverheadCostConfig;
import com.cretas.aims.repository.UserRepository;
import com.cretas.aims.service.PermissionService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServletServerHttpRequest;
import org.springframework.mock.web.MockHttpServletRequest;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.Collections;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

/**
 * End-to-end advice test for PR #455 BUG-2 — BOM-domain RBAC strip.
 *
 * <p>Mirrors the {@code samplePurchaseOrder} pattern in {@link PriceFieldResponseAdviceTest}:
 * build a realistic response payload, run it through the advice with mocked permission, then
 * assert every {@code @PriceSensitive} field is nulled (warehouse) or preserved (admin) and
 * non-price fields (quantity / yieldRate / taxRate / allocationRate / process metadata) are
 * untouched.
 *
 * <p>BUG-2 root cause: PR #423 ({@link com.cretas.aims.entity.inventory.PurchaseOrder} et al.)
 * landed {@link PriceFieldResponseAdvice} for procurement/sales but skipped the BOM/recipe/
 * production-cost domain. PR #455 verified the gap via Playwright; this test class is the
 * regression guard at the advice layer.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("PriceFieldResponseAdvice — BOM-domain RBAC strip (PR #455 BUG-2)")
class BomDomainPriceFieldAdviceTest {

    @Mock private PermissionService permissionService;
    @Mock private UserRepository userRepository;
    @InjectMocks private PriceFieldResponseAdvice advice;

    private MockHttpServletRequest httpRequest;
    private ServerHttpRequest serverRequest;

    @BeforeEach
    void setup() {
        httpRequest = new MockHttpServletRequest();
        serverRequest = new ServletServerHttpRequest(httpRequest);
        PriceSensitiveContext.clear();
    }

    @AfterEach
    void teardown() {
        PriceSensitiveContext.clear();
    }

    // ───────── Helpers ─────────

    private void asUser(Long userId, boolean canViewPrice) {
        httpRequest.setAttribute("userId", userId);
        User user = new User();
        user.setId(userId);
        lenient().when(userRepository.findById(userId)).thenReturn(Optional.of(user));
        lenient().when(permissionService.hasPermission(eq(user),
                eq(PriceFieldResponseAdvice.PRICE_VIEW_PERMISSION))).thenReturn(canViewPrice);
    }

    private Object run(Object body) {
        return advice.beforeBodyWrite(body, null, MediaType.APPLICATION_JSON, null, serverRequest, null);
    }

    private BomRecipeItem sampleBomItem() {
        return BomRecipeItem.builder()
                .id(1L)
                .factoryId("F001")
                .recipeId("RECIPE-PT-F001-001")
                .materialTypeId("MB-F001-001")
                .materialName("带鱼原料")
                .standardQuantity(new BigDecimal("1.2000"))
                .yieldRate(new BigDecimal("85.00"))
                .unit("kg")
                .unitPrice(new BigDecimal("18.5000"))
                .taxRate(new BigDecimal("13.00"))
                .materialCategory("RAW")
                .sortOrder(1)
                .build();
    }

    private BomCostSummaryDTO sampleCostSummary() {
        MaterialCostItem mat = MaterialCostItem.builder()
                .materialName("带鱼原料")
                .materialTypeId("MB-F001-001")
                .standardQuantity(new BigDecimal("1.2000"))
                .yieldRate(new BigDecimal("85.00"))
                .actualQuantity(new BigDecimal("1.4118"))
                .unit("kg")
                .unitPrice(new BigDecimal("18.5000"))
                .taxRate(new BigDecimal("13.00"))
                .subtotal(new BigDecimal("26.1183"))
                .build();
        LaborCostItem labor = LaborCostItem.builder()
                .processName("前处理")
                .processCategory("加工")
                .unitPrice(new BigDecimal("12.00"))
                .priceUnit("元/kg")
                .quantity(new BigDecimal("1.0000"))
                .subtotal(new BigDecimal("12.00"))
                .build();
        OverheadCostItem overhead = OverheadCostItem.builder()
                .name("设备折旧")
                .category("管理费用")
                .unitPrice(new BigDecimal("3.50"))
                .priceUnit("元/批次")
                .allocationRate(new BigDecimal("0.20"))
                .subtotal(new BigDecimal("0.70"))
                .build();

        return BomCostSummaryDTO.builder()
                .productTypeId("PT-F001-001")
                .productName("带鱼段")
                .materialCosts(Collections.singletonList(mat))
                .materialCostTotal(new BigDecimal("26.1183"))
                .laborCosts(Collections.singletonList(labor))
                .laborCostTotal(new BigDecimal("12.00"))
                .overheadCosts(Collections.singletonList(overhead))
                .overheadCostTotal(new BigDecimal("0.70"))
                .totalCost(new BigDecimal("38.8183"))
                .calculatedAt("2026-05-12T14:00:00")
                .build();
    }

    // ───────── Tests ─────────

    @Test
    @DisplayName("warehouse_manager → BomRecipeItem.unitPrice stripped; identity / quantity / rate preserved")
    void warehouse_bomItem_unitPriceStripped() {
        asUser(143L, false);  // warehouse_mgr1 (matches data.sql seed userId=143)

        BomRecipeItem item = sampleBomItem();
        run(ApiResponse.success(item));

        assertNull(item.getUnitPrice(), "BomRecipeItem.unitPrice must be stripped for warehouse role");
        // Identity / quantity / rate preserved — warehouse legitimately needs these for stock ops.
        assertEquals("RECIPE-PT-F001-001", item.getRecipeId());
        assertEquals("带鱼原料", item.getMaterialName());
        assertEquals(new BigDecimal("1.2000"), item.getStandardQuantity(), "standardQuantity preserved");
        assertEquals(new BigDecimal("85.00"), item.getYieldRate(), "yieldRate preserved");
        assertEquals(new BigDecimal("13.00"), item.getTaxRate(), "taxRate preserved (not price-sensitive)");
    }

    @Test
    @DisplayName("admin → BomRecipeItem.unitPrice preserved end-to-end")
    void admin_bomItem_unitPricePreserved() {
        asUser(1L, true);  // factory_admin1
        BomRecipeItem item = sampleBomItem();
        run(ApiResponse.success(item));
        assertEquals(new BigDecimal("18.5000"), item.getUnitPrice(),
                "BomRecipeItem.unitPrice must be visible to admin (procurement:price:view granted)");
    }

    @Test
    @DisplayName("warehouse_manager → BomCostSummaryDTO: all 10 monetary fields stripped, identity / quantity / rate preserved")
    void warehouse_costSummary_allMonetaryStripped() {
        asUser(143L, false);

        BomCostSummaryDTO summary = sampleCostSummary();
        run(ApiResponse.success(summary));

        // Outer roll-up totals
        assertNull(summary.getMaterialCostTotal(), "materialCostTotal stripped");
        assertNull(summary.getLaborCostTotal(),    "laborCostTotal stripped");
        assertNull(summary.getOverheadCostTotal(), "overheadCostTotal stripped");
        assertNull(summary.getTotalCost(),         "totalCost stripped");

        // Nested MaterialCostItem
        MaterialCostItem mat = summary.getMaterialCosts().get(0);
        assertNull(mat.getUnitPrice(), "MaterialCostItem.unitPrice stripped");
        assertNull(mat.getSubtotal(),  "MaterialCostItem.subtotal stripped");
        // Quantity + rate preserved
        assertEquals(new BigDecimal("1.4118"), mat.getActualQuantity(), "actualQuantity preserved");
        assertEquals(new BigDecimal("13.00"),  mat.getTaxRate(),        "taxRate preserved");

        // Nested LaborCostItem
        LaborCostItem labor = summary.getLaborCosts().get(0);
        assertNull(labor.getUnitPrice(), "LaborCostItem.unitPrice stripped");
        assertNull(labor.getSubtotal(),  "LaborCostItem.subtotal stripped");
        assertEquals("前处理", labor.getProcessName(), "processName preserved");

        // Nested OverheadCostItem
        OverheadCostItem overhead = summary.getOverheadCosts().get(0);
        assertNull(overhead.getUnitPrice(), "OverheadCostItem.unitPrice stripped");
        assertNull(overhead.getSubtotal(),  "OverheadCostItem.subtotal stripped");
        assertEquals(new BigDecimal("0.20"), overhead.getAllocationRate(),
                "allocationRate preserved (ratio, not price)");
    }

    @Test
    @DisplayName("admin → BomCostSummaryDTO all monetary fields preserved")
    void admin_costSummary_allMonetaryPreserved() {
        asUser(1L, true);
        BomCostSummaryDTO summary = sampleCostSummary();
        run(ApiResponse.success(summary));

        assertEquals(new BigDecimal("26.1183"), summary.getMaterialCostTotal());
        assertEquals(new BigDecimal("38.8183"), summary.getTotalCost());
        assertEquals(new BigDecimal("18.5000"), summary.getMaterialCosts().get(0).getUnitPrice());
        assertEquals(new BigDecimal("12.00"),   summary.getLaborCosts().get(0).getUnitPrice());
        assertEquals(new BigDecimal("3.50"),    summary.getOverheadCosts().get(0).getUnitPrice());
    }

    @Test
    @DisplayName("warehouse_manager → by-product NRV stripped while output contract remains visible")
    void warehouse_byProductNrvStripped() {
        asUser(143L, false);
        BomFamilyOutputCostingResponse response = new BomFamilyOutputCostingResponse();
        response.setOutputs(Collections.singletonList(
                new BomFamilyOutputCostingResponse.OutputCosting(
                        "RECIPE-BY", "FG-BY", "副产品",
                        BomRecipe.OutputRole.BY_PRODUCT, BigDecimal.ZERO,
                        BigDecimal.ONE, "kg", new BigDecimal("3.5000"))));

        run(ApiResponse.success(response));

        BomFamilyOutputCostingResponse.OutputCosting output = response.getOutputs().getFirst();
        assertNull(output.getByproductNrvUnitPrice(), "by-product NRV must be stripped");
        assertEquals(BomRecipe.OutputRole.BY_PRODUCT, output.getOutputRole());
        assertEquals(BigDecimal.ZERO, output.getCostAllocationRatio());
        assertEquals(BigDecimal.ONE, output.getOutputQuantity());
    }

    @Test
    @DisplayName("warehouse_manager → sister-site sweep: LaborCostConfig + OverheadCostConfig + work sessions + RawMaterialType")
    void warehouse_sisterSites_allStripped() {
        asUser(143L, false);

        LaborCostConfig labor = LaborCostConfig.builder()
                .id(1L).factoryId("F001").productTypeId("PT-F001-001")
                .processName("前处理").processCategory("加工")
                .unitPrice(new BigDecimal("12.0000")).priceUnit("元/kg")
                .build();
        OverheadCostConfig overhead = OverheadCostConfig.builder()
                .id(2L).factoryId("F001")
                .name("设备折旧").category("管理费用")
                .unitPrice(new BigDecimal("3.5000")).priceUnit("元/批次")
                .build();
        BatchWorkSession batchSession = new BatchWorkSession();
        batchSession.setId(10L);
        batchSession.setBatchId(100L);
        batchSession.setEmployeeId(143L);
        batchSession.setLaborCost(new BigDecimal("88.50"));
        EmployeeWorkSession empSession = new EmployeeWorkSession();
        empSession.setId(20L);
        empSession.setFactoryId("F001");
        empSession.setUserId(143L);
        empSession.setHourlyRate(new BigDecimal("25.00"));
        empSession.setLaborCost(new BigDecimal("100.00"));
        RawMaterialType rmt = new RawMaterialType();
        rmt.setId("RMT-1");
        rmt.setFactoryId("F001");
        rmt.setCode("DY");
        rmt.setName("带鱼");
        rmt.setUnitPrice(new BigDecimal("45.00"));

        run(ApiResponse.success(Arrays.asList(labor, overhead, batchSession, empSession, rmt)));

        assertNull(labor.getUnitPrice(),         "LaborCostConfig.unitPrice stripped");
        assertNull(overhead.getUnitPrice(),      "OverheadCostConfig.unitPrice stripped");
        assertNull(batchSession.getLaborCost(),  "BatchWorkSession.laborCost stripped");
        assertNull(empSession.getHourlyRate(),   "EmployeeWorkSession.hourlyRate stripped");
        assertNull(empSession.getLaborCost(),    "EmployeeWorkSession.laborCost stripped");
        assertNull(rmt.getUnitPrice(),           "RawMaterialType.unitPrice stripped");

        // Identity / non-price preserved on every entity
        assertEquals("前处理", labor.getProcessName());
        assertEquals("设备折旧", overhead.getName());
        assertEquals(Long.valueOf(100L), batchSession.getBatchId());
        assertEquals("F001", empSession.getFactoryId());
        assertEquals("DY", rmt.getCode());
        assertEquals("带鱼", rmt.getName());
    }

    @Test
    @DisplayName("admin → sister-site sweep: all sister-site prices preserved")
    void admin_sisterSites_allPreserved() {
        asUser(1L, true);

        LaborCostConfig labor = LaborCostConfig.builder()
                .unitPrice(new BigDecimal("12.0000")).build();
        RawMaterialType rmt = new RawMaterialType();
        rmt.setUnitPrice(new BigDecimal("45.00"));

        run(ApiResponse.success(Arrays.asList(labor, rmt)));

        assertNotNull(labor.getUnitPrice(),
                "LaborCostConfig.unitPrice preserved for admin (procurement:price:view granted)");
        assertNotNull(rmt.getUnitPrice(),
                "RawMaterialType.unitPrice preserved for admin");
    }
}
