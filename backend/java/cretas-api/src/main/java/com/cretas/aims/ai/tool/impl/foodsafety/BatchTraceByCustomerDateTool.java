package com.cretas.aims.ai.tool.impl.foodsafety;

import com.cretas.aims.ai.tool.AbstractBusinessTool;
import com.cretas.aims.entity.Customer;
import com.cretas.aims.entity.ShipmentRecord;
import com.cretas.aims.repository.CustomerRepository;
import com.cretas.aims.repository.ShipmentRecordRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * 按客户 + 投诉日期反查产品批次 Tool — Sprint 8 P3 Phase B (食品召回闭环 step 1).
 *
 * <p>食品召回首步: 客户投诉时,先反查"该客户在该日期收到的是哪批产品". 通过 ShipmentRecord
 * (出货记录) 反查 batch_number, 之后才能跑 batch_full_trace / haccp_review.
 *
 * <p>LLM 触发场景:
 * <ul>
 *   <li>"鲜湘缘餐厅 5/18 那批卤猪蹄是哪批"</li>
 *   <li>"X 客户上周三吃的是哪批"</li>
 *   <li>"按客户日期追溯批次"</li>
 * </ul>
 *
 * <p>read-only, 防呆 R2 — message 含客户名 + 日期, 找不到时给 actionHint
 * 跳到 ShipmentRecord 列表手工确认.
 *
 * <p>Intent Code: {@code BATCH_TRACE_BY_CUSTOMER}
 */
@Slf4j
@Component
public class BatchTraceByCustomerDateTool extends AbstractBusinessTool {

    @Autowired
    private ShipmentRecordRepository shipmentRecordRepository;

    @Autowired
    private CustomerRepository customerRepository;

    @Override
    public String getToolName() {
        return "batch_trace_by_customer_date";
    }

    @Override
    public String getDescription() {
        return "按客户 + 投诉日期反查产品批次 (食品召回首步). "
                + "LLM 触发: '鲜湘缘餐厅 5/18 那批卤猪蹄是哪批' / '王老板上周三的货是哪批' / "
                + "'按客户日期追溯批次'. read-only. "
                + "返回该客户在指定日期收到的全部出货记录及其 batch_number 列表.";
    }

    @Override
    public Map<String, Object> getParametersSchema() {
        Map<String, Object> schema = new HashMap<>();
        schema.put("type", "object");
        Map<String, Object> properties = new HashMap<>();

        Map<String, Object> customerId = new HashMap<>();
        customerId.put("type", "string");
        customerId.put("description", "客户 UUID (必填). 可由 customer_search Tool 通过客户名解析");
        properties.put("customerId", customerId);

        Map<String, Object> date = new HashMap<>();
        date.put("type", "string");
        date.put("description", "投诉日期 ISO 格式 YYYY-MM-DD (必填)");
        properties.put("date", date);

        schema.put("properties", properties);
        schema.put("required", List.of("customerId", "date"));
        return schema;
    }

    @Override
    protected List<String> getRequiredParameters() {
        return List.of("customerId", "date");
    }

    @Override
    protected Map<String, Object> doExecute(String factoryId,
            Map<String, Object> params, Map<String, Object> context) throws Exception {
        String customerId = getString(params, "customerId");
        String dateStr = getString(params, "date");

        LocalDate complaintDate;
        try {
            complaintDate = LocalDate.parse(dateStr);
        } catch (DateTimeParseException ex) {
            throw new IllegalArgumentException("date 格式非法 (期望 YYYY-MM-DD): " + dateStr);
        }

        log.info("batch_trace_by_customer_date — factory={} customer={} date={}",
                factoryId, customerId, complaintDate);

        // 解析客户名 (R2 context)
        String customerName = resolveCustomerName(factoryId, customerId);

        // 查该客户全部出货记录 → 过滤到指定日期
        List<ShipmentRecord> all = shipmentRecordRepository
                .findByFactoryIdAndCustomerIdOrderByShipmentDateDesc(factoryId, customerId);
        List<ShipmentRecord> onDate = all.stream()
                .filter(s -> s.getShipmentDate() != null && s.getShipmentDate().equals(complaintDate))
                .collect(Collectors.toList());

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("customerId", customerId);
        data.put("customerName", customerName);
        data.put("date", complaintDate.toString());

        if (onDate.isEmpty()) {
            data.put("shipmentCount", 0);
            data.put("batchNumbers", List.of());
            data.put("shipments", List.of());
            data.put("actionHint",
                    "/sales/shipments?customerId=" + customerId + "&date=" + complaintDate);
            return buildSimpleResult(String.format(
                    "ℹ️ %s 在 %s 没有出货记录. 请确认日期或客户是否正确",
                    nameForDisplay(customerName), complaintDate), data);
        }

        List<Map<String, Object>> shipments = new ArrayList<>();
        List<String> batchNumbers = new ArrayList<>();
        for (ShipmentRecord s : onDate) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("shipmentNumber", s.getShipmentNumber());
            row.put("batchNumber", s.getBatchNumber());
            row.put("productName", s.getProductName());
            row.put("quantity", s.getQuantity());
            row.put("unit", s.getUnit());
            shipments.add(row);
            if (s.getBatchNumber() != null && !s.getBatchNumber().isBlank()
                    && !batchNumbers.contains(s.getBatchNumber())) {
                batchNumbers.add(s.getBatchNumber());
            }
        }

        data.put("shipmentCount", onDate.size());
        data.put("batchNumbers", batchNumbers);
        data.put("shipments", shipments);
        data.put("actionHint",
                "/sales/shipments?customerId=" + customerId + "&date=" + complaintDate);

        String message = String.format(
                "📦 %s 在 %s 收到 %d 笔出货, 涉及 %d 个批次: %s",
                nameForDisplay(customerName), complaintDate, onDate.size(),
                batchNumbers.size(), String.join(", ", batchNumbers));
        return buildSimpleResult(message, data);
    }

    private String resolveCustomerName(String factoryId, String customerId) {
        if (customerId == null) return null;
        try {
            Optional<Customer> opt = customerRepository.findByIdAndFactoryId(customerId, factoryId);
            return opt.map(Customer::getName).orElse(null);
        } catch (Exception ex) {
            log.warn("resolveCustomerName failed customer={} factory={}: {}",
                    customerId, factoryId, ex.getMessage());
            return null;
        }
    }

    private String nameForDisplay(String name) {
        return (name != null && !name.isBlank()) ? name : "该客户";
    }

    /** spec §8.2 只读查询, 无副作用 */
    @Override
    public AccessMode getAccessMode() {
        return AccessMode.READ;
    }
}
