package com.cretas.aims.service.yield;

import com.cretas.aims.dto.yield.BatchYieldDTO;
import com.cretas.aims.dto.yield.StepYieldDTO;
import com.cretas.aims.entity.ProductionReport;

import java.math.BigDecimal;
import java.util.List;

public interface YieldCalculationService {

    /** 按工序分组算单工序出成率 + carryover (纯函数). reports 须为同一批次的 YIELD 报工. */
    List<StepYieldDTO> calculateSteps(List<ProductionReport> reports);

    /**
     * 整批出成率. standardGramsPerUnit: 末道产出单位 != 首道投入单位时, 末道每单位折算克重 (盒→kg);
     * 同单位传 null.
     */
    BatchYieldDTO calculateBatchYield(List<ProductionReport> reports, BigDecimal standardGramsPerUnit);
}
