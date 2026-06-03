package com.cretas.aims.controller;

import com.cretas.aims.annotation.RequirePermission;
import com.cretas.aims.dto.common.ApiResponse;
import com.cretas.aims.service.restock.RestockBoardService;
import com.cretas.aims.service.restock.dto.RestockBoardDTO;
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
}
