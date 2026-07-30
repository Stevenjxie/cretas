package com.cretas.aims.service.trace;

import com.cretas.aims.dto.trace.BusinessDocumentTraceResponse;
import com.cretas.aims.entity.ProductionPlan;
import com.cretas.aims.entity.enums.ProductionPlanStatus;
import com.cretas.aims.entity.enums.PurchaseOrderStatus;
import com.cretas.aims.entity.enums.ReturnOrderStatus;
import com.cretas.aims.entity.enums.ReturnType;
import com.cretas.aims.entity.enums.SalesOrderStatus;
import com.cretas.aims.entity.enums.TransferStatus;
import com.cretas.aims.entity.inventory.InternalTransfer;
import com.cretas.aims.entity.inventory.PaymentRequest;
import com.cretas.aims.entity.inventory.PurchaseInvoice;
import com.cretas.aims.entity.inventory.PurchaseOrder;
import com.cretas.aims.entity.inventory.PurchaseReceiveRecord;
import com.cretas.aims.entity.inventory.ReturnOrder;
import com.cretas.aims.entity.inventory.SalesDeliveryRecord;
import com.cretas.aims.entity.inventory.SalesOrder;
import com.cretas.aims.entity.inventory.TransferDiffRecord;
import com.cretas.aims.exception.ResourceNotFoundException;
import com.cretas.aims.repository.ProductionPlanRepository;
import com.cretas.aims.repository.inventory.InternalTransferRepository;
import com.cretas.aims.repository.inventory.PaymentRequestRepository;
import com.cretas.aims.repository.inventory.PurchaseInvoiceRepository;
import com.cretas.aims.repository.inventory.PurchaseOrderRepository;
import com.cretas.aims.repository.inventory.PurchaseReceiveRecordRepository;
import com.cretas.aims.repository.inventory.ReturnOrderRepository;
import com.cretas.aims.repository.inventory.SalesDeliveryRecordRepository;
import com.cretas.aims.repository.inventory.SalesOrderRepository;
import com.cretas.aims.repository.inventory.TransferDiffRecordRepository;
import org.assertj.core.groups.Tuple;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class BusinessDocumentTraceServiceTest {

    private static final String FACTORY = "F006";
    private static final String OTHER_FACTORY = "F007";

    @Mock private SalesOrderRepository salesOrderRepository;
    @Mock private PurchaseOrderRepository purchaseOrderRepository;
    @Mock private PurchaseReceiveRecordRepository purchaseReceiveRecordRepository;
    @Mock private PurchaseInvoiceRepository purchaseInvoiceRepository;
    @Mock private PaymentRequestRepository paymentRequestRepository;
    @Mock private SalesDeliveryRecordRepository salesDeliveryRecordRepository;
    @Mock private ReturnOrderRepository returnOrderRepository;
    @Mock private ProductionPlanRepository productionPlanRepository;
    @Mock private InternalTransferRepository internalTransferRepository;
    @Mock private TransferDiffRecordRepository transferDiffRecordRepository;

    private BusinessDocumentTraceService service;

    @BeforeEach
    void setUp() {
        service = new BusinessDocumentTraceService(
                salesOrderRepository,
                purchaseOrderRepository,
                purchaseReceiveRecordRepository,
                purchaseInvoiceRepository,
                paymentRequestRepository,
                salesDeliveryRecordRepository,
                returnOrderRepository,
                productionPlanRepository,
                internalTransferRepository,
                transferDiffRecordRepository);
    }

    // ==================== 销售订单 ====================

    @Test
    void tracesSalesOrderUpstreamExecutionAndDownstream() {
        SalesOrder order = salesOrder("so-1", FACTORY, "SO-001");
        when(salesOrderRepository.findById("so-1")).thenReturn(Optional.of(order));

        PurchaseOrder purchaseOrder = purchaseOrder("po-1", FACTORY, "PO-001");
        when(purchaseOrderRepository.findByFactoryIdAndSalesOrderId(eq(FACTORY), eq("so-1"), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(purchaseOrder)));

        PurchaseReceiveRecord receive = new PurchaseReceiveRecord();
        receive.setId("rc-1");
        receive.setReceiveNumber("REC-001");
        when(purchaseReceiveRecordRepository
                .findByFactoryIdAndPurchaseOrderIdOrderByCreatedAtAsc(FACTORY, "po-1"))
                .thenReturn(List.of(receive));

        ProductionPlan plan = productionPlan("plan-1", "PLAN-001");
        when(productionPlanRepository.findByFactoryIdAndSourceOrderIdExact(FACTORY, "so-1"))
                .thenReturn(List.of(plan));
        when(productionPlanRepository.findByFactoryIdAndSourceOrderIdsContaining(eq(FACTORY), anyString()))
                .thenReturn(List.of(plan));   // 同一张计划两条路径都命中 → 必须去重

        SalesDeliveryRecord delivery = new SalesDeliveryRecord();
        delivery.setId("dl-1");
        delivery.setFactoryId(FACTORY);
        delivery.setDeliveryNumber("DEL-001");
        when(salesDeliveryRecordRepository.findBySalesOrderId("so-1")).thenReturn(List.of(delivery));

        ReturnOrder salesReturn = returnOrder("ret-1", "SR-001");
        when(returnOrderRepository.findByFactoryIdAndReturnTypeAndSourceOrderIdOrderByCreatedAtDesc(
                FACTORY, ReturnType.SALES_RETURN, "so-1")).thenReturn(List.of(salesReturn));

        BusinessDocumentTraceResponse result = service.traceSalesOrder(FACTORY, "so-1");

        assertThat(result.getAnchorType()).isEqualTo("SALES_ORDER");
        assertThat(result.getAnchorNumber()).isEqualTo("SO-001");
        assertThat(result.getDocuments())
                .extracting(BusinessDocumentTraceResponse.TraceDocument::getDocumentType,
                        BusinessDocumentTraceResponse.TraceDocument::getDocumentNumber,
                        BusinessDocumentTraceResponse.TraceDocument::getDirection)
                .containsExactly(
                        Tuple.tuple("PURCHASE_ORDER", "PO-001", "UPSTREAM"),
                        Tuple.tuple("PURCHASE_RECEIPT", "REC-001", "UPSTREAM"),
                        Tuple.tuple("PRODUCTION_PLAN", "PLAN-001", "EXECUTION"),
                        Tuple.tuple("SALES_DELIVERY", "DEL-001", "DOWNSTREAM"),
                        Tuple.tuple("SALES_RETURN", "SR-001", "DOWNSTREAM"));
        assertThat(result.getMissingLinks()).isEmpty();
    }

    @Test
    void salesOrderTraceRefusesAnotherFactoryOrder() {
        SalesOrder foreign = salesOrder("so-x", OTHER_FACTORY, "SO-SECRET");
        when(salesOrderRepository.findById("so-x")).thenReturn(Optional.of(foreign));

        assertThatThrownBy(() -> service.traceSalesOrder(FACTORY, "so-x"))
                .isInstanceOf(ResourceNotFoundException.class);
        // 跨租户请求不得继续往下查任何关联表
        verify(purchaseOrderRepository, never())
                .findByFactoryIdAndSalesOrderId(anyString(), anyString(), any(Pageable.class));
    }

    @Test
    void salesOrderTraceReportsCrossFactoryDeliveryInsteadOfRenderingIt() {
        when(salesOrderRepository.findById("so-1")).thenReturn(Optional.of(salesOrder("so-1", FACTORY, "SO-001")));
        when(purchaseOrderRepository.findByFactoryIdAndSalesOrderId(eq(FACTORY), eq("so-1"), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of()));
        when(productionPlanRepository.findByFactoryIdAndSourceOrderIdExact(FACTORY, "so-1")).thenReturn(List.of());
        when(productionPlanRepository.findByFactoryIdAndSourceOrderIdsContaining(eq(FACTORY), anyString()))
                .thenReturn(List.of());

        SalesDeliveryRecord foreignDelivery = new SalesDeliveryRecord();
        foreignDelivery.setId("dl-foreign");
        foreignDelivery.setFactoryId(OTHER_FACTORY);
        foreignDelivery.setDeliveryNumber("DEL-SECRET");
        when(salesDeliveryRecordRepository.findBySalesOrderId("so-1")).thenReturn(List.of(foreignDelivery));
        when(returnOrderRepository.findByFactoryIdAndReturnTypeAndSourceOrderIdOrderByCreatedAtDesc(
                FACTORY, ReturnType.SALES_RETURN, "so-1")).thenReturn(List.of());

        BusinessDocumentTraceResponse result = service.traceSalesOrder(FACTORY, "so-1");

        assertThat(result.getDocuments()).isEmpty();
        // 静默丢弃是禁的: 必须说出来, 但不能泄漏别厂的单号
        assertThat(result.getMissingLinks()).singleElement().asString()
                .contains("dl-foreign").doesNotContain("DEL-SECRET");
    }

    // ==================== 采购订单 ====================

    @Test
    void tracesPurchaseOrderBackToItsSourceSalesOrder() {
        PurchaseOrder order = purchaseOrder("po-1", FACTORY, "PO-001");
        order.setSalesOrderId("so-1");
        when(purchaseOrderRepository.findByIdAndFactoryId("po-1", FACTORY)).thenReturn(Optional.of(order));
        when(salesOrderRepository.findById("so-1")).thenReturn(Optional.of(salesOrder("so-1", FACTORY, "SO-001")));

        PurchaseInvoice invoice = new PurchaseInvoice();
        invoice.setId("inv-1");
        invoice.setInvoiceNumber("INV-001");
        invoice.setReconcileStatus("MATCHED");
        when(purchaseInvoiceRepository.findByFactoryIdAndPurchaseOrderId(FACTORY, "po-1"))
                .thenReturn(List.of(invoice));

        PaymentRequest payment = new PaymentRequest();
        payment.setId("pay-1");
        payment.setFactoryId(FACTORY);
        payment.setRequestNumber("PAY-001");
        when(paymentRequestRepository.findByPurchaseOrderId("po-1")).thenReturn(List.of(payment));

        when(purchaseReceiveRecordRepository
                .findByFactoryIdAndPurchaseOrderIdOrderByCreatedAtAsc(FACTORY, "po-1")).thenReturn(List.of());
        when(returnOrderRepository.findByFactoryIdAndReturnTypeAndSourceOrderIdOrderByCreatedAtDesc(
                FACTORY, ReturnType.PURCHASE_RETURN, "po-1")).thenReturn(List.of());

        BusinessDocumentTraceResponse result = service.tracePurchaseOrder(FACTORY, "po-1");

        assertThat(result.getAnchorType()).isEqualTo("PURCHASE_ORDER");
        assertThat(result.getDocuments())
                .extracting(BusinessDocumentTraceResponse.TraceDocument::getDocumentType,
                        BusinessDocumentTraceResponse.TraceDocument::getDocumentNumber,
                        BusinessDocumentTraceResponse.TraceDocument::getDirection)
                .containsExactly(
                        Tuple.tuple("SALES_ORDER", "SO-001", "UPSTREAM"),
                        Tuple.tuple("PURCHASE_INVOICE", "INV-001", "DOWNSTREAM"),
                        Tuple.tuple("PAYMENT_REQUEST", "PAY-001", "DOWNSTREAM"));
        assertThat(result.getMissingLinks()).isEmpty();
    }

    @Test
    void purchaseOrderTraceReportsBrokenSourceSalesOrderInsteadOfHidingIt() {
        PurchaseOrder order = purchaseOrder("po-1", FACTORY, "PO-001");
        order.setSalesOrderId("so-gone");
        when(purchaseOrderRepository.findByIdAndFactoryId("po-1", FACTORY)).thenReturn(Optional.of(order));
        when(salesOrderRepository.findById("so-gone")).thenReturn(Optional.empty());
        when(purchaseReceiveRecordRepository
                .findByFactoryIdAndPurchaseOrderIdOrderByCreatedAtAsc(FACTORY, "po-1")).thenReturn(List.of());
        when(purchaseInvoiceRepository.findByFactoryIdAndPurchaseOrderId(FACTORY, "po-1")).thenReturn(List.of());
        when(paymentRequestRepository.findByPurchaseOrderId("po-1")).thenReturn(List.of());
        when(returnOrderRepository.findByFactoryIdAndReturnTypeAndSourceOrderIdOrderByCreatedAtDesc(
                FACTORY, ReturnType.PURCHASE_RETURN, "po-1")).thenReturn(List.of());

        BusinessDocumentTraceResponse result = service.tracePurchaseOrder(FACTORY, "po-1");

        assertThat(result.getDocuments()).isEmpty();
        assertThat(result.getMissingLinks()).singleElement().asString().contains("so-gone");
    }

    @Test
    void purchaseOrderTraceUsesPurchaseReturnDiscriminatorNotBareSourceOrderId() {
        PurchaseOrder order = purchaseOrder("po-1", FACTORY, "PO-001");
        when(purchaseOrderRepository.findByIdAndFactoryId("po-1", FACTORY)).thenReturn(Optional.of(order));
        when(purchaseReceiveRecordRepository
                .findByFactoryIdAndPurchaseOrderIdOrderByCreatedAtAsc(FACTORY, "po-1")).thenReturn(List.of());
        when(purchaseInvoiceRepository.findByFactoryIdAndPurchaseOrderId(FACTORY, "po-1")).thenReturn(List.of());
        when(paymentRequestRepository.findByPurchaseOrderId("po-1")).thenReturn(List.of());
        when(returnOrderRepository.findByFactoryIdAndReturnTypeAndSourceOrderIdOrderByCreatedAtDesc(
                FACTORY, ReturnType.PURCHASE_RETURN, "po-1")).thenReturn(List.of(returnOrder("ret-2", "PR-001")));

        BusinessDocumentTraceResponse result = service.tracePurchaseOrder(FACTORY, "po-1");

        assertThat(result.getDocuments())
                .extracting(BusinessDocumentTraceResponse.TraceDocument::getDocumentType)
                .containsExactly("PURCHASE_RETURN");
        // sourceOrderId 的 ID 空间由 returnType 区分, 销售退货绝不能混进采购单的链路
        verify(returnOrderRepository, never())
                .findByFactoryIdAndReturnTypeAndSourceOrderIdOrderByCreatedAtDesc(
                        FACTORY, ReturnType.SALES_RETURN, "po-1");
    }

    // ==================== 调拨单 ====================

    @Test
    void tracesTransferBackToItsProductionPlanAndForwardToDiffs() {
        InternalTransfer transfer = transfer("tr-1", FACTORY, FACTORY, "TRF-001");
        transfer.setProductionPlanId("plan-1");
        when(internalTransferRepository.findByIdAndEitherFactoryId("tr-1", FACTORY))
                .thenReturn(Optional.of(transfer));
        when(productionPlanRepository.findByIdAndFactoryId("plan-1", FACTORY))
                .thenReturn(Optional.of(productionPlan("plan-1", "PLAN-001")));

        TransferDiffRecord diff = new TransferDiffRecord();
        diff.setId("diff-1");
        diff.setDiffNumber("DIFF-001");
        diff.setSourceFactoryId(FACTORY);
        diff.setTargetFactoryId(FACTORY);
        diff.setStatus("PENDING");
        when(transferDiffRecordRepository.findByTransferIdOrderByCreatedAtDesc("tr-1"))
                .thenReturn(List.of(diff));

        BusinessDocumentTraceResponse result = service.traceInternalTransfer(FACTORY, "tr-1");

        assertThat(result.getAnchorType()).isEqualTo("INTERNAL_TRANSFER");
        assertThat(result.getAnchorNumber()).isEqualTo("TRF-001");
        assertThat(result.getDocuments())
                .extracting(BusinessDocumentTraceResponse.TraceDocument::getDocumentType,
                        BusinessDocumentTraceResponse.TraceDocument::getDocumentNumber,
                        BusinessDocumentTraceResponse.TraceDocument::getDirection)
                .containsExactly(
                        Tuple.tuple("PRODUCTION_PLAN", "PLAN-001", "UPSTREAM"),
                        Tuple.tuple("TRANSFER_DIFF", "DIFF-001", "DOWNSTREAM"));
        assertThat(result.getMissingLinks()).isEmpty();
    }

    @Test
    void transferTraceTellsInboundFactoryThePlanBelongsToTheSourceFactory() {
        // 跨厂调拨, 请求方是调入方 → 计划归调出方, 本就查不到。
        // 这时说"链接失效"是撒谎, 必须说清是权限边界。
        InternalTransfer transfer = transfer("tr-2", OTHER_FACTORY, FACTORY, "TRF-002");
        transfer.setProductionPlanId("plan-of-source");
        when(internalTransferRepository.findByIdAndEitherFactoryId("tr-2", FACTORY))
                .thenReturn(Optional.of(transfer));
        when(productionPlanRepository.findByIdAndFactoryId("plan-of-source", FACTORY))
                .thenReturn(Optional.empty());
        when(transferDiffRecordRepository.findByTransferIdOrderByCreatedAtDesc("tr-2")).thenReturn(List.of());

        BusinessDocumentTraceResponse result = service.traceInternalTransfer(FACTORY, "tr-2");

        assertThat(result.getDocuments()).isEmpty();
        assertThat(result.getMissingLinks()).singleElement().asString()
                .contains(OTHER_FACTORY).contains("无权查看").doesNotContain("失效");
    }

    @Test
    void transferTraceReportsBrokenPlanLinkWhenTheTransferIsOwnFactory() {
        InternalTransfer transfer = transfer("tr-3", FACTORY, FACTORY, "TRF-003");
        transfer.setProductionPlanId("plan-gone");
        when(internalTransferRepository.findByIdAndEitherFactoryId("tr-3", FACTORY))
                .thenReturn(Optional.of(transfer));
        when(productionPlanRepository.findByIdAndFactoryId("plan-gone", FACTORY)).thenReturn(Optional.empty());
        when(transferDiffRecordRepository.findByTransferIdOrderByCreatedAtDesc("tr-3")).thenReturn(List.of());

        BusinessDocumentTraceResponse result = service.traceInternalTransfer(FACTORY, "tr-3");

        assertThat(result.getMissingLinks()).singleElement().asString()
                .contains("plan-gone").contains("失效");
    }

    @Test
    void transferTraceRefusesATransferOfAnotherFactoryPair() {
        when(internalTransferRepository.findByIdAndEitherFactoryId("tr-x", FACTORY)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.traceInternalTransfer(FACTORY, "tr-x"))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    // ==================== fixtures ====================

    private SalesOrder salesOrder(String id, String factoryId, String number) {
        SalesOrder order = new SalesOrder();
        order.setId(id);
        order.setFactoryId(factoryId);
        order.setOrderNumber(number);
        order.setStatus(SalesOrderStatus.CONFIRMED);
        return order;
    }

    private PurchaseOrder purchaseOrder(String id, String factoryId, String number) {
        PurchaseOrder order = new PurchaseOrder();
        order.setId(id);
        order.setFactoryId(factoryId);
        order.setOrderNumber(number);
        order.setStatus(PurchaseOrderStatus.APPROVED);
        return order;
    }

    private ProductionPlan productionPlan(String id, String number) {
        ProductionPlan plan = new ProductionPlan();
        plan.setId(id);
        plan.setFactoryId(FACTORY);
        plan.setPlanNumber(number);
        plan.setStatus(ProductionPlanStatus.IN_PROGRESS);
        return plan;
    }

    private ReturnOrder returnOrder(String id, String number) {
        ReturnOrder order = new ReturnOrder();
        order.setId(id);
        order.setFactoryId(FACTORY);
        order.setReturnNumber(number);
        order.setStatus(ReturnOrderStatus.SUBMITTED);
        return order;
    }

    private InternalTransfer transfer(String id, String sourceFactoryId, String targetFactoryId, String number) {
        InternalTransfer transfer = new InternalTransfer();
        transfer.setId(id);
        transfer.setSourceFactoryId(sourceFactoryId);
        transfer.setTargetFactoryId(targetFactoryId);
        transfer.setTransferNumber(number);
        transfer.setStatus(TransferStatus.SHIPPED);
        return transfer;
    }
}
