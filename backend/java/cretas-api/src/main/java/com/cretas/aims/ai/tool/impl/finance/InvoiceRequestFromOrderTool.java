package com.cretas.aims.ai.tool.impl.finance;

import com.cretas.aims.ai.tool.AbstractBusinessTool;
import com.cretas.aims.entity.finance.InvoiceRecord;
import com.cretas.aims.service.finance.InvoiceService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 一键基于销售订单生成税率分组开票申请。
 *
 * V3 P0-3 / G1 — 客户原话 (会议 2645-2660s):
 * "一笔订单可能同时含 9% 原料 + 13% 加工费两个税项, 开票申请要自动按税率分组拆分"
 *
 * 与 InvoiceRequestTool 的区别:
 * - InvoiceRequestTool: 用户手动输入金额和税额 (单一税率场景)
 * - 本 Tool: 自动从销售订单 items 按 tax_rate 聚合分组 (多税率场景, 六扇门核心诉求)
 *
 * 触发场景:
 * - "为销售订单 SO123 一键开票"
 * - "把这单按税率开票"
 * - "9% 和 13% 分开开"
 */
@Slf4j
@Component
public class InvoiceRequestFromOrderTool extends AbstractBusinessTool {

    @Autowired
    private InvoiceService invoiceService;

    @Override
    public String getToolName() {
        return "finance_invoice_request_from_order";
    }

    @Override
    public String getDescription() {
        return "基于销售订单一键生成税率分组开票申请。系统自动按 9% 原料 + 13% 加工费等税率分组聚合, " +
                "无需手动输入金额。适用场景: '为订单一键开票'、'按税率分组开票'、'9% 和 13% 分开开'。";
    }

    @Override
    public Map<String, Object> getParametersSchema() {
        return Map.of(
                "type", "object",
                "properties", Map.of(
                        "salesOrderId", Map.of(
                                "type", "string",
                                "description", "销售订单ID"),
                        "invoiceType", Map.of(
                                "type", "string",
                                "description", "发票类型: NORMAL (普票) / SPECIAL (专票), 默认 NORMAL"),
                        "remark", Map.of(
                                "type", "string",
                                "description", "备注 (选填)")),
                "required", List.of("salesOrderId"));
    }

    @Override
    protected List<String> getRequiredParameters() {
        return List.of("salesOrderId");
    }

    @Override
    protected Map<String, Object> doExecute(String factoryId, Map<String, Object> params,
                                             Map<String, Object> context) throws Exception {
        InvoiceRecord record = invoiceService.requestInvoiceFromOrder(
                factoryId,
                getString(params, "salesOrderId"),
                getString(params, "invoiceType"),
                getLong(params, "userId"),
                getString(params, "remark"));

        Map<String, Object> result = new HashMap<>();
        result.put("invoiceNumber", record.getInvoiceNumber());
        result.put("status", record.getStatus().name());
        result.put("amount", record.getAmount());
        result.put("taxAmount", record.getTaxAmount());
        result.put("totalAmount", record.getTotalAmount());
        result.put("taxBreakdown", record.getTaxBreakdown());
        result.put("breakdownGroups",
                record.getTaxBreakdown() != null ? record.getTaxBreakdown().size() : 0);

        String message = String.format("已生成开票申请 %s, 共 %d 个税率组, 价税合计 %s",
                record.getInvoiceNumber(),
                record.getTaxBreakdown() != null ? record.getTaxBreakdown().size() : 0,
                record.getTotalAmount());

        return buildSimpleResult(message, result);
    }

    /** spec §8.2 有副作用, 须走 W0 写确认闸 */
    @Override
    public AccessMode getAccessMode() {
        return AccessMode.WRITE;
    }
}
