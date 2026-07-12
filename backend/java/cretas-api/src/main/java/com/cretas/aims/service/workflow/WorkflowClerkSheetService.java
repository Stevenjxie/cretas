package com.cretas.aims.service.workflow;

import com.cretas.aims.dto.workflow.WorkflowClerkSheetConfigDTO;

/**
 * 2B Task B2: 把生产计划关联的 workflow 批次快照投影为文员过程单可消费的配置。
 */
public interface WorkflowClerkSheetService {

    /**
     * @param factoryId 工厂 ID (租户隔离)
     * @param planId    生产计划 ID
     * @return 该计划的 workflow 过程单配置; 计划没有 workflow 批次 (legacy 计划) 时返回 {@code null}
     */
    WorkflowClerkSheetConfigDTO getWorkflowSheetConfig(String factoryId, String planId);
}
