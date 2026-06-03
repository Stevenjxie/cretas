package com.cretas.aims.controller;

import com.cretas.aims.annotation.RequirePermission;
import com.cretas.aims.dto.common.ApiResponse;
import com.cretas.aims.service.restock.RestockBoardService;
import com.cretas.aims.service.restock.dto.RestockBoardDTO;
import com.cretas.aims.service.restock.dto.WarehouseRestockBoardDTO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.NotBlank;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;

/** 全天备货看板 — 订单需求 vs 可用结存 vs 缺口对账 (只读)。 */
@Slf4j
@RestController
@RequestMapping("/api/mobile/{factoryId}/restock-board")
@RequiredArgsConstructor
@Tag(name = "备货看板", description = "全天备货看板: 订单需求 vs 可用结存 vs 生产缺口")
public class RestockBoardController {

    private final RestockBoardService restockBoardService;

    @RequirePermission({"production:read", "production:read_write"})
    @GetMapping
    @Operation(summary = "获取某交货日备货看板", description = "按产品聚合: 需求/成品可用/在产估/已排产/缺口")
    public ApiResponse<RestockBoardDTO> getRestockBoard(
            @PathVariable @NotBlank String factoryId,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate deliveryDate) {
        log.info("备货看板: factoryId={}, deliveryDate={}", factoryId, deliveryDate);
        return ApiResponse.success(restockBoardService.getRestockBoard(factoryId, deliveryDate));
    }

    /**
     * P3 多仓备货看板 — 按产品 × 目的仓拆分需求, 库存三层仍为全厂共享池。
     *
     * <p>向后兼容: 旧订单 (无目的仓) 归入"未分仓"桶, 等效产品级结果。
     *
     * <p>端点签名: GET /api/mobile/{factoryId}/restock-board/by-warehouse?deliveryDate=YYYY-MM-DD
     *
     * @param factoryId    工厂 ID
     * @param deliveryDate 要求交货日期
     * @return 按产品 × 仓分组的备货看板; rows 带 destWarehouseCode/Name + warehouseDemandQty
     */
    @RequirePermission({"production:read", "production:read_write"})
    @GetMapping("/by-warehouse")
    @Operation(
        summary = "P3 多仓备货看板",
        description = "按产品 × 目的仓展开需求; 库存三层(FG/WIP/已排产)仍为全厂共享池。" +
                      "旧订单(无目的仓)归入'未分仓'桶. 标注: warehouseDemandQty=该仓需求, " +
                      "totalAvailableQty=全厂可用.")
    public ApiResponse<WarehouseRestockBoardDTO> getRestockBoardByWarehouse(
            @PathVariable @NotBlank String factoryId,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate deliveryDate) {
        log.info("多仓备货看板: factoryId={}, deliveryDate={}", factoryId, deliveryDate);
        return ApiResponse.success(restockBoardService.getRestockBoardByWarehouse(factoryId, deliveryDate));
    }
}
