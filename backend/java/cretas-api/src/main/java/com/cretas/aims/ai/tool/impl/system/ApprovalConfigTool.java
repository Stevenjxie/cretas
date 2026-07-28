package com.cretas.aims.ai.tool.impl.system;

import com.cretas.aims.ai.tool.AbstractBusinessTool;
import com.cretas.aims.entity.config.ApprovalChainConfig;
import com.cretas.aims.service.ApprovalChainService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * 审批流程配置工具
 *
 * 查询和配置采购审批流程。
 * Intent Code: APPROVAL_CONFIG_PURCHASE_ORDER
 *
 * @author Cretas Team
 * @version 1.0.0
 * @since 2026-03-07
 */
@Slf4j
@Component
public class ApprovalConfigTool extends AbstractBusinessTool {

    @Autowired
    private ApprovalChainService approvalChainService;

    @Override
    public String getToolName() {
        return "approval_config";
    }

    @Override
    public String getDescription() {
        return "查询和配置采购审批流程。支持查看当前审批链配置、审批级别和审批角色。" +
                "适用场景：查看采购审批流程、配置审批链、管理审批角色分配。";
    }

    @Override
    public Map<String, Object> getParametersSchema() {
        Map<String, Object> schema = new HashMap<>();
        schema.put("type", "object");

        Map<String, Object> properties = new HashMap<>();

        Map<String, Object> decisionType = new HashMap<>();
        decisionType.put("type", "string");
        decisionType.put("description", "审批决策类型");
        decisionType.put("enum", Arrays.asList("PURCHASE_ORDER", "SUPPLIER_APPROVAL", "PRODUCTION_PLAN"));
        properties.put("decisionType", decisionType);

        schema.put("properties", properties);
        schema.put("required", Collections.emptyList());

        return schema;
    }

    @Override
    protected List<String> getRequiredParameters() {
        return Collections.emptyList();
    }

    @Override
    protected Map<String, Object> doExecute(String factoryId, Map<String, Object> params, Map<String, Object> context) throws Exception {
        try {
            String decisionTypeStr = getString(params, "decisionType", null);

            List<ApprovalChainConfig> configs;
            if (decisionTypeStr != null && !decisionTypeStr.isEmpty()) {
                ApprovalChainConfig.DecisionType decisionType = null;
                // Map tool-facing enum names to actual DecisionType values
                switch (decisionTypeStr) {
                    case "PURCHASE_ORDER" -> decisionType = ApprovalChainConfig.DecisionType.CUSTOM;
                    case "SUPPLIER_APPROVAL" -> decisionType = ApprovalChainConfig.DecisionType.SUPPLIER_APPROVAL;
                    case "PRODUCTION_PLAN" -> decisionType = ApprovalChainConfig.DecisionType.PRODUCTION_PLAN_CHANGE;
                    default -> {
                        try {
                            decisionType = ApprovalChainConfig.DecisionType.valueOf(decisionTypeStr);
                        } catch (IllegalArgumentException ignored) {
                            log.warn("未知的决策类型: {}, 查询所有配置", decisionTypeStr);
                        }
                    }
                }
                if (decisionType != null) {
                    configs = approvalChainService.getConfigsByDecisionType(factoryId, decisionType);
                } else {
                    configs = approvalChainService.getAllConfigs(factoryId);
                }
            } else {
                configs = approvalChainService.getAllConfigs(factoryId);
            }

            Map<String, Object> result = new HashMap<>();
            result.put("total", configs.size());
            result.put("configs", configs);
            result.put("factoryId", factoryId);
            if (decisionTypeStr != null) {
                result.put("decisionType", decisionTypeStr);
            }
            return buildSimpleResult("查询审批链配置成功，共 " + configs.size() + " 条记录", result);
        } catch (Exception e) {
            log.error("查询审批链配置失败 - 工厂ID: {}", factoryId, e);
            throw e;
        }
    }

    /** spec §8.2 只读查询, 无副作用 */
    @Override
    public AccessMode getAccessMode() {
        return AccessMode.READ;
    }
}
