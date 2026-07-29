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

/**
 * 出货单查询工具
 *
 * 提供出货单的分页查询功能，支持按状态、客户、日期范围等条件筛选。
 * 作为查询类Tool，无必需参数，所有参数均为可选。
 *
 * @author Cretas Team
 * @version 1.0.0
 * @since 2026-01-07
 */
@Slf4j
@Component
public class ShipmentQueryTool extends AbstractBusinessTool {

    @Autowired
    private ShipmentRecordService shipmentRecordService;

    private static final DateTimeFormatter DATE_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    @Override
    public String getToolName() {
        return "shipment_query";
    }

    @Override
    public String getDescription() {
        return "查询出货单列表。支持按状态、客户ID、日期范围进行筛选，支持分页。" +
                "适用场景：查看出货记录、查找特定客户的出货单、查询某时间段的出货情况。";
    }

    @Override
    public Map<String, Object> getParametersSchema() {
        Map<String, Object> schema = new HashMap<>();
        schema.put("type", "object");

        Map<String, Object> properties = new HashMap<>();

        // status: 状态筛选（可选）
        Map<String, Object> status = new HashMap<>();
        status.put("type", "string");
        status.put("description", "出货单状态筛选");
        status.put("enum", Arrays.asList(
                "PENDING",    // 待发货
                "SHIPPED",    // 已发货
                "DELIVERED",  // 已送达
                "CANCELLED"   // 已取消
        ));
        properties.put("status", status);

        // customerId: 客户ID筛选（可选）
        Map<String, Object> customerId = new HashMap<>();
        customerId.put("type", "string");
        customerId.put("description", "客户ID，筛选指定客户的出货单");
        properties.put("customerId", customerId);

        // dateFrom: 开始日期（可选）
        Map<String, Object> dateFrom = new HashMap<>();
        dateFrom.put("type", "string");
        dateFrom.put("description", "查询开始日期，格式：yyyy-MM-dd");
        dateFrom.put("format", "date");
        properties.put("dateFrom", dateFrom);

        // dateTo: 结束日期（可选）
        Map<String, Object> dateTo = new HashMap<>();
        dateTo.put("type", "string");
        dateTo.put("description", "查询结束日期，格式：yyyy-MM-dd");
        dateTo.put("format", "date");
        properties.put("dateTo", dateTo);

        // shipmentNumber: 出货单号（可选，精确查询）
        Map<String, Object> shipmentNumber = new HashMap<>();
        shipmentNumber.put("type", "string");
        shipmentNumber.put("description", "出货单号，用于精确查询单个出货单");
        properties.put("shipmentNumber", shipmentNumber);

        // shipmentId: 出货单ID（可选，精确查询）
        Map<String, Object> shipmentId = new HashMap<>();
        shipmentId.put("type", "string");
        shipmentId.put("description", "出货单ID，用于精确查询单个出货单");
        properties.put("shipmentId", shipmentId);

        // page: 页码（可选，默认0）
        Map<String, Object> page = new HashMap<>();
        page.put("type", "integer");
        page.put("description", "页码，从0开始");
        page.put("default", 0);
        page.put("minimum", 0);
        properties.put("page", page);

        // size: 每页数量（可选，默认10）
        Map<String, Object> size = new HashMap<>();
        size.put("type", "integer");
        size.put("description", "每页记录数");
        size.put("default", 10);
        size.put("minimum", 1);
        size.put("maximum", 100);
        properties.put("size", size);

        schema.put("properties", properties);
        schema.put("required", Collections.emptyList());

        return schema;
    }

    @Override
    protected List<String> getRequiredParameters() {
        return Collections.emptyList();
    }

    @Override
    protected Map<String, Object> doExecute(String factoryId, Map<String, Object> params, Map<String, Object> context) throws Exception {
        log.info("执行出货单查询 - 工厂ID: {}, 参数: {}", factoryId, params);

        // 解析查询参数
        String shipmentId = getString(params, "shipmentId");
        String shipmentNumber = getString(params, "shipmentNumber");
        String status = getString(params, "status");
        String customerId = getString(params, "customerId");
        String dateFromStr = getString(params, "dateFrom");
        String dateToStr = getString(params, "dateTo");
        Integer page = getInteger(params, "page", 0);
        Integer size = getInteger(params, "size", 10);

        // 1. 优先精确查询 - 按ID
        if (shipmentId != null && !shipmentId.trim().isEmpty()) {
            Optional<ShipmentRecord> record = shipmentRecordService.getByIdAndFactoryId(shipmentId, factoryId);
            if (record.isPresent()) {
                return buildSingleResult(record.get());
            } else {
                Map<String, Object> result = new HashMap<>();
                result.put("found", false);
                result.put("message", "未找到出货单: " + shipmentId);
                return result;
            }
        }

        // 2. 精确查询 - 按出货单号
        if (shipmentNumber != null && !shipmentNumber.trim().isEmpty()) {
            Optional<ShipmentRecord> record = shipmentRecordService.getByShipmentNumberAndFactoryId(shipmentNumber, factoryId);
            if (record.isPresent()) {
                return buildSingleResult(record.get());
            } else {
                Map<String, Object> result = new HashMap<>();
                result.put("found", false);
                result.put("message", "未找到出货单号: " + shipmentNumber);
                return result;
            }
        }

        // 3. 按客户查询
        if (customerId != null && !customerId.trim().isEmpty()) {
            List<ShipmentRecord> records = shipmentRecordService.getByCustomer(factoryId, customerId);
            return buildListResult(records, customerId, "customerId");
        }

        // 4. 按日期范围查询
        if (dateFromStr != null && dateToStr != null) {
            try {
                LocalDate dateFrom = LocalDate.parse(dateFromStr, DATE_FORMATTER);
                LocalDate dateTo = LocalDate.parse(dateToStr, DATE_FORMATTER);
                List<ShipmentRecord> records = shipmentRecordService.getByDateRange(factoryId, dateFrom, dateTo);
                return buildDateRangeResult(records, dateFromStr, dateToStr);
            } catch (DateTimeParseException e) {
                log.warn("日期格式解析失败: {} - {}", dateFromStr, dateToStr);
            }
        }

        // 5. 分页查询 - 按状态或全部
        Page<ShipmentRecord> pageResult;
        String mappedStatus = mapStatus(status);

        if (mappedStatus != null) {
            pageResult = shipmentRecordService.getByFactoryIdAndStatus(factoryId, mappedStatus, page, size);
        } else {
            pageResult = shipmentRecordService.getByFactoryId(factoryId, page, size);
        }

        // 构建分页结果
        Map<String, Object> result = buildPageResult(
                pageResult.getContent(),
                pageResult.getTotalElements(),
                pageResult.getTotalPages(),
                page
        );

        // 添加查询条件摘要
        Map<String, Object> queryConditions = new HashMap<>();
        if (status != null) queryConditions.put("status", status);
        if (customerId != null) queryConditions.put("customerId", customerId);
        if (dateFromStr != null) queryConditions.put("dateFrom", dateFromStr);
        if (dateToStr != null) queryConditions.put("dateTo", dateToStr);
        result.put("queryConditions", queryConditions);

        log.info("出货单查询完成 - 总记录数: {}, 当前页: {}", pageResult.getTotalElements(), page);

        return result;
    }

    /**
     * 构建单条记录结果
     */
    private Map<String, Object> buildSingleResult(ShipmentRecord record) {
        Map<String, Object> result = new HashMap<>();
        result.put("found", true);
        result.put("shipment", convertToMap(record));
        result.put("message", "查询到出货单: " + record.getShipmentNumber());
        return result;
    }

    /**
     * 构建列表结果
     */
    private Map<String, Object> buildListResult(List<ShipmentRecord> records, String filterValue, String filterType) {
        Map<String, Object> result = new HashMap<>();
        result.put("content", records.stream().map(this::convertToMap).toList());
        result.put("totalElements", records.size());
        result.put("filterType", filterType);
        result.put("filterValue", filterValue);
        result.put("message", String.format("按%s查询到 %d 条出货记录", filterType, records.size()));
        return result;
    }

    /**
     * 构建日期范围结果
     */
    private Map<String, Object> buildDateRangeResult(List<ShipmentRecord> records, String dateFrom, String dateTo) {
        Map<String, Object> result = new HashMap<>();
        result.put("content", records.stream().map(this::convertToMap).toList());
        result.put("totalElements", records.size());
        result.put("dateFrom", dateFrom);
        result.put("dateTo", dateTo);
        result.put("message", String.format("%s 至 %s 期间共 %d 条出货记录", dateFrom, dateTo, records.size()));
        return result;
    }

    /**
     * 将实体转换为Map
     */
    private Map<String, Object> convertToMap(ShipmentRecord record) {
        Map<String, Object> map = new HashMap<>();
        map.put("id", record.getId());
        map.put("shipmentNumber", record.getShipmentNumber());
        map.put("customerId", record.getCustomerId());
        map.put("productName", record.getProductName());
        map.put("quantity", record.getQuantity());
        map.put("unit", record.getUnit());
        map.put("unitPrice", record.getUnitPrice());
        map.put("totalAmount", record.getTotalAmount());
        map.put("shipmentDate", record.getShipmentDate() != null ? record.getShipmentDate().toString() : null);
        map.put("status", mapStatusToUpper(record.getStatus()));
        map.put("deliveryAddress", record.getDeliveryAddress());
        map.put("logisticsCompany", record.getLogisticsCompany());
        map.put("trackingNumber", record.getTrackingNumber());
        map.put("notes", record.getNotes());
        return map;
    }

    /**
     * 映射状态值（从请求参数到数据库值）
     */
    private String mapStatus(String status) {
        if (status == null) return null;
        return switch (status.toUpperCase()) {
            case "PENDING" -> "pending";
            case "SHIPPED" -> "shipped";
            case "DELIVERED" -> "delivered";
            case "CANCELLED" -> "returned"; // CANCELLED映射到returned
            default -> status.toLowerCase();
        };
    }

    /**
     * 映射状态值（从数据库值到响应格式）
     */
    private String mapStatusToUpper(String status) {
        if (status == null) return null;
        return switch (status.toLowerCase()) {
            case "pending" -> "PENDING";
            case "shipped" -> "SHIPPED";
            case "delivered" -> "DELIVERED";
            case "returned" -> "CANCELLED";
            default -> status.toUpperCase();
        };
    }

    @Override
    protected String getParameterQuestion(String paramName) {
        switch (paramName) {
            case "status":
                return "请问要查询哪种状态的出货单？（PENDING待发货/SHIPPED已发货/DELIVERED已送达/CANCELLED已取消）";
            case "customerId":
                return "请问要查询哪个客户的出货单？";
            case "dateFrom":
                return "请问查询的开始日期是？（格式：yyyy-MM-dd）";
            case "dateTo":
                return "请问查询的结束日期是？（格式：yyyy-MM-dd）";
            default:
                return super.getParameterQuestion(paramName);
        }
    }

    @Override
    protected String getParameterDisplayName(String paramName) {
        switch (paramName) {
            case "status":
                return "状态";
            case "customerId":
                return "客户ID";
            case "dateFrom":
                return "开始日期";
            case "dateTo":
                return "结束日期";
            case "shipmentNumber":
                return "出货单号";
            case "shipmentId":
                return "出货单ID";
            default:
                return super.getParameterDisplayName(paramName);
        }
    }

    /** spec §8.2 只读查询, 无副作用 */
    @Override
    public AccessMode getAccessMode() {
        return AccessMode.READ;
    }
}
