package com.cretas.aims.ai.tool.impl.finance;

import com.cretas.aims.ai.tool.AbstractBusinessTool;
import com.cretas.aims.service.finance.InvoiceService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

@Slf4j
@Component
public class InvoiceApproveTool extends AbstractBusinessTool {

    @Autowired
    private InvoiceService invoiceService;

    @Override
    public String getToolName() { return "finance_invoice_approve"; }

    @Override
    public String getDescription() { return "审核通过开票申请"; }

    @Override
    public Map<String, Object> getParametersSchema() {
        return Map.of("type", "object", "properties", Map.of(
                "invoiceId", Map.of("type", "string", "description", "开票记录ID"),
                "notes", Map.of("type", "string", "description", "审核意见")
        ), "required", List.of("invoiceId"));
    }

    @Override
    protected List<String> getRequiredParameters() { return List.of("invoiceId"); }

    @Override
    protected Map<String, Object> doExecute(String factoryId, Map<String, Object> params, Map<String, Object> context) throws Exception {
        var record = invoiceService.approveInvoice(
                factoryId,
                getString(params, "invoiceId"),
                getLong(params, "userId"),
                getString(params, "notes"));
        return buildSimpleResult("开票申请已审核通过", Map.of(
                "invoiceNumber", record.getInvoiceNumber(),
                "status", record.getStatus().name()));
    }

    /** spec §8.2 有副作用, 须走 W0 写确认闸 */
    @Override
    public AccessMode getAccessMode() {
        return AccessMode.WRITE;
    }
}
