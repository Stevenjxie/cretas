package com.cretas.aims.service.restaurant.impl;

import com.cretas.aims.dto.common.PageResponse;
import com.cretas.aims.dto.restaurant.SupplierMonthlyReconciliationDto;
import com.cretas.aims.entity.Supplier;
import com.cretas.aims.entity.enums.ArApTransactionType;
import com.cretas.aims.entity.enums.CounterpartyType;
import com.cretas.aims.entity.finance.ArApTransaction;
import com.cretas.aims.entity.restaurant.SupplierDeliveryNote;
import com.cretas.aims.entity.restaurant.SupplierMonthlyReconciliation;
import com.cretas.aims.entity.restaurant.SupplierMonthlyReconciliationLine;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.exception.ResourceNotFoundException;
import com.cretas.aims.repository.SupplierRepository;
import com.cretas.aims.repository.finance.ArApTransactionRepository;
import com.cretas.aims.repository.restaurant.SupplierDeliveryNoteRepository;
import com.cretas.aims.repository.restaurant.SupplierMonthlyReconciliationRepository;
import com.cretas.aims.service.restaurant.SupplierMonthlyReconciliationService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class SupplierMonthlyReconciliationServiceImpl implements SupplierMonthlyReconciliationService {

    private static final String DELIVERY_NOTE_SOURCE_TYPE = "SUPPLIER_DELIVERY_NOTE";

    private final SupplierMonthlyReconciliationRepository reconciliationRepository;
    private final SupplierDeliveryNoteRepository deliveryNoteRepository;
    private final ArApTransactionRepository arApTransactionRepository;
    private final SupplierRepository supplierRepository;

    @Override
    @Transactional
    public SupplierMonthlyReconciliationDto createOrRefreshDraft(
            String factoryId, String supplierId, YearMonth month, Long userId) {
        YearMonth ym = month != null ? month : YearMonth.now();
        Supplier supplier = supplierRepository.findByIdAndFactoryId(supplierId, factoryId)
                .orElseThrow(() -> new ResourceNotFoundException("供应商不存在: " + supplierId));
        LocalDate monthStart = ym.atDay(1);

        SupplierMonthlyReconciliation reconciliation = reconciliationRepository
                .findByFactoryIdAndSupplierIdAndReconciliationMonthAndDeletedAtIsNull(
                        factoryId, supplierId, monthStart)
                .orElseGet(() -> {
                    SupplierMonthlyReconciliation created = new SupplierMonthlyReconciliation();
                    created.setFactoryId(factoryId);
                    created.setSupplierId(supplierId);
                    created.setReconciliationMonth(monthStart);
                    created.setPeriodStart(monthStart);
                    created.setPeriodEnd(ym.atEndOfMonth());
                    created.setCreatedBy(userId);
                    return created;
                });

        if (reconciliation.getStatus() == SupplierMonthlyReconciliation.Status.CONFIRMED) {
            return toDto(reconciliation);
        }

        rebuildDraft(reconciliation, supplier, ym);
        return toDto(reconciliationRepository.save(reconciliation));
    }

    @Override
    @Transactional
    public SupplierMonthlyReconciliationDto confirm(String factoryId, String reconciliationId, Long userId) {
        SupplierMonthlyReconciliation reconciliation = mustFind(factoryId, reconciliationId);
        if (reconciliation.getStatus() == SupplierMonthlyReconciliation.Status.CONFIRMED) {
            return toDto(reconciliation);
        }

        Supplier supplier = supplierRepository.findByIdAndFactoryId(reconciliation.getSupplierId(), factoryId)
                .orElseThrow(() -> new ResourceNotFoundException("供应商不存在: " + reconciliation.getSupplierId()));
        rebuildDraft(reconciliation, supplier, YearMonth.from(reconciliation.getReconciliationMonth()));

        if (reconciliation.getDeliveryNoteCount() == 0 && reconciliation.getApTransactionCount() == 0) {
            throw new BusinessException(409, "本月没有送货单或应付交易，不能确认月对账")
                    .withCode("RECONCILIATION_EMPTY")
                    .withHint("请选择有送货或应付记录的月份后再生成并确认月对账")
                    .withHintTarget("month");
        }

        List<SupplierMonthlyReconciliationLine> blockingLines = reconciliation.getLines().stream()
                .filter(line -> line.getLineStatus() == SupplierMonthlyReconciliationLine.LineStatus.MISSING_AP
                        || line.getLineStatus() == SupplierMonthlyReconciliationLine.LineStatus.AMOUNT_MISMATCH
                        || line.getLineStatus() == SupplierMonthlyReconciliationLine.LineStatus.UNMATCHED_AP)
                .toList();
        if (!blockingLines.isEmpty() || reconciliation.getDifferenceAmount().compareTo(BigDecimal.ZERO) != 0) {
            throw new BusinessException(409, "供应商月对账存在差异，不能确认")
                    .withCode("RECONCILIATION_HAS_DIFFERENCE")
                    .withHint("请先补齐应付挂账或调整金额，确认差异为 0 后再确认月对账")
                    .withHintTarget("differenceAmount");
        }

        reconciliation.setStatus(SupplierMonthlyReconciliation.Status.CONFIRMED);
        reconciliation.setConfirmedBy(userId);
        reconciliation.setConfirmedAt(LocalDateTime.now());
        return toDto(reconciliationRepository.save(reconciliation));
    }

    @Override
    @Transactional(readOnly = true)
    public SupplierMonthlyReconciliationDto getById(String factoryId, String reconciliationId) {
        return toDto(mustFind(factoryId, reconciliationId));
    }

    @Override
    @Transactional(readOnly = true)
    public PageResponse<SupplierMonthlyReconciliationDto> list(
            String factoryId, String supplierId, int page, int size) {
        PageRequest pageable = PageRequest.of(Math.max(0, page - 1), size);
        Page<SupplierMonthlyReconciliation> result = StringUtils.hasText(supplierId)
                ? reconciliationRepository.findByFactoryIdAndSupplierIdOrderByReconciliationMonthDesc(
                        factoryId, supplierId, pageable)
                : reconciliationRepository.findByFactoryIdOrderByReconciliationMonthDesc(factoryId, pageable);
        List<SupplierMonthlyReconciliationDto> content = result.getContent().stream()
                .map(this::toDto)
                .toList();
        return PageResponse.of(content, page, size, result.getTotalElements());
    }

    private SupplierMonthlyReconciliation mustFind(String factoryId, String reconciliationId) {
        return reconciliationRepository.findByIdAndFactoryId(reconciliationId, factoryId)
                .orElseThrow(() -> new BusinessException(404, "供应商月对账单不存在: " + reconciliationId)
                        .withHint("请刷新月对账列表后重试"));
    }

    private void rebuildDraft(SupplierMonthlyReconciliation reconciliation, Supplier supplier, YearMonth ym) {
        LocalDate start = ym.atDay(1);
        LocalDate end = ym.atEndOfMonth();
        reconciliation.setSupplierName(supplier.getName());
        reconciliation.setReconciliationMonth(start);
        reconciliation.setPeriodStart(start);
        reconciliation.setPeriodEnd(end);
        reconciliation.getLines().clear();

        List<SupplierDeliveryNote> notes = deliveryNoteRepository.findConfirmedBySupplierAndDateRange(
                reconciliation.getFactoryId(), reconciliation.getSupplierId(), start, end);

        List<String> noteIds = notes.stream().map(SupplierDeliveryNote::getId).toList();
        List<ArApTransaction> sourceInvoices = noteIds.isEmpty() ? List.of()
                : arApTransactionRepository.findByFactoryIdAndSourceTypeAndSourceIdInAndTransactionTypeAndDeletedAtIsNull(
                        reconciliation.getFactoryId(), DELIVERY_NOTE_SOURCE_TYPE, noteIds,
                        ArApTransactionType.AP_INVOICE);
        Map<String, List<ArApTransaction>> invoicesBySourceId = sourceInvoices.stream()
                .collect(Collectors.groupingBy(ArApTransaction::getSourceId));
        failOnDuplicateSourceInvoices(invoicesBySourceId);

        List<ArApTransaction> periodInvoices =
                arApTransactionRepository.findByFactoryIdAndCounterpartyTypeAndCounterpartyIdAndTransactionTypeAndTransactionDateBetweenOrderByTransactionDateAscCreatedAtAsc(
                        reconciliation.getFactoryId(), CounterpartyType.SUPPLIER, reconciliation.getSupplierId(),
                        ArApTransactionType.AP_INVOICE, start, end);
        List<ArApTransaction> periodOtherTransactions = arApTransactionRepository.findByCounterpartyAndTypesAndDateRange(
                reconciliation.getFactoryId(), CounterpartyType.SUPPLIER, reconciliation.getSupplierId(),
                List.copyOf(EnumSet.of(ArApTransactionType.AP_PAYMENT,
                        ArApTransactionType.AP_ADJUSTMENT,
                        ArApTransactionType.AP_CREDIT_NOTE)),
                start, end);

        Set<String> consumedTransactionIds = new HashSet<>();
        BigDecimal deliveryTotal = BigDecimal.ZERO;
        BigDecimal invoiceTotal = BigDecimal.ZERO;

        for (SupplierDeliveryNote note : notes) {
            BigDecimal deliveryAmount = requirePositiveAmount(note.getTotalAmount(),
                    "送货单缺少有效总金额: " + displayNote(note),
                    "请先补录送货单总金额或行金额，再重新生成月对账");
            deliveryTotal = deliveryTotal.add(deliveryAmount);

            ArApTransaction invoice = firstOrNull(invoicesBySourceId.get(note.getId()));
            SupplierMonthlyReconciliationLine line = new SupplierMonthlyReconciliationLine();
            line.setLineType(SupplierMonthlyReconciliationLine.LineType.DELIVERY_NOTE);
            line.setDeliveryNoteId(note.getId());
            line.setDeliveryNoteNumber(displayNote(note));
            line.setDeliveryDate(note.getDeliveryDate());
            line.setDeliveryAmount(deliveryAmount);

            if (invoice == null) {
                line.setLineStatus(SupplierMonthlyReconciliationLine.LineStatus.MISSING_AP);
                line.setRemark("该送货单尚未生成应付挂账");
            } else {
                BigDecimal apAmount = requirePositiveAmount(invoice.getAmount(),
                        "应付交易金额无效: " + invoice.getId(),
                        "请检查应付挂账记录金额，不能用 0 或空金额对账");
                invoiceTotal = invoiceTotal.add(apAmount);
                consumedTransactionIds.add(invoice.getId());
                line.setApTransactionId(invoice.getId());
                line.setApTransactionNumber(invoice.getTransactionNumber());
                line.setTransactionDate(invoice.getTransactionDate());
                line.setTransactionType(invoice.getTransactionType());
                line.setApAmount(apAmount);
                BigDecimal difference = deliveryAmount.subtract(apAmount);
                line.setDifferenceAmount(difference);
                line.setLineStatus(difference.compareTo(BigDecimal.ZERO) == 0
                        ? SupplierMonthlyReconciliationLine.LineStatus.MATCHED
                        : SupplierMonthlyReconciliationLine.LineStatus.AMOUNT_MISMATCH);
                if (line.getLineStatus() == SupplierMonthlyReconciliationLine.LineStatus.AMOUNT_MISMATCH) {
                    line.setRemark("送货单金额与应付挂账金额不一致");
                }
            }
            reconciliation.addLine(line);
        }

        for (ArApTransaction invoice : periodInvoices) {
            if (consumedTransactionIds.contains(invoice.getId())) {
                continue;
            }
            BigDecimal apAmount = requirePositiveAmount(invoice.getAmount(),
                    "应付交易金额无效: " + invoice.getId(),
                    "请检查应付挂账记录金额，不能用 0 或空金额对账");
            invoiceTotal = invoiceTotal.add(apAmount);
            SupplierMonthlyReconciliationLine line = transactionLine(invoice, apAmount);
            line.setLineStatus(SupplierMonthlyReconciliationLine.LineStatus.UNMATCHED_AP);
            line.setRemark("本月存在未匹配到送货单的应付挂账");
            reconciliation.addLine(line);
        }

        BigDecimal paymentTotal = BigDecimal.ZERO;
        BigDecimal adjustmentTotal = BigDecimal.ZERO;
        for (ArApTransaction transaction : periodOtherTransactions) {
            BigDecimal amount = requireNonZeroAmount(transaction.getAmount(), "AP交易金额无效: " + transaction.getId());
            SupplierMonthlyReconciliationLine line = transactionLine(transaction, amount);
            line.setLineStatus(SupplierMonthlyReconciliationLine.LineStatus.INFO);
            reconciliation.addLine(line);

            if (transaction.getTransactionType() == ArApTransactionType.AP_PAYMENT) {
                paymentTotal = paymentTotal.add(amount.abs());
            } else if (transaction.getTransactionType() == ArApTransactionType.AP_CREDIT_NOTE) {
                adjustmentTotal = adjustmentTotal.subtract(amount.abs());
            } else {
                adjustmentTotal = adjustmentTotal.add(amount);
            }
        }

        reconciliation.getLines().sort(Comparator
                .comparing((SupplierMonthlyReconciliationLine l) ->
                        l.getDeliveryDate() != null ? l.getDeliveryDate() : l.getTransactionDate(),
                        Comparator.nullsLast(Comparator.naturalOrder()))
                .thenComparing(l -> l.getLineType().name())
                .thenComparing(l -> l.getDeliveryNoteId() != null ? l.getDeliveryNoteId() : "", Comparator.naturalOrder())
                .thenComparing(l -> l.getApTransactionId() != null ? l.getApTransactionId() : "", Comparator.naturalOrder()));

        reconciliation.setDeliveryNoteCount(notes.size());
        reconciliation.setApTransactionCount(uniqueTransactionCount(sourceInvoices, periodInvoices, periodOtherTransactions));
        reconciliation.setDeliveryTotal(deliveryTotal);
        reconciliation.setApInvoiceTotal(invoiceTotal);
        reconciliation.setApPaymentTotal(paymentTotal);
        reconciliation.setApAdjustmentTotal(adjustmentTotal);
        reconciliation.setDifferenceAmount(deliveryTotal.subtract(invoiceTotal));
        reconciliation.setNetPayableAmount(invoiceTotal.subtract(paymentTotal).add(adjustmentTotal));
    }

    private void failOnDuplicateSourceInvoices(Map<String, List<ArApTransaction>> invoicesBySourceId) {
        for (Map.Entry<String, List<ArApTransaction>> entry : invoicesBySourceId.entrySet()) {
            if (entry.getValue().size() > 1) {
                String ids = entry.getValue().stream().map(ArApTransaction::getId).collect(Collectors.joining(","));
                throw new BusinessException(409, "同一送货单存在多笔应付挂账: " + entry.getKey())
                        .withCode("DUPLICATE_AP_INVOICE")
                        .withHint("请先处理重复的应付交易后再生成月对账。重复交易: " + ids)
                        .withHintTarget("apTransactionId");
            }
        }
    }

    private SupplierMonthlyReconciliationLine transactionLine(ArApTransaction transaction, BigDecimal amount) {
        SupplierMonthlyReconciliationLine line = new SupplierMonthlyReconciliationLine();
        line.setLineType(SupplierMonthlyReconciliationLine.LineType.AP_TRANSACTION);
        line.setApTransactionId(transaction.getId());
        line.setApTransactionNumber(transaction.getTransactionNumber());
        line.setTransactionDate(transaction.getTransactionDate());
        line.setTransactionType(transaction.getTransactionType());
        line.setApAmount(amount);
        return line;
    }

    private BigDecimal requirePositiveAmount(BigDecimal amount, String message, String hint) {
        if (amount == null || amount.compareTo(BigDecimal.ZERO) <= 0) {
            throw new BusinessException(400, message)
                    .withCode("AMOUNT_REQUIRED")
                    .withHint(hint)
                    .withHintTarget("amount");
        }
        return amount;
    }

    private BigDecimal requireNonZeroAmount(BigDecimal amount, String message) {
        if (amount == null || amount.compareTo(BigDecimal.ZERO) == 0) {
            throw new BusinessException(400, message)
                    .withCode("AMOUNT_REQUIRED")
                    .withHint("请检查AP交易金额，不能用 0 或空金额参与月对账")
                    .withHintTarget("amount");
        }
        return amount;
    }

    private ArApTransaction firstOrNull(List<ArApTransaction> transactions) {
        return transactions == null || transactions.isEmpty() ? null : transactions.get(0);
    }

    private int uniqueTransactionCount(List<ArApTransaction>... groups) {
        Set<String> ids = new LinkedHashSet<>();
        for (List<ArApTransaction> group : groups) {
            for (ArApTransaction transaction : group) {
                ids.add(transaction.getId());
            }
        }
        return ids.size();
    }

    private String displayNote(SupplierDeliveryNote note) {
        return StringUtils.hasText(note.getNoteNumber()) ? note.getNoteNumber() : note.getId();
    }

    private SupplierMonthlyReconciliationDto toDto(SupplierMonthlyReconciliation r) {
        List<SupplierMonthlyReconciliationDto.UnReconciledNoteDto> unReconciledNotes = List.of();
        if (r.getStatus() == SupplierMonthlyReconciliation.Status.CONFIRMED
                && r.getId() != null
                && r.getPeriodStart() != null
                && r.getPeriodEnd() != null) {
            unReconciledNotes = buildUnReconciledNotes(r);
        }
        return SupplierMonthlyReconciliationDto.builder()
                .id(r.getId())
                .factoryId(r.getFactoryId())
                .supplierId(r.getSupplierId())
                .supplierName(r.getSupplierName())
                .month(YearMonth.from(r.getReconciliationMonth()).toString())
                .periodStart(r.getPeriodStart())
                .periodEnd(r.getPeriodEnd())
                .status(r.getStatus() != null ? r.getStatus().name() : null)
                .deliveryNoteCount(r.getDeliveryNoteCount())
                .apTransactionCount(r.getApTransactionCount())
                .deliveryTotal(r.getDeliveryTotal())
                .apInvoiceTotal(r.getApInvoiceTotal())
                .apPaymentTotal(r.getApPaymentTotal())
                .apAdjustmentTotal(r.getApAdjustmentTotal())
                .differenceAmount(r.getDifferenceAmount())
                .netPayableAmount(r.getNetPayableAmount())
                .confirmedBy(r.getConfirmedBy())
                .confirmedAt(r.getConfirmedAt() != null ? r.getConfirmedAt().toString() : null)
                .createdAt(r.getCreatedAt() != null ? r.getCreatedAt().toString() : null)
                .updatedAt(r.getUpdatedAt() != null ? r.getUpdatedAt().toString() : null)
                .lines(r.getLines() == null ? List.of() : r.getLines().stream()
                        .map(line -> toLineDto(r.getFactoryId(), line)).toList())
                .unReconciledNotes(unReconciledNotes)
                .build();
    }

    /**
     * Reads delivery notes that were confirmed (have AP invoice) within the reconciliation
     * period but are NOT present in any reconciliation line of this frozen reconciliation.
     * Pure read-only: does not write or modify any financial data.
     */
    private List<SupplierMonthlyReconciliationDto.UnReconciledNoteDto> buildUnReconciledNotes(
            SupplierMonthlyReconciliation r) {
        List<SupplierDeliveryNote> orphans = deliveryNoteRepository.findOrphanNotesNotInReconciliation(
                r.getFactoryId(), r.getSupplierId(),
                r.getPeriodStart(), r.getPeriodEnd(),
                r.getId());
        return orphans.stream()
                .map(note -> SupplierMonthlyReconciliationDto.UnReconciledNoteDto.builder()
                        .deliveryNoteId(note.getId())
                        .noteNumber(note.getNoteNumber())
                        .deliveryDate(note.getDeliveryDate())
                        .totalAmount(note.getTotalAmount())
                        .payableTransactionId(note.getPayableTransactionId())
                        .confirmedAt(note.getConfirmedAt() != null ? note.getConfirmedAt().toString() : null)
                        .build())
                .toList();
    }

    private SupplierMonthlyReconciliationDto.LineDto toLineDto(String factoryId,
            SupplierMonthlyReconciliationLine line) {
        SupplierMonthlyReconciliationDto.LineDto.LineDtoBuilder builder =
                SupplierMonthlyReconciliationDto.LineDto.builder()
                .id(line.getId())
                .lineType(line.getLineType() != null ? line.getLineType().name() : null)
                .deliveryNoteId(line.getDeliveryNoteId())
                .deliveryNoteNumber(line.getDeliveryNoteNumber())
                .deliveryDate(line.getDeliveryDate())
                .deliveryAmount(line.getDeliveryAmount())
                .apTransactionId(line.getApTransactionId())
                .apTransactionNumber(line.getApTransactionNumber())
                .transactionDate(line.getTransactionDate())
                .transactionType(line.getTransactionType() != null ? line.getTransactionType().name() : null)
                .apAmount(line.getApAmount())
                .differenceAmount(line.getDifferenceAmount())
                .lineStatus(line.getLineStatus() != null ? line.getLineStatus().name() : null)
                .remark(line.getRemark());
        if (StringUtils.hasText(line.getDeliveryNoteId())) {
            deliveryNoteRepository.findByIdAndFactoryId(line.getDeliveryNoteId(), factoryId)
                    .ifPresent(note -> builder
                            .photoOssUrl(note.getPhotoOssUrl())
                            .voiceAudioUrl(note.getVoiceAudioUrl())
                            .voiceTranscriptText(note.getVoiceTranscriptText())
                            .supplierContactNote(note.getSupplierContactNote())
                            .priceAnomalyApprovalStatus(note.getPriceAnomalyApprovalStatus() != null
                                    ? note.getPriceAnomalyApprovalStatus().name() : null)
                            .priceAnomalyApprovedBy(note.getPriceAnomalyApprovedBy())
                            .priceAnomalyApprovedAt(note.getPriceAnomalyApprovedAt() != null
                                    ? note.getPriceAnomalyApprovedAt().toString() : null)
                            .priceAnomalyApprovalComment(note.getPriceAnomalyApprovalComment())
                            .payableTransactionId(note.getPayableTransactionId())
                            .payablePostedAt(note.getPayablePostedAt() != null
                                    ? note.getPayablePostedAt().toString() : null));
        }
        return builder.build();
    }
}
