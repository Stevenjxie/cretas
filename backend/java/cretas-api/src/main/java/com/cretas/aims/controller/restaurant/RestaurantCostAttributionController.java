package com.cretas.aims.controller.restaurant;

import com.cretas.aims.annotation.RequireModule;
import com.cretas.aims.annotation.RequirePermission;
import com.cretas.aims.dto.common.ApiResponse;
import com.cretas.aims.dto.restaurant.RestaurantCostAttributionSummary;
import com.cretas.aims.service.restaurant.RestaurantCostAttributionService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;

@RestController
@RequestMapping("/api/mobile/{factoryId}/restaurant/cost-attribution")
@RequiredArgsConstructor
@Tag(name = "Restaurant cost attribution")
public class RestaurantCostAttributionController {

    private final RestaurantCostAttributionService restaurantCostAttributionService;

    @RequireModule("restaurant")
    @RequirePermission({"procurement:price:view", "finance:read", "finance:read_write"})
    @GetMapping("/summary")
    @Operation(summary = "Restaurant material cost attribution summary")
    public ApiResponse<RestaurantCostAttributionSummary> summary(
            @PathVariable String factoryId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate) {
        LocalDate end = endDate != null ? endDate : LocalDate.now();
        LocalDate start = startDate != null ? startDate : end.withDayOfMonth(1);
        return ApiResponse.success(restaurantCostAttributionService.getSummary(factoryId, start, end));
    }
}
