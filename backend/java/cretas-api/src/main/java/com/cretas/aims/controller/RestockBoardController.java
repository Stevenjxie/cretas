package com.cretas.aims.controller;

import com.cretas.aims.annotation.RequirePermission;
import com.cretas.aims.dto.common.ApiResponse;
import com.cretas.aims.service.restock.RestockBoardService;
import com.cretas.aims.service.restock.dto.RestockBoardDTO;
import com.cretas.aims.service.restock.dto.RestockHorizonDTO;
import com.cretas.aims.service.restock.dto.WarehouseRestockBoardDTO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.NotBlank;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;

@Slf4j
@RestController
@RequestMapping("/api/mobile/{factoryId}/restock-board")
@RequiredArgsConstructor
@Tag(name = "Restock Board", description = "Order demand vs stock, WIP, raw material coverage and shortfall")
public class RestockBoardController {

    private final RestockBoardService restockBoardService;

    @RequirePermission({"production:read", "production:read_write"})
    @GetMapping
    @Operation(summary = "Get one-day restock board")
    public ApiResponse<RestockBoardDTO> getRestockBoard(
            @PathVariable @NotBlank String factoryId,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate deliveryDate) {
        log.info("Restock board: factoryId={}, deliveryDate={}", factoryId, deliveryDate);
        return ApiResponse.success(restockBoardService.getRestockBoard(factoryId, deliveryDate));
    }

    @RequirePermission({"production:read", "production:read_write"})
    @GetMapping("/horizon")
    @Operation(summary = "Get multi-day restock horizon")
    public ApiResponse<RestockHorizonDTO> getRestockHorizon(
            @PathVariable @NotBlank String factoryId,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate) {
        log.info("Restock horizon: factoryId={}, startDate={}, endDate={}", factoryId, startDate, endDate);
        return ApiResponse.success(restockBoardService.getRestockHorizon(factoryId, startDate, endDate));
    }

    @RequirePermission({"production:read", "production:read_write"})
    @GetMapping("/by-warehouse")
    @Operation(summary = "Get one-day restock board by destination warehouse")
    public ApiResponse<WarehouseRestockBoardDTO> getRestockBoardByWarehouse(
            @PathVariable @NotBlank String factoryId,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate deliveryDate) {
        log.info("Warehouse restock board: factoryId={}, deliveryDate={}", factoryId, deliveryDate);
        return ApiResponse.success(restockBoardService.getRestockBoardByWarehouse(factoryId, deliveryDate));
    }
}
