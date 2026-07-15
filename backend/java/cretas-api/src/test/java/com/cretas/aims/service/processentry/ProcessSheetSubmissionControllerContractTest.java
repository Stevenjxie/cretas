package com.cretas.aims.service.processentry;

import com.cretas.aims.controller.ProcessSheetController;
import com.cretas.aims.dto.common.ApiResponse;
import com.cretas.aims.dto.processentry.ProcessSheetRowRequest;
import com.cretas.aims.dto.processentry.ProcessSheetRowResult;
import com.cretas.aims.dto.processentry.ProductionStockShortageDTO;
import com.cretas.aims.service.workflow.WorkflowClerkSheetService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.ResponseEntity;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ProcessSheetSubmissionControllerContractTest {

    @Mock
    private ProcessSheetService service;
    @Mock
    private WorkflowClerkSheetService workflowClerkSheetService;

    private ProcessSheetController controller;

    @BeforeEach
    void setUp() {
        controller = new ProcessSheetController(service, workflowClerkSheetService);
    }

    @Test
    void draftEndpointUsesDraftPathOnly() {
        ProcessSheetRowRequest request = new ProcessSheetRowRequest();
        ProcessSheetRowResult result = new ProcessSheetRowResult();
        result.setSubmissionStatus("DRAFT");
        when(service.saveDraft("F006", "PLAN-1", request, 7L)).thenReturn(result);

        ApiResponse<ProcessSheetRowResult> response =
                controller.saveDraft("F006", "PLAN-1", 7L, request);

        assertThat(response.getSuccess()).isTrue();
        assertThat(response.getData().getSubmissionStatus()).isEqualTo("DRAFT");
        verify(service).saveDraft("F006", "PLAN-1", request, 7L);
        verify(service, never()).saveRow("F006", "PLAN-1", request, 7L);
        verify(service, never()).submitRow("F006", "PLAN-1", request, 7L);
    }

    @Test
    void formalSubmissionReturnsHttp409AndStructuredShortage() {
        ProcessSheetRowRequest request = new ProcessSheetRowRequest();
        ProductionStockShortageDTO shortage = new ProductionStockShortageDTO(
                new BigDecimal("10"),
                new BigDecimal("7"),
                new BigDecimal("3"),
                "kg",
                List.of(new ProductionStockShortageDTO.Item(
                        "RAW-1",
                        new BigDecimal("10"),
                        new BigDecimal("7"),
                        new BigDecimal("3"),
                        "kg")));
        when(service.submitRow("F006", "PLAN-1", request, 7L))
                .thenThrow(new ProductionStockShortageException(shortage));

        ResponseEntity<ApiResponse<?>> response =
                controller.submitRow("F006", "PLAN-1", 7L, request);

        assertThat(response.getStatusCode().value()).isEqualTo(409);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getMessage())
                .isEqualTo("当前只能保存草稿，生产库中投料量不足。需要 10kg，可用 7kg，缺少 3kg，请联系仓管补料");
        assertThat(response.getBody().getErrorCode()).isEqualTo("PRODUCTION_STOCK_SHORTAGE");
        assertThat(response.getBody().getData()).isSameAs(shortage);
    }
}
