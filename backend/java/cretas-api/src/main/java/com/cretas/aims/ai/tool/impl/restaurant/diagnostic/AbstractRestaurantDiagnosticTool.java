package com.cretas.aims.ai.tool.impl.restaurant.diagnostic;

import com.cretas.aims.ai.tool.AbstractBusinessTool;
import com.cretas.aims.client.PythonSmartBIClient;
import com.cretas.aims.dto.python.PythonRestaurantSectionRequest;
import com.cretas.aims.dto.python.PythonRestaurantSectionResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * 餐饮诊断工具基类 — 封装"调用 Python section endpoint 并包装响应"的通用模板.
 *
 * <p>子类只需实现三个方法:
 * <ul>
 *   <li>{@link #getToolName()} — tool 唯一标识</li>
 *   <li>{@link #getDescription()} — LLM 判断何时调用的自然语言描述</li>
 *   <li>{@link #getSectionName()} — 对应的 Python section endpoint 名, 如 {@code "cost_rigidity"}</li>
 * </ul>
 *
 * <p>可选覆盖:
 * <ul>
 *   <li>{@link #buildSectionParams(String, Map, Map)} — 自定义参数转换 (例如从 DB 加载 reviews)</li>
 *   <li>{@link #formatResult(String, Map, List)} — 自定义结果格式化 (例如生成自然语言摘要)</li>
 *   <li>{@link #getParametersSchema()} / {@link #getRequiredParameters()} — 如有额外必填参数</li>
 * </ul>
 */
@Slf4j
public abstract class AbstractRestaurantDiagnosticTool extends AbstractBusinessTool {

    @Autowired
    protected PythonSmartBIClient pythonClient;

    /** 对应的 Python section endpoint 名, 例如 {@code "cost_rigidity"}. */
    protected abstract String getSectionName();

    @Override
    public Map<String, Object> getParametersSchema() {
        Map<String, Object> schema = new HashMap<>();
        schema.put("type", "object");

        Map<String, Object> properties = new HashMap<>();

        Map<String, Object> subSector = new HashMap<>();
        subSector.put("type", "string");
        subSector.put("description", "餐饮子行业, 如火锅/川菜/烧烤/西餐/日料. 默认火锅");
        properties.put("sub_sector", subSector);

        Map<String, Object> storeId = new HashMap<>();
        storeId.put("type", "string");
        storeId.put("description", "门店 ID, 可选");
        properties.put("store_id", storeId);

        Map<String, Object> uploadId = new HashMap<>();
        uploadId.put("type", "string");
        uploadId.put("description", "数据上传 ID, 可选 (用于指定历史批次)");
        properties.put("upload_id", uploadId);

        schema.put("properties", properties);
        schema.put("required", Collections.emptyList());
        return schema;
    }

    @Override
    protected List<String> getRequiredParameters() {
        return Collections.emptyList();
    }

    @Override
    protected Map<String, Object> doExecute(
            String factoryId,
            Map<String, Object> params,
            Map<String, Object> context) throws Exception {

        log.info("Diagnostic tool {} invoked — factory: {}, section: {}",
                 getToolName(), factoryId, getSectionName());

        String subSector = Objects.toString(params.getOrDefault("sub_sector", "火锅"), "火锅");
        String storeId = getString(params, "store_id");
        String uploadId = getString(params, "upload_id");
        String storeName = getString(params, "store_name");
        // #302: when an analysis month is resolved upstream (e.g. composite parses
        // "2025年12月" → month=2025-12), surface it as the Python `period` display label
        // so the P&L one-pager header matches the queried month instead of showing the
        // default "current". Absent month → keep the "current" default (no behavior change).
        String month = getString(params, "month");

        PythonRestaurantSectionRequest.PythonRestaurantSectionRequestBuilder builder =
            PythonRestaurantSectionRequest.builder()
                .factoryId(factoryId)
                .uploadId(uploadId)
                .subSector(subSector)
                .storeId(storeId)
                .storeName(storeName)
                .params(buildSectionParams(factoryId, params, context));
        if (month != null && !month.trim().isEmpty()) {
            builder.period(month.trim());
        }
        PythonRestaurantSectionRequest request = builder.build();

        Optional<PythonRestaurantSectionResponse> responseOpt =
            pythonClient.callRestaurantSection(getSectionName(), request);

        if (responseOpt.isEmpty()) {
            log.warn("Python section 调用失败: section={}, factory={}", getSectionName(), factoryId);
            return buildError("无法连接数据分析服务。请确认：\n" +
                "1. 已上传该门店的经营数据 (Excel/POS)\n" +
                "2. Python 分析服务正在运行\n" +
                "如需帮助请联系管理员 (section=" + getSectionName() + ")");
        }

        PythonRestaurantSectionResponse response = responseOpt.get();
        if (!response.isSuccess()) {
            String warnings = (response.getWarnings() != null && !response.getWarnings().isEmpty())
                ? String.join("; ", response.getWarnings())
                : "数据不足";
            // User-friendly message with actionable guidance
            String toolLabel = getDescription().contains("—")
                ? getDescription().split("—")[0].trim()
                : getSectionName();
            return buildError("暂无法生成「" + toolLabel + "」分析：" + warnings +
                "\n\n请先上传相关数据或补充所需参数后重试。");
        }

        return formatResult(response.getSectionName(), response.getData(), response.getWarnings());
    }

    /**
     * 构造 Python section params. 子类可覆盖注入特殊参数
     * (例如 review_analysis 需要从 DB 加载 reviews, cost_rigidity 需要组装 financial_data).
     *
     * <p>默认实现: 原样透传 params 下所有字段, 排除框架字段 (sub_sector / store_id / upload_id / store_name).
     */
    protected Map<String, Object> buildSectionParams(
            String factoryId,
            Map<String, Object> params,
            Map<String, Object> context) {
        Map<String, Object> sectionParams = new HashMap<>(params);
        sectionParams.remove("sub_sector");
        sectionParams.remove("store_id");
        sectionParams.remove("upload_id");
        sectionParams.remove("store_name");
        return sectionParams;
    }

    /**
     * Build contextual follow-up chip suggestions based on section output.
     *
     * <p>Default: empty list. Override in specific tools to suggest 3-4
     * context-aware next queries. Frontend renders as clickable chips below
     * each AI response bubble; clicking sends the chip text as the next query.
     *
     * <p>Part of P5 Task 5.5 chat UI integration.
     *
     * @param sectionName the Python section name that was executed
     * @param data the section's data dict
     * @return list of Chinese follow-up query strings (empty = no chips)
     */
    protected List<String> buildFollowUps(String sectionName, Map<String, Object> data) {
        return Collections.emptyList();
    }

    /** 默认结果格式. 子类可覆盖生成更友好的自然语言摘要. */
    protected Map<String, Object> formatResult(
            String sectionName,
            Map<String, Object> data,
            List<String> warnings) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("success", true);
        result.put("section", sectionName);
        result.put("data", data != null ? data : Collections.emptyMap());
        if (warnings != null && !warnings.isEmpty()) {
            result.put("warnings", warnings);
        }
        result.put("followUpChips", buildFollowUps(sectionName, data != null ? data : Collections.emptyMap()));
        return result;
    }

    /** 统一错误响应格式. */
    protected Map<String, Object> buildError(String message) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("success", false);
        result.put("message", message);
        return result;
    }
}
