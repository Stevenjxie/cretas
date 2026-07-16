package com.cretas.aims.service.production;

import com.cretas.aims.dto.production.ProductionDocumentTraceResponse;
import com.cretas.aims.entity.ProductionBatch;
import com.cretas.aims.entity.ProductionPlan;
import com.cretas.aims.entity.ProductionSettlement;
import com.cretas.aims.entity.enums.ProductionBatchStatus;
import com.cretas.aims.entity.enums.ProductionPlanStatus;
import com.cretas.aims.entity.factory.FactoryMaterialRequisition;
import com.cretas.aims.entity.inventory.FinishedGoodsBatch;
import com.cretas.aims.entity.inventory.PurchaseOrder;
import com.cretas.aims.entity.inventory.PurchaseReceiveRecord;
import com.cretas.aims.entity.inventory.SalesDeliveryRecord;
import com.cretas.aims.entity.inventory.SalesOrder;
import com.cretas.aims.repository.ProductionBatchRepository;
import com.cretas.aims.repository.ProductionPlanRepository;
import com.cretas.aims.repository.ProductionSettlementRepository;
import com.cretas.aims.repository.factory.FactoryMaterialRequisitionRepository;
import com.cretas.aims.repository.inventory.FinishedGoodsBatchRepository;
import com.cretas.aims.repository.inventory.PurchaseOrderRepository;
import com.cretas.aims.repository.inventory.PurchaseReceiveRecordRepository;
import com.cretas.aims.repository.inventory.SalesDeliveryRecordRepository;
import com.cretas.aims.repository.inventory.SalesOrderRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ProductionDocumentTraceServiceTest {

    @Mock private ProductionPlanRepository productionPlanRepository;
    @Mock private SalesOrderRepository salesOrderRepository;
    @Mock private PurchaseOrderRepository purchaseOrderRepository;
    @Mock private PurchaseReceiveRecordRepository purchaseReceiveRecordRepository;
    @Mock private FactoryMaterialRequisitionRepository materialRequisitionRepository;
    @Mock private ProductionBatchRepository productionBatchRepository;
    @Mock private ProductionSettlementRepository productionSettlementRepository;
    @Mock private FinishedGoodsBatchRepository finishedGoodsBatchRepository;
    @Mock private SalesDeliveryRecordRepository salesDeliveryRecordRepository;

    private ProductionDocumentTraceService service;

    @BeforeEach
    void setUp() {
        service = new ProductionDocumentTraceService(
                productionPlanRepository,
                salesOrderRepository,
                purchaseOrderRepository,
                purchaseReceiveRecordRepository,
                materialRequisitionRepository,
                productionBatchRepository,
                productionSettlementRepository,
                finishedGoodsBatchRepository,
                salesDeliveryRecordRepository);
    }

    @Test
    void tracesUpstreamAndDownstreamDocumentsFromOneProductionPlan() {
        ProductionPlan plan = new ProductionPlan();
        plan.setId("plan-1");
        plan.setFactoryId("F006");
        plan.setPlanNumber("PLAN-001");
        plan.setStatus(ProductionPlanStatus.IN_PROGRESS);
        plan.setSourceOrderId("so-1");
        plan.setSourceOrderIds(List.of("so-1"));
        when(productionPlanRepository.findByIdAndFactoryId("plan-1", "F006"))
                .thenReturn(Optional.of(plan));

        SalesOrder salesOrder = new SalesOrder();
        salesOrder.setId("so-1");
        salesOrder.setFactoryId("F006");
        salesOrder.setOrderNumber("SO-001");
        when(salesOrderRepository.findById("so-1")).thenReturn(Optional.of(salesOrder));

        PurchaseOrder purchaseOrder = new PurchaseOrder();
        purchaseOrder.setId("po-1");
        purchaseOrder.setFactoryId("F006");
        purchaseOrder.setOrderNumber("PO-001");
        purchaseOrder.setSalesOrderId("so-1");
        when(purchaseOrderRepository.findByFactoryIdAndSalesOrderId(
                org.mockito.ArgumentMatchers.eq("F006"),
                org.mockito.ArgumentMatchers.eq("so-1"), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(purchaseOrder)));

        PurchaseReceiveRecord receive = new PurchaseReceiveRecord();
        receive.setId("receive-1");
        receive.setReceiveNumber("REC-001");
        when(purchaseReceiveRecordRepository.findByPurchaseOrderId("po-1"))
                .thenReturn(List.of(receive));

        FactoryMaterialRequisition requisition = new FactoryMaterialRequisition();
        requisition.setId("mr-1");
        requisition.setRequisitionNo("MR-001");
        when(materialRequisitionRepository.findByFactoryIdAndProductionPlanIdAndDeletedAtIsNull("F006", "plan-1"))
                .thenReturn(List.of(requisition));

        ProductionBatch batch = new ProductionBatch();
        batch.setId(101L);
        batch.setBatchNumber("PB-001");
        batch.setStatus(ProductionBatchStatus.IN_PROGRESS);
        when(productionBatchRepository.findByFactoryIdAndProductionPlanId("F006", "plan-1"))
                .thenReturn(List.of(batch));

        ProductionSettlement settlement = new ProductionSettlement();
        settlement.setId("settlement-1");
        settlement.setPlanNumber("PLAN-001");
        settlement.setPostingStatus("POSTED");
        when(productionSettlementRepository.findByFactoryIdAndProductionPlanIdAndDeletedAtIsNull("F006", "plan-1"))
                .thenReturn(Optional.of(settlement));

        FinishedGoodsBatch finishedGoods = new FinishedGoodsBatch();
        finishedGoods.setId("fg-1");
        finishedGoods.setBatchNumber("FG-001");
        finishedGoods.setStatus(FinishedGoodsBatch.Status.AVAILABLE);
        when(finishedGoodsBatchRepository.findByFactoryIdAndProductionPlanIdAndDeletedAtIsNull("F006", "plan-1"))
                .thenReturn(List.of(finishedGoods));

        SalesDeliveryRecord delivery = new SalesDeliveryRecord();
        delivery.setId("delivery-1");
        delivery.setDeliveryNumber("DEL-001");
        when(salesDeliveryRecordRepository.findBySalesOrderId("so-1"))
                .thenReturn(List.of(delivery));

        ProductionDocumentTraceResponse result = service.trace("F006", "plan-1");

        assertThat(result.getPlanNumber()).isEqualTo("PLAN-001");
        assertThat(result.getDocuments())
                .extracting(ProductionDocumentTraceResponse.TraceDocument::getDocumentType,
                        ProductionDocumentTraceResponse.TraceDocument::getDocumentNumber)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple("SALES_ORDER", "SO-001"),
                        org.assertj.core.groups.Tuple.tuple("PURCHASE_ORDER", "PO-001"),
                        org.assertj.core.groups.Tuple.tuple("PURCHASE_RECEIPT", "REC-001"),
                        org.assertj.core.groups.Tuple.tuple("MATERIAL_REQUISITION", "MR-001"),
                        org.assertj.core.groups.Tuple.tuple("PRODUCTION_BATCH", "PB-001"),
                        org.assertj.core.groups.Tuple.tuple("PRODUCTION_SETTLEMENT", "PLAN-001"),
                        org.assertj.core.groups.Tuple.tuple("FINISHED_GOODS_BATCH", "FG-001"),
                        org.assertj.core.groups.Tuple.tuple("SALES_DELIVERY", "DEL-001"));
        assertThat(result.getMissingLinks()).isEmpty();
    }

    @Test
    void reportsBrokenExplicitSourceAndNeverLeaksAnotherFactoryOrder() {
        ProductionPlan plan = new ProductionPlan();
        plan.setId("plan-2");
        plan.setFactoryId("F006");
        plan.setPlanNumber("PLAN-002");
        plan.setSourceOrderId("so-other-factory");
        when(productionPlanRepository.findByIdAndFactoryId("plan-2", "F006"))
                .thenReturn(Optional.of(plan));

        SalesOrder otherFactoryOrder = new SalesOrder();
        otherFactoryOrder.setId("so-other-factory");
        otherFactoryOrder.setFactoryId("F007");
        otherFactoryOrder.setOrderNumber("SO-SECRET");
        when(salesOrderRepository.findById("so-other-factory"))
                .thenReturn(Optional.of(otherFactoryOrder));
        when(materialRequisitionRepository.findByFactoryIdAndProductionPlanIdAndDeletedAtIsNull("F006", "plan-2"))
                .thenReturn(List.of());
        when(productionBatchRepository.findByFactoryIdAndProductionPlanId("F006", "plan-2"))
                .thenReturn(List.of());
        when(productionSettlementRepository.findByFactoryIdAndProductionPlanIdAndDeletedAtIsNull("F006", "plan-2"))
                .thenReturn(Optional.empty());
        when(finishedGoodsBatchRepository.findByFactoryIdAndProductionPlanIdAndDeletedAtIsNull("F006", "plan-2"))
                .thenReturn(List.of());

        ProductionDocumentTraceResponse result = service.trace("F006", "plan-2");

        assertThat(result.getDocuments()).isEmpty();
        assertThat(result.getMissingLinks()).singleElement()
                .asString().contains("so-other-factory");
    }
}
