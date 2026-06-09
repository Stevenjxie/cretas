package com.cretas.aims.dto.inventory;

import com.cretas.aims.security.PriceSensitive;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.util.List;

/**
 * SP11: 进销存台账查询响应.
 *
 * <p>金额字段标 @PriceSensitive — PriceFieldResponseAdvice 对非财务角色自动 null.
 * 红线 R1: openingAmount / closingAmount / movingAvgUnitPrice 必须标注.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class InventoryLedgerDTO {

    private String factoryId;
    private String startDate;
    private String endDate;

    /** 每种物料一行 */
    private List<InventoryLedgerLineDTO> lines;
}
