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
                .details(detailsOf(entity))
                .build();
    }

    /**
     * 从**已经读出来的**实体上摘几个关键字段, 供前端就地展开 (客户 2026-07-31: 追踪里看详情不跳页)。
     *
     * <p>与 {@code BusinessDocumentTraceService#detailsOf} 同一口径: 只放"看一眼就能确认是不是
     * 这张单"的字段; 拿不到的**直接不放**(空标签会让用户以为"这张单没有客户", 而事实是没填);
     * 没列到的类型返回空列表, 前端只显示链路本身的字段并说明原因。</p>
     */
    private List<ProductionDocumentTraceResponse.Field> detailsOf(BaseEntity entity) {
        List<ProductionDocumentTraceResponse.Field> out = new ArrayList<>();
        if (entity instanceof SalesOrder so) {
            put(out, "客户", so.getCustomerName());
            put(out, "下单日期", text(so.getOrderDate()));
            put(out, "订单金额", money(so.getTotalAmount()));
        } else if (entity instanceof PurchaseOrder po) {
            put(out, "供应商", po.getSupplierName());
            put(out, "下单日期", text(po.getOrderDate()));
            put(out, "订单金额", money(po.getTotalAmount()));
        } else if (entity instanceof FactoryMaterialRequisition req) {
            put(out, "领料单号", req.getRequisitionNo());
        }
        return out;
    }

    private void put(List<ProductionDocumentTraceResponse.Field> out, String label, String value) {
        if (value == null || value.isBlank()) {
            return;
        }
        out.add(ProductionDocumentTraceResponse.Field.builder().label(label).value(value.trim()).build());
    }

    private String text(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private String money(java.math.BigDecimal value) {
        return value == null ? null : "¥" + value.setScale(2, java.math.RoundingMode.HALF_UP).toPlainString();
    }

    private String asText(Object value) {
        return value == null ? null : String.valueOf(value);
    }
}
