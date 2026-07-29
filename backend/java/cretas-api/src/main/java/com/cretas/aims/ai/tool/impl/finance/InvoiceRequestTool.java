package com.cretas.aims.ai.tool.impl.finance;

import com.cretas.aims.ai.tool.AbstractBusinessTool;
import com.cretas.aims.service.finance.InvoiceService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

@Slf4j
@Component
public class InvoiceRequestTool extends AbstractBusinessTool {

    @Autowired
    private InvoiceService invoiceService;

    @Override
    public String getToolName() { return "finance_invoice_request"; }

    @Override
    public String getDescription() { return "提交开票申请，关联销售订单"; }

    @Override
    public Map<String, Object> getParametersSchema() {
        return Map.of("type", "object", "properties", Map.of(
                "salesOrderId", Map.of("type", "string", "description", "销售订单ID"),
                "amount", Map.of("type", "number", "description", "不含税金额"),
                "taxAmount", Map.of("type", "number", "description", "税额"),
                "invoiceType", Map.of("type", "string", "description", "发票类型: NORMAL/SPECIAL"),
                "remark", Map.of("type", "string", "description", "备注")
        ), "required", List.of("salesOrderId", "amount"));
    }

    @Override
    protected List<String> getRequiredParameters() { return List.of("salesOrderId", "amount"); }

    @Override
    protected Map<String, Object> doExecute(String factoryId, Map<String, Object> params, Map<String, Object> context) throws Exception {
        var record = invoiceService.requestInvoice(
                factoryId,
                getString(params, "salesOrderId"),
                new BigDecimal(params.get("amount").toString()),
                params.get("taxAmount") != null ? new BigDecimal(params.get("taxAmount").toString()) : BigDecimal.ZERO,
                getString(params, "invoiceType"),
                getLong(params, "userId"),
                getString(params, "remark"));
        return buildSimpleResult("开票申请已提交", Map.of(
                "invoiceNumber", record.getInvoiceNumber(),
                "status", record.getStatus().name(),
                "totalAmount", record.getTotalAmount()));
    }

    /** spec §8.2 有副作用, 须走 W0 写确认闸 */
    @Override
    public AccessMode getAccessMode() {
        return AccessMode.WRITE;
    }
}
