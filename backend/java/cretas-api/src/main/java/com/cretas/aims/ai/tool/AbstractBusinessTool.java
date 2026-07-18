package com.cretas.aims.ai.tool;

import com.cretas.aims.ai.dto.ToolCall;
import lombok.extern.slf4j.Slf4j;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 业务 Tool 抽象基类
 *
 * 为业务操作类 Tool 提供通用功能：
 * 1. 自动参数校验
 * 2. 缺失参数提示
 * 3. 标准化执行流程
 * 4. 类型转换工具方法
 *
 * @author Cretas Team
 * @version 1.0.0
 * @since 2026-01-07
 */
@Slf4j
public abstract class AbstractBusinessTool extends AbstractTool {

    /**
     * 获取必需参数列表
     *
     * @return 必需参数名称列表
     */
    protected abstract List<String> getRequiredParameters();

    /**
     * 执行业务逻辑（子类实现）
     *
     * @param factoryId 工厂ID
     * @param params 已校验的参数
     * @param context 执行上下文
     * @return 执行结果（Map格式）
     * @throws Exception 执行异常
     */
    protected abstract Map<String, Object> doExecute(String factoryId, Map<String, Object> params, Map<String, Object> context) throws Exception;

    /**
     * 预览业务操作（支持预览的子类必须覆盖）
     *
     * 默认实现必须 fail closed，绝不能委托到 doExecute()。子类只有在实现了真正的
     * 只读预览后，才能同时覆盖 {@link #supportsPreview()} 返回 true。
     *
     * @param factoryId 工厂ID
     * @param params 已校验的参数
     * @param context 执行上下文
     * @return 预览结果（Map格式），应包含 status=PREVIEW
     * @throws Exception 预览异常
     */
    protected Map<String, Object> doPreview(String factoryId, Map<String, Object> params, Map<String, Object> context) throws Exception {
        throw new UnsupportedOperationException(
                "Tool '" + getToolName() + "' does not implement a safe business preview");
    }

    /**
     * 统一执行入口
     */
    @Override
    public String execute(ToolCall toolCall, Map<String, Object> context) throws Exception {
        logExecutionStart(toolCall, context);
        validateContext(context);

        try {
            // 1. 解析参数
            Map<String, Object> params = parseArguments(toolCall);
            String factoryId = getFactoryId(context);

            // 2. 校验必需参数
            List<String> missingParams = validateRequiredParams(params);
            if (!missingParams.isEmpty()) {
                return buildNeedMoreInfoResult(missingParams);
            }

            // 3. 执行业务逻辑
            Map<String, Object> result = doExecute(factoryId, params, context);

            // 4. 构建成功响应
            String response = buildSuccessResult(result);
            logExecutionSuccess(toolCall, response);
            return response;

        } catch (IllegalArgumentException e) {
            log.warn("⚠️  参数验证失败: {}", e.getMessage());
            // 脱敏处理：只返回经过安全检查的消息
            String safeMessage = sanitizeErrorMessage(e.getMessage());
            return buildErrorResult("参数验证失败: " + safeMessage);
        } catch (com.cretas.aims.exception.BusinessException e) {
            // BusinessException 消息本就面向用户(GlobalExceptionHandler 也直接展示), 不脱敏,
            // 让工具的友好提示(如"找不到原料 X"/"匹配到多条")透传给用户, 而非笼统"操作执行失败"。
            log.warn("业务规则: {}", e.getMessage());
            return buildErrorResult(e.getMessage());
        } catch (Exception e) {
            logExecutionFailure(toolCall, e);
            // 脱敏处理：不暴露具体异常信息
            return buildSanitizedErrorResult(e);
        }
    }

    /**
     * 校验必需参数
     *
     * @param params 输入参数
     * @return 缺失的参数列表
     */
    protected List<String> validateRequiredParams(Map<String, Object> params) {
        List<String> required = getRequiredParameters();
        if (required == null || required.isEmpty()) {
            return new ArrayList<>();
        }

        return required.stream()
                .filter(p -> !params.containsKey(p) || params.get(p) == null ||
                             (params.get(p) instanceof String && ((String) params.get(p)).trim().isEmpty()))
                .collect(Collectors.toList());
    }

    /**
     * 构建需要更多信息的响应
     *
     * @param missingParams 缺失的参数列表
     * @return JSON响应
     */
    protected String buildNeedMoreInfoResult(List<String> missingParams) {
        try {
            Map<String, Object> result = new HashMap<>();
            result.put("success", false);
            result.put("status", "NEED_MORE_INFO");
            result.put("needMoreInfo", true);
            result.put("missingParameters", missingParams);

            // 生成友好的提示问题
            List<String> questions = generateClarificationQuestions(missingParams);
            result.put("clarificationQuestions", questions);

            // 构建友好消息（替代 "缺少必需参数: x, y"）
            StringBuilder friendlyMsg = new StringBuilder();
            friendlyMsg.append("为了完成操作，还需要以下信息：\n");
            for (int i = 0; i < questions.size(); i++) {
                friendlyMsg.append(i + 1).append(". ").append(questions.get(i)).append("\n");
            }
            friendlyMsg.append("\n请直接告诉我，我来帮您处理。");
            result.put("message", friendlyMsg.toString());

            return objectMapper.writeValueAsString(result);
        } catch (Exception e) {
            log.error("❌ 构建NEED_MORE_INFO结果失败", e);
            // 脱敏处理：不暴露序列化错误
            return buildErrorResult("响应处理失败");
        }
    }

    /**
     * 根据缺失参数生成澄清问题
     *
     * @param missingParams 缺失参数列表
     * @return 问题列表
     */
    protected List<String> generateClarificationQuestions(List<String> missingParams) {
        List<String> questions = new ArrayList<>();

        for (String param : missingParams) {
            String question = getParameterQuestion(param);
            if (question != null) {
                questions.add(question);
            } else {
                // 默认问题格式
                questions.add("请提供" + getParameterDisplayName(param));
            }
        }

        return questions;
    }

    /**
     * 获取参数的提示问题（子类可覆盖）
     *
     * @param paramName 参数名
     * @return 提示问题
     */
    protected String getParameterQuestion(String paramName) {
        // 通用参数问题映射
        Map<String, String> commonQuestions = Map.of(
            "batchId", "请问您要操作哪个批次？请提供批次ID或批次号。",
            "batchNumber", "请问您要操作哪个批次？请提供批次号。",
            "quantity", "请问数量是多少？",
            "materialTypeId", "请问是哪种原材料类型？",
            "productionPlanId", "请问是哪个生产计划？",
            "reason", "请说明原因。",
            "status", "请问要设置为什么状态？"
        );

        return commonQuestions.get(paramName);
    }

    /**
     * 获取参数显示名称（子类可覆盖）
     *
     * @param paramName 参数名
     * @return 显示名称
     */
    protected String getParameterDisplayName(String paramName) {
        Map<String, String> displayNames = Map.ofEntries(
            Map.entry("batchId", "批次ID"),
            Map.entry("batchNumber", "批次号"),
            Map.entry("quantity", "数量"),
            Map.entry("materialTypeId", "原材料类型ID"),
            Map.entry("productionPlanId", "生产计划ID"),
            Map.entry("reason", "原因"),
            Map.entry("status", "状态"),
            Map.entry("supplierId", "供应商ID"),
            Map.entry("warningDays", "预警天数"),
            Map.entry("page", "页码"),
            Map.entry("size", "每页数量")
        );

        return displayNames.getOrDefault(paramName, paramName);
    }

    /**
     * 预览入口 — 走 doPreview() 钩子
     */
    @Override
    public String preview(ToolCall toolCall, Map<String, Object> context) throws Exception {
        logExecutionStart(toolCall, context);
        validateContext(context);

        try {
            Map<String, Object> params = parseArguments(toolCall);
            String factoryId = getFactoryId(context);

            List<String> missingParams = validateRequiredParams(params);
            if (!missingParams.isEmpty()) {
                return buildNeedMoreInfoResult(missingParams);
            }

            Map<String, Object> result = doPreview(factoryId, params, context);
            return buildSuccessResult(result);
        } catch (IllegalArgumentException e) {
            log.warn("⚠️  预览参数验证失败: {}", e.getMessage());
            String safeMessage = sanitizeErrorMessage(e.getMessage());
            return buildErrorResult("参数验证失败: " + safeMessage);
        } catch (com.cretas.aims.exception.BusinessException e) {
            // 同 execute(): BusinessException 友好提示透传, 不脱敏
            log.warn("业务规则(预览): {}", e.getMessage());
            return buildErrorResult(e.getMessage());
        } catch (Exception e) {
            logExecutionFailure(toolCall, e);
            return buildSanitizedErrorResult(e);
        }
    }

    // ==================== Governance: Convention-based Defaults ====================

    /**
     * Auto-derive ActionType from tool name convention.
     * Tools named *_create → WRITE, *_delete → DELETE, *_update → UPDATE,
     * *_analyze / *_analysis → ANALYZE, *_notify → NOTIFY, *_generate → GENERATE.
     * Everything else defaults to READ.
     */
    @Override
    public ActionType getActionType() {
        String name = getToolName();
        if (name == null) return ActionType.READ;
        if (name.endsWith("_create") || name.contains("_create_")) return ActionType.WRITE;
        if (name.endsWith("_delete") || name.contains("_delete_")) return ActionType.DELETE;
        if (name.endsWith("_update") || name.contains("_update_")) return ActionType.UPDATE;
        if (name.contains("_analyze") || name.contains("_analysis")) return ActionType.ANALYZE;
        if (name.contains("_notify") || name.contains("_alert")) return ActionType.NOTIFY;
        if (name.contains("_generate")) return ActionType.GENERATE;
        return ActionType.READ;
    }

    /**
     * Auto-derive RiskLevel from ActionType convention.
     * DELETE → MEDIUM, WRITE → MEDIUM (data creation), UPDATE → LOW,
     * everything else → LOW.
     */
    @Override
    public RiskLevel getRiskLevel() {
        ActionType action = getActionType();
        switch (action) {
            case DELETE: return RiskLevel.MEDIUM;
            case WRITE: return RiskLevel.MEDIUM;
            default: return RiskLevel.LOW;
        }
    }

    /**
     * Auto-derive domain tag from tool name prefix (first segment before '_').
     */
    @Override
    public Set<String> getDomainTags() {
        String name = getToolName();
        if (name == null || !name.contains("_")) return Set.of();
        return Set.of(name.substring(0, name.indexOf('_')));
    }

    // ==================== 类型转换工具方法 ====================

    /**
     * 获取String参数
     */
    protected String getString(Map<String, Object> params, String key) {
        Object value = params.get(key);
        return value != null ? value.toString() : null;
    }

    /**
     * 获取String参数（带默认值）
     */
    protected String getString(Map<String, Object> params, String key, String defaultValue) {
        Object value = params.get(key);
        return value != null ? value.toString() : defaultValue;
    }

    /**
     * 获取Integer参数
     */
    protected Integer getInteger(Map<String, Object> params, String key) {
        Object value = params.get(key);
        if (value == null) return null;
        if (value instanceof Number) return ((Number) value).intValue();
        try {
            return Integer.parseInt(value.toString());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * 获取Integer参数（带默认值）
     */
    protected Integer getInteger(Map<String, Object> params, String key, Integer defaultValue) {
        Integer value = getInteger(params, key);
        return value != null ? value : defaultValue;
    }

    /**
     * 获取Long参数
     */
    protected Long getLong(Map<String, Object> params, String key) {
        Object value = params.get(key);
        if (value == null) return null;
        if (value instanceof Number) return ((Number) value).longValue();
        try {
            return Long.parseLong(value.toString());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * 获取BigDecimal参数
     */
    protected BigDecimal getBigDecimal(Map<String, Object> params, String key) {
        Object value = params.get(key);
        if (value == null) return null;
        if (value instanceof BigDecimal) return (BigDecimal) value;
        if (value instanceof Number) return BigDecimal.valueOf(((Number) value).doubleValue());
        try {
            return new BigDecimal(value.toString());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * 获取Boolean参数
     */
    protected Boolean getBoolean(Map<String, Object> params, String key) {
        Object value = params.get(key);
        if (value == null) return null;
        if (value instanceof Boolean) return (Boolean) value;
        return Boolean.parseBoolean(value.toString());
    }

    /**
     * 获取Boolean参数（带默认值）
     */
    protected Boolean getBoolean(Map<String, Object> params, String key, Boolean defaultValue) {
        Boolean value = getBoolean(params, key);
        return value != null ? value : defaultValue;
    }

    /**
     * 获取List参数
     */
    @SuppressWarnings("unchecked")
    protected <T> List<T> getList(Map<String, Object> params, String key) {
        Object value = params.get(key);
        if (value == null) return null;
        if (value instanceof List) return (List<T>) value;
        return null;
    }

    // ==================== 结果构建工具方法 ====================

    /**
     * 构建分页结果
     */
    protected Map<String, Object> buildPageResult(List<?> content, long totalElements, int totalPages, int currentPage) {
        Map<String, Object> result = new HashMap<>();
        result.put("content", content);
        result.put("totalElements", totalElements);
        result.put("totalPages", totalPages);
        result.put("currentPage", currentPage);
        result.put("hasMore", currentPage < totalPages);
        return result;
    }

    /**
     * 构建简单成功结果
     */
    protected Map<String, Object> buildSimpleResult(String message, Object data) {
        Map<String, Object> result = new HashMap<>();
        result.put("message", message);
        result.put("data", data);
        return result;
    }
}
