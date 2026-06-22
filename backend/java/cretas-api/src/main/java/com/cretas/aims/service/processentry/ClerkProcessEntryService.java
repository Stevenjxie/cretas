package com.cretas.aims.service.processentry;

import com.cretas.aims.dto.processentry.ProcessChainEntryRequest;
import com.cretas.aims.dto.processentry.ProcessChainEntryResult;

/**
 * 文员逐道录入服务接口. SP-B1 Task 3.
 *
 * <p>接收一条生产链(多个半成品批 + 1 成品批)的文员录入负载,
 * 物化成本图: 每道工序的原料/调料/混锅消耗 → MaterialConsumption 行,
 * 半成品产出 → MaterialBatch(sourceDocType=PRODUCTION_BATCH), 供 OrderCostBreakdownService.traceCost 回溯。
 */
public interface ClerkProcessEntryService {
    /**
     * 录入一条生产链并物化成本图。幂等(idempotencyKey per factoryId+planId)。
     *
     * @param factoryId  工厂 ID
     * @param planId     生产计划 ID (可空, 无计划时传 null)
     * @param req        逐道录入请求
     * @param operatorId 文员 token userId (⛔ 禁用 SecurityUtils 取, 从 controller @RequestAttribute 注入)
     * @return 录入结果 (含批次号 + 统计 + 幂等标记)
     */
    ProcessChainEntryResult recordChain(String factoryId, String planId,
                                        ProcessChainEntryRequest req, Long operatorId);
}
