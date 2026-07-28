package com.cretas.aims.ai.tool.impl.shipment;

import com.cretas.aims.ai.tool.AbstractBusinessTool;
import com.cretas.aims.entity.ShipmentRecord;
import com.cretas.aims.service.ShipmentRecordService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 按日期查询出货记录工具
 *
 * 按日期范围查询出货记录，支持状态筛选和分页。
 *
 * @author Cretas Team
 * @version 1.0.0
 * @since 2026-01-07
 */
@Slf4j
@Component
public class ShipmentByDateTool extends AbstractBusinessTool {

    @Autowired
    private ShipmentRecordService shipmentRecordService;

    private static final DateTimeFormatter DATE_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    @Override
    public String getToolName() {
        return "shipment_by_date";
    }

    @Override
    public String getDescription() {
        return "按日期范围查询出货记录。" +
                "需要提供开始日期和结束日期（格式：yyyy-MM-dd）。" +
                "可选按状态筛选，支持分页。" +
                "适用场景：查询某段时间的出货记录、月度报表、历史记录查询。";
    }

    @Override
    public Map<String, Object> getParametersSchema() {
        Map<String, Object> schema = new HashMap<>();
        schema.put("type", "object");

        Map<String, Object> properties = new HashMap<>();

        // startDate: 开始日期（必需）
        Map<String, Object> startDate = new HashMap<>();
        startDate.put("type", "string");
        startDate.put("description", "开始日期，格式: yyyy-MM-dd");
        startDate.put("pattern", "^\\d{4}-\\d{2}-\\d{2}$");
        properties.put("startDate", startDate);

        // endDate: 结束日期（必需）
        Map<String, Object> endDate = new HashMap<>();
        endDate.put("type", "string");
        endDate.put("description", "结束日期，格式: yyyy-MM-dd");
        endDate.put("pattern", "^\\d{4}-\\d{2}-\\d{2}$");
        properties.put("endDate", endDate);

        // status: 状态筛选（可选）
        Map<String, Object> status = new HashMap<>();
        status.put("type", "string");
        status.put("description", "按状态筛选");
        status.put("enum", Arrays.asList("pending", "shipped", "delivered", "returned"));
        properties.put("status", status);

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
        schema.put("required", Arrays.asList("startDate", "endDate"));

        return schema;
    }

    @Override
    protected List<String> getRequiredParameters() {
        return Arrays.asList("startDate", "endDate");
    }

    @Override
    protected Map<String, Object> doExecute(String factoryId, Map<String, Object> params, Map<String, Object> context) throws Exception {
        // 1. 获取参数
        String startDateStr = getString(params, "startDate");
        String endDateStr = getString(params, "endDate");
        String status = getString(params, "status");
        Integer page = getInteger(params, "page", 1);
        Integer size = getInteger(params, "size", 10);

        log.info("按日期查询出货: factoryId={}, startDate={}, endDate={}, status={}, page={}, size={}",
                factoryId, startDateStr, endDateStr, status, page, size);

        // 2. 解析日期
        LocalDate startDate;
        LocalDate endDate;
        try {
            startDate = LocalDate.parse(startDateStr, DATE_FORMATTER);
            endDate = LocalDate.parse(endDateStr, DATE_FORMATTER);
        } catch (DateTimeParseException e) {
            throw new IllegalArgumentException("日期格式错误，请使用 yyyy-MM-dd 格式");
        }

        // 验证日期范围
        if (startDate.isAfter(endDate)) {
            throw new IllegalArgumentException("开始日期不能晚于结束日期");
        }

        // 3. 查询数据
        List<ShipmentRecord> allRecords = shipmentRecordService.getByDateRange(factoryId, startDate, endDate);

        // 4. 状态筛选
        List<ShipmentRecord> filteredRecords = allRecords;
        if (status != null && !status.trim().isEmpty()) {
            filteredRecords = allRecords.stream()
                    .filter(r -> status.equalsIgnoreCase(r.getStatus()))
                    .collect(Collectors.toList());
        }

        // 5. 手动分页（因为 getByDateRange 返回的是 List）
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

        // 6. 转换为简化的结果格式
        List<Map<String, Object>> shipmentList = new ArrayList<>();
        for (ShipmentRecord record : pageContent) {
            Map<String, Object> item = new HashMap<>();
            item.put("id", record.getId());
            item.put("shipmentNumber", record.getShipmentNumber());
            item.put("customerId", record.getCustomerId());
            item.put("productName", record.getProductName());
            item.put("quantity", record.getQuantity());
            item.put("unit", record.getUnit());
            item.put("shipmentDate", record.getShipmentDate() != null ? record.getShipmentDate().toString() : null);
            item.put("status", record.getStatus());
            item.put("statusDesc", getStatusDescription(record.getStatus()));
            shipmentList.add(item);
        }

        // 7. 构建返回结果
        Map<String, Object> result = buildPageResult(shipmentList, totalElements, totalPages, page);

        // 添加查询条件摘要
        Map<String, Object> queryConditions = new HashMap<>();
        queryConditions.put("startDate", startDateStr);
        queryConditions.put("endDate", endDateStr);
        if (status != null) {
            queryConditions.put("status", status);
        }
        result.put("queryConditions", queryConditions);

        result.put("message", String.format(
                "%s 至 %s 出货记录：共%d条，当前第%d页",
                startDateStr, endDateStr, totalElements, page
        ));

        log.info("按日期查询完成: 总记录数={}, 当前页={}", totalElements, page);

        return result;
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
        questions.put("startDate", "请问查询的开始日期是什么？格式：yyyy-MM-dd");
        questions.put("endDate", "请问查询的结束日期是什么？格式：yyyy-MM-dd");
        questions.put("status", "请问要按什么状态筛选？可选：pending（待发货）、shipped（已发货）、delivered（已送达）、returned（已退回）");
        questions.put("page", "请问要查看第几页？");
        questions.put("size", "请问每页显示多少条记录？");

        String question = questions.get(paramName);
        return question != null ? question : super.getParameterQuestion(paramName);
    }

    @Override
    protected String getParameterDisplayName(String paramName) {
        Map<String, String> displayNames = new HashMap<>();
        displayNames.put("startDate", "开始日期");
        displayNames.put("endDate", "结束日期");
        displayNames.put("status", "状态");
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
