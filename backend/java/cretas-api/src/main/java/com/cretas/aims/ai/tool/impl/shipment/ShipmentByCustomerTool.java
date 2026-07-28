package com.cretas.aims.ai.tool.impl.shipment;

import com.cretas.aims.ai.tool.AbstractBusinessTool;
import com.cretas.aims.entity.ShipmentRecord;
import com.cretas.aims.service.ShipmentRecordService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 按客户查询出货记录工具
 *
 * 按客户ID查询出货记录，支持日期范围筛选、状态筛选和分页。
 *
 * @author Cretas Team
 * @version 1.0.0
 * @since 2026-01-07
 */
@Slf4j
@Component
public class ShipmentByCustomerTool extends AbstractBusinessTool {

    @Autowired
    private ShipmentRecordService shipmentRecordService;

    private static final DateTimeFormatter DATE_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    @Override
    public String getToolName() {
        return "shipment_by_customer";
    }

    @Override
    public String getDescription() {
        return "按客户查询出货记录。" +
                "需要提供客户ID。" +
                "可选按日期范围、状态筛选，支持分页。" +
                "适用场景：查看客户的出货历史、客户对账、客户服务查询。";
    }

    @Override
    public Map<String, Object> getParametersSchema() {
        Map<String, Object> schema = new HashMap<>();
        schema.put("type", "object");

        Map<String, Object> properties = new HashMap<>();

        // customerId: 客户ID（必需）
        Map<String, Object> customerId = new HashMap<>();
        customerId.put("type", "string");
        customerId.put("description", "客户ID");
        properties.put("customerId", customerId);

        // status: 状态筛选（可选）
        Map<String, Object> status = new HashMap<>();
        status.put("type", "string");
        status.put("description", "按状态筛选");
        status.put("enum", Arrays.asList("pending", "shipped", "delivered", "returned"));
        properties.put("status", status);

        // dateFrom: 开始日期（可选）
        Map<String, Object> dateFrom = new HashMap<>();
        dateFrom.put("type", "string");
        dateFrom.put("description", "筛选开始日期，格式: yyyy-MM-dd");
        dateFrom.put("pattern", "^\\d{4}-\\d{2}-\\d{2}$");
        properties.put("dateFrom", dateFrom);

        // dateTo: 结束日期（可选）
        Map<String, Object> dateTo = new HashMap<>();
        dateTo.put("type", "string");
        dateTo.put("description", "筛选结束日期，格式: yyyy-MM-dd");
        dateTo.put("pattern", "^\\d{4}-\\d{2}-\\d{2}$");
        properties.put("dateTo", dateTo);

        // page: 页码（可选）
        Map<String, Object> page = new HashMap<>();
        page.put("type", "integer");
        page.put("description", "页码，从1开始");
        page.put("default", 1);
        page.put("minimum", 1);
        properties.put("page", page);

        // size: 每页数量（可选）
        Map<String, Object> size = new HashMap<>();
        size.put("type", "integer");
        size.put("description", "每页记录数");
        size.put("default", 10);
        size.put("minimum", 1);
        size.put("maximum", 100);
        properties.put("size", size);

        schema.put("properties", properties);
        schema.put("required", Collections.singletonList("customerId"));

        return schema;
    }

    @Override
    protected List<String> getRequiredParameters() {
        return Collections.singletonList("customerId");
    }

    @Override
    protected Map<String, Object> doExecute(String factoryId, Map<String, Object> params, Map<String, Object> context) throws Exception {
        // 1. 获取参数
        String customerId = getString(params, "customerId");
        String status = getString(params, "status");
        String dateFromStr = getString(params, "dateFrom");
        String dateToStr = getString(params, "dateTo");
        Integer page = getInteger(params, "page", 1);
        Integer size = getInteger(params, "size", 10);

        log.info("按客户查询出货: factoryId={}, customerId={}, status={}, dateFrom={}, dateTo={}, page={}, size={}",
                factoryId, customerId, status, dateFromStr, dateToStr, page, size);

        // 2. 解析日期（如果提供）
        LocalDate dateFrom = null;
        LocalDate dateTo = null;
        try {
            if (dateFromStr != null && !dateFromStr.trim().isEmpty()) {
                dateFrom = LocalDate.parse(dateFromStr, DATE_FORMATTER);
            }
            if (dateToStr != null && !dateToStr.trim().isEmpty()) {
                dateTo = LocalDate.parse(dateToStr, DATE_FORMATTER);
            }
        } catch (DateTimeParseException e) {
            throw new IllegalArgumentException("日期格式错误，请使用 yyyy-MM-dd 格式");
        }

        // 验证日期范围
        if (dateFrom != null && dateTo != null && dateFrom.isAfter(dateTo)) {
            throw new IllegalArgumentException("开始日期不能晚于结束日期");
        }

        // 3. 查询数据
        List<ShipmentRecord> allRecords = shipmentRecordService.getByCustomer(factoryId, customerId);

        // 4. 日期筛选
        List<ShipmentRecord> filteredRecords = allRecords;
        if (dateFrom != null || dateTo != null) {
            final LocalDate finalDateFrom = dateFrom;
            final LocalDate finalDateTo = dateTo;
            filteredRecords = allRecords.stream()
                    .filter(r -> {
                        if (r.getShipmentDate() == null) return false;
                        LocalDate shipDate = r.getShipmentDate();
                        if (finalDateFrom != null && shipDate.isBefore(finalDateFrom)) return false;
                        if (finalDateTo != null && shipDate.isAfter(finalDateTo)) return false;
                        return true;
                    })
                    .collect(Collectors.toList());
        }

        // 5. 状态筛选
        if (status != null && !status.trim().isEmpty()) {
            filteredRecords = filteredRecords.stream()
                    .filter(r -> status.equalsIgnoreCase(r.getStatus()))
                    .collect(Collectors.toList());
        }

        // 6. 手动分页
        int totalElements = filteredRecords.size();
        int totalPages = (int) Math.ceil((double) totalElements / size);
        int fromIndex = (page - 1) * size;
        int toIndex = Math.min(fromIndex + size, totalElements);

        List<ShipmentRecord> pageContent;
        if (fromIndex >= totalElements) {
            pageContent = Collections.emptyList();
        } else {
            pageContent = filteredRecords.subList(fromIndex, toIndex);
        }

        // 7. 转换为简化的结果格式
        List<Map<String, Object>> shipmentList = new ArrayList<>();
        for (ShipmentRecord record : pageContent) {
            Map<String, Object> item = new HashMap<>();
            item.put("id", record.getId());
            item.put("shipmentNumber", record.getShipmentNumber());
            item.put("orderNumber", record.getOrderNumber());
            item.put("productName", record.getProductName());
            item.put("quantity", record.getQuantity());
            item.put("unit", record.getUnit());
            item.put("unitPrice", record.getUnitPrice());
            item.put("totalAmount", record.getTotalAmount());
            item.put("shipmentDate", record.getShipmentDate() != null ? record.getShipmentDate().toString() : null);
            item.put("deliveryAddress", record.getDeliveryAddress());
            item.put("status", record.getStatus());
            item.put("statusDesc", getStatusDescription(record.getStatus()));
            shipmentList.add(item);
        }

        // 8. 构建返回结果
        Map<String, Object> result = buildPageResult(shipmentList, totalElements, totalPages, page);

        // 添加查询条件摘要
        Map<String, Object> queryConditions = new HashMap<>();
        queryConditions.put("customerId", customerId);
        if (status != null) {
            queryConditions.put("status", status);
        }
        if (dateFromStr != null) {
            queryConditions.put("dateFrom", dateFromStr);
        }
        if (dateToStr != null) {
            queryConditions.put("dateTo", dateToStr);
        }
        result.put("queryConditions", queryConditions);

        // 客户统计摘要
        Map<String, Object> customerSummary = new HashMap<>();
        customerSummary.put("customerId", customerId);
        customerSummary.put("totalRecords", totalElements);
        customerSummary.put("pendingCount", countByStatus(filteredRecords, "pending"));
        customerSummary.put("shippedCount", countByStatus(filteredRecords, "shipped"));
        customerSummary.put("deliveredCount", countByStatus(filteredRecords, "delivered"));
        customerSummary.put("returnedCount", countByStatus(filteredRecords, "returned"));
        result.put("customerSummary", customerSummary);

        result.put("message", String.format(
                "客户 %s 的出货记录：共%d条，当前第%d页",
                customerId, totalElements, page
        ));

        log.info("按客户查询完成: customerId={}, 总记录数={}, 当前页={}",
                customerId, totalElements, page);

        return result;
    }

    /**
     * 按状态统计数量
     */
    private long countByStatus(List<ShipmentRecord> records, String status) {
        return records.stream()
                .filter(r -> status.equals(r.getStatus()))
                .count();
    }

    /**
     * 获取状态描述
     */
    private String getStatusDescription(String status) {
        if (status == null) return "未知";
        return switch (status.toLowerCase()) {
            case "pending" -> "待发货";
            case "shipped" -> "已发货";
            case "delivered" -> "已送达";
            case "returned" -> "已退回";
            default -> status;
        };
    }

    @Override
    protected String getParameterQuestion(String paramName) {
        Map<String, String> questions = new HashMap<>();
        questions.put("customerId", "请问您要查询哪个客户的出货记录？请提供客户ID。");
        questions.put("status", "请问要按什么状态筛选？可选：pending（待发货）、shipped（已发货）、delivered（已送达）、returned（已退回）");
        questions.put("dateFrom", "请问查询的开始日期是什么？格式：yyyy-MM-dd");
        questions.put("dateTo", "请问查询的结束日期是什么？格式：yyyy-MM-dd");
        questions.put("page", "请问要查看第几页？");
        questions.put("size", "请问每页显示多少条记录？");

        String question = questions.get(paramName);
        return question != null ? question : super.getParameterQuestion(paramName);
    }

    @Override
    protected String getParameterDisplayName(String paramName) {
        Map<String, String> displayNames = new HashMap<>();
        displayNames.put("customerId", "客户ID");
        displayNames.put("status", "状态");
        displayNames.put("dateFrom", "开始日期");
        displayNames.put("dateTo", "结束日期");
        displayNames.put("page", "页码");
        displayNames.put("size", "每页数量");

        String name = displayNames.get(paramName);
        return name != null ? name : super.getParameterDisplayName(paramName);
    }

    /** spec §8.2 只读查询, 无副作用 */
    @Override
    public AccessMode getAccessMode() {
        return AccessMode.READ;
    }
}
