package com.cretas.aims.ai.tool.impl.report;

import com.cretas.aims.ai.tool.AbstractBusinessTool;
import com.cretas.aims.entity.config.ApprovalChainConfig;
import com.cretas.aims.service.ApprovalChainService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * 审批记录查询 Tool
 *
 * 查询审批链配置和统计信息。
 * 对应意图: QUERY_APPROVAL_RECORD
 *
 * @author Cretas Team
 * @version 1.0.0
 * @since 2026-03-07
 */
@Slf4j
@Component
public class ReportApprovalRecordTool extends AbstractBusinessTool {

    @Autowired
    private ApprovalChainService approvalChainService;

    @Override
    public String getToolName() {
        return "report_approval_record";
    }

    @Override
    public String getDescription() {
        return "查询审批记录概览，包含审批链配置和各类型统计。" +
                "适用场景：审批记录查询、审批流程统计。";
    }

    @Override
    public Map<String, Object> getParametersSchema() {
        Map<String, Object> schema = new HashMap<>();
        schema.put("type", "object");
        schema.put("properties", Collections.emptyMap());
        schema.put("required", Collections.emptyList());
        return schema;
    }

    @Override
    protected List<String> getRequiredParameters() {
        return Collections.emptyList();
    }

    @Override
    protected Map<String, Object> doExecute(String factoryId, Map<String, Object> params, Map<String, Object> context) throws Exception {
        log.info("执行审批记录查询 - 工厂ID: {}", factoryId);

        List<ApprovalChainConfig> configs = approvalChainService.getAllConfigs(factoryId);
        Map<ApprovalChainConfig.DecisionType, Long> stats = approvalChainService.getConfigStatistics(factoryId);

        Map<String, Object> result = new HashMap<>();
        result.put("configs", configs);
        result.put("statistics", stats);
        result.put("totalConfigs", configs.size());

        StringBuilder sb = new StringBuilder();
        sb.append("审批记录概览\n");
        sb.append("审批链配置: ").append(configs.size()).append("条\n");
        stats.forEach((type, count) ->
                sb.append("  ").append(type.name()).append(": ").append(count).append("条\n"));

        result.put("message", sb.toString().trim());

        return result;
    }

    /** spec §8.2 只读查询, 无副作用 */
    @Override
    public AccessMode getAccessMode() {
        return AccessMode.READ;
    }
}
