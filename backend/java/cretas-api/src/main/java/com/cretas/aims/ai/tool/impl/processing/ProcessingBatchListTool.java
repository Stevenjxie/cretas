package com.cretas.aims.ai.tool.impl.processing;

import com.cretas.aims.ai.tool.AbstractBusinessTool;
import com.cretas.aims.dto.common.PageRequest;
import com.cretas.aims.dto.common.PageResponse;
import com.cretas.aims.entity.ProductionBatch;
import com.cretas.aims.service.ProcessingService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.stream.Collectors;

/**
 * 生产批次列表工具
 *
 * 获取生产批次列表，支持按状态筛选和分页查询。
 * 返回批次的基本信息列表，适用于概览和选择场景。
 *
 * @author Cretas Team
 * @version 1.0.0
 * @since 2026-01-08
 */
@Slf4j
@Component
public class ProcessingBatchListTool extends AbstractBusinessTool {

    @Autowired
    private ProcessingService processingService;

    @Override
    public String getToolName() {
        return "processing_batch_list";
    }

    @Override
    public String getDescription() {
        return "获取生产批次列表。支持按状态（计划中、生产中、已暂停、已完成、已取消）筛选，支持分页。" +
                "适用场景：查看生产任务列表、筛选特定状态的批次、批次概览。";
    }

    @Override
    public Map<String, Object> getParametersSchema() {
        Map<String, Object> schema = new HashMap<>();
        schema.put("type", "object");

        Map<String, Object> properties = new HashMap<>();

        // status: 状态筛选（可选）
        Map<String, Object> status = new HashMap<>();
        status.put("type", "string");
        status.put("description", "批次状态筛选");
        status.put("enum", Arrays.asList(
                "PLANNED",      // 已计划
                "IN_PROGRESS",  // 生产中
                "PAUSED",       // 已暂停
                "COMPLETED",    // 已完成
                "CANCELLED"     // 已取消
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
        log.info("执行获取生产批次列表 - 工厂ID: {}, 参数: {}", factoryId, params);

        // 1. 解析参数
        String status = getString(params, "status");
        Integer page = getInteger(params, "page", 1);
        Integer size = getInteger(params, "size", 10);

        // 2. 构建分页请求
        PageRequest pageRequest = new PageRequest();
        pageRequest.setPage(page);
        pageRequest.setSize(size);

        // 3. 调用服务获取数据
        PageResponse<ProductionBatch> pageResponse = processingService.getBatches(factoryId, status, pageRequest);

        // 4. 转换批次数据为简化格式
        List<Map<String, Object>> batchList = new ArrayList<>();
        if (pageResponse.getContent() != null) {
            for (ProductionBatch batch : pageResponse.getContent()) {
                Map<String, Object> batchInfo = new HashMap<>();
                batchInfo.put("batchId", batch.getId());
                batchInfo.put("batchNumber", batch.getBatchNumber());
                batchInfo.put("productTypeId", batch.getProductTypeId());
                batchInfo.put("productName", batch.getProductName());
                batchInfo.put("status", batch.getStatus() != null ? batch.getStatus().name() : null);
                batchInfo.put("plannedQuantity", batch.getPlannedQuantity());
                batchInfo.put("actualQuantity", batch.getActualQuantity());
                batchInfo.put("unit", batch.getUnit());
                batchInfo.put("yieldRate", batch.getYieldRate());
                batchInfo.put("supervisorName", batch.getSupervisorName());
                batchInfo.put("startTime", batch.getStartTime() != null ? batch.getStartTime().toString() : null);
                batchInfo.put("endTime", batch.getEndTime() != null ? batch.getEndTime().toString() : null);
                batchList.add(batchInfo);
            }
        }

        // 5. 构建返回结果
        Map<String, Object> result = buildPageResult(
                batchList,
                pageResponse.getTotalElements() != null ? pageResponse.getTotalElements() : 0L,
                pageResponse.getTotalPages() != null ? pageResponse.getTotalPages() : 0,
                page
        );

        // 添加查询条件摘要
        Map<String, Object> queryConditions = new HashMap<>();
        if (status != null) queryConditions.put("status", status);
        queryConditions.put("page", page);
        queryConditions.put("size", size);
        result.put("queryConditions", queryConditions);

        // 添加状态统计（如果未指定状态筛选）
        if (status == null && batchList.size() > 0) {
            Map<String, Long> statusCounts = batchList.stream()
                    .filter(b -> b.get("status") != null)
                    .collect(Collectors.groupingBy(
                            b -> (String) b.get("status"),
                            Collectors.counting()
                    ));
            result.put("statusSummary", statusCounts);
        }

        log.info("获取生产批次列表完成 - 总记录数: {}, 当前页: {}",
                pageResponse.getTotalElements(), page);

        return result;
    }

    @Override
    protected String getParameterQuestion(String paramName) {
        switch (paramName) {
            case "status":
                return "请问要筛选哪种状态的批次？（PLANNED/IN_PROGRESS/PAUSED/COMPLETED/CANCELLED，可选）";
            case "page":
                return "请问要查看第几页？（默认第1页）";
            case "size":
                return "请问每页显示多少条？（默认10条）";
            default:
                return super.getParameterQuestion(paramName);
        }
    }

    @Override
    protected String getParameterDisplayName(String paramName) {
        switch (paramName) {
            case "status":
                return "批次状态";
            case "page":
                return "页码";
            case "size":
                return "每页数量";
            default:
                return super.getParameterDisplayName(paramName);
        }
    }

    @Override
    public boolean requiresPermission() {
        return false; // 查询类工具不需要特殊权限
    }

    /** spec §8.2 只读查询, 无副作用 */
    @Override
    public AccessMode getAccessMode() {
        return AccessMode.READ;
    }
}
