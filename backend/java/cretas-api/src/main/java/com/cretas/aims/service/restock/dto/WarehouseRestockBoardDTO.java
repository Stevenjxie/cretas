package com.cretas.aims.service.restock.dto;

import lombok.Builder;
import lombok.Data;
import java.time.LocalDate;
import java.util.List;

/**
 * P3 多仓备货看板 — 某交货日按产品 × 目的仓展开的需求 vs 全厂供给对账。
 *
 * <p>注意: 库存三层 (FG/WIP/已排产) 为全厂共享池, 仅需求侧按目的仓拆分。
 * 客户端展示应标注"该仓需求 / 全厂可用"以防误读。
 */
@Data
@Builder
public class WarehouseRestockBoardDTO {
    private LocalDate deliveryDate;
    private List<WarehouseRestockRow> rows;
    private Summary summary;

    @Data
    @Builder
    public static class Summary {
        /** 总行数 (产品 × 目的仓 组合数)。 */
        private int totalRows;
        /** 存在缺口的行数。 */
        private int shortfallRows;
        /** 满足的行数。 */
        private int satisfiedRows;
        /** 涉及的不同目的仓数量 (含"未分仓"桶)。 */
        private long warehouseCount;
    }
}
