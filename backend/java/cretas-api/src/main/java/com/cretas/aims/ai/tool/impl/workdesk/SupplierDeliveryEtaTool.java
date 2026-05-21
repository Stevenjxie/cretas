package com.cretas.aims.ai.tool.impl.workdesk;

import com.cretas.aims.ai.tool.AbstractBusinessTool;
import com.cretas.aims.entity.Supplier;
import com.cretas.aims.entity.inventory.PurchaseOrder;
import com.cretas.aims.entity.inventory.PurchaseOrderItem;
import com.cretas.aims.entity.inventory.PurchaseReceiveRecord;
import com.cretas.aims.repository.SupplierRepository;
import com.cretas.aims.repository.inventory.PurchaseOrderItemRepository;
import com.cretas.aims.repository.inventory.PurchaseOrderRepository;
import com.cretas.aims.repository.inventory.PurchaseReceiveRecordRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.*;

/**
 * 供应商交期 ETA Tool — Sprint 8 P4b 采购员 Workdesk.
 *
 * <p>采购员小赵关心: "X 物料从 Y 供应商订货, 大概几天能到?" — 综合两个信号:
 * <ol>
 *   <li>{@link Supplier#getDeliveryDays()} 维护的标称交期 (供应商主数据)</li>
 *   <li>历史 PO 的 (receiveDate - orderDate) 实际 AVG (最近 90 天)</li>
 * </ol>
 *
 * <p>vs HJ 模式: 采购员要点供应商详情 + 翻历史 PO 自己算. Cretas 一个 Tool 输出推荐 ETA.
 *
 * <p>Intent Code: {@code SUPPLIER_DELIVERY_ETA}
 *
 * @author Cretas Team
 * @since 2026-05-20 (Sprint 8 P4b)
 */
@Slf4j
@Component
public class SupplierDeliveryEtaTool extends AbstractBusinessTool {

    /** 历史交期回看天数. */
    private static final int HISTORY_LOOKBACK_DAYS = 90;
    /** 默认推荐 ETA (无任何数据 fallback). */
    private static final int DEFAULT_ETA_DAYS = 7;

    @Autowired
    private SupplierRepository supplierRepository;

    @Autowired
    private PurchaseOrderRepository purchaseOrderRepository;

    @Autowired
    private PurchaseOrderItemRepository purchaseOrderItemRepository;

    @Autowired
    private PurchaseReceiveRecordRepository purchaseReceiveRecordRepository;

    @Override
    public String getToolName() {
        return "supplier_delivery_eta";
    }

    @Override
    public String getDescription() {
        return "供应商交期 ETA — 综合 supplier.deliveryDays (主数据) + 最近 90 天历史 PO 平均 "
                + "(实收日 - 下单日). LLM 触发场景: 采购员问 '这供应商几天能到货?' / "
                + "'X 供应商交期多久' / '供应商交期靠谱不' / '历史平均交期'. read-only. "
                + "可选按 supplierId / materialTypeId 过滤. 防呆 R1: 直接告诉采购员预计哪天到货.";
    }

    @Override
    public Map<String, Object> getParametersSchema() {
        Map<String, Object> schema = new HashMap<>();
        schema.put("type", "object");

        Map<String, Object> properties = new HashMap<>();

        Map<String, Object> materialTypeId = new HashMap<>();
        materialTypeId.put("type", "string");
        materialTypeId.put("description",
                "可选 — 指定 materialTypeId 仅返该物料对应供应商. 不填则按 supplierId 维度返.");
        properties.put("materialTypeId", materialTypeId);

        Map<String, Object> supplierId = new HashMap<>();
        supplierId.put("type", "string");
        supplierId.put("description",
                "可选 — 指定 supplierId 仅返该供应商. 不填则返工厂全部供应商 (Top 20).");
        properties.put("supplierId", supplierId);

        schema.put("properties", properties);
        schema.put("required", Collections.emptyList());
        return schema;
    }

    @Override
    protected List<String> getRequiredParameters() {
        return Collections.emptyList();
    }

    @Override
    protected Map<String, Object> doExecute(String factoryId,
            Map<String, Object> params, Map<String, Object> context) throws Exception {
        String filterMaterialTypeId = getString(params, "materialTypeId");
        String filterSupplierId = getString(params, "supplierId");

        log.info("supplier_delivery_eta — factory={} material={} supplier={}",
                factoryId, filterMaterialTypeId, filterSupplierId);

        // 1. 选定供应商范围
        List<Supplier> suppliers;
        if (filterSupplierId != null && !filterSupplierId.isBlank()) {
            Optional<Supplier> opt = supplierRepository.findById(filterSupplierId);
            if (opt.isEmpty() || !factoryId.equals(opt.get().getFactoryId())) {
                Map<String, Object> empty = new HashMap<>();
                empty.put("eta", Collections.emptyList());
                return buildSimpleResult(
                        String.format("⚠️ 供应商 %s 不存在或非本工厂", filterSupplierId), empty);
            }
            suppliers = List.of(opt.get());
        } else if (filterMaterialTypeId != null && !filterMaterialTypeId.isBlank()) {
            // 通过历史 PO items 反推供应该物料的供应商
            suppliers = findSuppliersByMaterial(factoryId, filterMaterialTypeId);
        } else {
            suppliers = supplierRepository.findByFactoryId(factoryId);
            if (suppliers.size() > 20) suppliers = suppliers.subList(0, 20);
        }

        // 2. 计算每个供应商的 ETA
        LocalDate today = LocalDate.now();
        LocalDate historyStart = today.minusDays(HISTORY_LOOKBACK_DAYS);
        List<Map<String, Object>> etaList = new ArrayList<>();

        for (Supplier sup : suppliers) {
            Map<String, Object> eta = computeEtaForSupplier(
                    factoryId, sup, historyStart, today, filterMaterialTypeId);
            etaList.add(eta);
        }

        // 排 recommendedEtaDays asc — 快的供应商在前
        etaList.sort(Comparator.comparing(
                m -> ((Number) m.get("recommendedEtaDays")).doubleValue()));

        Map<String, Object> data = new HashMap<>();
        data.put("totalSuppliers", etaList.size());
        data.put("historyLookbackDays", HISTORY_LOOKBACK_DAYS);
        data.put("materialFilter", filterMaterialTypeId);
        data.put("supplierFilter", filterSupplierId);
        data.put("eta", etaList);

        String message = etaList.isEmpty()
                ? "无匹配供应商. 请确认 supplier 数据已维护."
                : String.format("📅 %d 个供应商交期 ETA (基于 supplier.deliveryDays + 最近 %d 天历史 AVG)",
                        etaList.size(), HISTORY_LOOKBACK_DAYS);
        return buildSimpleResult(message, data);
    }

    private Map<String, Object> computeEtaForSupplier(String factoryId, Supplier sup,
            LocalDate historyStart, LocalDate today, String filterMaterialTypeId) {
        Map<String, Object> eta = new LinkedHashMap<>();
        eta.put("supplierId", sup.getId());
        eta.put("supplierName", sup.getName());
        eta.put("nominalDeliveryDays", sup.getDeliveryDays());

        // 历史 PO + 收货
        List<PurchaseOrder> historyPOs = purchaseOrderRepository.findByFactoryIdAndSupplierId(
                factoryId, sup.getId());

        long sumDays = 0;
        int countCompleted = 0;
        for (PurchaseOrder po : historyPOs) {
            LocalDate orderDate = po.getOrderDate();
            if (orderDate == null || orderDate.isBefore(historyStart)) continue;

            // 找该 PO 第一次收货日
            List<PurchaseReceiveRecord> records = purchaseReceiveRecordRepository
                    .findByPurchaseOrderId(po.getId());
            LocalDate firstReceiveDate = null;
            for (PurchaseReceiveRecord r : records) {
                if (r.getReceiveDate() == null) continue;
                if (firstReceiveDate == null || r.getReceiveDate().isBefore(firstReceiveDate)) {
                    firstReceiveDate = r.getReceiveDate();
                }
            }
            if (firstReceiveDate == null) continue;
            long days = ChronoUnit.DAYS.between(orderDate, firstReceiveDate);
            if (days < 0 || days > 180) continue; // sanity 过滤
            sumDays += days;
            countCompleted++;
        }

        Integer historicalAvgDays = countCompleted > 0
                ? (int) Math.round((double) sumDays / countCompleted)
                : null;
        eta.put("historicalAvgDays", historicalAvgDays);
        eta.put("historicalSampleSize", countCompleted);

        // 推荐 ETA: 历史 vs nominal, 取较大值更保守
        int recommendedEtaDays;
        if (historicalAvgDays != null && sup.getDeliveryDays() != null) {
            recommendedEtaDays = Math.max(historicalAvgDays, sup.getDeliveryDays());
        } else if (historicalAvgDays != null) {
            recommendedEtaDays = historicalAvgDays;
        } else if (sup.getDeliveryDays() != null) {
            recommendedEtaDays = sup.getDeliveryDays();
        } else {
            recommendedEtaDays = DEFAULT_ETA_DAYS;
        }
        eta.put("recommendedEtaDays", recommendedEtaDays);
        eta.put("predictedDeliveryDate", today.plusDays(recommendedEtaDays).toString());

        String hint = String.format(
                "%s — 主数据 %s 天, 历史 %d 单 AVG %s 天, 推荐 ETA = %d 天 (≈ %s 到货)",
                sup.getName(),
                sup.getDeliveryDays() != null ? sup.getDeliveryDays() : "未维护",
                countCompleted,
                historicalAvgDays != null ? historicalAvgDays.toString() : "无样本",
                recommendedEtaDays,
                today.plusDays(recommendedEtaDays));
        eta.put("displayHint", hint);
        return eta;
    }

    private List<Supplier> findSuppliersByMaterial(String factoryId, String materialTypeId) {
        // 通过历史 PO items 反推 (最近 180 天)
        LocalDate windowStart = LocalDate.now().minusDays(180);
        List<PurchaseOrderItem> items = purchaseOrderItemRepository
                .findByMaterialTypeId(materialTypeId);
        Set<String> supplierIds = new LinkedHashSet<>();
        for (PurchaseOrderItem item : items) {
            String poId = item.getPurchaseOrderId();
            Optional<PurchaseOrder> poOpt = purchaseOrderRepository.findById(poId);
            if (poOpt.isEmpty()) continue;
            PurchaseOrder po = poOpt.get();
            if (!factoryId.equals(po.getFactoryId())) continue;
            if (po.getOrderDate() == null || po.getOrderDate().isBefore(windowStart)) continue;
            if (po.getSupplierId() != null) supplierIds.add(po.getSupplierId());
        }
        List<Supplier> result = new ArrayList<>();
        for (String sid : supplierIds) {
            supplierRepository.findById(sid).ifPresent(result::add);
            if (result.size() >= 20) break;
        }
        return result;
    }
}
