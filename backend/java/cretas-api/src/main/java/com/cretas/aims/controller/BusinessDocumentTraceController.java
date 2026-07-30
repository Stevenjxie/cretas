package com.cretas.aims.controller;

import com.cretas.aims.annotation.RequirePermission;
import com.cretas.aims.dto.common.ApiResponse;
import com.cretas.aims.dto.trace.BusinessDocumentTraceResponse;
import com.cretas.aims.service.trace.BusinessDocumentTraceService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.NotBlank;
import lombok.RequiredArgsConstructor;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 销售 / 采购 / 调拨 单据追踪。
 *
 * <p>权限口径 = <b>各自锚点单据「详情」接口的注解原样照抄</b>, 不新开也不收紧:
 * <ul>
 *   <li>销售订单 — {@code SalesController#getOrder} 用 {@code sales:read_write | sales:read}</li>
 *   <li>采购订单 — {@code PurchaseController#getOrder} 用 {@code procurement:read_write | procurement:read}</li>
 *   <li>调拨单 — {@code TransferController#getTransfer} 用 {@code inventory:write | inventory:read}</li>
 * </ul>
 * 于是"能打开这张单"⇔"能看这张单的追踪", 不会出现某条线能进详情却在追踪按钮上吃 403。
 * 追踪结果里出现的跨模块单据(如销售单里的采购单号)沿用 {@code ProductionDocumentTraceController}
 * 已有口径 —— 按锚点单据鉴权, 不叠加下游模块权限。
 */
@Validated
@RestController
@RequestMapping("/api/mobile/{factoryId}")
@RequiredArgsConstructor
@Tag(name = "单据追踪", description = "按销售/采购/调拨单追踪真实关联业务单据")
public class BusinessDocumentTraceController {

    private final BusinessDocumentTraceService traceService;

    @GetMapping("/sales/orders/{orderId}/document-trace")
    @RequirePermission({"sales:read_write", "sales:read"})
    @Operation(summary = "销售订单关联单据追踪")
    public ApiResponse<BusinessDocumentTraceResponse> traceSalesOrder(
            @PathVariable @NotBlank String factoryId,
            @PathVariable @NotBlank String orderId) {
        return ApiResponse.success("查询成功", traceService.traceSalesOrder(factoryId, orderId));
    }

    @GetMapping("/purchase/orders/{orderId}/document-trace")
    // 必须与 PurchaseController#getOrder (采购单**详情**) 逐字相同 —— 追踪抽屉就开在那张详情页里,
    // 权限比它窄就会出现「页面打得开、抽屉一开就 403」。详情页多出的 warehouse:* 是有理由的:
    // 仓库员选中待收货采购单后, RN 的 WHReceiptCreateScreen 用它拉明细预填收货行。
    // ⚠️ 别照 PurchaseController#listOrders (行 113) 抄 —— 那是**列表**接口, 只有 procurement:*。
    @RequirePermission({"procurement:read_write", "procurement:read", "warehouse:read_write", "warehouse:read"})
    @Operation(summary = "采购订单关联单据追踪")
    public ApiResponse<BusinessDocumentTraceResponse> tracePurchaseOrder(
            @PathVariable @NotBlank String factoryId,
            @PathVariable @NotBlank String orderId) {
        return ApiResponse.success("查询成功", traceService.tracePurchaseOrder(factoryId, orderId));
    }

    @GetMapping("/transfers/{transferId}/document-trace")
    @RequirePermission({"inventory:write", "inventory:read"})
    @Operation(summary = "调拨单关联单据追踪")
    public ApiResponse<BusinessDocumentTraceResponse> traceInternalTransfer(
            @PathVariable @NotBlank String factoryId,
            @PathVariable @NotBlank String transferId) {
        return ApiResponse.success("查询成功", traceService.traceInternalTransfer(factoryId, transferId));
    }
}
