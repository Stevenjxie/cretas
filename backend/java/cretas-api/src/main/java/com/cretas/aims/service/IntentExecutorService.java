package com.cretas.aims.service;

import com.cretas.aims.ai.tool.gateway.ConfirmationProof;
import com.cretas.aims.dto.ai.IntentExecuteRequest;
import com.cretas.aims.dto.ai.IntentExecuteResponse;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * AI意图执行服务接口
 *
 * 负责将识别到的意图路由到对应的业务模块执行:
 * - FORM: 表单字段生成 → FormTemplate/FormAssistant
 * - DATA_OP: 数据操作 → 对应业务Service
 * - ANALYSIS: 数据分析 → AI分析服务
 * - SCHEDULE: 排程操作 → Scheduling服务
 * - SYSTEM: 系统配置 → 配置服务
 *
 * @author Cretas Team
 * @version 1.0.0
 * @since 2026-01-02
 */
public interface IntentExecutorService {

    /**
     * 执行用户输入的AI意图
     *
     * 流程:
     * 1. 使用 AIIntentService 识别意图
     * 2. 检查用户权限
     * 3. 根据敏感度决定是否需要审批
     * 4. 路由到对应的 Handler 执行
     * 5. 返回执行结果
     *
     * @param factoryId 工厂ID
     * @param request 执行请求
     * @param userId 当前用户ID
     * @param userRole 当前用户角色
     * @return 执行响应
     */
    IntentExecuteResponse execute(String factoryId, IntentExecuteRequest request,
                                  Long userId, String userRole);

    /**
     * 预览执行结果 (不实际执行)
     *
     * @param factoryId 工厂ID
     * @param request 执行请求
     * @param userId 当前用户ID
     * @param userRole 当前用户角色
     * @return 预览响应
     */
    IntentExecuteResponse preview(String factoryId, IntentExecuteRequest request,
                                  Long userId, String userRole);

    /**
     * 确认执行预览的操作
     *
     * @param factoryId 工厂ID
     * @param confirmationProof 参数绑定的确认凭证
     * @param userId 当前用户ID
     * @param userRole 当前用户角色
     * @return 执行响应
     */
    IntentExecuteResponse confirm(String factoryId, ConfirmationProof confirmationProof,
                                  Long userId, String userRole);

    /**
     * 流式执行用户输入的AI意图 (SSE)
     *
     * 通过 Server-Sent Events 实时返回执行进度:
     * 1. start - 开始处理
     * 2. cache_hit / cache_miss - 缓存查询结果
     * 3. intent_recognized - 意图识别完成
     * 4. executing - 开始执行
     * 5. result - 执行结果
     * 6. complete - 完成
     * 7. error - 发生错误
     *
     * @param factoryId 工厂ID
     * @param request 执行请求
     * @param userId 当前用户ID
     * @param userRole 当前用户角色
     * @return SSE Emitter
     */
    SseEmitter executeStream(String factoryId, IntentExecuteRequest request,
                              Long userId, String userRole);

    /**
     * 执行多意图 (Multi-Label Classification)
     *
     * 使用 Sigmoid-based 多标签分类识别并执行多个意图:
     * 1. 使用 MultiLabelIntentClassifier 识别多个意图
     * 2. 根据执行策略 (PARALLEL/SEQUENTIAL/USER_CONFIRM) 执行
     * 3. 合并多个意图的执行结果
     *
     * @param factoryId 工厂ID
     * @param request 执行请求
     * @param userId 当前用户ID
     * @param userRole 当前用户角色
     * @return 执行响应（包含多意图结果）
     */
    IntentExecuteResponse executeMultiIntent(String factoryId, IntentExecuteRequest request,
                                              Long userId, String userRole);
}
