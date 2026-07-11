package com.cretas.aims.controller;

import com.cretas.aims.dto.common.ApiResponse;
import com.cretas.aims.dto.workflow.ProductProcessWorkflowActivationDTO;
import com.cretas.aims.service.ProductProcessWorkflowService;
import com.cretas.aims.service.workflow.ProductProcessWorkflowActivationService;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.Mockito.*;

class ProductProcessWorkflowActivationControllerTest {

    private ProductProcessWorkflowActivationService activationService;
    private HttpServletRequest request;
    private ProductProcessWorkflowController controller;

    @BeforeEach
    void setUp() {
        ProductProcessWorkflowService workflowService = mock(ProductProcessWorkflowService.class);
        activationService = mock(ProductProcessWorkflowActivationService.class);
        request = mock(HttpServletRequest.class);
        controller = new ProductProcessWorkflowController(workflowService, activationService);
    }

    @Test
    void activateUsesJwtOperatorAndDelegatesExactWorkflowId() {
        ProductProcessWorkflowActivationDTO expected = new ProductProcessWorkflowActivationDTO();
        when(request.getAttribute("userId")).thenReturn(7001L);
        when(activationService.activate("F006", 44L, 7001L)).thenReturn(expected);

        ApiResponse<ProductProcessWorkflowActivationDTO> response =
                controller.activate("F006", 44L, request);

        assertSame(expected, response.getData());
        verify(activationService).activate("F006", 44L, 7001L);
    }

    @Test
    void deactivateDelegatesProductAndExpectedLockVersion() {
        ProductProcessWorkflowActivationDTO expected = new ProductProcessWorkflowActivationDTO();
        when(activationService.deactivate("F006", "PT-PIG", 5L)).thenReturn(expected);

        ApiResponse<ProductProcessWorkflowActivationDTO> response =
                controller.deactivate("F006", "PT-PIG", 5L);

        assertSame(expected, response.getData());
        verify(activationService).deactivate("F006", "PT-PIG", 5L);
    }

    @Test
    void getActivationDelegatesFactoryAndProduct() {
        ProductProcessWorkflowActivationDTO expected = new ProductProcessWorkflowActivationDTO();
        when(activationService.get("F006", "PT-PIG")).thenReturn(expected);

        ApiResponse<ProductProcessWorkflowActivationDTO> response =
                controller.getActivation("F006", "PT-PIG");

        assertSame(expected, response.getData());
        verify(activationService).get("F006", "PT-PIG");
    }
}
