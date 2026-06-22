package com.cretas.aims.dto.processentry;

import lombok.Data;
import java.util.List;
import java.util.Map;

/**
 * 文员逐道录入结果 DTO. SP-B1 Task 3.
 */
@Data
public class ProcessChainEntryResult {
    /** true = 命中幂等缓存，本次未重复写入 */
    private boolean idempotentReplay;
    /** clientBatchKey → 生成/找到的 ProductionBatch.id */
    private Map<String, Long> batchIdsByKey;
    /** clientBatchKey → 生成/找到的 batchNumber */
    private Map<String, String> batchNumbersByKey;
    /** 成品批号 (供 SP-C 按批次号查核算) */
    private String finishedBatchNumber;
    /** 写入的 MaterialConsumption 行数 */
    private int consumptionsWritten;
    /** 物化的半成品 MaterialBatch 数 */
    private int wipBatchesMaterialized;
    /** 非致命警告 (如: 调料配方缺失) */
    private List<String> warnings;
}
