package com.cretas.aims.service.inventory.impl;

import com.cretas.aims.entity.inventory.PurchaseInvoice;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.repository.inventory.PurchaseInvoiceRepository;
import com.cretas.aims.service.inventory.PurchaseInvoiceService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 采购发票 Service 实现（SP6）
 *
 * <p>上传发票 → 对账（PENDING/MATCHED/MISMATCHED）→ 逾期提醒。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PurchaseInvoiceServiceImpl implements PurchaseInvoiceService {

    private final PurchaseInvoiceRepository invoiceRepository;

    // ─── upload ───────────────────────────────────────────────────────────────

    @Override
    @Transactional
    public PurchaseInvoice upload(String factoryId, String poId, String supplierId,
                                  String invoiceNumber, BigDecimal amount,
                                  String ocrRawResult, Long uploadedBy) {

        // 幂等性检查：同一工厂内发票号唯一
        if (invoiceNumber != null && !invoiceNumber.isBlank()) {
            invoiceRepository.findByFactoryIdAndInvoiceNumber(factoryId, invoiceNumber)
                    .ifPresent(existing -> {
                        throw new BusinessException(409,
                                "发票号 " + invoiceNumber + " 在工厂 " + factoryId +
                                        " 下已存在（ID=" + existing.getId() + "）");
                    });
        }

        PurchaseInvoice invoice = new PurchaseInvoice();
        invoice.setFactoryId(factoryId);
        invoice.setPurchaseOrderId(poId);
        invoice.setSupplierId(supplierId);
        invoice.setInvoiceNumber(invoiceNumber);
        invoice.setAmount(amount);
        invoice.setOcrRawResult(ocrRawResult);
        invoice.setUploadedBy(uploadedBy);
        invoice.setReconcileStatus("PENDING");

        PurchaseInvoice saved = invoiceRepository.save(invoice);
        log.info("[SP6] 上传采购发票 invoiceNumber={} poId={} amount={} by userId={}",
                invoiceNumber, poId, amount, uploadedBy);
        return saved;
    }

    // ─── reconcile ────────────────────────────────────────────────────────────

    @Override
    @Transactional
    public void reconcile(String invoiceId, String factoryId, String reconcileStatus,
                          String note, Long reconciledBy) {

        PurchaseInvoice invoice = invoiceRepository.findById(invoiceId)
                .orElseThrow(() -> new BusinessException("采购发票不存在: " + invoiceId));

        // 工厂隔离检查
        if (!factoryId.equals(invoice.getFactoryId())) {
            throw new BusinessException(403,
                    "无权操作工厂 " + invoice.getFactoryId() + " 的发票");
        }

        // 幂等性保护：已对账不允许重复操作
        if (!"PENDING".equals(invoice.getReconcileStatus())) {
            throw new BusinessException("发票已完成对账（当前状态: " +
                    invoice.getReconcileStatus() + "），无法重复操作");
        }

        invoice.setReconcileStatus(reconcileStatus);
        invoice.setReconcileNote(note);
        invoice.setReconciledBy(reconciledBy);
        invoice.setReconciledAt(LocalDateTime.now());
        invoiceRepository.save(invoice);

        log.info("[SP6] 发票 {} 对账完成 status={} by userId={}", invoiceId, reconcileStatus, reconciledBy);
    }

    // ─── listPendingReminder ──────────────────────────────────────────────────

    @Override
    public List<String> listPendingReminder(String factoryId) {
        return invoiceRepository.findOverdueInvoicePoIds(factoryId, LocalDate.now());
    }

    // ─── listByPurchaseOrder ──────────────────────────────────────────────────

    @Override
    public List<PurchaseInvoice> listByPurchaseOrder(String factoryId, String poId) {
        return invoiceRepository.findByFactoryIdAndPurchaseOrderId(factoryId, poId);
    }
}
