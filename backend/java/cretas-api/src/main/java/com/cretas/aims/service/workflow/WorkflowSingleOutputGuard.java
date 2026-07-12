package com.cretas.aims.service.workflow;

import com.cretas.aims.exception.BusinessException;

/**
 * 2B MVP 单产出守卫 (spec 2026-07-11 product-process-workflow-runtime-2b-clerk-implementation.md#Task B1).
 *
 * <p>MVP 约束: 每个可报工 (reportingRequired) PROCESS 节点最多只能有一个 OUTPUT 端口。
 * 由 {@link com.cretas.aims.service.workflow.impl.ProductProcessWorkflowActivationServiceImpl#activate}
 * 在启用前主动拒绝, 并由 {@link com.cretas.aims.service.workflow.impl.ProductProcessWorkflowRuntimeServiceImpl
 * #materializeIfActive} 在批次实例化时防御性地再次校验 (防止已启用的历史激活生成多产出批次)。
 *
 * <p>禁止降级处理: 违反本约束一律拒绝 (409), 从不静默丢弃多余产出端口。真正的多产出扇出留给 2B.2。
 */
public final class WorkflowSingleOutputGuard {

    public static final String ERROR_CODE = "WORKFLOW_MULTI_OUTPUT_UNSUPPORTED";

    private static final String HINT =
            "当前版本每道工序仅支持一个产出，请在 Workflow 配置中拆分或删除多余产出后再启用。";

    private WorkflowSingleOutputGuard() {
    }

    /**
     * 校验已编译工作流的每个可报工节点最多一个 OUTPUT 端口, 违反则抛出 {@link #multiOutputUnsupported()}。
     */
    public static void assertSingleOutputPerReportableTask(CompiledProductProcessWorkflow compiled) {
        for (CompiledProductProcessWorkflow.CompiledTask task : compiled.reportableTasks()) {
            long outputCount = compiled.portsFor(task.workflowNodeId()).stream()
                    .filter(port -> "OUTPUT".equals(port.direction()))
                    .count();
            if (outputCount > 1) {
                throw multiOutputUnsupported();
            }
        }
    }

    /**
     * 构造稳定的 409 异常, 供其它需要复用同一错误码/提示的调用方 (如 clerk-sheet 投影服务) 直接抛出。
     */
    public static BusinessException multiOutputUnsupported() {
        return new BusinessException(409,
                "Workflow has a process node with more than one OUTPUT port, which is not supported yet")
                .withCode(ERROR_CODE)
                .withHint(HINT)
                .withSeverity("warning");
    }
}
