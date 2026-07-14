package com.cretas.aims.service.finance.impl;

import com.cretas.aims.ai.client.DashScopeVisionClient;
import com.cretas.aims.entity.finance.InvoiceRecord;
import com.cretas.aims.entity.finance.InvoiceRecord.TaxBreakdownEntry;
import com.cretas.aims.entity.inventory.SalesOrder;
import com.cretas.aims.entity.inventory.SalesOrderItem;
import com.cretas.aims.entity.enums.InvoiceStatus;
import com.cretas.aims.entity.enums.InvoiceType;
import com.cretas.aims.event.InvoiceIssuedEvent;
import com.cretas.aims.repository.CustomerRepository;
import com.cretas.aims.repository.InvoiceRecordRepository;
import com.cretas.aims.repository.inventory.SalesOrderItemRepository;
import com.cretas.aims.repository.inventory.SalesOrderRepository;
import com.cretas.aims.service.OssService;
import com.cretas.aims.service.finance.InvoiceService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class InvoiceServiceImpl implements InvoiceService {

    private final InvoiceRecordRepository invoiceRecordRepository;
    private final SalesOrderRepository salesOrderRepository;
    private final SalesOrderItemRepository salesOrderItemRepository;
    private final CustomerRepository customerRepository;
    private final OssService ossService;
    private final ApplicationEventPublisher eventPublisher;

    /** F-INV-1 gap-fill: PDF→PNG→vision OCR, 解析失败仅记 ocr_error_message。 */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private DashScopeVisionClient dashScopeVisionClient;

    /** Canvas V2: DB-driven validation rules */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.cretas.aims.engine.ValidationRuleEvaluator validationRuleEvaluator;
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.cretas.aims.engine.DynamicFieldService dynamicFieldService;

    private void runConfiguredValidation(String factoryId, String operation, java.util.Map<String, Object> context) {
        if (validationRuleEvaluator == null) return;
        try {
            validationRuleEvaluator.validate(factoryId, "invoice_record", operation, context);
        } catch (com.cretas.aims.exception.BusinessException e) {
            throw e;
        } catch (Exception e) {
            log.warn("Canvas validation non-blocking error: {}", e.getMessage());
        }
    }

    @Override
    @Transactional
    public InvoiceRecord requestInvoice(String factoryId, String salesOrderId, BigDecimal amount,
                                         BigDecimal taxAmount, String invoiceType, Long requestedBy, String remark) {
        runConfiguredValidation(factoryId, "CREATE", java.util.Map.of(
            "amount", amount != null ? amount : BigDecimal.ZERO,
            "invoiceType", invoiceType != null ? invoiceType : "",
            "salesOrderId", salesOrderId != null ? salesOrderId : ""));
        SalesOrder so = salesOrderRepository.findById(salesOrderId)
                .orElseThrow(() -> new IllegalArgumentException("销售订单不存在: " + salesOrderId));

        // Tenant isolation guard (parity with requestInvoiceFromOrder)
        if (!factoryId.equals(so.getFactoryId())) {
            throw new IllegalArgumentException("销售订单不存在或无权访问: " + salesOrderId);
        }

        // R18 audit: enforce business invariant — only post-finance-approved SOs can be invoiced.
        // Previously only the dropdown UX (R17) gated this, but direct API POST would bypass.
        // Same whitelist as ReferenceDataController.findSalesOrders.
        validateInvoiceableStatus(so);

        // 防呆 R4 (fool-proof-design 2026-05-17): dedup guard parity with
        // requestInvoiceFromOrder. Free-form /request path 也走同套幂等检查 — 之前 漏
        // 让前端 dedup 是 UI-only gate, 直接 POST API 仍可重复创建.
        List<InvoiceRecord> dupActive = invoiceRecordRepository
                .findByFactoryIdAndSalesOrderIdAndStatusInAndDeletedAtIsNull(
                        factoryId, salesOrderId,
                        List.of(InvoiceStatus.REQUESTED, InvoiceStatus.APPROVED));
        if (!dupActive.isEmpty()) {
            String existingNumbers = dupActive.stream()
                    .map(InvoiceRecord::getInvoiceNumber)
                    .collect(java.util.stream.Collectors.joining(", "));
            throw new com.cretas.aims.exception.BusinessException(409,
                    "该销售订单已有待处理开票申请 (" + dupActive.size() + " 张: " + existingNumbers + "), 请先处理或撤销")
                    .withHint("请在 \"开票申请\" Tab 里审核通过或驳回已有的发票, 再提交新的开票申请")
                    .withHintTarget("开票申请");
        }

        // 防呆 R1: 总开票额不得超过订单未开金额 (server-side 防呆, 同 UI 双保险)
        BigDecimal totalAmount = amount.add(taxAmount != null ? taxAmount : BigDecimal.ZERO);
        BigDecimal soTotal = so.getTotalAmount() != null ? so.getTotalAmount() : BigDecimal.ZERO;
        BigDecimal alreadyInvoiced = so.getInvoicedAmount() != null
                ? so.getInvoicedAmount() : BigDecimal.ZERO;
        BigDecimal unbilled = soTotal.subtract(alreadyInvoiced);
        if (totalAmount.compareTo(unbilled) > 0) {
            throw new com.cretas.aims.exception.BusinessException(400,
                    String.format("开票金额 ¥%s 超过订单未开金额 ¥%s (订单总额 ¥%s, 已开 ¥%s)",
                            totalAmount.toPlainString(), unbilled.toPlainString(),
                            soTotal.toPlainString(), alreadyInvoiced.toPlainString()))
                    .withHint("请调低金额或使用 \"按订单一键生成\" 自动按未开金额申请");
        }

        InvoiceRecord record = new InvoiceRecord();
        record.setFactoryId(factoryId);
        record.setInvoiceNumber(generateInvoiceNumber());
        record.setSalesOrderId(salesOrderId);
        record.setCustomerId(so.getCustomerId());
        record.setCustomerName(so.getCustomerId() != null ?
                customerRepository.findById(so.getCustomerId()).map(c -> c.getName()).orElse(null) : null);
        record.setAmount(amount);
        record.setTaxAmount(taxAmount);
        // Rebase merge (2026-05-17): keep #772's resolveInvoiceType chain (explicit > SO > customer > NORMAL)
        // + 148e4784f's totalAmount local var reuse (computed earlier for R1 max-check).
        record.setTotalAmount(totalAmount);
        record.setInvoiceType(resolveInvoiceType(invoiceType, so));
        record.setStatus(InvoiceStatus.REQUESTED);
        record.setRequestedBy(requestedBy);
        record.setRequestedAt(LocalDateTime.now());
        record.setRemark(remark);

        InvoiceRecord saved = invoiceRecordRepository.save(record);
        try {
            eventPublisher.publishEvent(new com.cretas.aims.event.InvoiceRequestedEvent(
                    this, factoryId, saved.getId(), salesOrderId, saved.getTotalAmount()));
        } catch (Exception e) { log.warn("Publish InvoiceRequestedEvent failed: {}", e.getMessage()); }
        log.info("开票申请创建: orderId={}, amount={}", salesOrderId, saved.getTotalAmount());
        return saved;
    }

    /**
     * Sprint 4 W2 S-INVOICE-CLIENT-1 Option 3 三层 default 链 — 第 3 层 (最终落地) resolver.
     *
     * 顺序: 显式参数 > SO.defaultInvoiceType > customer.defaultInvoiceType > InvoiceType.NORMAL.
     * 显式参数 invalid (非 enum 值) → 抛 IllegalArgumentException (caller 应捕获).
     */
    private InvoiceType resolveInvoiceType(String explicit, SalesOrder so) {
        if (explicit != null && !explicit.isBlank()) {
            return InvoiceType.valueOf(explicit);
        }
        if (so != null && so.getDefaultInvoiceType() != null) {
            return so.getDefaultInvoiceType();
        }
        if (so != null && so.getCustomerId() != null) {
            InvoiceType cust = customerRepository.findById(so.getCustomerId())
                    .map(com.cretas.aims.entity.Customer::getDefaultInvoiceType)
                    .orElse(null);
            if (cust != null) return cust;
        }
        return InvoiceType.NORMAL;
    }

    @Override
    @Transactional
    public InvoiceRecord requestInvoiceFromOrder(String factoryId, String salesOrderId,
                                                  String invoiceType, Long requestedBy, String remark) {
        runConfiguredValidation(factoryId, "CREATE", java.util.Map.of(
            "salesOrderId", salesOrderId != null ? salesOrderId : "",
            "invoiceType", invoiceType != null ? invoiceType : ""));
        SalesOrder so = salesOrderRepository.findById(salesOrderId)
                .orElseThrow(() -> new IllegalArgumentException("销售订单不存在: " + salesOrderId));

        // Tenant isolation guard
        if (!factoryId.equals(so.getFactoryId())) {
            throw new IllegalArgumentException("销售订单不存在或无权访问: " + salesOrderId);
        }

        // R18 audit: business invariant (see requestInvoice above).
        validateInvoiceableStatus(so);

        // Bug #2 fix (R2 2026-04-16): prevent duplicate invoice requests for same SO.
        // Reject if an active (REQUESTED/APPROVED) invoice exists — caller must wait or cancel it first.
        List<InvoiceRecord> existing = invoiceRecordRepository
                .findByFactoryIdAndSalesOrderIdAndStatusInAndDeletedAtIsNull(
                        factoryId, salesOrderId,
                        List.of(InvoiceStatus.REQUESTED, InvoiceStatus.APPROVED));
        if (!existing.isEmpty()) {
            String existingNumbers = existing.stream()
                    .map(InvoiceRecord::getInvoiceNumber)
                    .collect(java.util.stream.Collectors.joining(", "));
            // UX 2026-04-18 进阶: use BusinessException with hints so frontend
            // renders ElNotification with "去查看发票" button instead of plain toast.
            // Still returns 409 via GlobalExceptionHandler (BusinessException code=409).
            throw new com.cretas.aims.exception.BusinessException(409,
                    "该销售订单已有待处理开票申请 (" + existing.size() + " 张: " + existingNumbers + "), 请先处理或撤销")
                    .withHint("请在 \"开票申请\" Tab 里审核通过或驳回已有的发票, 再提交新的开票申请")
                    .withHintTarget("开票申请");
        }

        List<SalesOrderItem> items = salesOrderItemRepository.findBySalesOrderId(salesOrderId);
        if (items.isEmpty()) {
            throw new IllegalArgumentException("销售订单无明细行,无法生成开票申请: " + salesOrderId);
        }

        List<TaxBreakdownEntry> breakdown = aggregateByTaxRate(items);

        // Sum totals from breakdown
        BigDecimal totalTaxable = BigDecimal.ZERO;
        BigDecimal totalTax = BigDecimal.ZERO;
        for (TaxBreakdownEntry entry : breakdown) {
            totalTaxable = totalTaxable.add(entry.getTaxableAmount());
            totalTax = totalTax.add(entry.getTaxAmount());
        }

        InvoiceRecord record = new InvoiceRecord();
        record.setFactoryId(factoryId);
        record.setInvoiceNumber(generateInvoiceNumber());
        record.setSalesOrderId(salesOrderId);
        record.setCustomerId(so.getCustomerId());
        record.setCustomerName(so.getCustomerId() != null ?
                customerRepository.findById(so.getCustomerId()).map(c -> c.getName()).orElse(null) : null);
        record.setAmount(totalTaxable);
        record.setTaxAmount(totalTax);
        record.setTotalAmount(totalTaxable.add(totalTax));
        record.setTaxBreakdown(breakdown);
        record.setInvoiceType(resolveInvoiceType(invoiceType, so));
        record.setStatus(InvoiceStatus.REQUESTED);
        record.setRequestedBy(requestedBy);
        record.setRequestedAt(LocalDateTime.now());
        record.setRemark(remark);

        InvoiceRecord saved = invoiceRecordRepository.save(record);
        log.info("税率分组开票申请创建: orderId={}, breakdownGroups={}, totalAmount={}",
                salesOrderId, breakdown.size(), saved.getTotalAmount());
        return saved;
    }

    /**
     * 按 tax_rate 对销售订单行进行分组聚合。
     *
     * <h3>金额来源策略 (V3 P0-3b — 客户原话 2906-2921s)</h3>
     * <pre>
     * "如果这个订单还没有出库, 那么只显示我们的订单金额,
     *  一旦有出库了, 就要显示我们的那个出库金额,
     *  不然的话我们金额会对不上, 因为出库金额跟订单金额有时候是不一样的"
     * </pre>
     *
     * <ul>
     *   <li>当订单尚未出库 (任意行 deliveredQty=0) → 该行用 <b>订单数量</b></li>
     *   <li>当行有出库 (deliveredQty > 0) → 该行用 <b>已发货数量</b></li>
     *   <li>这样做的好处: 部分出库订单只对已发货部分开票, 不会出现 "开了 10 万但只发了 8 万" 的对账错</li>
     * </ul>
     *
     * <p>null tax_rate 视为 0% (兼容历史数据)</p>
     * <p>行金额 = effectiveQty × unit_price × (1 - discount/100)</p>
     * <p>税额 = 行金额 × tax_rate / 100</p>
     */
    private List<TaxBreakdownEntry> aggregateByTaxRate(List<SalesOrderItem> items) {
        // LinkedHashMap 保持插入顺序的稳定输出
        Map<BigDecimal, TaxBreakdownEntry> grouped = new LinkedHashMap<>();

        for (SalesOrderItem item : items) {
            BigDecimal taxRate = item.getTaxRate() != null ? item.getTaxRate() : BigDecimal.ZERO;
            // Normalize scale so 9.0 and 9.00 are same key
            BigDecimal key = taxRate.setScale(2, RoundingMode.HALF_UP);

            BigDecimal lineAmount = computeLineAmountForInvoice(item);
            BigDecimal lineTax = lineAmount
                    .multiply(taxRate)
                    .divide(new BigDecimal("100"), 2, RoundingMode.HALF_UP);

            TaxBreakdownEntry entry = grouped.computeIfAbsent(key,
                    k -> new TaxBreakdownEntry(k, BigDecimal.ZERO, BigDecimal.ZERO, 0));
            entry.setTaxableAmount(entry.getTaxableAmount().add(lineAmount));
            entry.setTaxAmount(entry.getTaxAmount().add(lineTax));
            entry.setLineCount(entry.getLineCount() + 1);
        }

        // Sort by taxRate ascending so 9% always before 13% in UI
        List<TaxBreakdownEntry> result = new ArrayList<>(grouped.values());
        result.sort(Comparator.comparing(TaxBreakdownEntry::getTaxRate));
        return result;
    }

    /**
     * 计算单行的开票金额 — V3 P0-3b 出库联动。
     *
     * 规则:
     * <ol>
     *   <li>该行已有出库 (deliveredQuantity > 0) → 用 deliveredQuantity</li>
     *   <li>该行未出库 (deliveredQuantity = 0 或 null) → 退回 quantity (订单数量)</li>
     * </ol>
     *
     * 折扣始终应用 (订单数量和出库数量都要按折扣率打折)。
     */
    private BigDecimal computeLineAmountForInvoice(SalesOrderItem item) {
        if (item.getUnitPrice() == null) return BigDecimal.ZERO;

        BigDecimal effectiveQty;
        BigDecimal delivered = item.getDeliveredQuantity();
        if (delivered != null && delivered.compareTo(BigDecimal.ZERO) > 0) {
            // 部分或全部已出库 — 按出库数量开票
            effectiveQty = delivered;
        } else {
            // 未出库 — 按订单数量开票 (退回原 SalesOrderItem.getLineAmount() 行为)
            effectiveQty = item.getQuantity() != null ? item.getQuantity() : BigDecimal.ZERO;
        }

        BigDecimal amount = effectiveQty
                .multiply(item.getUnitPrice())
                .setScale(2, RoundingMode.HALF_UP);

        BigDecimal discountRate = item.getDiscountRate();
        if (discountRate != null && discountRate.compareTo(BigDecimal.ZERO) > 0) {
            BigDecimal discountMultiplier = BigDecimal.ONE.subtract(
                    discountRate.divide(new BigDecimal("100"), 6, RoundingMode.HALF_UP));
            amount = amount.multiply(discountMultiplier).setScale(2, RoundingMode.HALF_UP);
        }
        return amount;
    }

    @Override
    @Transactional
    public InvoiceRecord approveInvoice(String factoryId, String invoiceId, Long reviewedBy, String notes) {
        InvoiceRecord record = getInvoice(factoryId, invoiceId);
        if (record.getStatus() != InvoiceStatus.REQUESTED) {
            throw new IllegalStateException("只能审核状态为REQUESTED的开票申请, 当前: " + record.getStatus());
        }
        record.setStatus(InvoiceStatus.APPROVED);
        record.setReviewedBy(reviewedBy);
        record.setReviewedAt(LocalDateTime.now());
        record.setReviewNotes(notes);
        return invoiceRecordRepository.save(record);
    }

    @Override
    @Transactional
    public InvoiceRecord rejectInvoice(String factoryId, String invoiceId, Long reviewedBy, String notes) {
        InvoiceRecord record = getInvoice(factoryId, invoiceId);
        if (record.getStatus() != InvoiceStatus.REQUESTED) {
            throw new IllegalStateException("只能驳回状态为REQUESTED的开票申请");
        }
        record.setStatus(InvoiceStatus.REJECTED);
        record.setReviewedBy(reviewedBy);
        record.setReviewedAt(LocalDateTime.now());
        record.setReviewNotes(notes);
        return invoiceRecordRepository.save(record);
    }

    @Override
    @Transactional
    public InvoiceRecord issueInvoice(String factoryId, String invoiceId, MultipartFile pdfFile, Long issuedBy) {
        InvoiceRecord record = getInvoice(factoryId, invoiceId);
        if (record.getStatus() != InvoiceStatus.APPROVED) {
            throw new IllegalStateException("只能对已审核的申请开具发票, 当前: " + record.getStatus());
        }

        // V3 P0-3c — 客户原话 2675-2693s "发票必须以附件形式上传"
        // PDF 文件强制必填, 否则销售方无法在订单页下载, 闭环不闭
        if (pdfFile == null || pdfFile.isEmpty()) {
            throw new IllegalArgumentException("开具发票必须上传 PDF 附件 (客户要求: 销售从订单页下载发票)");
        }

        // 上传发票PDF到OSS
        String pdfUrl = ossService.uploadFile(pdfFile, "invoices", record.getFactoryId());
        record.setInvoicePdfUrl(pdfUrl);
        record.setInvoiceFileName(pdfFile.getOriginalFilename());

        // F-INV-1 gap-fill: OCR auto-parse 发票号 + 金额 (供财务核对申请金额)。
        // 失败仅记 ocr_error_message, 不抛异常 — 财务可手动核对。
        runInvoiceOcr(record, pdfFile);

        record.setStatus(InvoiceStatus.ISSUED);
        record.setIssuedAt(LocalDateTime.now());
        InvoiceRecord saved = invoiceRecordRepository.save(record);

        // 回写销售订单
        updateSalesOrderInvoiceStatus(record.getSalesOrderId());

        // 发布事件
        eventPublisher.publishEvent(new InvoiceIssuedEvent(
                this, record.getFactoryId(), record.getId(),
                record.getSalesOrderId(), record.getTotalAmount()));

        log.info("发票已开具: invoiceId={}, fileName={}, pdfUrl={}",
                invoiceId, record.getInvoiceFileName(), record.getInvoicePdfUrl());
        return saved;
    }

    @Override
    public Page<InvoiceRecord> listInvoices(String factoryId, InvoiceStatus status, Pageable pageable) {
        return listInvoices(factoryId, status, null, pageable);
    }

    @Override
    public Page<InvoiceRecord> listInvoices(String factoryId, InvoiceStatus status, InvoiceType invoiceType, Pageable pageable) {
        return invoiceRecordRepository.searchInvoices(
                factoryId,
                status != null ? status.name() : null,
                invoiceType != null ? invoiceType.name() : null,
                pageable);
    }

    @Override
    public InvoiceRecord getInvoice(String factoryId, String invoiceId) {
        return invoiceRecordRepository.findByIdAndFactoryIdAndDeletedAtIsNull(invoiceId, factoryId)
                .orElseThrow(() -> new IllegalArgumentException("开票记录不存在或无权访问: " + invoiceId));
    }

    @Override
    public List<InvoiceRecord> listInvoicesBySalesOrder(String factoryId, String salesOrderId) {
        return invoiceRecordRepository
                .findByFactoryIdAndSalesOrderIdAndDeletedAtIsNullOrderByCreatedAtDesc(factoryId, salesOrderId);
    }

    private void updateSalesOrderInvoiceStatus(String salesOrderId) {
        if (salesOrderId == null) return;
        SalesOrder so = salesOrderRepository.findById(salesOrderId).orElse(null);
        if (so == null) return;

        BigDecimal totalInvoiced = invoiceRecordRepository.sumIssuedAmountBySalesOrderId(salesOrderId);
        so.setInvoicedAmount(totalInvoiced);

        if (totalInvoiced.compareTo(BigDecimal.ZERO) == 0) {
            so.setInvoiceStatus("NOT_INVOICED");
        } else if (so.getTotalAmount() != null && totalInvoiced.compareTo(so.getTotalAmount()) >= 0) {
            so.setInvoiceStatus("FULLY_INVOICED");
        } else {
            so.setInvoiceStatus("PARTIAL_INVOICED");
        }
        salesOrderRepository.save(so);
    }

    /**
     * F-INV-1 gap-fill: 调 DashScopeVisionClient.parseInvoicePdf 写回 OCR 字段。
     *
     * 策略 (no 降级):
     * - vision client 未注入 → 跳过, 不报错 (LLM 服务可选);
     * - 解析失败 → 记录 ocr_error_message + ocr_parsed_at, OCR 字段留 null;
     * - 解析成功 → 写所有 ocr_* 字段 + parsed_at, 财务在 UI 上对比 record.amount。
     *
     * 永远不抛异常 — 发票开具流程不被 OCR 失败阻塞。
     */
    private void runInvoiceOcr(InvoiceRecord record, MultipartFile pdfFile) {
        record.setOcrParsedAt(LocalDateTime.now());
        if (dashScopeVisionClient == null) {
            record.setOcrErrorMessage("Vision client 未配置, 跳过 OCR");
            return;
        }
        try {
            DashScopeVisionClient.InvoiceParseResult result =
                    dashScopeVisionClient.parseInvoicePdf(pdfFile.getBytes());
            if (result == null) {
                record.setOcrErrorMessage("OCR 返回 null");
                return;
            }
            if (!result.isSuccess()) {
                record.setOcrErrorMessage(result.getMessage());
                return;
            }
            record.setOcrInvoiceNumber(result.getInvoiceNumber());
            record.setOcrAmount(result.getAmount());
            record.setOcrTaxAmount(result.getTaxAmount());
            if (result.getConfidence() >= 0) {
                record.setOcrConfidence(
                        BigDecimal.valueOf(result.getConfidence())
                                .setScale(3, RoundingMode.HALF_UP));
            }
            record.setOcrRawJson(result.getRawResponse());
            record.setOcrErrorMessage(null);
            log.info("发票 OCR 成功: invoiceId={}, ocrNumber={}, ocrAmount={}, confidence={}",
                    record.getId(), result.getInvoiceNumber(),
                    result.getAmount(), result.getConfidence());
        } catch (Exception e) {
            log.warn("发票 OCR 异常 (不阻塞开具): {}", e.getMessage());
            record.setOcrErrorMessage("OCR 异常: " + e.getMessage());
        }
    }

    private String generateInvoiceNumber() {
        String dateStr = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd"));
        long count = invoiceRecordRepository.count() + 1;
        return String.format("INV-%s-%04d", dateStr, count);
    }

    /**
     * R18 audit: enforce business invariant — invoice can only be requested against
     * a post-finance-approved SO. R21 audit C2: now uses centralized OrderUsageWhitelists
     * (single source of truth), no longer a local duplicate Set.
     */
    private void validateInvoiceableStatus(SalesOrder so) {
        if (so.getStatus() == null
                || !com.cretas.aims.domain.OrderUsageWhitelists.SO_INVOICEABLE.contains(so.getStatus())) {
            throw new com.cretas.aims.exception.BusinessException(409,
                "销售订单状态不允许开票 (当前: " + (so.getStatus() != null ? so.getStatus().name() : "null") +
                "). 仅财务审核通过及之后状态可开票.")
                .withHint("先完成财务审核流程后再申请开票")
                .withHintTarget("销售订单");
        }
    }
}
