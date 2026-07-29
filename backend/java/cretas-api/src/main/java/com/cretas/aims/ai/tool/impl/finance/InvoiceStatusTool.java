package com.cretas.aims.ai.tool.impl.finance;

import com.cretas.aims.ai.tool.AbstractBusinessTool;
import com.cretas.aims.entity.finance.InvoiceRecord;
import com.cretas.aims.repository.InvoiceRecordRepository;
import com.cretas.aims.service.finance.InvoiceService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 开票申请状态查询 Tool — F-INV-1 gap-fill 2026-05-16
 *
 * 输入: salesOrderId 或 invoiceId (二选一)。
 * 输出: status / amount / taxBreakdown / 三个里程碑时间戳 (申请/审核/开票)。
 *
 * 用例: AIChat "查一下销售订单 SO-001 的开票进度", "INV-20260516-0001 现在到哪一步了"。
 */
@Slf4j
@Component
public class InvoiceStatusTool extends AbstractBusinessTool {

    @Autowired
    private InvoiceRecordRepository invoiceRecordRepository;

    @Autowired
    private InvoiceService invoiceService;

    @Override
    public String getToolName() {
        return "finance_invoice_status";
    }

    @Override
    public String getDescription() {
        return "查询开票申请状态 — 输入 salesOrderId 或 invoiceId,返回状态/金额/税率明细/各阶段时间戳。";
    }

    @Override
    public Map<String, Object> getParametersSchema() {
        return Map.of(
                "type", "object",
                "properties", Map.of(
                        "salesOrderId", Map.of("type", "string", "description", "销售订单ID (可选,优先级低于 invoiceId)"),
                        "invoiceId", Map.of("type", "string", "description", "开票记录ID (可选,优先级最高)")
                ),
                "required", List.of()
        );
    }

    @Override
    protected List<String> getRequiredParameters() {
        return List.of();
    }

    @Override
    protected Map<String, Object> doExecute(String factoryId, Map<String, Object> params,
                                             Map<String, Object> context) throws Exception {
        String invoiceId = getString(params, "invoiceId");
        String salesOrderId = getString(params, "salesOrderId");

        if ((invoiceId == null || invoiceId.isBlank())
                && (salesOrderId == null || salesOrderId.isBlank())) {
            throw new IllegalArgumentException("必须提供 invoiceId 或 salesOrderId");
        }

        // invoiceId 优先 — 精确查 1 条
        if (invoiceId != null && !invoiceId.isBlank()) {
            InvoiceRecord record = invoiceService.getInvoice(factoryId, invoiceId);
            return buildSimpleResult("开票记录查询完成",
                    Map.of("invoices", List.of(toSummary(record))));
        }

        // salesOrderId — 返回该订单下所有 invoice,按 createdAt desc
        List<InvoiceRecord> records = invoiceRecordRepository
                .findByFactoryIdAndSalesOrderIdAndDeletedAtIsNullOrderByCreatedAtDesc(
                        factoryId, salesOrderId);
        if (records.isEmpty()) {
            return buildSimpleResult("该销售订单暂无开票申请",
                    Map.of("invoices", List.of(), "salesOrderId", salesOrderId));
        }
        List<Map<String, Object>> summaries = new ArrayList<>();
        for (InvoiceRecord r : records) {
            summaries.add(toSummary(r));
        }
        summaries.sort(Comparator.comparing(m -> ((String) m.get("createdAt")),
                Comparator.nullsLast(Comparator.reverseOrder())));
        return buildSimpleResult(
                String.format("找到 %d 条开票记录", records.size()),
                Map.of("invoices", summaries, "salesOrderId", salesOrderId));
    }

    /**
     * Convert InvoiceRecord to LLM-friendly summary.
     * LinkedHashMap 保 key order 稳定输出。
     */
    private Map<String, Object> toSummary(InvoiceRecord r) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("invoiceId", r.getId());
        m.put("invoiceNumber", r.getInvoiceNumber());
        m.put("salesOrderId", r.getSalesOrderId());
        m.put("customerName", r.getCustomerName());
        m.put("status", r.getStatus() != null ? r.getStatus().name() : null);
        m.put("invoiceType", r.getInvoiceType() != null ? r.getInvoiceType().name() : null);
        m.put("amount", r.getAmount());
        m.put("taxAmount", r.getTaxAmount());
        m.put("totalAmount", r.getTotalAmount());
        m.put("taxBreakdown", r.getTaxBreakdown());
        m.put("requestedAt", r.getRequestedAt() != null ? r.getRequestedAt().toString() : null);
        m.put("reviewedAt", r.getReviewedAt() != null ? r.getReviewedAt().toString() : null);
        m.put("issuedAt", r.getIssuedAt() != null ? r.getIssuedAt().toString() : null);
        m.put("reviewNotes", r.getReviewNotes());
        m.put("invoicePdfUrl", r.getInvoicePdfUrl());
        m.put("ocrInvoiceNumber", r.getOcrInvoiceNumber());
        m.put("ocrAmount", r.getOcrAmount());
        m.put("ocrConfidence", r.getOcrConfidence());
        m.put("ocrErrorMessage", r.getOcrErrorMessage());
        m.put("createdAt", r.getCreatedAt() != null ? r.getCreatedAt().toString() : null);
        return m;
    }

    /** spec §8.2 只读查询, 无副作用 */
    @Override
    public AccessMode getAccessMode() {
        return AccessMode.READ;
    }
}
