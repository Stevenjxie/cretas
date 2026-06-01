package com.cretas.aims.service.yield;

import com.cretas.aims.dto.yield.ProcessYieldAggDTO;

import java.time.LocalDate;
import java.util.List;

public interface YieldAnalysisService {
    /** 厂级按工序聚合出成率. start/end/productTypeId 可为 null (service 内转 sentinel). */
    List<ProcessYieldAggDTO> aggregateByProcess(String factoryId, LocalDate startDate, LocalDate endDate, String productTypeId);
}
