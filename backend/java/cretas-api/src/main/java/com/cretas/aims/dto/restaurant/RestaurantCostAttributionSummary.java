package com.cretas.aims.dto.restaurant;

import com.cretas.aims.security.PriceSensitive;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.util.List;

@Data
@AllArgsConstructor
@NoArgsConstructor
public class RestaurantCostAttributionSummary {

    private String startDate;
    private String endDate;
    @PriceSensitive
    private BigDecimal totalCost;
    private Long totalCount;
    private List<Bucket> bySource;
    private List<Bucket> bySection;
    private List<Bucket> byStall;
    private List<Bucket> byPerson;
    private List<Bucket> byChef;

    @Data
    @AllArgsConstructor
    @NoArgsConstructor
    public static class Bucket {
        private String key;
        private String label;
        private Long count;
        private BigDecimal totalQuantity;
        @PriceSensitive
        private BigDecimal totalCost;
    }
}
