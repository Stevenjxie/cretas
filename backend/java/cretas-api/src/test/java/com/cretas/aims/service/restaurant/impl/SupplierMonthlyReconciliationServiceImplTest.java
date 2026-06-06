package com.cretas.aims.service.restaurant.impl;

import com.cretas.aims.dto.restaurant.SupplierMonthlyReconciliationDto;
import com.cretas.aims.entity.Supplier;
import com.cretas.aims.entity.enums.ArApTransactionType;
import com.cretas.aims.entity.enums.CounterpartyType;
import com.cretas.aims.entity.finance.ArApTransaction;
import com.cretas.aims.entity.restaurant.SupplierDeliveryNote;
import com.cretas.aims.entity.restaurant.SupplierMonthlyReconciliation;
import com.cretas.aims.entity.restaurant.enums.DeliveryNoteStatus;
import com.cretas.aims.entity.restaurant.enums.PriceAnomalyApprovalStatus;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.repository.SupplierRepository;
import com.cretas.aims.repository.finance.ArApTransactionRepository;
import com.cretas.aims.repository.restaurant.SupplierDeliveryNoteRepository;
import com.cretas.aims.repository.restaurant.SupplierMonthlyReconciliationRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SupplierMonthlyReconciliationServiceImplTest {

    private static final String FACTORY = "F_TEST";
    private static final String SUPPLIER_ID = "SUP_TEST";
    private static final YearMonth MONTH = YearMonth.of(2026, 6);

    @Mock SupplierMonthlyReconciliationRepository reconciliationRepository;
    @Mock SupplierDeliveryNoteRepository deliveryNoteRepository;
    @Mock ArApTransactionRepository arApTransactionRepository;
    @Mock SupplierRepository supplierRepository;

    SupplierMonthlyReconciliationServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new SupplierMonthlyReconciliationServiceImpl(
                reconciliationRepository,
                deliveryNoteRepository,
                arApTransactionRepository,
                supplierRepository);
    }

    @Test
    void createOrRefreshDraft_reusesExistingDraftAndRebuildsSnapshot() {
        SupplierMonthlyReconciliation existing = draft("REC1");
        SupplierDeliveryNote note = deliveryNote("DN1", "SDN-001", "100.00");
        ArApTransaction invoice = apInvoice("AP1", "DN1", "100.00");

        when(supplierRepository.findByIdAndFactoryId(SUPPLIER_ID, FACTORY)).thenReturn(Optional.of(supplier()));
        when(reconciliationRepository.findByFactoryIdAndSupplierIdAndReconciliationMonthAndDeletedAtIsNull(
                FACTORY, SUPPLIER_ID, MONTH.atDay(1))).thenReturn(Optional.of(existing));
        when(deliveryNoteRepository.findConfirmedBySupplierAndDateRange(
                FACTORY, SUPPLIER_ID, MONTH.atDay(1), MONTH.atEndOfMonth())).thenReturn(List.of(note));
        when(arApTransactionRepository.findByFactoryIdAndSourceTypeAndSourceIdInAndTransactionTypeAndDeletedAtIsNull(
                FACTORY, "SUPPLIER_DELIVERY_NOTE", List.of("DN1"), ArApTransactionType.AP_INVOICE))
                .thenReturn(List.of(invoice));
        when(arApTransactionRepository.findByFactoryIdAndCounterpartyTypeAndCounterpartyIdAndTransactionTypeAndTransactionDateBetweenOrderByTransactionDateAscCreatedAtAsc(
                FACTORY, CounterpartyType.SUPPLIER, SUPPLIER_ID, ArApTransactionType.AP_INVOICE,
                MONTH.atDay(1), MONTH.atEndOfMonth())).thenReturn(List.of(invoice));
        when(arApTransactionRepository.findByCounterpartyAndTypesAndDateRange(any(), any(), any(), any(), any(), any()))
                .thenReturn(List.of());
        when(reconciliationRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        SupplierMonthlyReconciliationDto dto =
                service.createOrRefreshDraft(FACTORY, SUPPLIER_ID, MONTH, 7L);

        assertThat(dto.getId()).isEqualTo("REC1");
        assertThat(dto.getDeliveryTotal()).isEqualByComparingTo("100.00");
        assertThat(dto.getApInvoiceTotal()).isEqualByComparingTo("100.00");
        assertThat(dto.getDifferenceAmount()).isEqualByComparingTo("0.00");
        assertThat(dto.getLines()).hasSize(1);
        assertThat(dto.getLines().get(0).getLineStatus()).isEqualTo("MATCHED");
    }

    @Test
    void createOrRefreshDraft_failsClosedWhenDeliveryAmountMissing() {
        SupplierDeliveryNote note = deliveryNote("DN1", "SDN-001", null);

        when(supplierRepository.findByIdAndFactoryId(SUPPLIER_ID, FACTORY)).thenReturn(Optional.of(supplier()));
        when(reconciliationRepository.findByFactoryIdAndSupplierIdAndReconciliationMonthAndDeletedAtIsNull(
                FACTORY, SUPPLIER_ID, MONTH.atDay(1))).thenReturn(Optional.empty());
        when(deliveryNoteRepository.findConfirmedBySupplierAndDateRange(
                FACTORY, SUPPLIER_ID, MONTH.atDay(1), MONTH.atEndOfMonth())).thenReturn(List.of(note));
        when(arApTransactionRepository.findByFactoryIdAndSourceTypeAndSourceIdInAndTransactionTypeAndDeletedAtIsNull(
                FACTORY, "SUPPLIER_DELIVERY_NOTE", List.of("DN1"), ArApTransactionType.AP_INVOICE))
                .thenReturn(List.of());

        assertThatThrownBy(() -> service.createOrRefreshDraft(FACTORY, SUPPLIER_ID, MONTH, 7L))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("送货单缺少有效总金额");

        verify(reconciliationRepository, never()).save(any());
    }

    @Test
    void confirm_returnsExistingConfirmedWithoutRebuilding() {
        SupplierMonthlyReconciliation confirmed = draft("REC1");
        confirmed.setStatus(SupplierMonthlyReconciliation.Status.CONFIRMED);

        when(reconciliationRepository.findByIdAndFactoryId("REC1", FACTORY)).thenReturn(Optional.of(confirmed));

        SupplierMonthlyReconciliationDto dto = service.confirm(FACTORY, "REC1", 9L);

        assertThat(dto.getStatus()).isEqualTo("CONFIRMED");
        verify(supplierRepository, never()).findByIdAndFactoryId(any(), any());
        verify(reconciliationRepository, never()).save(any());
    }

    @Test
    void createOrRefreshDraft_enrichesDeliveryEvidenceFromNote() {
        SupplierMonthlyReconciliation existing = draft("REC1");
        SupplierDeliveryNote note = deliveryNoteWithEvidence("DN1", "SDN-001", "100.00");
        ArApTransaction invoice = apInvoice("AP1", "DN1", "100.00");

        when(supplierRepository.findByIdAndFactoryId(SUPPLIER_ID, FACTORY)).thenReturn(Optional.of(supplier()));
        when(reconciliationRepository.findByFactoryIdAndSupplierIdAndReconciliationMonthAndDeletedAtIsNull(
                FACTORY, SUPPLIER_ID, MONTH.atDay(1))).thenReturn(Optional.of(existing));
        when(deliveryNoteRepository.findConfirmedBySupplierAndDateRange(
                FACTORY, SUPPLIER_ID, MONTH.atDay(1), MONTH.atEndOfMonth())).thenReturn(List.of(note));
        when(deliveryNoteRepository.findByIdAndFactoryId("DN1", FACTORY)).thenReturn(Optional.of(note));
        when(arApTransactionRepository.findByFactoryIdAndSourceTypeAndSourceIdInAndTransactionTypeAndDeletedAtIsNull(
                FACTORY, "SUPPLIER_DELIVERY_NOTE", List.of("DN1"), ArApTransactionType.AP_INVOICE))
                .thenReturn(List.of(invoice));
        when(arApTransactionRepository.findByFactoryIdAndCounterpartyTypeAndCounterpartyIdAndTransactionTypeAndTransactionDateBetweenOrderByTransactionDateAscCreatedAtAsc(
                FACTORY, CounterpartyType.SUPPLIER, SUPPLIER_ID, ArApTransactionType.AP_INVOICE,
                MONTH.atDay(1), MONTH.atEndOfMonth())).thenReturn(List.of(invoice));
        when(arApTransactionRepository.findByCounterpartyAndTypesAndDateRange(any(), any(), any(), any(), any(), any()))
                .thenReturn(List.of());
        when(reconciliationRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        SupplierMonthlyReconciliationDto dto =
                service.createOrRefreshDraft(FACTORY, SUPPLIER_ID, MONTH, 7L);

        assertThat(dto.getLines()).hasSize(1);
        SupplierMonthlyReconciliationDto.LineDto line = dto.getLines().get(0);
        assertThat(line.getPhotoOssUrl()).isEqualTo("https://oss/delivery.jpg");
        assertThat(line.getVoiceTranscriptText()).isEqualTo("供应商电话确认今日涨价");
        assertThat(line.getSupplierContactNote()).isEqualTo("已电话询价");
        assertThat(line.getPriceAnomalyApprovalStatus()).isEqualTo("APPROVED");
        assertThat(line.getPriceAnomalyApprovedBy()).isEqualTo(99L);
        assertThat(line.getPriceAnomalyApprovalComment()).isEqualTo("老板同意按供应商报价入库");
        assertThat(line.getPayableTransactionId()).isEqualTo("AP-PAY-1");
    }

    @Test
    void confirm_blocksWhenDeliveryAndApAmountsDiffer() {
        SupplierMonthlyReconciliation existing = draft("REC1");
        SupplierDeliveryNote note = deliveryNote("DN1", "SDN-001", "100.00");
        ArApTransaction invoice = apInvoice("AP1", "DN1", "90.00");

        when(reconciliationRepository.findByIdAndFactoryId("REC1", FACTORY)).thenReturn(Optional.of(existing));
        when(supplierRepository.findByIdAndFactoryId(SUPPLIER_ID, FACTORY)).thenReturn(Optional.of(supplier()));
        when(deliveryNoteRepository.findConfirmedBySupplierAndDateRange(
                FACTORY, SUPPLIER_ID, MONTH.atDay(1), MONTH.atEndOfMonth())).thenReturn(List.of(note));
        when(arApTransactionRepository.findByFactoryIdAndSourceTypeAndSourceIdInAndTransactionTypeAndDeletedAtIsNull(
                FACTORY, "SUPPLIER_DELIVERY_NOTE", List.of("DN1"), ArApTransactionType.AP_INVOICE))
                .thenReturn(List.of(invoice));
        when(arApTransactionRepository.findByFactoryIdAndCounterpartyTypeAndCounterpartyIdAndTransactionTypeAndTransactionDateBetweenOrderByTransactionDateAscCreatedAtAsc(
                FACTORY, CounterpartyType.SUPPLIER, SUPPLIER_ID, ArApTransactionType.AP_INVOICE,
                MONTH.atDay(1), MONTH.atEndOfMonth())).thenReturn(List.of(invoice));
        when(arApTransactionRepository.findByCounterpartyAndTypesAndDateRange(any(), any(), any(), any(), any(), any()))
                .thenReturn(List.of());

        assertThatThrownBy(() -> service.confirm(FACTORY, "REC1", 9L))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("供应商月对账存在差异");

        verify(reconciliationRepository, never()).save(any());
    }

    private Supplier supplier() {
        Supplier supplier = new Supplier();
        supplier.setId(SUPPLIER_ID);
        supplier.setFactoryId(FACTORY);
        supplier.setName("Test Supplier");
        return supplier;
    }

    private SupplierMonthlyReconciliation draft(String id) {
        SupplierMonthlyReconciliation reconciliation = new SupplierMonthlyReconciliation();
        reconciliation.setId(id);
        reconciliation.setFactoryId(FACTORY);
        reconciliation.setSupplierId(SUPPLIER_ID);
        reconciliation.setReconciliationMonth(MONTH.atDay(1));
        reconciliation.setPeriodStart(MONTH.atDay(1));
        reconciliation.setPeriodEnd(MONTH.atEndOfMonth());
        reconciliation.setStatus(SupplierMonthlyReconciliation.Status.DRAFT);
        return reconciliation;
    }

    private SupplierDeliveryNote deliveryNote(String id, String number, String amount) {
        SupplierDeliveryNote note = new SupplierDeliveryNote();
        note.setId(id);
        note.setFactoryId(FACTORY);
        note.setSupplierId(SUPPLIER_ID);
        note.setNoteNumber(number);
        note.setDeliveryDate(LocalDate.of(2026, 6, 5));
        note.setStatus(DeliveryNoteStatus.CONFIRMED);
        note.setTotalAmount(amount == null ? null : new BigDecimal(amount));
        return note;
    }

    private SupplierDeliveryNote deliveryNoteWithEvidence(String id, String number, String amount) {
        SupplierDeliveryNote note = deliveryNote(id, number, amount);
        note.setPhotoOssUrl("https://oss/delivery.jpg");
        note.setVoiceTranscriptText("供应商电话确认今日涨价");
        note.setSupplierContactNote("已电话询价");
        note.setPriceAnomalyApprovalStatus(PriceAnomalyApprovalStatus.APPROVED);
        note.setPriceAnomalyApprovedBy(99L);
        note.setPriceAnomalyApprovedAt(LocalDateTime.of(2026, 6, 5, 10, 0));
        note.setPriceAnomalyApprovalComment("老板同意按供应商报价入库");
        note.setPayableTransactionId("AP-PAY-1");
        note.setPayablePostedAt(LocalDateTime.of(2026, 6, 5, 11, 0));
        return note;
    }

    private ArApTransaction apInvoice(String id, String sourceId, String amount) {
        ArApTransaction transaction = new ArApTransaction();
        transaction.setId(id);
        transaction.setFactoryId(FACTORY);
        transaction.setCounterpartyType(CounterpartyType.SUPPLIER);
        transaction.setCounterpartyId(SUPPLIER_ID);
        transaction.setTransactionType(ArApTransactionType.AP_INVOICE);
        transaction.setTransactionNumber("AP-001");
        transaction.setSourceType("SUPPLIER_DELIVERY_NOTE");
        transaction.setSourceId(sourceId);
        transaction.setTransactionDate(LocalDate.of(2026, 6, 5));
        transaction.setAmount(new BigDecimal(amount));
        transaction.setBalanceAfter(new BigDecimal(amount));
        return transaction;
    }
}
