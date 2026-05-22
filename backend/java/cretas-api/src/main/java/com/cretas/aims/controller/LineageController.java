package com.cretas.aims.controller;

import com.cretas.aims.controller.dto.lineage.BatchLineageClosureDTO;
import com.cretas.aims.controller.dto.lineage.BatchLineageEdgeDTO;
import com.cretas.aims.controller.dto.lineage.LineageGraph;
import com.cretas.aims.dto.common.ApiResponse;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.service.lineage.LineageService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.NotBlank;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.List;

/**
 * Lineage 控制器 — 食品行业批次溯源 API. Phase 1 Sprint 1 Day 6 (2026-05-22).
 *
 * <p>承载食品行业批次级 lineage 的 HTTP 入口. 所有端点基于工厂 ID 进行多租户隔离。
 *
 * <h3>API 路径</h3>
 * <p>基础路径: <code>/api/mobile/{factoryId}/lineage</code></p>
 *
 * <h3>提供的 API 端点 (共 4 个)</h3>
 * <ol>
 *   <li><b>GET</b> /lineage/edges/from/{nodeId}?nodeType=... — 某节点的下游直接边 (1 跳)</li>
 *   <li><b>GET</b> /lineage/edges/to/{nodeId}?nodeType=...   — 某节点的上游直接边 (1 跳)</li>
 *   <li><b>GET</b> /lineage/closure/{nodeId}?nodeType=...&amp;maxDepth=N&amp;direction=... — 多跳闭包</li>
 *   <li><b>GET</b> /lineage/graph/{nodeId}?nodeType=...&amp;maxDepth=N — 双向整图</li>
 * </ol>
 *
 * <h3>权限</h3>
 * <p>全部端点要求 {@code @PreAuthorize("hasAuthority('lineage:read')")} —
 * Phase 1 lineage 只读, 写入由批次/出货模块自动维护。
 *
 * <h3>多租户隔离</h3>
 * <p>所有查询基于 {@code factoryId} 路径变量, Service 层 enforce 跨租户隔离, Controller 仅传递。
 *
 * @author Cretas Team
 * @version 1.0.0
 * @since 2026-05-22 (Phase 1 Sprint 1 Day 6)
 * @see LineageService 业务逻辑层
 */
@Slf4j
@RestController
@RequestMapping("/api/mobile/{factoryId}/lineage")
@RequiredArgsConstructor
@Tag(name = "批次溯源", description = "食品行业批次级 lineage — 客户投诉追溯 / 召回影响范围分析 / CCP 关联查询")
public class LineageController {

    private final LineageService lineageService;

    // ============================================================
    // 1. Outbound edges — 某节点的下游直接边
    // ============================================================

    /**
     * 查询某节点的下游直接边 ("这批原料/批次去了哪 1 跳").
     *
     * @param factoryId 工厂 ID
     * @param nodeId    源节点 ID
     * @param nodeType  源节点类型 (MATERIAL_BATCH / PRODUCTION_BATCH / FINISHED_BATCH / SHIPMENT_RECORD)
     * @return 直接下游 edges
     */
    @GetMapping("/edges/from/{nodeId}")
    @PreAuthorize("hasAuthority('lineage:read')")
    @Operation(summary = "查询节点的下游直接边",
            description = "返回该节点作为 source 的所有直接 edges (1 跳, 不含传递闭包)")
    public ApiResponse<List<BatchLineageEdgeDTO>> getOutboundEdges(
            @Parameter(description = "工厂 ID", required = true, example = "F006")
            @PathVariable @NotBlank String factoryId,
            @Parameter(description = "源节点 ID", required = true)
            @PathVariable @NotBlank String nodeId,
            @Parameter(description = "源节点类型 (MATERIAL_BATCH/PRODUCTION_BATCH/FINISHED_BATCH/SHIPMENT_RECORD)",
                    required = true, example = "PRODUCTION_BATCH")
            @RequestParam @NotBlank String nodeType) {

        log.debug("getOutboundEdges: factoryId={}, nodeId={}, nodeType={}",
                factoryId, nodeId, nodeType);
        List<BatchLineageEdgeDTO> edges = lineageService.findOutbound(factoryId, nodeId, nodeType);
        return ApiResponse.success(edges);
    }

    // ============================================================
    // 2. Inbound edges — 某节点的上游直接边
    // ============================================================

    /**
     * 查询某节点的上游直接边 ("这个成品/出货来自哪 1 跳").
     *
     * @param factoryId 工厂 ID
     * @param nodeId    目标节点 ID
     * @param nodeType  目标节点类型
     * @return 直接上游 edges
     */
    @GetMapping("/edges/to/{nodeId}")
    @PreAuthorize("hasAuthority('lineage:read')")
    @Operation(summary = "查询节点的上游直接边",
            description = "返回该节点作为 target 的所有直接 edges (1 跳)")
    public ApiResponse<List<BatchLineageEdgeDTO>> getInboundEdges(
            @Parameter(description = "工厂 ID", required = true, example = "F006")
            @PathVariable @NotBlank String factoryId,
            @Parameter(description = "目标节点 ID", required = true)
            @PathVariable @NotBlank String nodeId,
            @Parameter(description = "目标节点类型", required = true, example = "FINISHED_BATCH")
            @RequestParam @NotBlank String nodeType) {

        log.debug("getInboundEdges: factoryId={}, nodeId={}, nodeType={}",
                factoryId, nodeId, nodeType);
        List<BatchLineageEdgeDTO> edges = lineageService.findInbound(factoryId, nodeId, nodeType);
        return ApiResponse.success(edges);
    }

    // ============================================================
    // 3. Closure walk — 多跳传递闭包
    // ============================================================

    /**
     * 查询某节点的多跳闭包. 支持 {@code direction} 参数:
     * <ul>
     *   <li>{@code ancestors} (默认) — 上游溯源 (e.g. 客户投诉成品 → 找原料)</li>
     *   <li>{@code descendants} — 下游影响分析 (e.g. 原料出问题 → 找影响哪些成品)</li>
     *   <li>{@code both} — 双向 (合并去重)</li>
     * </ul>
     *
     * @param factoryId 工厂 ID
     * @param nodeId    查询节点 ID
     * @param nodeType  查询节点类型
     * @param maxDepth  最大深度 (默认 5, 业务上 4-5 跳已足够)
     * @param direction 方向 ancestors/descendants/both, 默认 ancestors
     * @return 闭包记录列表 (depth ASC)
     */
    @GetMapping("/closure/{nodeId}")
    @PreAuthorize("hasAuthority('lineage:read')")
    @Operation(summary = "多跳传递闭包查询",
            description = "用于客户投诉追溯 / 召回影响范围分析; direction=ancestors/descendants/both")
    public ApiResponse<List<BatchLineageClosureDTO>> getClosure(
            @Parameter(description = "工厂 ID", required = true, example = "F006")
            @PathVariable @NotBlank String factoryId,
            @Parameter(description = "查询节点 ID", required = true)
            @PathVariable @NotBlank String nodeId,
            @Parameter(description = "查询节点类型", required = true, example = "FINISHED_BATCH")
            @RequestParam @NotBlank String nodeType,
            @Parameter(description = "最大深度 (默认 5)")
            @RequestParam(required = false, defaultValue = "5") Integer maxDepth,
            @Parameter(description = "方向: ancestors / descendants / both (默认 ancestors)")
            @RequestParam(required = false, defaultValue = "ancestors") String direction) {

        log.debug("getClosure: factoryId={}, nodeId={}, nodeType={}, maxDepth={}, direction={}",
                factoryId, nodeId, nodeType, maxDepth, direction);

        List<BatchLineageClosureDTO> result;
        switch (direction == null ? "ancestors" : direction.toLowerCase()) {
            case "ancestors" -> result = lineageService.findAncestors(
                    factoryId, nodeId, nodeType, maxDepth);
            case "descendants" -> result = lineageService.findDescendants(
                    factoryId, nodeId, nodeType, maxDepth);
            case "both" -> {
                result = new ArrayList<>();
                result.addAll(lineageService.findAncestors(factoryId, nodeId, nodeType, maxDepth));
                result.addAll(lineageService.findDescendants(factoryId, nodeId, nodeType, maxDepth));
            }
            default -> throw new BusinessException(
                    "Invalid direction: " + direction + " (must be ancestors/descendants/both)");
        }
        return ApiResponse.success(result);
    }

    // ============================================================
    // 4. Full graph — 双向可视化图
    // ============================================================

    /**
     * 构建某节点的整图 (双向遍历, 给前端可视化).
     *
     * <p>典型用法 — 前端 d3/vis-network 渲染溯源图: 节点 + 有向边.
     *
     * @param factoryId 工厂 ID
     * @param nodeId    起点节点 ID
     * @param nodeType  起点节点类型
     * @param maxDepth  最大深度 (默认 5)
     * @return 整图数据 ({@code nodes} + {@code edges})
     */
    @GetMapping("/graph/{nodeId}")
    @PreAuthorize("hasAuthority('lineage:read')")
    @Operation(summary = "构建节点的双向溯源整图",
            description = "返回 {nodes, edges} 用于前端 d3/vis-network 可视化")
    public ApiResponse<LineageGraph> getGraph(
            @Parameter(description = "工厂 ID", required = true, example = "F006")
            @PathVariable @NotBlank String factoryId,
            @Parameter(description = "起点节点 ID", required = true)
            @PathVariable @NotBlank String nodeId,
            @Parameter(description = "起点节点类型", required = true, example = "FINISHED_BATCH")
            @RequestParam @NotBlank String nodeType,
            @Parameter(description = "最大深度 (默认 5)")
            @RequestParam(required = false, defaultValue = "5") Integer maxDepth) {

        log.debug("getGraph: factoryId={}, nodeId={}, nodeType={}, maxDepth={}",
                factoryId, nodeId, nodeType, maxDepth);
        LineageGraph graph = lineageService.buildFullGraph(factoryId, nodeId, nodeType, maxDepth);
        return ApiResponse.success(graph);
    }
}
