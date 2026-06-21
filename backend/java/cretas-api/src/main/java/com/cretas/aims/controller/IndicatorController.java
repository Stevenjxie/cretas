package com.cretas.aims.controller;

import com.cretas.aims.controller.dto.indicator.IndicatorDetailResponse;
import com.cretas.aims.controller.dto.indicator.IndicatorListResponse;
import com.cretas.aims.controller.dto.indicator.IndicatorTreeNodeResponse;
import com.cretas.aims.controller.dto.indicator.IndicatorValueResponse;
import com.cretas.aims.controller.dto.indicator.ThresholdResponse;
import com.cretas.aims.dto.common.ApiResponse;
import com.cretas.aims.dto.common.PageResponse;
import com.cretas.aims.entity.indicator.Indicator;
import com.cretas.aims.entity.indicator.IndicatorThreshold;
import com.cretas.aims.entity.indicator.IndicatorTreeNode;
import com.cretas.aims.entity.indicator.IndicatorVersion;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.repository.indicator.IndicatorRepository;
import com.cretas.aims.repository.indicator.IndicatorThresholdRepository;
import com.cretas.aims.repository.indicator.IndicatorTreeNodeRepository;
import com.cretas.aims.repository.indicator.IndicatorVersionRepository;
import com.cretas.aims.service.indicator.IndicatorQueryService;
import com.cretas.aims.service.indicator.dto.IndicatorValueResult;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.NotBlank;
import lombok.RequiredArgsConstructor;
import com.cretas.aims.annotation.RequirePermission;
import lombok.extern.slf4j.Slf4j;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 指标控制器 — 食品行业指标中心 Phase 1 Sprint 1 Day 5 (2026-05-22).
 *
 * <p>承载指标中心的 HTTP 入口。所有端点基于工厂 ID 进行多租户隔离。
 *
 * <h3>API 路径</h3>
 * <p>基础路径: <code>/api/mobile/{factoryId}/indicators</code></p>
 *
 * <h3>提供的 API 端点 (共 7 个)</h3>
 * <ol>
 *   <li><b>GET</b>  /indicators                       - 列出工厂全部启用指标 (可选 ?category 过滤)</li>
 *   <li><b>GET</b>  /indicators/tree                  - 完整指标树 (递归 children[])</li>
 *   <li><b>GET</b>  /indicators/{code}                - 按 code 获取指标详情 (含 thresholds[])</li>
 *   <li><b>GET</b>  /indicators/{code}/value          - 计算指标当前值 (REALTIME / CACHED / PRECOMPUTED 策略分发)</li>
 *   <li><b>GET</b>  /indicators/{code}/versions       - 指标历史快照 (分页)</li>
 *   <li><b>GET</b>  /indicators/thresholds/{code}     - 取指标的阈值规则 (GREEN/YELLOW/RED)</li>
 *   <li><b>POST</b> /indicators/{code}/recompute      - 强制重算 (admin only, 绕过 CACHED TTL)</li>
 * </ol>
 *
 * <h3>权限</h3>
 * <p>指标是 KPI/分析数据 (analytics 模块), 故用 {@code @RequirePermission} 对齐 analytics 模块权限。
 * 此前用 {@code @PreAuthorize} 但本项目未启用 Spring Security method security → no-op 失效守卫
 * (indicator:read/write 权限也从未授予任何角色)。改用真实生效的 @RequirePermission + 既有 analytics 权限,
 * 避免锁死 (analytics 已授予 platform_admin/factory_super_admin/dispatcher/production_manager 等)。</p>
 * <ul>
 *   <li>读取类 (GET): <code>@RequirePermission({"analytics:read", "analytics:read_write"})</code></li>
 *   <li>重算 (POST): <code>@RequirePermission({"analytics:read_write"})</code></li>
 * </ul>
 *
 * <h3>多租户隔离</h3>
 * <p>所有查询都基于 {@code factoryId} 路径变量, 自动注入到 Service 层的查询条件。
 * Day 4 Service 层已经 enforce 跨租户隔离, Controller 仅传递 factoryId。
 *
 * @author Cretas Team
 * @version 1.0.0
 * @since 2026-05-22 (Phase 1 Sprint 1 Day 5)
 * @see IndicatorQueryService 业务逻辑层
 * @see Indicator 实体类
 */
@Slf4j
@RestController
@RequestMapping("/api/mobile/{factoryId}/indicators")
@RequiredArgsConstructor
@Tag(name = "指标中心", description = "食品行业指标中心 — 工厂良品率 / 餐饮经营 / 质量管控 指标查询与计算")
public class IndicatorController {

    private final IndicatorQueryService indicatorQueryService;
    private final IndicatorRepository indicatorRepository;
    private final IndicatorTreeNodeRepository indicatorTreeNodeRepository;
    private final IndicatorThresholdRepository indicatorThresholdRepository;
    private final IndicatorVersionRepository indicatorVersionRepository;

    /**
     * Sprint 12 Phase D (#266): 统一构造 "指标不存在" 404 异常, 满足 4 位一体.
     *
     * <p>消息 specific (code + factoryId), 带 actionHint + hintTarget + severity=warning,
     * GlobalExceptionHandler 会 propagate 到前端, axios interceptor 404 分支看到 actionHint
     * 时用 showRichError 渲 sticky notification + "返回指标中心" 跳转按钮 (而非之前 silent fail).
     *
     * <p>替换原 5 处 inline {@code new BusinessException(404, "指标不存在...")} — 之前那些
     * 没 actionHint, 前端只 showMessage 不引导 next-action.
     */
    private BusinessException indicatorNotFound(String code, String factoryId) {
        return new BusinessException(404,
                "指标不存在: code=" + code + ", factoryId=" + factoryId)
                .withHint("该指标可能尚未配置，请返回指标中心查看可用指标")
                .withHintTarget("/indicator-center")
                .withSeverity("warning");
    }

    // ============================================================
    // 1. List indicators by factory + optional category
    // ============================================================

    /**
     * 列出工厂全部启用指标 (按 displayOrder 升序, code 二级排序).
     *
     * <p>可选 {@code ?category=FACTORY|RESTAURANT|QUALITY} 过滤分类; 不传默认全部。
     *
     * @param factoryId 工厂 ID
     * @param category  分类过滤 (可选)
     * @return 指标列表
     */
    @GetMapping
    @RequirePermission({"analytics:read", "analytics:read_write"})
    @Operation(summary = "列出工厂启用指标", description = "可选 ?category 过滤分类")
    public ApiResponse<List<IndicatorListResponse>> listIndicators(
            @Parameter(description = "工厂 ID", required = true, example = "F001")
            @PathVariable @NotBlank String factoryId,
            @Parameter(description = "分类: FACTORY / RESTAURANT / QUALITY (可选)")
            @RequestParam(required = false) String category) {

        log.debug("listIndicators: factoryId={}, category={}", factoryId, category);
        List<Indicator> indicators;
        if (category != null && !category.isBlank()) {
            indicators = indicatorQueryService.listByCategory(factoryId, category);
        } else {
            indicators = indicatorRepository.findActiveByFactoryId(factoryId);
        }
        List<IndicatorListResponse> result = indicators.stream()
                .map(IndicatorListResponse::fromEntity)
                .collect(Collectors.toList());
        return ApiResponse.success(result);
    }

    // ============================================================
    // 2. Full indicator tree
    // ============================================================

    /**
     * 完整指标树 — 根节点开始递归构建.
     *
     * <p>响应是 {@link IndicatorTreeNodeResponse} 数组, 每个根节点的 {@code children[]} 递归填充。
     *
     * @param factoryId 工厂 ID
     * @return 指标树 (根节点列表)
     */
    @GetMapping("/tree")
    @RequirePermission({"analytics:read", "analytics:read_write"})
    @Operation(summary = "完整指标树", description = "递归 children[] 结构")
    public ApiResponse<List<IndicatorTreeNodeResponse>> getTree(
            @Parameter(description = "工厂 ID", required = true, example = "F001")
            @PathVariable @NotBlank String factoryId) {

        log.debug("getTree: factoryId={}", factoryId);
        List<IndicatorTreeNode> roots = indicatorTreeNodeRepository.findRootsByFactoryId(factoryId);
        List<IndicatorTreeNodeResponse> tree = roots.stream()
                .map(IndicatorTreeNodeResponse::fromEntity)
                .peek(root -> attachChildren(root, factoryId))
                .collect(Collectors.toList());
        return ApiResponse.success(tree);
    }

    /**
     * 递归填充子节点 (DFS). 防止无限递归: depth 上限 10 层 (业务上不会超过).
     */
    private void attachChildren(IndicatorTreeNodeResponse parent, String factoryId) {
        if (parent.getDepth() != null && parent.getDepth() >= 10) {
            log.warn("attachChildren: skip subtree at depth >= 10 for parent indicatorId={}",
                    parent.getIndicatorId());
            return;
        }
        List<IndicatorTreeNode> children = indicatorTreeNodeRepository
                .findByParentIndicatorIdAndFactoryIdAndDeletedAtIsNull(parent.getIndicatorId(), factoryId);
        List<IndicatorTreeNodeResponse> childResponses = new ArrayList<>();
        for (IndicatorTreeNode child : children) {
            IndicatorTreeNodeResponse childResponse = IndicatorTreeNodeResponse.fromEntity(child);
            attachChildren(childResponse, factoryId);
            childResponses.add(childResponse);
        }
        parent.setChildren(childResponses);
    }

    // ============================================================
    // 3. Indicator detail by code
    // ============================================================

    /**
     * 按 code 获取指标详情 (含 thresholds[]).
     *
     * @param factoryId 工厂 ID
     * @param code      指标编码
     * @return 详情 DTO
     * @throws BusinessException 指标不存在
     */
    @GetMapping("/{code}")
    @RequirePermission({"analytics:read", "analytics:read_write"})
    @Operation(summary = "按 code 获取指标详情", description = "含 thresholds[]")
    public ApiResponse<IndicatorDetailResponse> getByCode(
            @Parameter(description = "工厂 ID", required = true, example = "F001")
            @PathVariable @NotBlank String factoryId,
            @Parameter(description = "指标编码", required = true, example = "FACTORY_YIELD_RATE")
            @PathVariable @NotBlank String code) {

        log.debug("getByCode: factoryId={}, code={}", factoryId, code);
        Indicator ind = indicatorRepository
                .findByCodeAndFactoryIdAndDeletedAtIsNull(code, factoryId)
                .orElseThrow(() -> indicatorNotFound(code, factoryId));
        List<IndicatorThreshold> thresholds = indicatorThresholdRepository
                .findActiveByIndicatorIdAndFactoryId(ind.getId(), factoryId);
        return ApiResponse.success(IndicatorDetailResponse.fromEntity(ind, thresholds));
    }

    // ============================================================
    // 4. Compute indicator value
    // ============================================================

    /**
     * 计算指标当前值 — 走 Day 4 IndicatorQueryService 的策略分发.
     *
     * <p>不传 {@code periodStart} / {@code periodEnd} 时默认当月初到今天。
     *
     * @param factoryId   工厂 ID
     * @param code        指标编码
     * @param periodStart 业务时间窗起 (可选, 默认本月 1 号)
     * @param periodEnd   业务时间窗止 (可选, 默认今天)
     * @return 计算结果 + cache hit + 来源
     */
    @GetMapping("/{code}/value")
    @RequirePermission({"analytics:read", "analytics:read_write"})
    @Operation(summary = "计算指标当前值",
            description = "策略分发 REALTIME/CACHED/PRECOMPUTED; 不传时间窗默认当月")
    public ApiResponse<IndicatorValueResponse> getValue(
            @Parameter(description = "工厂 ID", required = true, example = "F001")
            @PathVariable @NotBlank String factoryId,
            @Parameter(description = "指标编码", required = true, example = "FACTORY_YIELD_RATE")
            @PathVariable @NotBlank String code,
            @Parameter(description = "时间窗起 (yyyy-MM-dd)")
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
            LocalDate periodStart,
            @Parameter(description = "时间窗止 (yyyy-MM-dd)")
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
            LocalDate periodEnd) {

        LocalDate start = periodStart != null ? periodStart : LocalDate.now().withDayOfMonth(1);
        LocalDate end = periodEnd != null ? periodEnd : LocalDate.now();
        log.debug("getValue: factoryId={}, code={}, start={}, end={}", factoryId, code, start, end);

        // Service throws if not found. We still need name/unit for the response.
        Indicator ind = indicatorRepository
                .findByCodeAndFactoryIdAndDeletedAtIsNull(code, factoryId)
                .orElseThrow(() -> indicatorNotFound(code, factoryId));

        IndicatorValueResult result = indicatorQueryService.computeForCode(code, factoryId, start, end);
        IndicatorValueResponse resp = IndicatorValueResponse.fromWithIndicator(
                ind, result, start, end);
        return ApiResponse.success(resp);
    }

    // ============================================================
    // 5. Indicator versions (paginated history)
    // ============================================================

    /**
     * 指标历史快照 — 用于趋势图.
     *
     * <p>分页参数:
     * <ul>
     *   <li>{@code page}: 从 1 开始, 默认 1</li>
     *   <li>{@code size}: 默认 50, 上限 200</li>
     * </ul>
     *
     * <p>时间窗参数: 若都不传则返工厂全部 (按 computedAt 降序).
     * 若传 windowStart + windowEnd 则返时间窗内全部 (overlap).
     *
     * @param factoryId   工厂 ID
     * @param code        指标编码
     * @param windowStart 时间窗起 (可选)
     * @param windowEnd   时间窗止 (可选)
     * @param page        页码 (从 1 开始, 默认 1)
     * @param size        每页大小 (默认 50, 上限 200)
     * @return 分页快照列表
     */
    @GetMapping("/{code}/versions")
    @RequirePermission({"analytics:read", "analytics:read_write"})
    @Operation(summary = "指标历史快照 (分页)", description = "用于趋势图; 默认 50 条/页, 上限 200")
    public ApiResponse<PageResponse<IndicatorVersion>> getVersions(
            @Parameter(description = "工厂 ID", required = true, example = "F001")
            @PathVariable @NotBlank String factoryId,
            @Parameter(description = "指标编码", required = true, example = "FACTORY_YIELD_RATE")
            @PathVariable @NotBlank String code,
            @Parameter(description = "时间窗起 (yyyy-MM-dd, 可选)")
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
            LocalDate windowStart,
            @Parameter(description = "时间窗止 (yyyy-MM-dd, 可选)")
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
            LocalDate windowEnd,
            @Parameter(description = "页码 (从 1 开始)")
            @RequestParam(required = false, defaultValue = "1") Integer page,
            @Parameter(description = "每页大小 (上限 200)")
            @RequestParam(required = false, defaultValue = "50") Integer size) {

        log.debug("getVersions: factoryId={}, code={}, start={}, end={}, page={}, size={}",
                factoryId, code, windowStart, windowEnd, page, size);

        if (page < 1) page = 1;
        if (size < 1) size = 50;
        if (size > 200) size = 200;

        Indicator ind = indicatorRepository
                .findByCodeAndFactoryIdAndDeletedAtIsNull(code, factoryId)
                .orElseThrow(() -> indicatorNotFound(code, factoryId));

        List<IndicatorVersion> all;
        if (windowStart != null && windowEnd != null) {
            all = indicatorVersionRepository
                    .findByIndicatorIdAndFactoryIdAndPeriodOverlap(
                            ind.getId(), factoryId, windowStart, windowEnd);
        } else {
            // Use factory-wide recent fetch then filter by indicator (Day 4 repo didn't add
            // factory + indicator scoped variant; this is fine for Day 5 — Phase 2 can add
            // dedicated paged method when version table grows).
            all = indicatorVersionRepository.findRecentByFactoryId(factoryId).stream()
                    .filter(v -> ind.getId().equals(v.getIndicatorId()))
                    .collect(Collectors.toList());
        }
        long total = all.size();
        int from = (page - 1) * size;
        int to = Math.min(from + size, all.size());
        List<IndicatorVersion> pageContent = from < to ? all.subList(from, to) : List.of();
        PageResponse<IndicatorVersion> resp = PageResponse.of(pageContent, page, size, total);
        return ApiResponse.success(resp);
    }

    // ============================================================
    // 6. Thresholds for indicator
    // ============================================================

    /**
     * 取指标的阈值规则 (GREEN/YELLOW/RED 三色预警).
     *
     * @param factoryId 工厂 ID
     * @param code      指标编码
     * @return 阈值列表
     */
    @GetMapping("/thresholds/{code}")
    @RequirePermission({"analytics:read", "analytics:read_write"})
    @Operation(summary = "取指标阈值规则", description = "GREEN/YELLOW/RED 三色预警")
    public ApiResponse<List<ThresholdResponse>> getThresholds(
            @Parameter(description = "工厂 ID", required = true, example = "F001")
            @PathVariable @NotBlank String factoryId,
            @Parameter(description = "指标编码", required = true, example = "FACTORY_YIELD_RATE")
            @PathVariable @NotBlank String code) {

        log.debug("getThresholds: factoryId={}, code={}", factoryId, code);
        Indicator ind = indicatorRepository
                .findByCodeAndFactoryIdAndDeletedAtIsNull(code, factoryId)
                .orElseThrow(() -> indicatorNotFound(code, factoryId));
        List<IndicatorThreshold> thresholds = indicatorThresholdRepository
                .findActiveByIndicatorIdAndFactoryId(ind.getId(), factoryId);
        List<ThresholdResponse> result = thresholds.stream()
                .map(ThresholdResponse::fromEntity)
                .collect(Collectors.toList());
        return ApiResponse.success(result);
    }

    // ============================================================
    // 7. Force recompute (admin only)
    // ============================================================

    /**
     * 强制重算指标值 — 绕过 CACHED 策略的 TTL 直接调 Python.
     *
     * <p>权限: 需要 {@code indicator:write} authority 或 {@code factory_super_admin} role.
     *
     * <p>实现细节: 临时把 indicator.lastComputedAt 置 null 以强制 cache miss, 然后调
     * service.computeForCode; service 内部会写回 lastValue + lastComputedAt + 落 version.
     *
     * @param factoryId   工厂 ID
     * @param code        指标编码
     * @param periodStart 业务时间窗起 (可选, 默认本月 1 号)
     * @param periodEnd   业务时间窗止 (可选, 默认今天)
     * @return 重算后的取值响应
     */
    @PostMapping("/{code}/recompute")
    @RequirePermission({"analytics:read_write"})
    @Operation(summary = "强制重算指标值",
            description = "绕过 CACHED TTL 直接调 Python; 需要 indicator:write 或 factory_super_admin")
    public ApiResponse<IndicatorValueResponse> recompute(
            @Parameter(description = "工厂 ID", required = true, example = "F001")
            @PathVariable @NotBlank String factoryId,
            @Parameter(description = "指标编码", required = true, example = "FACTORY_YIELD_RATE")
            @PathVariable @NotBlank String code,
            @Parameter(description = "时间窗起 (yyyy-MM-dd)")
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
            LocalDate periodStart,
            @Parameter(description = "时间窗止 (yyyy-MM-dd)")
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
            LocalDate periodEnd) {

        LocalDate start = periodStart != null ? periodStart : LocalDate.now().withDayOfMonth(1);
        LocalDate end = periodEnd != null ? periodEnd : LocalDate.now();
        log.info("recompute: factoryId={}, code={}, start={}, end={}", factoryId, code, start, end);

        Indicator ind = indicatorRepository
                .findByCodeAndFactoryIdAndDeletedAtIsNull(code, factoryId)
                .orElseThrow(() -> indicatorNotFound(code, factoryId));

        // Force cache miss by clearing lastComputedAt; for PRECOMPUTED also clear lastValue
        // so the service falls through to Python fetch (PRECOMPUTED strategy normally returns
        // lastValue directly — but for force-recompute we expect the indicator to flip to
        // python source for this single call).
        ind.setLastComputedAt(null);
        if ("PRECOMPUTED".equals(ind.getComputeStrategy())) {
            ind.setLastValue(null);
        }
        indicatorRepository.save(ind);

        IndicatorValueResult result = indicatorQueryService.computeForCode(code, factoryId, start, end);
        IndicatorValueResponse resp = IndicatorValueResponse.fromWithIndicator(
                ind, result, start, end);
        return ApiResponse.success("指标重算成功", resp);
    }

    // ============================================================
    // Reserved helpers (kept for future Phase 2 expansion)
    // ============================================================

    /**
     * 内部 helper: 把分页参数包装成 {@code Map<String,Object>} (FE expects this in some endpoints).
     * Day 5 暂未使用; 留作 Phase 2 reference.
     */
    @SuppressWarnings("unused")
    private Map<String, Object> wrapPage(List<?> content, long total, int page, int size) {
        Map<String, Object> m = new HashMap<>();
        m.put("content", content);
        m.put("totalElements", total);
        m.put("totalPages", (int) Math.ceil((double) total / size));
        m.put("page", page);
        m.put("size", size);
        return m;
    }
}
