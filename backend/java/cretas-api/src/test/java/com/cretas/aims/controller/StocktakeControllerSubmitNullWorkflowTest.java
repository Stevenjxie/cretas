package com.cretas.aims.controller;

import com.cretas.aims.dto.common.ApiResponse;
import com.cretas.aims.service.factory.FactoryStocktakeService;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * 追踪码 C51560EB (客户 2026-07-29 11:35 按 SOP 录入期初原料库存提交审批失败) 回归测试.
 *
 * <p>根因: 工厂未配置 {@code INVENTORY_ADJUSTMENT} 审批流时
 * {@code FactoryStocktakeServiceImpl#submitForApproval} 走"直接通过"分支, 不产生 OA 实例,
 * 返回 null. 控制器旧代码 {@code Map.of("workflowInstanceId", null)} 抛 NPE.
 *
 * <p>危险之处在于这是一次<b>假失败</b>: service 的事务已提交 (线上 ST-202607-576D6E70
 * 状态 APPLIED, approved_at=11:35:54.838), 盘点其实已生效, 用户却收到"系统处理异常，请稍后重试".
 *
 * <p>线上证据: F006 的 approval_workflows 里只有 QUALITY_RELEASE / PURCHASE_ORDER_APPROVAL /
 * EXPENSE_APPROVAL / SALES_ORDER_APPROVAL, 没有 INVENTORY_ADJUSTMENT → 该工厂每次提交必现,
 * 并非偶发.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("StocktakeController.submit 无审批流工厂 (追踪码 C51560EB)")
class StocktakeControllerSubmitNullWorkflowTest {

    private static final String FACTORY_ID = "F006";
    private static final String STOCKTAKE_ID = "5ef78231-25c9-4b11-a00f-61182925b8c2";

    @Mock
    private FactoryStocktakeService stocktakeService;
    @Mock
    private HttpServletRequest request;

    @InjectMocks
    private StocktakeController controller;

    @Test
    @DisplayName("未配置审批流 → 返回成功并说明已直接通过, 不抛 NPE")
    void submit_whenNoWorkflowConfigured_shouldSucceedWithoutNpe() {
        when(request.getAttribute("userId")).thenReturn(1309L);
        when(stocktakeService.submitForApproval(anyString(), anyString(), anyLong()))
                .thenReturn(null);   // 无 OA 配置 → 直接通过分支, 无实例 id

        ApiResponse<Map<String, String>> res =
                controller.submit(FACTORY_ID, STOCKTAKE_ID, request);

        assertNotNull(res, "不得抛 NPE");
        assertTrue(res.getSuccess(), "盘点已生效, 响应必须是成功而不是系统异常");
        assertFalse(res.getData().containsKey("workflowInstanceId"),
                "没有 OA 实例时不应伪造 workflowInstanceId 键");
        assertEquals("false", res.getData().get("approvalRequired"),
                "应如实告知前端本次无需 OA 审批");
        assertTrue(res.getMessage().contains("直接通过"),
                "提示语必须说明是直接通过而非已提交审批, 实际: " + res.getMessage());
    }

    @Test
    @DisplayName("已配置审批流 → 照旧回传 workflowInstanceId 供前端跳转审批中心")
    void submit_whenWorkflowConfigured_shouldReturnInstanceId() {
        when(request.getAttribute("userId")).thenReturn(1309L);
        when(stocktakeService.submitForApproval(anyString(), anyString(), anyLong()))
                .thenReturn("wf-instance-001");

        ApiResponse<Map<String, String>> res =
                controller.submit(FACTORY_ID, STOCKTAKE_ID, request);

        assertTrue(res.getSuccess());
        assertEquals("wf-instance-001", res.getData().get("workflowInstanceId"));
        assertEquals("true", res.getData().get("approvalRequired"));
        assertTrue(res.getMessage().contains("已提交 OA 审批"));
    }
}
