package com.cretas.aims.service.workflow;

import com.cretas.aims.dto.workflow.WorkflowOutputDirectoryDTO;

/**
 * 配置侧「按产出成品反查工艺图」—— 独立只读入口。
 *
 * <p>⛔ 刻意**不**复用 {@code ProductWorkflowResolutionService#resolveForOutputs}, 也**不**给它加
 * 布尔开关。那条是计划侧的精确语义(勾选集合 == 终端集合, 且只返回最高优先层); 本接口是配置侧的
 * 包含语义(终端集合 ⊇ {这个成品})。spec §4.5: 语义不能取决于调用方记得传什么参数, 必须是两个入口。
 *
 * <p>举例说明两者读数为什么必须不同: 图 A 产出 {P1}, 图 B 产出 {P1,P2,P3}。
 * 计划侧问「我要做 P1」→ 只给图 A(精确)。配置侧问「谁产出 P1」→ 图 A 和图 B 都要给, 因为
 * 用户就是要找到并打开那张图。用计划侧的收敛回答配置侧的问题会让图 B 从界面上消失。
 */
public interface WorkflowOutputDirectoryService {

    /**
     * 找出所有终端产出**包含**该成品的已启用工艺图。
     *
     * <p>「已启用」= activation.enabled 且指向的图是 PUBLISHED 且版本与 activation 记录一致。
     * 不施加计划准入用的单位契约复核(那是建计划时才该拦的事), 否则一个单位主数据变更会让
     * 配置界面上整张图凭空消失, 用户连打开它去修都做不到。
     *
     * @return 永不为 null; 没有任何图产出它时 workflows 为空列表(前端据此给明确空态)
     */
    WorkflowOutputDirectoryDTO findWorkflowsProducing(String factoryId, String finishedGoodProductTypeId);
}
