package com.cretas.aims.dto.ai;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;

/**
 * AI意图执行请求DTO
 *
 * 用于发送用户的自然语言输入，系统会：
 * 1. 识别意图 (使用 AIIntentService)
 * 2. 检查权限和审批要求
 * 3. 路由到对应的业务模块执行
 * 4. 返回执行结果
 *
 * @author Cretas Team
 * @version 1.0.0
 * @since 2026-01-02
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class IntentExecuteRequest {

    /**
     * 用户输入的自然语言文本
     * 例如: "给原材料表单添加一个运输温度字段，数值类型，-30到30度"
     */
    private String userInput;

    /**
     * 显式指定的意图代码 (可选)
     * 如果提供，跳过意图识别，直接执行指定意图
     * 例如: SCALE_ADD_DEVICE, MATERIAL_BATCH_QUERY
     */
    private String intentCode;

    /**
     * 目标实体类型 (可选，用于限定操作范围)
     * 例如: MATERIAL_BATCH, PRODUCT_TYPE, FACTORY, ENCODING_RULE
     */
    private String entityType;

    /**
     * 目标实体ID (可选，用于指定具体实体)
     * 例如: 表单模板ID、编码规则ID
     */
    private String entityId;

    /**
     * 执行上下文参数 (可选)
     * 可包含现有字段列表、业务场景等
     */
    private Map<String, Object> context;

    /**
     * 是否为预览模式 (不实际执行，只返回将要执行的操作)
     */
    @Builder.Default
    private Boolean previewOnly = false;

    /**
     * 强制执行 (仅服务端内部编排可设置；JSON 入站只读)
     */
    @JsonProperty(access = JsonProperty.Access.READ_ONLY)
    @Builder.Default
    private Boolean forceExecute = false;

    /**
     * 会话ID (可选，用于多轮对话延续)
     * 当意图识别置信度低于30%时，系统会启动多轮对话
     * 后续回复需携带此sessionId以延续对话
     */
    private String sessionId;

    /**
     * 是否启用AI思考过程 (可选)
     * 启用后AI会进行深度推理分析，适用于咨询类问题
     * 禁用时使用快速响应模式
     * 默认: false
     */
    @Builder.Default
    private Boolean enableThinking = false;

    /**
     * 思考预算Token数 (仅enableThinking=true时有效)
     * 范围: 10-100，值越大推理越深入但响应越慢
     * 建议值: 简单问题20, 复杂问题50-80
     * 默认: 20
     */
    @Builder.Default
    private Integer thinkingBudget = 20;

    /**
     * 预处理后的查询 (内部使用)
     * 由查询预处理服务填充，包含：
     * - 归一化的时间范围
     * - 解析的实体引用
     * - 质量评估分数
     */
    private PreprocessedQuery preprocessedQuery;

    /**
     * 是否跳过 Slot Filling 参数收集 (可选)
     * 当设置为 true 时，即使有缺失参数也不会触发渐进式参数收集
     * 适用于已在 context 中提供所有参数的场景
     * 默认: false
     */
    @Builder.Default
    private Boolean skipSlotFilling = false;

    /**
     * AI 问答入口模式: READ=咨询tab(强制只读) | OPERATE=操作tab(全能力)。
     * null 视为 OPERATE (向后兼容, 老客户端零破坏)。
     */
    private String mode;
}
