package com.cretas.aims.service.finance;

import com.cretas.aims.entity.finance.InvoiceRecord;
import com.cretas.aims.entity.enums.InvoiceStatus;
import com.cretas.aims.entity.enums.InvoiceType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.web.multipart.MultipartFile;

public interface InvoiceService {

    InvoiceRecord requestInvoice(String factoryId, String salesOrderId, java.math.BigDecimal amount,
                                  java.math.BigDecimal taxAmount, String invoiceType, Long requestedBy, String remark);

    /**
     * 基于销售订单自动按税率分组创建开票申请。
     *
     * 业务背景 (V3 P0-3 / G1 — 客户原话 2645-2660s):
     * 一笔销售订单可同时含 9% 原料 + 13% 加工费两个税项,
     * 开票必须按税率分组拆分显示,财务才能正确开票。
     *
     * 实现逻辑:
     * 1. 加载 SalesOrderItem 列表
     * 2. 按 taxRate 分组 (null tax_rate 视为 0%)
     * 3. 每组聚合 taxableAmount + taxAmount + lineCount
     * 4. 写入 InvoiceRecord.taxBreakdown JSON 字段
     * 5. amount/taxAmount/totalAmount 自动 = 各组之和
     *
     * @param factoryId 工厂 ID (强制隔离)
     * @param salesOrderId 销售订单 ID
     * @param invoiceType 发票类型 (NORMAL/SPECIAL)
     * @param requestedBy 申请人
     * @param remark 备注
     * @return 创建的开票记录, taxBreakdown 字段含分组明细
     */
    InvoiceRecord requestInvoiceFromOrder(String factoryId, String salesOrderId,
                                           String invoiceType, Long requestedBy, String remark);

    InvoiceRecord approveInvoice(String factoryId, String invoiceId, Long reviewedBy, String notes);

    InvoiceRecord rejectInvoice(String factoryId, String invoiceId, Long reviewedBy, String notes);

    InvoiceRecord issueInvoice(String factoryId, String invoiceId, MultipartFile pdfFile, Long issuedBy);

    Page<InvoiceRecord> listInvoices(String factoryId, InvoiceStatus status, Pageable pageable);

    /** 发票列表页 — 同时按 status + invoiceType 筛选 (前端下拉, 两者均可选). */
    Page<InvoiceRecord> listInvoices(String factoryId, InvoiceStatus status, InvoiceType invoiceType, Pageable pageable);

    InvoiceRecord getInvoice(String factoryId, String invoiceId);

    /** List all invoice records for a given sales order, factory-scoped. */
    java.util.List<InvoiceRecord> listInvoicesBySalesOrder(String factoryId, String salesOrderId);
}
