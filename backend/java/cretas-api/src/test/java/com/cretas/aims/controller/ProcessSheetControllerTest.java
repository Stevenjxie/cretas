package com.cretas.aims.controller;

import com.cretas.aims.dto.processentry.ProcessSheetRowRequest;
import com.cretas.aims.dto.processentry.ProcessSheetRowResult;
import com.cretas.aims.exception.GlobalExceptionHandler;
import com.cretas.aims.service.processentry.ProcessSheetService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;

import java.math.BigDecimal;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * ProcessSheetController — 路由映射烟雾测试。
 *
 * <p>策略: standaloneSetup 绕过 @RequirePermission 拦截器，通过
 * {@code requestAttr("userId", ...)} 注入 @RequestAttribute。
 * 业务逻辑 + 跨租户校验已由 ProcessSheetServiceImplTest 覆盖；
 * 本测试只验证: POST /row → saveRow、DELETE /row/{id} → deleteRow 路由正确，
 * 响应体包含 success:true + data。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ProcessSheetController — 路由映射烟雾测试")
class ProcessSheetControllerTest {

    @Mock
    private ProcessSheetService service;

    private MockMvc mockMvc;
    private ObjectMapper objectMapper;

    private static final String FACTORY_ID = "F006";
    private static final String PLAN_ID = "PLAN-001";
    private static final Long USER_ID = 42L;

    private static final String SAVE_ROW_URL =
            "/api/mobile/" + FACTORY_ID + "/production-plans/" + PLAN_ID + "/process-sheet/row";
    private static final String DELETE_ROW_URL =
            "/api/mobile/" + FACTORY_ID + "/production-plans/" + PLAN_ID + "/process-sheet/row/ROW-001";

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();

        LocalValidatorFactoryBean validator = new LocalValidatorFactoryBean();
        validator.afterPropertiesSet();

        mockMvc = MockMvcBuilders
                .standaloneSetup(new ProcessSheetController(service))
                .setControllerAdvice(new GlobalExceptionHandler())
                .setValidator(validator)
                .build();
    }

    @Test
    @DisplayName("POST /row → service.saveRow 被调用, 响应 success:true + data")
    void saveRow_routesToService_returnsWrappedResult() throws Exception {
        ProcessSheetRowResult result = new ProcessSheetRowResult();
        result.setClientRowId("ROW-001");
        result.setBatchNumber("CLK-W-001");
        result.setMaterialized(true);
        result.setRowTotalCost(new BigDecimal("500.00"));

        when(service.saveRow(eq(FACTORY_ID), eq(PLAN_ID), any(ProcessSheetRowRequest.class), eq(USER_ID)))
                .thenReturn(result);

        ProcessSheetRowRequest req = new ProcessSheetRowRequest();
        req.setClientRowId("ROW-001");
        req.setProcessCode("xiuyou");
        req.setProcessOrder(1);
        req.setProductTypeId("PT-001");
        req.setOutputQuantity(new BigDecimal("80.0"));

        mockMvc.perform(post(SAVE_ROW_URL)
                        .contentType(MediaType.APPLICATION_JSON)
                        .requestAttr("userId", USER_ID)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.clientRowId").value("ROW-001"))
                .andExpect(jsonPath("$.data.batchNumber").value("CLK-W-001"))
                .andExpect(jsonPath("$.data.materialized").value(true));

        // Verify controller passes PATH factoryId/planId (not body values) to the service
        verify(service).saveRow(eq(FACTORY_ID), eq(PLAN_ID), any(ProcessSheetRowRequest.class), eq(USER_ID));
    }

    @Test
    @DisplayName("DELETE /row/{clientRowId} → service.deleteRow 被调用, 响应 success:true")
    void deleteRow_routesToService_returnsSuccess() throws Exception {
        // service.deleteRow is void — no stubbing needed (Mockito default is no-op for void)

        mockMvc.perform(delete(DELETE_ROW_URL)
                        .requestAttr("userId", USER_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));

        verify(service).deleteRow(eq(FACTORY_ID), eq(PLAN_ID), eq("ROW-001"), eq(USER_ID));
    }
}
