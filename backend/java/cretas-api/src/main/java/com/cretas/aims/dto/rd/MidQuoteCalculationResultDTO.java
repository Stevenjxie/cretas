package com.cretas.aims.dto.rd;

import com.cretas.aims.entity.rd.ProductMidQuote;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 中报价计算结果 DTO.
 *
 * <p>包含汇算后的中报价实体 + 数据缺失 warnings.</p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MidQuoteCalculationResultDTO {

    /** 汇算完成的中报价实体 */
    private ProductMidQuote midQuote;

    /**
     * 数据质量 warnings.
     * 非空 = 部分成本数据缺失, totalCostPerKg 可能为 null.
     */
    @Builder.Default
    private List<String> warnings = java.util.Collections.emptyList();
}
