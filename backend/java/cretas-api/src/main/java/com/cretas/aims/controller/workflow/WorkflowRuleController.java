package com.cretas.aims.controller.workflow;

import com.cretas.aims.config.RequireRole;
import com.cretas.aims.dto.common.ApiResponse;
import com.cretas.aims.dto.workflow.WorkflowRuleRequest;
import com.cretas.aims.entity.config.WorkflowRule;
import com.cretas.aims.service.workflow.WorkflowRuleEvaluator;
import com.cretas.aims.service.workflow.WorkflowRuleService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * WorkflowRule REST controller — Sprint 4 Wave 1 (C-WF-RULE-1).
 *
 * <p>Base path: {@code /api/mobile/{factoryId}/workflow-rules}
 *
 * @since 2026-05-16
 */
@RestController
@RequestMapping("/api/mobile/{factoryId}/workflow-rules")
@RequiredArgsConstructor
@Slf4j
@Tag(name = "工作流流转规则",
     description = "ApprovalWorkflow condition 节点的用户友好条件配置 (AMOUNT/DEPT/ROLE/SPEL_CUSTOM).")
public class WorkflowRuleController {

    private final WorkflowRuleService ruleService;
    private final WorkflowRuleEvaluator ruleEvaluator;

    @GetMapping
    @Operation(summary = "列指定 workflow 下所有规则")
    public ApiResponse<List<WorkflowRule>> listByWorkflow(
            @PathVariable @Parameter(description = "工厂 ID") String factoryId,
            @RequestParam @Parameter(description = "ApprovalWorkflow.id") String workflowId) {
        return ApiResponse.success(ruleService.listByWorkflow(factoryId, workflowId));
    }

    @GetMapping("/by-node")
    @Operation(summary = "列指定 node 下已启用规则 (executor preview / debug)")
    public ApiResponse<List<WorkflowRule>> listByNode(
            @PathVariable String factoryId,
            @RequestParam String workflowId,
            @RequestParam String nodeId) {
        // factoryId 不直接 filter — 但通过 workflowId 间接绑定到工厂; 调用方应先校验 workflow 属于 factoryId
        return ApiResponse.success(ruleService.findActiveByNode(workflowId, nodeId));
    }

    @GetMapping("/{id}")
    @Operation(summary = "获取规则详情")
    public ApiResponse<WorkflowRule> getById(
            @PathVariable String factoryId,
            @PathVariable String id) {
        return ApiResponse.success(ruleService.getRequired(factoryId, id));
    }

    @PostMapping
    @RequireRole({"factory_super_admin", "platform_admin"})
    @Operation(summary = "创建规则")
    public ApiResponse<WorkflowRule> create(
            @PathVariable String factoryId,
            @RequestBody @Valid WorkflowRuleRequest request) {
        return ApiResponse.success("规则创建成功", ruleService.create(factoryId, request));
    }

    @PutMapping("/{id}")
    @RequireRole({"factory_super_admin", "platform_admin"})
    @Operation(summary = "更新规则")
    public ApiResponse<WorkflowRule> update(
            @PathVariable String factoryId,
            @PathVariable String id,
            @RequestBody @Valid WorkflowRuleRequest request) {
        return ApiResponse.success("规则更新成功", ruleService.update(factoryId, id, request));
    }

    @DeleteMapping("/{id}")
    @RequireRole({"factory_super_admin", "platform_admin"})
    @Operation(summary = "软删除规则")
    public ApiResponse<Void> delete(
            @PathVariable String factoryId,
            @PathVariable String id) {
        ruleService.delete(factoryId, id);
        return ApiResponse.successMessage("规则已删除");
    }

    @PostMapping("/{id}/test")
    @Operation(summary = "用 mock context 测试规则 (Vue simulator + AIChat tool 调用)")
    public ApiResponse<Map<String, Object>> test(
            @PathVariable String factoryId,
            @PathVariable String id,
            @RequestBody Map<String, Object> mockContext) {
        WorkflowRule rule = ruleService.getRequired(factoryId, id);
        boolean result = ruleEvaluator.evaluate(rule, mockContext);

        Map<String, Object> response = new HashMap<>();
        response.put("ruleId", id);
        response.put("ruleType", rule.getRuleType().name());
        response.put("result", result);
        response.put("mockContext", mockContext);
        response.put("expression", rule.getExpression());
        return ApiResponse.success(result ? "规则命中" : "规则未命中", response);
    }
}
