package com.cretas.aims.service.production;

import com.cretas.aims.dto.production.ProductionDocumentTraceResponse;
import com.cretas.aims.entity.BaseEntity;
import com.cretas.aims.entity.ProductionPlan;
import com.cretas.aims.entity.factory.FactoryMaterialRequisition;
import com.cretas.aims.entity.inventory.PurchaseOrder;
import com.cretas.aims.entity.inventory.SalesOrder;
import com.cretas.aims.exception.ResourceNotFoundException;
import com.cretas.aims.repository.ProductionBatchRepository;
import com.cretas.aims.repository.ProductionPlanRepository;
import com.cretas.aims.repository.ProductionSettlementRepository;
import com.cretas.aims.repository.factory.FactoryMaterialRequisitionRepository;
import com.cretas.aims.repository.inventory.FinishedGoodsBatchRepository;
import com.cretas.aims.repository.inventory.PurchaseOrderRepository;
import com.cretas.aims.repository.inventory.PurchaseReceiveRecordRepository;
import com.cretas.aims.repository.inventory.SalesDeliveryRecordRepository;
import com.cretas.aims.repository.inventory.SalesOrderRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/** Builds an honest, tenant-scoped document lineage without inventing inferred links. */
@Service
@RequiredArgsConstructor
public class ProductionDocumentTraceService {

    private final ProductionPlanRepository productionPlanRepository;
    private final SalesOrderRepository salesOrderRepository;
    private final PurchaseOrderRepository purchaseOrderRepository;
    private final PurchaseReceiveRecordRepository purchaseReceiveRecordRepository;
    private final FactoryMaterialRequisitionRepository materialRequisitionRepository;
    private final ProductionBatchRepository productionBatchRepository;
    private final ProductionSettlementRepository productionSettlementRepository;
    private final FinishedGoodsBatchRepository finishedGoodsBatchRepository;
    private final SalesDeliveryRecordRepository salesDeliveryRecordRepository;

    @Transactional(readOnly = true)
    public ProductionDocumentTraceResponse trace(String factoryId, String productionPlanId) {
        ProductionPlan plan = productionPlanRepository.findByIdAndFactoryId(productionPlanId, factoryId)
                .orElseThrow(() -> new ResourceNotFoundException("ProductionPlan", "id", productionPlanId));

        List<ProductionDocumentTraceResponse.TraceDocument> documents = new ArrayList<>();
        List<String> missingLinks = new ArrayList<>();
        Set<String> sourceOrderIds = new LinkedHashSet<>();
        if (plan.getSourceOrderIds() != null) {
            plan.getSourceOrderIds().stream().filter(Objects::nonNull).filter(id -> !id.isBlank())
                    .forEach(sourceOrderIds::add);
        }
        if (plan.getSourceOrderId() != null && !plan.getSourceOrderId().isBlank()) {
            sourceOrderIds.add(plan.getSourceOrderId());
        }

        List<SalesOrder> salesOrders = new ArrayList<>();
        for (String sourceOrderId : sourceOrderIds) {
            SalesOrder salesOrder = salesOrderRepository.findById(sourceOrderId)
                    .filter(order -> factoryId.equals(order.getFactoryId()))
                    .orElse(null);
            if (salesOrder == null) {
                missingLinks.add("销售订单链接失效: " + sourceOrderId);
                continue;
            }
            salesOrders.add(salesOrder);
            documents.add(document("SALES_ORDER", salesOrder.getId(), salesOrder.getOrderNumber(),
                    salesOrder.getStatus(), "UPSTREAM", "计划来源销售订单", salesOrder));

            List<PurchaseOrder> orders = purchaseOrderRepository
                    .findByFactoryIdAndSalesOrderId(factoryId, sourceOrderId, PageRequest.of(0, 200))
                    .getContent();
            for (PurchaseOrder purchaseOrder : orders) {
                documents.add(document("PURCHASE_ORDER", purchaseOrder.getId(), purchaseOrder.getOrderNumber(),
                        purchaseOrder.getStatus(), "UPSTREAM", "销售订单生成采购", purchaseOrder));
                purchaseReceiveRecordRepository.findByPurchaseOrderId(purchaseOrder.getId()).forEach(receive ->
                        documents.add(document("PURCHASE_RECEIPT", receive.getId(), receive.getReceiveNumber(),
                                receive.getStatus(), "UPSTREAM", "采购到货入库", receive)));
            }
        }

        List<FactoryMaterialRequisition> requisitions = materialRequisitionRepository
                .findByFactoryIdAndProductionPlanIdAndDeletedAtIsNull(factoryId, productionPlanId);
        requisitions.forEach(requisition -> documents.add(document(
                "MATERIAL_REQUISITION", requisition.getId(), requisition.getRequisitionNo(),
                requisition.getStatus(), "EXECUTION", "生产领料", requisition)));

        productionBatchRepository.findByFactoryIdAndProductionPlanId(factoryId, productionPlanId)
                .forEach(batch -> documents.add(document(
                        "PRODUCTION_BATCH", String.valueOf(batch.getId()), batch.getBatchNumber(),
                        batch.getStatus(), "EXECUTION", "计划生产批次", batch)));

        productionSettlementRepository.findByFactoryIdAndProductionPlanIdAndDeletedAtIsNull(factoryId, productionPlanId)
                .ifPresent(settlement -> documents.add(document(
                        "PRODUCTION_SETTLEMENT", settlement.getId(), settlement.getPlanNumber(),
                        settlement.getPostingStatus(), "DOWNSTREAM", "核对结单", settlement)));

        finishedGoodsBatchRepository.findByFactoryIdAndProductionPlanIdAndDeletedAtIsNull(factoryId, productionPlanId)
                .forEach(batch -> documents.add(document(
                        "FINISHED_GOODS_BATCH", batch.getId(), batch.getBatchNumber(), batch.getStatus(),
                        "DOWNSTREAM", "计划产出成品", batch)));

        for (SalesOrder salesOrder : salesOrders) {
            salesDeliveryRecordRepository.findBySalesOrderId(salesOrder.getId()).forEach(delivery ->
                    documents.add(document("SALES_DELIVERY", delivery.getId(), delivery.getDeliveryNumber(),
                            delivery.getStatus(), "DOWNSTREAM", "销售订单出库", delivery)));
        }

        return ProductionDocumentTraceResponse.builder()
                .productionPlanId(plan.getId())
                .planNumber(plan.getPlanNumber())
                .planStatus(asText(plan.getStatus()))
                .documents(documents)
                .missingLinks(missingLinks)
                .build();
    }

    private ProductionDocumentTraceResponse.TraceDocument document(
            String type, String id, String number, Object status,
            String direction, String relation, BaseEntity entity) {
        LocalDateTime occurredAt = entity == null ? null : entity.getCreatedAt();
        return ProductionDocumentTraceResponse.TraceDocument.builder()
                .documentType(type)
                .documentId(id)
                .documentNumber(number)
                .status(asText(status))
                .direction(direction)
                .relation(relation)
                .occurredAt(occurredAt)
                .build();
    }

    private String asText(Object value) {
        return value == null ? null : String.valueOf(value);
    }
}
