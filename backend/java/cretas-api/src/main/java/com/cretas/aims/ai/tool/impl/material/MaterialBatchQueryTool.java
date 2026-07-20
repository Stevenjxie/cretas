package com.cretas.aims.ai.tool.impl.material;

import com.cretas.aims.ai.tool.AbstractBusinessTool;
import com.cretas.aims.dto.common.PageRequest;
import com.cretas.aims.dto.common.PageResponse;
import com.cretas.aims.dto.material.MaterialBatchDTO;
import com.cretas.aims.service.MaterialBatchService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.*;

/**
 * 原料批次查询工具
 *
 * 提供原料批次的分页查询功能，支持按批次号、原料类型、状态等条件筛选。
 * 作为查询类Tool，无必需参数，所有参数均为可选。
 *
 * @author Cretas Team
 * @version 1.0.0
 * @since 2026-01-07
 */
@Slf4j
@Component
public class MaterialBatchQueryTool extends AbstractBusinessTool {

    @Autowired
    private MaterialBatchService materialBatchService;

    @Override
    public String getToolName() {
        return "material_batch_query";
    }

    @Override
    public String getDescription() {
        return "查询原料批次列表。支持按批次号、原料类型ID、状态进行筛选，支持分页。" +
                "适用场景：查看库存批次、查找特定批次、查询某类原料的所有批次。";
    }

    @Override
    public ActionType getActionType() {
        return ActionType.READ;
    }

    @Override
    public RiskLevel getRiskLevel() {
        return RiskLevel.LOW;
    }

    @Override
    public boolean supportsPreview() {
        return false;
    }

    @Override
    public boolean requiresPermission() {
        return true;
    }

    @Override
    public boolean hasPermission(String userRole) {
        return false;
    }

    @Override
    public Set<String> getRequiredPermissions() {
        return Set.of(
                "warehouse:read",
                "warehouse:read_write",
                "inventory:read",
                "inventory:read_write");
    }

    @Override
    public String getVersion() {
        return "1.0.0";
    }

    @Override
    public Set<String> getDomainTags() {
        return Set.of("material");
    }

    @Override
    public Map<String, Object> getParametersSchema() {
        Map<String, Object> schema = new HashMap<>();
        schema.put("type", "object");

        Map<String, Object> properties = new HashMap<>();

        // batchNumber: 批次号（可选）
        Map<String, Object> batchNumber = new HashMap<>();
        batchNumber.put("type", "string");
        batchNumber.put("description", "批次号，支持模糊查询");
        properties.put("batchNumber", batchNumber);

        // materialTypeId: 原料类型ID（可选）
        Map<String, Object> materialTypeId = new HashMap<>();
        materialTypeId.put("type", "string");
        materialTypeId.put("description", "原料类型ID，精确匹配");
        properties.put("materialTypeId", materialTypeId);

        // status: 状态（可选）
        Map<String, Object> status = new HashMap<>();
        status.put("type", "string");
        status.put("description", "批次状态筛选");
        status.put("enum", Arrays.asList(
                "PENDING",      // 待检验
                "AVAILABLE",    // 可用
                "RESERVED",     // 已预留
                "IN_USE",       // 使用中
                "USED_UP",      // 已用完
                "EXPIRED",      // 已过期
                "FROZEN",       // 冻品
                "REJECTED"      // 已拒收
        ));
        properties.put("status", status);

        // page: 页码（可选，默认1）
        Map<String, Object> page = new HashMap<>();
        page.put("type", "integer");
        page.put("description", "页码，从1开始");
        page.put("default", 1);
        page.put("minimum", 1);
        properties.put("page", page);

        // size: 每页数量（可选，默认10）
        Map<String, Object> size = new HashMap<>();
        size.put("type", "integer");
        size.put("description", "每页记录数");
        size.put("default", 10);
        size.put("minimum", 1);
        size.put("maximum", 100);
        properties.put("size", size);

        // startDate: 入库日期开始（可选，格式 YYYY-MM-DD）
        Map<String, Object> startDate = new HashMap<>();
        startDate.put("type", "string");
        startDate.put("format", "date");
        startDate.put("description", "入库日期开始（含），格式：YYYY-MM-DD");
        properties.put("startDate", startDate);

        // endDate: 入库日期结束（可选，格式 YYYY-MM-DD）
        Map<String, Object> endDate = new HashMap<>();
        endDate.put("type", "string");
        endDate.put("format", "date");
        endDate.put("description", "入库日期结束（含），格式：YYYY-MM-DD");
        properties.put("endDate", endDate);

        schema.put("properties", properties);

        // 查询类Tool无必需参数
        schema.put("required", Collections.emptyList());

        return schema;
    }

    /**
     * 查询类Tool无必需参数
     */
    @Override
    protected List<String> getRequiredParameters() {
        return Collections.emptyList();
    }

    @Override
    protected Map<String, Object> doExecute(String factoryId, Map<String, Object> params, Map<String, Object> context) throws Exception {
        log.info("执行原料批次查询 - 工厂ID: {}, 参数: {}", factoryId, params);

        // 解析分页参数
        Integer page = getInteger(params, "page", 1);
        Integer size = getInteger(params, "size", 10);

        // 解析筛选参数
        String batchNumber = getString(params, "batchNumber");
        String materialTypeId = getString(params, "materialTypeId");
        String status = getString(params, "status");
        String startDateStr = getString(params, "startDate");
        String endDateStr = getString(params, "endDate");

        // 解析日期参数
        LocalDate startDate = parseDate(startDateStr);
        LocalDate endDate = parseDate(endDateStr);
        if (startDate != null || endDate != null) {
            log.info("日期过滤条件: {} ~ {}", startDate, endDate);
        }

        // 构建分页请求
        PageRequest pageRequest = new PageRequest();
        pageRequest.setPage(page);
        pageRequest.setSize(size);

        // 设置筛选条件
        if (batchNumber != null && !batchNumber.trim().isEmpty()) {
            pageRequest.setKeyword(batchNumber);
        }
        if (status != null && !status.trim().isEmpty()) {
            pageRequest.setStatus(status);
        }

        // 调用服务获取数据
        PageResponse<MaterialBatchDTO> pageResponse = materialBatchService.getMaterialBatchList(factoryId, pageRequest);

        // 在结果中过滤（因为PageRequest不支持部分筛选条件）
        List<MaterialBatchDTO> content = pageResponse.getContent();
        if (content != null) {
            // 按原料类型过滤
            if (materialTypeId != null && !materialTypeId.trim().isEmpty()) {
                content = filterByMaterialTypeId(content, materialTypeId);
            }
            // 按日期范围过滤（入库日期）
            if (startDate != null || endDate != null) {
                content = filterByDateRange(content, startDate, endDate);
            }
        }

        // 构建返回结果
        Map<String, Object> result = buildPageResult(
                content != null ? content : Collections.emptyList(),
                pageResponse.getTotalElements() != null ? pageResponse.getTotalElements() : 0L,
                pageResponse.getTotalPages() != null ? pageResponse.getTotalPages() : 0,
                page
        );

        // 添加查询条件摘要
        Map<String, Object> queryConditions = new HashMap<>();
        if (batchNumber != null) queryConditions.put("batchNumber", batchNumber);
        if (materialTypeId != null) queryConditions.put("materialTypeId", materialTypeId);
        if (status != null) queryConditions.put("status", status);
        if (startDate != null) queryConditions.put("startDate", startDate.toString());
        if (endDate != null) queryConditions.put("endDate", endDate.toString());
        result.put("queryConditions", queryConditions);

        // 更新过滤后的记录数
        result.put("totalElements", content != null ? content.size() : 0L);

        log.info("原料批次查询完成 - 总记录数: {}, 当前页: {}",
                pageResponse.getTotalElements(), page);

        return result;
    }

    /**
     * 按原料类型ID过滤批次列表
     *
     * @param batches 批次列表
     * @param materialTypeId 原料类型ID
     * @return 过滤后的列表
     */
    private List<MaterialBatchDTO> filterByMaterialTypeId(List<MaterialBatchDTO> batches, String materialTypeId) {
        List<MaterialBatchDTO> filtered = new ArrayList<>();
        for (MaterialBatchDTO batch : batches) {
            if (batch.getMaterialTypeId() != null && batch.getMaterialTypeId().equals(materialTypeId)) {
                filtered.add(batch);
            }
        }
        return filtered;
    }

    /**
     * 解析日期字符串
     *
     * @param dateStr 日期字符串 (YYYY-MM-DD)
     * @return LocalDate 或 null
     */
    private LocalDate parseDate(String dateStr) {
        if (dateStr == null || dateStr.trim().isEmpty()) {
            return null;
        }
        try {
            return LocalDate.parse(dateStr.trim());
        } catch (DateTimeParseException e) {
            log.warn("无法解析日期: {}", dateStr);
            return null;
        }
    }

    /**
     * 按入库日期范围过滤批次列表
     *
     * @param batches 批次列表
     * @param startDate 开始日期（含）
     * @param endDate 结束日期（含）
     * @return 过滤后的列表
     */
    private List<MaterialBatchDTO> filterByDateRange(List<MaterialBatchDTO> batches, LocalDate startDate, LocalDate endDate) {
        List<MaterialBatchDTO> filtered = new ArrayList<>();
        for (MaterialBatchDTO batch : batches) {
            LocalDate receiptDate = batch.getReceiptDate();
            if (receiptDate == null) {
                continue; // 没有入库日期的记录跳过
            }
            boolean afterStart = (startDate == null) || !receiptDate.isBefore(startDate);
            boolean beforeEnd = (endDate == null) || !receiptDate.isAfter(endDate);
            if (afterStart && beforeEnd) {
                filtered.add(batch);
            }
        }
        log.info("日期过滤: {} 条 -> {} 条", batches.size(), filtered.size());
        return filtered;
    }
}
