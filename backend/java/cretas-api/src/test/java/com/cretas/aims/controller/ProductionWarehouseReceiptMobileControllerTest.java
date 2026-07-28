package com.cretas.aims.controller;

import com.cretas.aims.annotation.RequireModule;
import com.cretas.aims.annotation.RequirePermission;
import com.cretas.aims.config.PermissionInterceptor;
import com.cretas.aims.dto.common.ApiResponse;
import com.cretas.aims.dto.production.ProductionPlanDTO;
import com.cretas.aims.dto.production.ProductionWarehouseReceiptMobileConfirmRequest;
import com.cretas.aims.dto.production.ProductionWarehouseReceiptMobileDTO;
import com.cretas.aims.dto.production.ProductionWarehouseReceiptRequest;
import com.cretas.aims.dto.production.ProductionWarehouseReceiptResponse;
import com.cretas.aims.entity.ProductionSettlement;
import com.cretas.aims.entity.ProductionSettlementOutputLine;
import com.cretas.aims.entity.User;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.repository.ProductionSettlementRepository;
import com.cretas.aims.repository.ProductionSettlementOutputLineRepository;
import com.cretas.aims.repository.UserRepository;
import com.cretas.aims.service.PermissionService;
import com.cretas.aims.service.ProductionPlanService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
@DisplayName("ProductionWarehouseReceiptMobileController RN transit receipt contract")
class ProductionWarehouseReceiptMobileControllerTest {

    @Mock
    private ProductionSettlementRepository settlementRepository;
    @Mock
    private ProductionSettlementOutputLineRepository outputLineRepository;
    @Mock
    private ProductionPlanService productionPlanService;
    @Mock
    private PermissionService permissionService;
    @Mock
    private UserRepository userRepository;

    @AfterEach
    void tearDown() {
        RequestContextHolder.resetRequestAttributes();
    }

    @Test
    @DisplayName("GET pending confirmations queries only current factory pending warehouse receipts")
    void listTransitLedgers_queriesCurrentFactoryPendingWarehouseReceipts() {
        ProductionWarehouseReceiptMobileController controller =
                new ProductionWarehouseReceiptMobileController(
                        settlementRepository, outputLineRepository, productionPlanService);
        ProductionSettlement settlement = pendingSettlement("F006", "PLAN-1", "PP-001");
        when(settlementRepository.findByFactoryIdAndPostingStatusAndDeletedAtIsNull(
                "F006", "PENDING_WAREHOUSE_RECEIPT")).thenReturn(List.of(settlement));
        when(productionPlanService.getProductionPlanById("F006", "PLAN-1"))
                .thenReturn(ProductionPlanDTO.builder()
                        .id("PLAN-1")
                        .planNumber("PP-001")
                        .productName("Beef Slices")
                        .plannedQuantity(new BigDecimal("100.00"))
                        .productUnit("kg")
                        .build());

        ApiResponse<List<ProductionWarehouseReceiptMobileDTO>> response =
                controller.listTransitLedgers("F006", "PENDING_CONFIRMATION");

        verify(settlementRepository).findByFactoryIdAndPostingStatusAndDeletedAtIsNull(
                "F006", "PENDING_WAREHOUSE_RECEIPT");
        verify(settlementRepository, never()).findByFactoryIdAndPostingStatusAndDeletedAtIsNull(
                eq("F999"), anyString());
        ProductionWarehouseReceiptMobileDTO dto = response.getData().get(0);
        assertEquals("PLAN-1", dto.getId(), "RN confirm key must be productionPlanId");
        assertEquals("FINISHED_GOODS_RECEIPT", dto.getDirection());
        assertEquals("PENDING_CONFIRMATION", dto.getStatus());
        assertEquals("PP-001", dto.getSourceNumber());
        assertEquals("Beef Slices", dto.getProductName());
        assertEquals(new BigDecimal("100.00"), dto.getPlannedQuantity());
        assertEquals(new BigDecimal("95.00"), dto.getReportedQuantity());
        assertNull(dto.getReceivedQuantity());
        assertEquals(new BigDecimal("10.00"), dto.getToleranceQuantity());
        assertEquals("kg", dto.getUnit());
        assertEquals("中转/车间", dto.getFromLocation());
        assertEquals(33L, dto.getSubmittedBy());
        assertEquals(LocalDateTime.of(2026, 6, 12, 9, 30), dto.getSubmittedAt());
    }

    @Test
    @DisplayName("GET mixed-unit Workflow receipt exposes lines without inventing a scalar unit")
    void listTransitLedgers_mixedWorkflowOutputsRemainLineBased() {
        ProductionWarehouseReceiptMobileController controller =
                new ProductionWarehouseReceiptMobileController(
                        settlementRepository, outputLineRepository, productionPlanService);
        ProductionSettlement settlement = pendingSettlement("F006", "PLAN-1", "PP-001");
        settlement.setActualFinishedQuantity(null);
        settlement.setQuantityUnit(null);
        when(settlementRepository.findByFactoryIdAndPostingStatusAndDeletedAtIsNull(
                "F006", "PENDING_WAREHOUSE_RECEIPT")).thenReturn(List.of(settlement));
        when(productionPlanService.getProductionPlanById("F006", "PLAN-1"))
                .thenReturn(ProductionPlanDTO.builder()
                        .id("PLAN-1")
                        .planNumber("PP-001")
                        .productName("Raw owner")
                        .plannedQuantity(new BigDecimal("100.00"))
                        .productUnit("kg")
                        .build());
        when(outputLineRepository
                .findByFactoryIdAndSettlementIdOrderByProductTypeIdAscReportedBatchNumberAsc(
                        "F006", "SETTLE-1"))
                .thenReturn(List.of(
                        outputLine("FG-A", "FG-A-001", "8", "kg"),
                        outputLine("FG-B", "FG-B-001", "3", "box")));

        ProductionWarehouseReceiptMobileDTO dto =
                controller.listTransitLedgers("F006", "PENDING_CONFIRMATION").getData().getFirst();

        assertNull(dto.getReportedQuantity());
        assertNull(dto.getReceivedQuantity());
        assertNull(dto.getUnit());
        assertNull(dto.getToleranceQuantity());
        assertNull(dto.getBatchNumber());
        assertEquals(2, dto.getOutputLines().size());
        assertEquals("FG-A-001", dto.getOutputLines().getFirst().getBatchNumber());
        assertEquals("box", dto.getOutputLines().get(1).getUnit());
    }

    @Test
    @DisplayName("POST confirm wraps RN body and delegates to existing warehouse receipt service")
    void confirmTransitLedger_delegatesToExistingConfirmWarehouseReceipt() {
        ProductionWarehouseReceiptMobileController controller =
                new ProductionWarehouseReceiptMobileController(
                        settlementRepository, outputLineRepository, productionPlanService);
        setCurrentUser(27L);
        ProductionWarehouseReceiptMobileConfirmRequest body =
                new ProductionWarehouseReceiptMobileConfirmRequest();
        body.setReceivedQuantity(new BigDecimal("95.00"));
        body.setOutputLines(List.of(ProductionWarehouseReceiptRequest.ReceiptLine.builder()
                .productTypeId("PT-1")
                .batchNumber("FG-PP-001")
                .receivedQuantity(new BigDecimal("95.00"))
                .quantityUnit("kg")
                .build()));
        body.setNote("scale checked");
        ProductionWarehouseReceiptResponse serviceResponse = ProductionWarehouseReceiptResponse.builder()
                .productionPlanId("PLAN-1")
                .warehouseReceivedQuantity(new BigDecimal("95.00"))
                .postingStatus("POSTED_WITH_TOLERANCE")
                .build();
        when(productionPlanService.confirmWarehouseReceipt(eq("F006"), eq("PLAN-1"),
                any(ProductionWarehouseReceiptRequest.class), eq(27L))).thenReturn(serviceResponse);

        ApiResponse<ProductionWarehouseReceiptResponse> response =
                controller.confirmTransitLedger("F006", "PLAN-1", body);

        assertSame(serviceResponse, response.getData());
        ArgumentCaptor<ProductionWarehouseReceiptRequest> requestCaptor =
                ArgumentCaptor.forClass(ProductionWarehouseReceiptRequest.class);
        verify(productionPlanService).confirmWarehouseReceipt(eq("F006"), eq("PLAN-1"),
                requestCaptor.capture(), eq(27L));
        ProductionWarehouseReceiptRequest delegated = requestCaptor.getValue();
        assertTrue(delegated.getIdempotencyKey().startsWith("mobile-wh-receipt:"));
        assertEquals(new BigDecimal("95.00"), delegated.getReceivedQuantity());
        assertEquals(1, delegated.getOutputLines().size());
        assertEquals("PT-1", delegated.getOutputLines().getFirst().getProductTypeId());
        assertEquals("scale checked", delegated.getVarianceNote());
        assertNull(delegated.getVarianceReason());
        assertNull(delegated.getResponsibilitySide());
        assertNull(delegated.getQuantityUnit());
    }

    @Test
    @DisplayName("POST confirm over tolerance propagates existing 409 instead of swallowing")
    void confirmTransitLedger_overTolerancePropagatesConflict() {
        ProductionWarehouseReceiptMobileController controller =
                new ProductionWarehouseReceiptMobileController(
                        settlementRepository, outputLineRepository, productionPlanService);
        setCurrentUser(27L);
        ProductionWarehouseReceiptMobileConfirmRequest body =
                new ProductionWarehouseReceiptMobileConfirmRequest();
        body.setReceivedQuantity(new BigDecimal("80.00"));
        BusinessException conflict = new BusinessException(409,
                "仓库实收差异超出容差, 必须明确责任侧")
                .withCode("PRODUCTION_RECEIPT_RESPONSIBILITY_REQUIRED")
                .withHint("请选择生产侧处理、仓库侧处理或称重误差后再确认入库")
                .withHintTarget("责任侧");
        when(productionPlanService.confirmWarehouseReceipt(eq("F006"), eq("PLAN-1"),
                any(ProductionWarehouseReceiptRequest.class), eq(27L))).thenThrow(conflict);

        try {
            controller.confirmTransitLedger("F006", "PLAN-1", body);
            fail("Expected conflict");
        } catch (BusinessException ex) {
            assertSame(conflict, ex);
            assertEquals(409, ex.getCode());
            assertEquals("PRODUCTION_RECEIPT_RESPONSIBILITY_REQUIRED", ex.getErrorCode());
            assertNotNull(ex.getActionHint());
            assertEquals("责任侧", ex.getHintTarget());
        }
    }

    @Test
    @DisplayName("operator without warehouse:read_write gets 403 before confirm service is called")
    void confirmTransitLedger_operatorWithoutWarehousePermissionGets403() throws Exception {
        ProductionWarehouseReceiptMobileController controller =
                new ProductionWarehouseReceiptMobileController(
                        settlementRepository, outputLineRepository, productionPlanService);
        MockMvc mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .addInterceptors(new PermissionInterceptor(permissionService, userRepository, new ObjectMapper()))
                .build();
        User operator = new User();
        operator.setId(50L);
        when(userRepository.findById(50L)).thenReturn(Optional.of(operator));
        when(permissionService.hasAnyPermission(any(User.class), eq("warehouse:read_write")))
                .thenReturn(false);

        mockMvc.perform(post("/api/mobile/F006/warehouse/transit-ledgers/PLAN-1/confirm")
                        .requestAttr("userId", 50L)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"receivedQuantity\":95,\"note\":\"operator attempt\"}"))
                .andExpect(status().isForbidden());

        verify(productionPlanService, never()).confirmWarehouseReceipt(anyString(), anyString(), any(), any());
    }

    @Test
    @DisplayName("RN mobile route keeps warehouse module and warehouse read_write permission")
    void routeAnnotationsKeepWarehouseModuleAndPermission() throws Exception {
        RequireModule module = ProductionWarehouseReceiptMobileController.class
                .getAnnotation(RequireModule.class);
        assertNotNull(module);
        assertEquals("warehouse", module.value());

        assertMethodPermission("listTransitLedgers", String.class, String.class);
        assertMethodPermission("confirmTransitLedger", String.class, String.class,
                ProductionWarehouseReceiptMobileConfirmRequest.class);
    }

    private void assertMethodPermission(String methodName, Class<?>... parameterTypes) throws Exception {
        Method method = ProductionWarehouseReceiptMobileController.class
                .getDeclaredMethod(methodName, parameterTypes);
        RequirePermission permission = method.getAnnotation(RequirePermission.class);
        assertNotNull(permission);
        assertEquals(Set.of("warehouse:read_write"), Set.of(permission.value()));
    }

    private ProductionSettlement pendingSettlement(String factoryId, String planId, String planNumber) {
        ProductionSettlement settlement = new ProductionSettlement();
        settlement.setId("SETTLE-1");
        settlement.setFactoryId(factoryId);
        settlement.setProductionPlanId(planId);
        settlement.setPlanNumber(planNumber);
        settlement.setPlannedQuantity(new BigDecimal("100.00"));
        settlement.setActualFinishedQuantity(new BigDecimal("95.00"));
        settlement.setQuantityUnit("kg");
        settlement.setPostingStatus("PENDING_WAREHOUSE_RECEIPT");
        settlement.setSettledBy(33L);
        settlement.setSettledAt(LocalDateTime.of(2026, 6, 12, 9, 30));
        settlement.setPostingMessage("waiting warehouse receipt");
        return settlement;
    }

    private ProductionSettlementOutputLine outputLine(
            String productTypeId, String batchNumber, String quantity, String unit) {
        ProductionSettlementOutputLine line = ProductionSettlementOutputLine.create();
        line.setFactoryId("F006");
        line.setSettlementId("SETTLE-1");
        line.setProductionPlanId("PLAN-1");
        line.setProductTypeId(productTypeId);
        line.setReportedBatchNumber(batchNumber);
        line.setReportedQuantity(new BigDecimal(quantity));
        line.setQuantityUnit(unit);
        line.setStatus("REPORTED");
        return line;
    }

    private void setCurrentUser(Long userId) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setAttribute("userId", userId);
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
    }
}
