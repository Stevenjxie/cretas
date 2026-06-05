package com.cretas.aims.service.restaurant;

import com.cretas.aims.dto.restaurant.RestaurantCostAttributionSummary;

import java.time.LocalDate;

public interface RestaurantCostAttributionService {

    RestaurantCostAttributionSummary getSummary(String factoryId, LocalDate startDate, LocalDate endDate);
}
