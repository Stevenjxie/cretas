package com.cretas.aims.service.restock.dto;

import lombok.Builder;
import lombok.Data;

import java.time.LocalDate;
import java.util.List;

@Data
@Builder
public class RestockHorizonDTO {
    private LocalDate startDate;
    private LocalDate endDate;
    private List<LocalDate> dates;
    private List<RestockHorizonProductRow> rows;
    private Summary summary;

    @Data
    @Builder
    public static class Summary {
        private int totalProducts;
        private int shortfallProducts;
        private int fullyCoveredProducts;
        private int days;
    }
}
