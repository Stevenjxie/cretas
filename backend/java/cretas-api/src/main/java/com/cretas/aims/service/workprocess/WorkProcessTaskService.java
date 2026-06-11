package com.cretas.aims.service.workprocess;

import com.cretas.aims.dto.WorkProcessTaskDTO;
import com.cretas.aims.dto.common.PageResponse;
import com.cretas.aims.entity.workprocess.WorkProcessTask.Status;
import org.springframework.data.domain.Pageable;

import java.util.List;

/**
 * 工序任务 Service (Track D2 — M-WP-1/2).
 */
public interface WorkProcessTaskService {

    /**
     * 从 product_work_processes 模板 spawn 工序任务实例.
     *
     * <p>每个 product_work_process 绑定生成 1 个 work_process_task (status=PENDING).
     * 已 spawn 过的批次不会重复生成 (返回已有任务列表).
     *
     * <p>{@code productTypeId} 必填: 上层 (controller / Tool) 需先从 ProductionBatch 解析
     * 产品类型再传入. 这样 Service 不依赖 ProductionBatch entity, 规避 brief §2.4 fork 风险。
     *
     * @return 生成的任务 DTO 列表
     */
    List<WorkProcessTaskDTO> spawnTasks(
            String factoryId, Long productionBatchId, String productTypeId);

    /**
     * 计划级"免工序报工"感知的 spawn (六扇门 Wave2 升级, V20261017_01).
     *
     * <p>分支:
     * <ul>
     *   <li>{@code skipProcessReporting=true} 或 产品 0 工序 → spawn 2 个批次级哨兵任务:
     *       领料报工 (work_process_id={@link #SENTINEL_MATERIAL_INPUT}, process_order=0) +
     *       产出报工 (work_process_id={@link #SENTINEL_FINAL_OUTPUT}, process_order={@link #SENTINEL_OUTPUT_ORDER})。
     *       各绑头尾责任人 (materialResponsibleId / outputResponsibleId, 可同一人或 null)。不 spawn 工序 task。</li>
     *   <li>{@code skipProcessReporting=false} 且 产品已配工序 → 委托旧 {@link #spawnTasks(String, Long, String)}
     *       逐道行为 (零回归, #690 reporting_required 过滤不变)。</li>
     * </ul>
     *
     * <p>两点报工的 yield/cost 由现有 report-driven 链 (calculateBatchYield) 算:
     * cumulative = lastOutput/firstInput = 产出/领料; 人工不报 → laborCost null (诚实)。
     *
     * @param skipProcessReporting   计划级免工序报工开关 (null 视为 false, 向后兼容)
     * @param materialResponsibleId  领料报工责任人 (头, 可 null)
     * @param outputResponsibleId    产出报工责任人 (尾, 可 null; 与头同一人=一人兼)
     * @return 生成的任务 DTO 列表
     */
    List<WorkProcessTaskDTO> spawnTasks(
            String factoryId, Long productionBatchId, String productTypeId,
            Boolean skipProcessReporting, Long materialResponsibleId, Long outputResponsibleId);

    /**
     * 计划模式感知的 retry spawn (Fable 审计修复 2026-06-11 — 问题2).
     *
     * <p>HTTP 手动端点 / AI 工具 在批次首次 spawn fail-soft 失败后补 spawn 时调用。
     * 与计划转批次主路径 ({@code createBatchFromPlan}) 行为一致: 从批次解析其生产计划,
     * 读 {@code plan.skipProcessReporting} 决定两点 or 逐道, 头尾责任人取 {@code plan.assignedSupervisorId}
     * (一人兼, 与主路径相同)。
     *
     * <p>解析不到计划 (批次无 productionPlanId / 计划不存在) → 兜底 skip=false (逐道, 安全默认),
     * 避免无计划上下文时误判两点。
     *
     * @param factoryId         工厂ID (租户隔离)
     * @param productionBatchId 生产批次ID
     * @param productTypeId     产品类型ID (该批次绑定的产品)
     * @return 生成的任务 DTO 列表 (已 spawn 过则返回已有)
     */
    List<WorkProcessTaskDTO> spawnTasksForBatch(
            String factoryId, Long productionBatchId, String productTypeId);

    /** 哨兵 work_process_id: 批次级领料报工任务 (免工序报工模式, 不对应真实 WorkProcess 定义). */
    String SENTINEL_MATERIAL_INPUT = "__MATERIAL_INPUT__";

    /** 哨兵 work_process_id: 批次级产出报工任务 (免工序报工模式). */
    String SENTINEL_FINAL_OUTPUT = "__FINAL_OUTPUT__";

    /** 哨兵 product_work_process_id: 批次级任务无 PWP 模板, 用 0L 占位 (列 NOT NULL). */
    Long SENTINEL_PWP_ID = 0L;

    /** 产出报工任务的 process_order (排末位, 保证 calculateSteps 取它作 lastStep). */
    int SENTINEL_OUTPUT_ORDER = 9999;

    /**
     * 列表 — 多过滤条件 + 分页.
     */
    PageResponse<WorkProcessTaskDTO> list(
            String factoryId,
            Status status,
            Long productionBatchId,
            Long assignedTo,
            Pageable pageable);

    /**
     * 列出某批次的工序任务 (按 processOrder 升序, 不分页).
     *
     * <p>过滤逻辑 (M1/M2):
     * <ul>
     *   <li>若 {@code assignedTo != null} 且批次内存在已分配的任务 → 只返回 assignedTo 匹配或 null 的任务.</li>
     *   <li>若批次内全部任务 assigned_to = null (老批次/未配默认) → 返回全部 (M1 全null兜底, 防锁死).</li>
     *   <li>若 {@code assignedTo == null} → 返回全部 (主管视图).</li>
     * </ul>
     */
    List<WorkProcessTaskDTO> listByBatch(String factoryId, Long productionBatchId, Long assignedTo);

    /**
     * 列出某批次的全部工序任务 (按 processOrder 升序, 不分页) — 兼容旧内部调用, 不过滤.
     *
     * <p>默认委托至 {@link #listByBatch(String, Long, Long)} 传 {@code assignedTo=null}.
     */
    default List<WorkProcessTaskDTO> listByBatch(String factoryId, Long productionBatchId) {
        return listByBatch(factoryId, productionBatchId, null);
    }

    /**
     * 详情.
     */
    WorkProcessTaskDTO getById(String factoryId, Long id);

    /**
     * 开始工序: PENDING → IN_PROGRESS, 记录 actualStartAt.
     */
    WorkProcessTaskDTO start(String factoryId, Long id, Long operatorUserId);

    /**
     * 完成工序: IN_PROGRESS → COMPLETED, 必填 actualQuantity, 自动算 actualMinutes.
     */
    WorkProcessTaskDTO complete(
            String factoryId,
            Long id,
            Long operatorUserId,
            WorkProcessTaskDTO.CompleteRequest request);

    /**
     * 跳过工序: 任意非终态 → SKIPPED, 必填 notes, 限主管.
     */
    WorkProcessTaskDTO skip(
            String factoryId,
            Long id,
            Long operatorUserId,
            WorkProcessTaskDTO.SkipRequest request);

    /**
     * 分配责任人 / 调整计划 / 修改备注 (不改 status).
     */
    WorkProcessTaskDTO updatePlan(
            String factoryId,
            Long id,
            WorkProcessTaskDTO.UpdatePlanRequest request);

    /**
     * 软删 (deleted_at = NOW).
     */
    void delete(String factoryId, Long id);
}
