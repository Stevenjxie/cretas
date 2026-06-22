package com.cretas.aims.service.recipe;

import lombok.AllArgsConstructor;
import lombok.Data;
import java.math.BigDecimal;

/** 调料成本算法结果(绝对¥ + per-kg 速率, 供展示/引擎). */
@Data
@AllArgsConstructor
public class SeasoningCost {
    private BigDecimal injectionCostPerKg;   // 注射/kg
    private BigDecimal cookingFullCostPerKg; // 熟制全量/kg
    private BigDecimal injectionTotal;       // 注射总¥
    private BigDecimal cookingTotal;         // 熟制总¥(含锅序)
    private BigDecimal total;                // 合计¥
}
