package com.cretas.aims.ai.tool;

import com.cretas.aims.ai.dto.ToolCall;

import java.util.Map;
import java.util.Set;

/**
 * Tool 执行器接口
 *
 * 负责执行 LLM 调用的工具。
 * 每个工具实现此接口以处理特定的 Function Calling 请求。
 *
 * @author Cretas Team
 * @version 1.0.0
 * @since 2026-01-06
 */
public interface ToolExecutor {

    // ==================== Governance Enums ====================

    enum ActionType { READ, WRITE, UPDATE, DELETE, GENERATE, NOTIFY, ANALYZE }

    enum RiskLevel { LOW, MEDIUM, HIGH, CRITICAL }

    // ==================== Core Methods ====================

    /**
     * 获取工具名称（必须与 Tool Definition 中的 name 一致）
     *
     * @return 工具名称，如 "create_new_intent"
     */
    String getToolName();

    /**
     * 获取工具描述（用于生成 Tool Definition）
     *
     * @return 工具描述，LLM 根据此描述判断何时调用
     */
    String getDescription();

    /**
     * 获取工具参数定义（JSON Schema 格式）
     *
     * @return 参数定义，用于生成 Tool Definition
     * @example
     * {
     *   "type": "object",
     *   "properties": {
     *     "intent_code": {"type": "string", "description": "意图代码"},
     *     "intent_name": {"type": "string", "description": "意图名称"}
     *   },
     *   "required": ["intent_code", "intent_name"]
     * }
     */
    Map<String, Object> getParametersSchema();

    /**
     * 执行工具调用
     *
     * @param toolCall LLM 返回的工具调用对象（包含参数 JSON）
     * @param context 执行上下文（工厂ID、用户ID等）
     * @return 执行结果（JSON 字符串），将作为 tool_call_result 返回给 LLM
     * @throws Exception 执行失败时抛出异常
     */
    String execute(ToolCall toolCall, Map<String, Object> context) throws Exception;

    /**
     * 工具是否启用
     *
     * @return true 表示可用，false 表示禁用
     */
    default boolean isEnabled() {
        return true;
    }

    /**
     * 工具是否需要特殊权限
     *
     * @return true 表示需要权限校验
     */
    default boolean requiresPermission() {
        return false;
    }

    /**
     * 检查用户角色是否有权限使用此工具
     *
     * @param userRole 用户角色
     * @return true 表示有权限
     */
    default boolean hasPermission(String userRole) {
        return true;
    }

    /**
     * W9 红线 (AI-RBAC 系统性收口): 此工具在 AI 路径上要求的权限码 (module:action 格式,
     * 与 controller {@code @RequirePermission} 同源)。返回任一权限即可执行 (requireAll=false 语义)。
     *
     * <p><b>背景</b> (Fable Wave5/6/7 系统性审计): #704 只给 4 个工具加了 AI 路径鉴权, 但整类敏感写工具
     * (customer_delete / order_delete / finance_invoice_approve / transfer_approve / user_*  等 118 个)
     * 在 AI 对话路径上 <b>完全无鉴权</b> —— 默认 {@link #requiresPermission()}=false +
     * 默认 {@link #hasPermission(String)}=true, 而 {@code ToolExecutionManager} / W0 WriteGuard 只做
     * 可见性过滤 / 写确认门, 都不做角色鉴权。低权角色经 AI 对话即可删客户/订单/发票、批转账。
     *
     * <p>本方法让工具声明所需权限, 由中央 {@link ToolRbacEnforcer} 在 <b>所有执行路径</b>
     * (ToolDispatch / DynamicToolSelection-autoplan / SkillExecutor / ToolRouter / LlmIntentFallback /
     * IntentExecutionOrchestrator) 统一用 {@link com.cretas.aims.service.PermissionService} 矩阵校验,
     * 与 HTTP 面 RBAC 口径完全一致 (fail-closed)。
     *
     * <p>返回空集合 = 此工具不声明 AI 路径权限要求 (中央 enforcer 会回退到内置名称→权限映射;
     * 若映射也未命中则放行, 保持原 W0 写确认行为不变, 避免误伤 118 个混合写工具中的良性生成类)。
     *
     * @return 所需权限码集合 (任一即可); 空集合表示不声明显式要求
     */
    default Set<String> getRequiredPermissions() {
        return Set.of();
    }

    /**
     * 工具是否支持预览模式（TCC 确认流第一阶段）
     *
     * 支持预览的工具在 previewOnly=true 时返回操作预览而不执行，
     * 用户确认后再通过 confirm 流程真正执行。
     *
     * @return true 表示支持预览
     */
    default boolean supportsPreview() {
        return false;
    }

    /**
     * 预览执行结果（不实际执行操作）
     *
     * 默认实现必须 fail closed。支持预览的工具需要显式覆盖本方法，避免“预览”请求
     * 退化为真实 {@link #execute(ToolCall, Map)} 调用。
     *
     * @param toolCall LLM 返回的工具调用对象
     * @param context 执行上下文（工厂ID、用户ID等）
     * @return 预览结果（JSON 字符串），包含 status=PREVIEW
     * @throws Exception 预览失败时抛出异常
     */
    default String preview(ToolCall toolCall, Map<String, Object> context) throws Exception {
        throw new UnsupportedOperationException(
                "Tool '" + getToolName() + "' does not implement a safe preview");
    }

    // ==================== Governance Metadata (default methods) ====================

    /**
     * 工具操作类型（READ/WRITE/UPDATE/DELETE/GENERATE/NOTIFY/ANALYZE）
     */
    default ActionType getActionType() {
        return ActionType.READ;
    }

    /**
     * 工具风险等级（LOW/MEDIUM/HIGH/CRITICAL）
     */
    default RiskLevel getRiskLevel() {
        return RiskLevel.LOW;
    }

    /**
     * 工具版本号（语义化版本）
     */
    default String getVersion() {
        return "1.0.0";
    }

    /**
     * 废弃通知。返回 null 表示工具仍然活跃；非 null 表示已废弃，内容为迁移说明。
     */
    default String getDeprecationNotice() {
        return null;
    }

    /**
     * 工具所属领域标签，用于多维索引和治理分析。
     * 空集合表示未标注（将被治理审计标记为需要补充）。
     */
    default Set<String> getDomainTags() {
        return Set.of();
    }
}
