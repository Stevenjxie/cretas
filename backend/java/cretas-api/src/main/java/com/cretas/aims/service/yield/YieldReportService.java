package com.cretas.aims.service.yield;

import com.cretas.aims.dto.yield.BatchYieldDTO;
import com.cretas.aims.dto.yield.MaterialInputRequest;
import com.cretas.aims.dto.yield.YieldReportRequest;

import java.time.LocalDate;
import java.util.Map;

public interface YieldReportService {

    /** 工人逐道报工 (投入+产出双量). 返回 {reportId, yieldRate, alert?} */
    Map<String, Object> submitReport(String factoryId, Long batchId, Long workerId, YieldReportRequest req);

    /** 领料环节记 出库量+投料量 (首道). 返回 {reportId} */
    Map<String, Object> recordMaterialInput(String factoryId, Long batchId, Long workerId, MaterialInputRequest req);

    /** 整批出成率 (派生). */
    BatchYieldDTO getYield(String factoryId, Long batchId);

    /**
     * 人工标记每日结清. triggerComplete=true 时(末道结清/整批完成)聚合末道产出回写批次并入成品库.
     * 返回 {settledCount, batchYield, completed}
     */
    Map<String, Object> settleDay(String factoryId, Long batchId, Long workerId, LocalDate date, boolean triggerComplete);
}
