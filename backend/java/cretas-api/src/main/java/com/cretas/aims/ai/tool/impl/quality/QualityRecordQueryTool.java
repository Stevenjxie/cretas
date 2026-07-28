package com.cretas.aims.ai.tool.impl.quality;

import com.cretas.aims.ai.tool.AbstractBusinessTool;
import com.cretas.aims.dto.common.PageRequest;
import com.cretas.aims.dto.common.PageResponse;
import com.cretas.aims.entity.QualityInspection;
import com.cretas.aims.repository.QualityInspectionRepository;
import com.cretas.aims.service.QualityInspectionService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 质检记录查询工具
 *
 * 提供质检记录的分页查询功能，支持按批次、结果、日期范围等条件筛选。
 * 作为查询类Tool，无必需参数，所有参数均为可选。
 *
 * @author Cretas Team
 * @version 1.0.0
 * @since 2026-01-07
 */
@Slf4j
@Component
public class QualityRecordQueryTool extends AbstractBusinessTool {

    @Autowired
    private QualityInspectionService qualityInspectionService;

    @Autowired
    private QualityInspectionRepository qualityInspectionRepository;

    @Override
    public String getToolName() {
        return "quality_record_query";
    }

    @Override
    public String getDescription() {
        return "查询质检记录（历史结果）。支持按批次ID、检验结果、日期范围筛选，支持分页。" +
                "适用场景：查看历史质检记录、查找特定批次质检结果、统计质检通过率。";
    }

    @Override
    public Map<String, Object> getParametersSchema() {
        Map<String, Object> schema = new HashMap<>();
        schema.put("type", "object");

        Map<String, Object> properties = new HashMap<>();

        // batchId: 生产批次ID（可选）
        Map<String, Object> batchId = new HashMap<>();
        batchId.put("type", "integer");
        batchId.put("description", "生产批次ID，精确匹配");
        properties.put("batchId", batchId);

        // result: 质检结果（可选）
        Map<String, Object> result = new HashMap<>();
        result.put("type", "string");
        result.put("description", "质检结果筛选：PASS-合格，FAIL-不合格，CONDITIONAL-有条件放行");
        result.put("enum", Arrays.asList("PASS", "FAIL", "CONDITIONAL"));
        properties.put("result", result);

        // dateFrom: 开始日期（可选）
        Map<String, Object> dateFrom = new HashMap<>();
        dateFrom.put("type", "string");
        dateFrom.put("format", "date");
        dateFrom.put("description", "查询开始日期，格式：yyyy-MM-dd");
        properties.put("dateFrom", dateFrom);

        // dateTo: 结束日期（可选）
        Map<String, Object> dateTo = new HashMap<>();
        dateTo.put("type", "string");
        dateTo.put("format", "date");
        dateTo.put("description", "查询结束日期，格式：yyyy-MM-dd");
        properties.put("dateTo", dateTo);

        // inspectorId: 检验员ID（可选）
        Map<String, Object> inspectorId = new HashMap<>();
        inspectorId.put("type", "integer");
        inspectorId.put("description", "检验员ID，精确匹配");
        properties.put("inspectorId", inspectorId);

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
        log.info("执行质检记录查询 - 工厂ID: {}, 参数: {}", factoryId, params);

        // 解析分页参数
        Integer page = getInteger(params, "page", 1);
        Integer size = getInteger(params, "size", 10);

        // 解析筛选参数
        Long batchId = getLong(params, "batchId");
        String result = getString(params, "result");
        String dateFromStr = getString(params, "dateFrom");
        String dateToStr = getString(params, "dateTo");
        Long inspectorId = getLong(params, "inspectorId");

        // 解析日期
        LocalDate dateFrom = parseDate(dateFromStr);
        LocalDate dateTo = parseDate(dateToStr);

        // 构建分页请求
        PageRequest pageRequest = new PageRequest();
        pageRequest.setPage(page);
        pageRequest.setSize(size);

        // 调用服务获取数据
        String productionBatchIdStr = batchId != null ? batchId.toString() : null;
        PageResponse<QualityInspection> pageResponse = qualityInspectionService.getInspections(
                factoryId, productionBatchIdStr, pageRequest);

        // 获取内容并进行额外筛选
        List<QualityInspection> content = pageResponse.getContent();
        if (content == null) {
            content = Collections.emptyList();
        }

        // 按结果筛选
        if (result != null && !result.trim().isEmpty()) {
            content = content.stream()
                    .filter(q -> result.equals(q.getResult()))
                    .collect(Collectors.toList());
        }

        // 按日期范围筛选
        if (dateFrom != null) {
            final LocalDate from = dateFrom;
            content = content.stream()
                    .filter(q -> q.getInspectionDate() != null && !q.getInspectionDate().isBefore(from))
                    .collect(Collectors.toList());
        }
        if (dateTo != null) {
            final LocalDate to = dateTo;
            content = content.stream()
                    .filter(q -> q.getInspectionDate() != null && !q.getInspectionDate().isAfter(to))
                    .collect(Collectors.toList());
        }

        // 按检验员筛选
        if (inspectorId != null) {
            content = content.stream()
                    .filter(q -> inspectorId.equals(q.getInspectorId()))
                    .collect(Collectors.toList());
        }

        // 转换为返回格式
        List<Map<String, Object>> records = content.stream()
                .map(this::convertToMap)
                .collect(Collectors.toList());

        // 构建返回结果
        Map<String, Object> resultData = buildPageResult(
                records,
                content.size(), // 筛选后的数量
                (int) Math.ceil((double) content.size() / size),
                page
        );

        // 添加查询条件摘要
        Map<String, Object> queryConditions = new HashMap<>();
        if (batchId != null) queryConditions.put("batchId", batchId);
        if (result != null) queryConditions.put("result", result);
        if (dateFromStr != null) queryConditions.put("dateFrom", dateFromStr);
        if (dateToStr != null) queryConditions.put("dateTo", dateToStr);
        if (inspectorId != null) queryConditions.put("inspectorId", inspectorId);
        resultData.put("queryConditions", queryConditions);

        // 添加统计摘要
        Map<String, Object> summary = new HashMap<>();
        long passCount = content.stream().filter(q -> "PASS".equals(q.getResult())).count();
        long failCount = content.stream().filter(q -> "FAIL".equals(q.getResult())).count();
        long conditionalCount = content.stream().filter(q -> "CONDITIONAL".equals(q.getResult())).count();
        summary.put("total", content.size());
        summary.put("passCount", passCount);
        summary.put("failCount", failCount);
        summary.put("conditionalCount", conditionalCount);
        if (!content.isEmpty()) {
            summary.put("passRate", String.format("%.2f%%", (passCount * 100.0 / content.size())));
        }
        resultData.put("summary", summary);

        log.info("质检记录查询完成 - 总记录数: {}, 当前页: {}", content.size(), page);

        return resultData;
    }

    /**
     * 将质检记录转换为Map格式
     */
    private Map<String, Object> convertToMap(QualityInspection inspection) {
        Map<String, Object> map = new HashMap<>();
        map.put("id", inspection.getId());
        map.put("productionBatchId", inspection.getProductionBatchId());
        map.put("inspectorId", inspection.getInspectorId());
        map.put("inspectionDate", inspection.getInspectionDate() != null ?
                inspection.getInspectionDate().toString() : null);
        map.put("sampleSize", inspection.getSampleSize());
        map.put("passCount", inspection.getPassCount());
        map.put("failCount", inspection.getFailCount());
        map.put("passRate", inspection.getPassRate());
        map.put("result", inspection.getResult());
        map.put("qualityGrade", inspection.getQualityGrade());
        map.put("defectRate", inspection.getDefectRate());
        map.put("notes", inspection.getNotes());
        return map;
    }

    /**
     * 解析日期字符串
     */
    private LocalDate parseDate(String dateStr) {
        if (dateStr == null || dateStr.trim().isEmpty()) {
            return null;
        }
        try {
            return LocalDate.parse(dateStr, DateTimeFormatter.ISO_LOCAL_DATE);
        } catch (DateTimeParseException e) {
            log.warn("日期解析失败: {}", dateStr);
            return null;
        }
    }

    @Override
    protected String getParameterQuestion(String paramName) {
        switch (paramName) {
            case "batchId":
                return "请问要查询哪个生产批次的质检记录？";
            case "result":
                return "请问要筛选什么结果的记录？合格(PASS)、不合格(FAIL)还是有条件放行(CONDITIONAL)？";
            case "dateFrom":
                return "请问查询的开始日期是？";
            case "dateTo":
                return "请问查询的结束日期是？";
            default:
                return super.getParameterQuestion(paramName);
        }
    }

    @Override
    protected String getParameterDisplayName(String paramName) {
        switch (paramName) {
            case "batchId":
                return "生产批次ID";
            case "result":
                return "质检结果";
            case "dateFrom":
                return "开始日期";
            case "dateTo":
                return "结束日期";
            case "inspectorId":
                return "检验员ID";
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
