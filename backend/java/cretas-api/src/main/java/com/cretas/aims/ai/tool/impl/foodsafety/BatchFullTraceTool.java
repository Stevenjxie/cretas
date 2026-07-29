package com.cretas.aims.ai.tool.impl.foodsafety;

import com.cretas.aims.ai.tool.AbstractBusinessTool;
import com.cretas.aims.dto.traceability.TraceabilityDTO;
import com.cretas.aims.entity.ShipmentRecord;
import com.cretas.aims.repository.ShipmentRecordRepository;
import com.cretas.aims.service.TraceabilityService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 批次完整追溯 Tool — Sprint 8 P3 Phase B (食品召回闭环 step 2).
 *
 * <p>查指定 batch 的完整 trace: 原料 + 供应商 + 加工记录 + 出货客户列表.
 * 在 batch_trace_by_customer_date 找到 root batch 后, 用此 Tool 找原料/供应商/影响客户.
 *
 * <p>复用 {@link TraceabilityService#getFullTrace} (已有 full trace 实现) +
 * {@link ShipmentRecordRepository#findByFactoryIdAndBatchNumber} (反查影响客户).
 *
 * <p>LLM 触发场景:
 * <ul>
 *   <li>"B-20260518-A03 全追溯"</li>
 *   <li>"这批的原料和影响客户"</li>
 *   <li>"批次完整追溯"</li>
 * </ul>
 *
 * <p>read-only.
 *
 * <p>Intent Code: {@code BATCH_FULL_TRACE}
 */
@Slf4j
@Component
public class BatchFullTraceTool extends AbstractBusinessTool {

    @Autowired
    private TraceabilityService traceabilityService;

    @Autowired
    private ShipmentRecordRepository shipmentRecordRepository;

    @Override
    public String getToolName() {
        return "batch_full_trace";
    }

    @Override
    public String getDescription() {
        return "批次完整追溯 — 原料 + 供应商 + 加工记录 + 受影响客户列表 (食品召回闭环 step 2). "
                + "LLM 触发: 'B-X 全追溯' / '这批的原料和影响客户' / '批次完整追溯' / "
                + "'这批用了哪些原料卖给了谁'. read-only.";
    }

    @Override
    public Map<String, Object> getParametersSchema() {
        Map<String, Object> schema = new HashMap<>();
        schema.put("type", "object");
        Map<String, Object> properties = new HashMap<>();

        Map<String, Object> batchNumber = new HashMap<>();
        batchNumber.put("type", "string");
        batchNumber.put("description", "批次号 (必填), e.g. B-20260518-A03");
        properties.put("batchNumber", batchNumber);

        schema.put("properties", properties);
        schema.put("required", List.of("batchNumber"));
        return schema;
    }

    @Override
    protected List<String> getRequiredParameters() {
        return List.of("batchNumber");
    }

    @Override
    protected Map<String, Object> doExecute(String factoryId,
            Map<String, Object> params, Map<String, Object> context) throws Exception {
        String batchNumber = getString(params, "batchNumber");
        log.info("batch_full_trace — factory={} batch={}", factoryId, batchNumber);

        // 1. 调 TraceabilityService 拿 full trace (原料 + 供应商 + 加工记录)
        TraceabilityDTO.FullTraceResponse traceResp = null;
        try {
            traceResp = traceabilityService.getFullTrace(factoryId, batchNumber);
        } catch (Exception ex) {
            log.warn("getFullTrace failed batch={}: {}", batchNumber, ex.getMessage());
        }

        // 2. 反查影响客户 (通过 ShipmentRecord.batchNumber 找出货客户)
        List<ShipmentRecord> shipments = shipmentRecordRepository
                .findByFactoryIdAndBatchNumber(factoryId, batchNumber);

        Set<String> affectedCustomerIds = new LinkedHashSet<>();
        List<Map<String, Object>> affectedShipments = new ArrayList<>();
        for (ShipmentRecord s : shipments) {
            if (s.getCustomerId() != null) {
                affectedCustomerIds.add(s.getCustomerId());
            }
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("shipmentNumber", s.getShipmentNumber());
            row.put("customerId", s.getCustomerId());
            row.put("productName", s.getProductName());
            row.put("quantity", s.getQuantity());
            row.put("shipmentDate", s.getShipmentDate() != null
                    ? s.getShipmentDate().toString() : null);
            affectedShipments.add(row);
        }

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("batchNumber", batchNumber);
        data.put("trace", traceResp);
        data.put("affectedCustomerIds", new ArrayList<>(affectedCustomerIds));
        data.put("affectedCustomerCount", affectedCustomerIds.size());
        data.put("affectedShipments", affectedShipments);
        data.put("affectedShipmentCount", affectedShipments.size());
        data.put("actionHint", "/quality/recall?batchNumber=" + batchNumber);

        String message = String.format(
                "🔗 批次 %s 全追溯: %d 笔出货, 影响 %d 家客户. 详见 trace 字段",
                batchNumber, affectedShipments.size(), affectedCustomerIds.size());
        return buildSimpleResult(message, data);
    }

    /** spec §8.2 只读查询, 无副作用 */
    @Override
    public AccessMode getAccessMode() {
        return AccessMode.READ;
    }
}
