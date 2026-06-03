package com.cretas.aims.service.restock.dto;

import lombok.Builder;
import lombok.Data;
import java.time.LocalDate;
import java.util.List;

/** 某交货日的备货看板。 */
@Data
@Builder
public class RestockBoardDTO {
    private LocalDate deliveryDate;
    private List<RestockRow> rows;
    private Summary summary;

    @Data
    @Builder
    public static class Summary {
        private int totalProducts;
        private int shortfallProducts;
        private int fullySatisfiedProducts;
    }
}
