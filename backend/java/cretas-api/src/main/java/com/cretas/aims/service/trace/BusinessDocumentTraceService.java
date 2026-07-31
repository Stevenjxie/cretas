package com.cretas.aims.service.trace;

import com.cretas.aims.dto.trace.BusinessDocumentTraceResponse;
import com.cretas.aims.entity.BaseEntity;
import com.cretas.aims.entity.ProductionPlan;
import com.cretas.aims.entity.enums.ReturnType;
import com.cretas.aims.entity.inventory.InternalTransfer;
import com.cretas.aims.entity.inventory.PurchaseOrder;
import com.cretas.aims.entity.inventory.PurchaseReceiveRecord;
import com.cretas.aims.entity.inventory.SalesDeliveryRecord;
import com.cretas.aims.entity.inventory.SalesOrder;
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
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 销售 / 采购 / 调拨 三类单据的上下游追踪。
 *
 * <p>与 {@link com.cretas.aims.service.production.ProductionDocumentTraceService} 同一条诚实原则:
 * <b>只连真实持久化的外键</b>, 不按单号前缀 / 日期 / 数量做推断式连线。已记录但当前解析不出来的
 * 链接进 {@code missingLinks} 明说, 不静默省略。
 *
 * <p>租户隔离: 锚点单据本身用工厂内查询取, 每条下游记录再逐条核对 factoryId —— 只要有一条
 * 跨工厂就进 {@code missingLinks}, 绝不把别的工厂的单号渲染给用户。
 */
@Service
@RequiredArgsConstructor
public class BusinessDocumentTraceService {

    /** 一张销售单最多展开的采购订单条数; 超出部分不静默截断, 会在 missingLinks 里说明。 */
    private static final int MAX_FANOUT = 200;

    private final SalesOrderRepository salesOrderRepository;
    private final PurchaseOrderRepository purchaseOrderRepository;
    private final PurchaseReceiveRecordRepository purchaseReceiveRecordRepository;
    private final PurchaseInvoiceRepository purchaseInvoiceRepository;
    private final PaymentRequestRepository paymentRequestRepository;
    private final SalesDeliveryRecordRepository salesDeliveryRecordRepository;
    private final ReturnOrderRepository returnOrderRepository;
    private final ProductionPlanRepository productionPlanRepository;
    private final InternalTransferRepository internalTransferRepository;
    private final TransferDiffRecordRepository transferDiffRecordRepository;

    // ==================== 销售订单 ====================

    @Transactional(readOnly = true)
    public BusinessDocumentTraceResponse traceSalesOrder(String factoryId, String salesOrderId) {
        SalesOrder order = salesOrderRepository.findById(salesOrderId)
                .filter(so -> factoryId.equals(so.getFactoryId()))
                .orElseThrow(() -> new ResourceNotFoundException("SalesOrder", "id", salesOrderId));

        List<BusinessDocumentTraceResponse.TraceDocument> documents = new ArrayList<>();
        List<String> missingLinks = new ArrayList<>();

        // 上游: purchase_orders.sales_order_id
        Page<PurchaseOrder> purchaseOrderPage = purchaseOrderRepository
                .findByFactoryIdAndSalesOrderId(factoryId, salesOrderId, PageRequest.of(0, MAX_FANOUT));
        if (purchaseOrderPage.getTotalElements() > MAX_FANOUT) {
            missingLinks.add("关联采购订单共 " + purchaseOrderPage.getTotalElements()
                    + " 条, 本页只展示前 " + MAX_FANOUT + " 条, 请到采购订单列表按本销售单筛选查看全部");
        }
        for (PurchaseOrder purchaseOrder : purchaseOrderPage.getContent()) {
            documents.add(document("PURCHASE_ORDER", purchaseOrder.getId(), purchaseOrder.getOrderNumber(),
                    purchaseOrder.getStatus(), "UPSTREAM", "为本订单采购", purchaseOrder));
            // 上游: purchase_receive_records.purchase_order_id
            purchaseReceiveRecordRepository
                    .findByFactoryIdAndPurchaseOrderIdOrderByCreatedAtAsc(factoryId, purchaseOrder.getId())
                    .forEach(receive -> documents.add(document(
                            "PURCHASE_RECEIPT", receive.getId(), receive.getReceiveNumber(),
                            receive.getStatus(), "UPSTREAM", "采购到货入库", receive)));
        }

        // 执行: production_plans.source_order_id (单 SO) ∪ source_order_ids (多 SO 合并工单)
        Map<String, ProductionPlan> plans = new LinkedHashMap<>();
        productionPlanRepository.findByFactoryIdAndSourceOrderIdExact(factoryId, salesOrderId)
                .forEach(plan -> plans.put(plan.getId(), plan));
        productionPlanRepository
                .findByFactoryIdAndSourceOrderIdsContaining(factoryId, jsonIdArray(salesOrderId))
                .forEach(plan -> plans.putIfAbsent(plan.getId(), plan));
        plans.values().forEach(plan -> documents.add(document(
                "PRODUCTION_PLAN", plan.getId(), plan.getPlanNumber(), plan.getStatus(),
                "EXECUTION", "订单排产", plan)));

        // 下游: sales_delivery_records.sales_order_id
        salesDeliveryRecordRepository.findBySalesOrderId(salesOrderId).forEach(delivery -> {
            if (!factoryId.equals(delivery.getFactoryId())) {
                missingLinks.add("销售出库单跨工厂异常, 已隐藏: " + delivery.getId());
                return;
            }
            documents.add(document("SALES_DELIVERY", delivery.getId(), delivery.getDeliveryNumber(),
                    delivery.getStatus(), "DOWNSTREAM", "销售订单出库", delivery));
        });

        // 下游: return_orders.source_order_id (return_type = SALES_RETURN)
        returnOrderRepository
                .findByFactoryIdAndReturnTypeAndSourceOrderIdOrderByCreatedAtDesc(
                        factoryId, ReturnType.SALES_RETURN, salesOrderId)
                .forEach(ret -> documents.add(document(
                        "SALES_RETURN", ret.getId(), ret.getReturnNumber(), ret.getStatus(),
                        "DOWNSTREAM", "销售退货", ret)));

        return BusinessDocumentTraceResponse.builder()
                .anchorType("SALES_ORDER")
                .anchorId(order.getId())
                .anchorNumber(order.getOrderNumber())
                .anchorStatus(asText(order.getStatus()))
                .documents(documents)
                .missingLinks(missingLinks)
                .build();
    }

    // ==================== 采购订单 ====================

    @Transactional(readOnly = true)
    public BusinessDocumentTraceResponse tracePurchaseOrder(String factoryId, String purchaseOrderId) {
        PurchaseOrder order = purchaseOrderRepository.findByIdAndFactoryId(purchaseOrderId, factoryId)
                .orElseThrow(() -> new ResourceNotFoundException("PurchaseOrder", "id", purchaseOrderId));

        List<BusinessDocumentTraceResponse.TraceDocument> documents = new ArrayList<>();
        List<String> missingLinks = new ArrayList<>();

        // 上游 (返回原单): purchase_orders.sales_order_id → sales_orders
        String sourceSalesOrderId = order.getSalesOrderId();
        if (sourceSalesOrderId != null && !sourceSalesOrderId.isBlank()) {
            SalesOrder salesOrder = salesOrderRepository.findById(sourceSalesOrderId)
                    .filter(so -> factoryId.equals(so.getFactoryId()))
                    .orElse(null);
            if (salesOrder == null) {
                missingLinks.add("销售订单链接失效: " + sourceSalesOrderId);
            } else {
                documents.add(document("SALES_ORDER", salesOrder.getId(), salesOrder.getOrderNumber(),
                        salesOrder.getStatus(), "UPSTREAM", "采购来源销售订单", salesOrder));
            }
        }

        // 执行: purchase_receive_records.purchase_order_id
        purchaseReceiveRecordRepository
                .findByFactoryIdAndPurchaseOrderIdOrderByCreatedAtAsc(factoryId, purchaseOrderId)
                .forEach(receive -> documents.add(document(
                        "PURCHASE_RECEIPT", receive.getId(), receive.getReceiveNumber(),
                        receive.getStatus(), "EXECUTION", "采购到货入库", receive)));

        // 下游: purchase_invoices.purchase_order_id
        purchaseInvoiceRepository.findByFactoryIdAndPurchaseOrderId(factoryId, purchaseOrderId)
                .forEach(invoice -> documents.add(document(
                        "PURCHASE_INVOICE", invoice.getId(), invoice.getInvoiceNumber(),
                        invoice.getReconcileStatus(), "DOWNSTREAM", "采购发票对账", invoice)));

        // 下游: payment_requests.purchase_order_id
        paymentRequestRepository.findByPurchaseOrderId(purchaseOrderId).forEach(payment -> {
            if (!factoryId.equals(payment.getFactoryId())) {
                missingLinks.add("付款申请跨工厂异常, 已隐藏: " + payment.getId());
                return;
            }
            documents.add(document("PAYMENT_REQUEST", payment.getId(), payment.getRequestNumber(),
                    payment.getStatus(), "DOWNSTREAM", "采购付款申请", payment));
        });

        // 下游: return_orders.source_order_id (return_type = PURCHASE_RETURN)
        returnOrderRepository
                .findByFactoryIdAndReturnTypeAndSourceOrderIdOrderByCreatedAtDesc(
                        factoryId, ReturnType.PURCHASE_RETURN, purchaseOrderId)
                .forEach(ret -> documents.add(document(
                        "PURCHASE_RETURN", ret.getId(), ret.getReturnNumber(), ret.getStatus(),
                        "DOWNSTREAM", "采购退货", ret)));

        return BusinessDocumentTraceResponse.builder()
                .anchorType("PURCHASE_ORDER")
                .anchorId(order.getId())
                .anchorNumber(order.getOrderNumber())
                .anchorStatus(asText(order.getStatus()))
                .documents(documents)
                .missingLinks(missingLinks)
                .build();
    }

    // ==================== 调拨单 ====================

    @Transactional(readOnly = true)
    public BusinessDocumentTraceResponse traceInternalTransfer(String factoryId, String transferId) {
        InternalTransfer transfer = internalTransferRepository
                .findByIdAndEitherFactoryId(transferId, factoryId)
                .orElseThrow(() -> new ResourceNotFoundException("InternalTransfer", "id", transferId));

        List<BusinessDocumentTraceResponse.TraceDocument> documents = new ArrayList<>();
        List<String> missingLinks = new ArrayList<>();

        // 上游 (返回原单): internal_transfers.production_plan_id → production_plans
        String productionPlanId = transfer.getProductionPlanId();
        if (productionPlanId != null && !productionPlanId.isBlank()) {
            ProductionPlan plan = productionPlanRepository
                    .findByIdAndFactoryId(productionPlanId, factoryId)
                    .orElse(null);
            if (plan != null) {
                documents.add(document("PRODUCTION_PLAN", plan.getId(), plan.getPlanNumber(),
                        plan.getStatus(), "UPSTREAM", "调拨来源生产计划", plan));
            } else if (!factoryId.equals(transfer.getSourceFactoryId())) {
                // 跨厂调拨: 计划归调出方所有, 调入方本就看不到 —— 说明白, 不谎报"链接失效"。
                missingLinks.add("关联生产计划属于调出工厂 " + transfer.getSourceFactoryId() + ", 当前工厂无权查看");
            } else {
                missingLinks.add("生产计划链接失效: " + productionPlanId);
            }
        }

        // 下游: transfer_diff_records.transfer_id
        transferDiffRecordRepository.findByTransferIdOrderByCreatedAtDesc(transferId).forEach(diff -> {
            if (!factoryId.equals(diff.getSourceFactoryId()) && !factoryId.equals(diff.getTargetFactoryId())) {
                missingLinks.add("调拨差异单跨工厂异常, 已隐藏: " + diff.getId());
                return;
            }
            documents.add(document("TRANSFER_DIFF", diff.getId(), diff.getDiffNumber(),
                    diff.getStatus(), "DOWNSTREAM", "调拨收发差异", diff));
        });

        return BusinessDocumentTraceResponse.builder()
                .anchorType("INTERNAL_TRANSFER")
                .anchorId(transfer.getId())
                .anchorNumber(transfer.getTransferNumber())
                .anchorStatus(asText(transfer.getStatus()))
                .documents(documents)
                .missingLinks(missingLinks)
                .build();
    }

    // ==================== helpers ====================

    private BusinessDocumentTraceResponse.TraceDocument document(
            String type, String id, String number, Object status,
            String direction, String relation, BaseEntity entity) {
        LocalDateTime occurredAt = entity == null ? null : entity.getCreatedAt();
        return BusinessDocumentTraceResponse.TraceDocument.builder()
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
     * <p>只在这一个地方按类型分派 —— 12 个 {@code document(...)} 调用点一个都不用改。</p>
     *
     * <p>字段口径: 只放**看一眼就能确认"是不是这张单"**的东西 (对方是谁 / 什么时候 / 多少钱),
     * 不做完整详情页的替代品。金额与日期在这里格式化成字符串, 前端不再猜类型。</p>
     *
     * <p>🔴 拿不到的字段**直接不放**, 不塞 "—" 占位 —— 占位符会让前端渲染出一行空标签,
     * 用户以为"这张单没有客户", 而事实是"这个字段没填"。少一行比多一行假信息好。</p>
     *
     * <p>没列到的单据类型返回空列表, 前端只显示链路本身已有的字段并说明原因。刻意不 fallback
     * 到反射遍历: 那会把内部字段名 (乃至价格/成本) 无差别吐给所有角色。</p>
     */
    private List<BusinessDocumentTraceResponse.Field> detailsOf(BaseEntity entity) {
        List<BusinessDocumentTraceResponse.Field> out = new ArrayList<>();
        if (entity instanceof SalesOrder so) {
            put(out, "客户", so.getCustomerName());
            put(out, "下单日期", text(so.getOrderDate()));
            put(out, "订单金额", money(so.getTotalAmount()));
            put(out, "收货地址", so.getDeliveryAddress());
            put(out, "备注", so.getRemark());
        } else if (entity instanceof PurchaseOrder po) {
            put(out, "供应商", po.getSupplierName());
            put(out, "下单日期", text(po.getOrderDate()));
            put(out, "预计到货", text(po.getExpectedDeliveryDate()));
            put(out, "订单金额", money(po.getTotalAmount()));
            put(out, "备注", po.getRemark());
        } else if (entity instanceof PurchaseReceiveRecord rcv) {
            put(out, "供应商", rcv.getSupplierName());
            put(out, "收货日期", text(rcv.getReceiveDate()));
            put(out, "收货金额", money(rcv.getTotalAmount()));
            put(out, "备注", rcv.getRemark());
        } else if (entity instanceof SalesDeliveryRecord dlv) {
            put(out, "客户", dlv.getCustomerName());
            put(out, "发货日期", text(dlv.getDeliveryDate()));
            put(out, "发货金额", money(dlv.getTotalAmount()));
            put(out, "收货地址", dlv.getDeliveryAddress());
            put(out, "备注", dlv.getRemark());
        } else if (entity instanceof ProductionPlan pp) {
            put(out, "计划数量", text(pp.getPlannedQuantity()));
            put(out, "实际数量", text(pp.getActualQuantity()));
            put(out, "计划日期", text(pp.getPlannedDate()));
            put(out, "批次日期", text(pp.getBatchDate()));
        } else if (entity instanceof InternalTransfer it) {
            put(out, "调拨类型", asText(it.getTransferType()));
            put(out, "调出工厂", it.getSourceFactoryId());
            put(out, "调入工厂", it.getTargetFactoryId());
            put(out, "备注", it.getRemark());
        }
        return out;
    }

    /** 空白一律跳过 —— 见 detailsOf 的注释: 少一行好过一行假信息。 */
    private void put(List<BusinessDocumentTraceResponse.Field> out, String label, String value) {
        if (value == null || value.isBlank()) {
            return;
        }
        out.add(BusinessDocumentTraceResponse.Field.builder().label(label).value(value.trim()).build());
    }

    private String text(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    /** 金额统一两位小数 + ¥, 免得前端对着 BigDecimal 的科学计数法再猜一次。 */
    private String money(java.math.BigDecimal value) {
        return value == null ? null : "¥" + value.setScale(2, java.math.RoundingMode.HALF_UP).toPlainString();
    }

    /** {@code source_order_ids @> '["<id>"]'} 的右操作数; 走 JSON 字符串转义避免注入。 */
    private String jsonIdArray(String id) {
        return "[\"" + id.replace("\\", "\\\\").replace("\"", "\\\"") + "\"]";
    }

    private String asText(Object value) {
        return value == null ? null : String.valueOf(value);
    }
}
