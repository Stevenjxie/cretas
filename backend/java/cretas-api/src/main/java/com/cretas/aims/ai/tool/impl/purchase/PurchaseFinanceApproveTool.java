package com.cretas.aims.ai.tool.impl.purchase;

import com.cretas.aims.ai.tool.AbstractBusinessTool;
import com.cretas.aims.exception.BusinessException;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

@Component
public class PurchaseFinanceApproveTool extends AbstractBusinessTool {

    @Override
    public String getToolName() { return "purchase_finance_approve"; }

    @Override
    public String getDescription() {
        return "采购财务审批的旧兼容入口，仅提示用户前往工作台的待我审批；不执行通过或驳回";
    }

    @Override
    public Map<String, Object> getParametersSchema() {
        return Map.of("type", "object", "properties", Map.of());
    }

    @Override
    protected List<String> getRequiredParameters() { return List.of(); }

    @Override
    protected Map<String, Object> doExecute(String factoryId, Map<String, Object> params, Map<String, Object> context) throws Exception {
        throw oaOnly();
    }

    /**
     * 保留旧 tool name 是为了让生产中已有的 intent 配置得到明确、可行动的 OA 引导，
     * 而不是变成 tool-not-found。等运行时配置和历史迁移都不再引用
     * {@code purchase_finance_approve} 后，连同 descriptor 一并删除。
     */
    static BusinessException oaOnly() {
        return new BusinessException(410,
                "采购财务审批只能在工作台 → 待我审批中处理；AI 不执行通过或驳回")
                .withCode("PURCHASE_APPROVAL_OA_ONLY")
                .withHint("请打开工作台 → 待我审批，并在当前 OA 节点完成审批");
    }

    /**
     * 兼容入口自身不执行业务写，但旧 tool name 含 approve；按全局治理规则继续走
     * W0 写确认并保持最保守的权限语义。删除旧运行时 intent 后连同本 sentinel 一并删除。
     */
    @Override
    public AccessMode getAccessMode() {
        return AccessMode.WRITE;
    }
}
