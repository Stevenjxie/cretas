package com.cretas.aims.controller;

import com.cretas.aims.config.RequireRole;
import com.cretas.aims.dto.common.ApiResponse;
import com.cretas.aims.entity.indicator.Indicator;
import com.cretas.aims.entity.indicator.IndicatorComputation;
import com.cretas.aims.entity.indicator.IndicatorThreshold;
import com.cretas.aims.entity.indicator.IndicatorTreeNode;
import com.cretas.aims.entity.indicator.IndicatorVersion;
import com.cretas.aims.entity.lineage.BatchLineageClosure;
import com.cretas.aims.entity.lineage.BatchLineageEdge;
import com.cretas.aims.repository.indicator.IndicatorComputationRepository;
import com.cretas.aims.repository.indicator.IndicatorRepository;
import com.cretas.aims.repository.indicator.IndicatorThresholdRepository;
import com.cretas.aims.repository.indicator.IndicatorTreeNodeRepository;
import com.cretas.aims.repository.indicator.IndicatorVersionRepository;
import com.cretas.aims.repository.lineage.BatchLineageClosureRepository;
import com.cretas.aims.repository.lineage.BatchLineageEdgeRepository;
import com.cretas.aims.service.workflow.SandboxedSpelEvaluator;
import com.cretas.aims.service.workflow.SandboxedSpelEvaluator.SpelEvaluationFailure;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Canvas 指标中心 REST 接口 — Phase A subagent #3.
 *
 * <p>包装 Phase 1 已 ship 的 7 个 indicator + 2 个 lineage 实体，为 Vue Canvas Tab
 * 提供 8 个子功能聚合：
 * <ol>
 *   <li>指标定义列表 (factory-scoped + category 筛选)</li>
 *   <li>指标编辑 (create / update / soft delete via is_active)</li>
 *   <li>版本历史 + 当前值</li>
 *   <li>公式 dry-run (SpEL sandbox)</li>
 *   <li>分类汇总</li>
 *   <li>指标依赖图 (tree nodes → DAG)</li>
 *   <li>批次溯源 (lineage closure)</li>
 *   <li>实测值时间序列</li>
 * </ol>
 *
 * <p><b>Path</b>: {@code /api/mobile/{factoryId}/canvas-indicators} — 与 Phase 1 Day 5
 * 计划落地的 {@code /api/mobile/{factoryId}/indicators} 路径有意区分；本 controller
 * 服务 Canvas UI 聚合需求，Day 5 controller 服务通用查询。
 *
 * <p><b>RBAC</b>: 查询 factory_super_admin / permission_admin / operator，写入仅前两者。
 *
 * @since 2026-05-21 (Canvas Phase A)
 */
@Slf4j
@RestController
@RequestMapping("/api/mobile/{factoryId}/canvas-indicators")
@Tag(name = "Canvas-指标中心", description = "Phase A — 指标定义 + 公式编辑 + 批次溯源")
@RequiredArgsConstructor
public class CanvasIndicatorsController {

    private final IndicatorRepository indicatorRepository;
    private final IndicatorVersionRepository versionRepository;
    private final IndicatorThresholdRepository thresholdRepository;
    private final IndicatorComputationRepository computationRepository;
    private final IndicatorTreeNodeRepository treeNodeRepository;
    private final BatchLineageEdgeRepository edgeRepository;
    private final BatchLineageClosureRepository closureRepository;
    private final SandboxedSpelEvaluator spelEvaluator;

    /** SpEL formula max length (mirrors SandboxedSpelEvaluator.MAX_SPEL_LENGTH). */
    private static final int FORMULA_MAX_LENGTH = 2000;

    /** Indicator name VARCHAR(200). */
    private static final int NAME_MAX_LENGTH = 200;

    /** Allowed category values (uppercase) — write-time validation. */
    private static final List<String> ALLOWED_CATEGORIES =
            List.of("FACTORY", "RESTAURANT", "QUALITY", "FINANCE",
                    "INVENTORY", "SALES", "FOOD_SAFETY", "AI");

    private static final List<String> ALLOWED_STRATEGIES =
            List.of("REALTIME", "CACHED", "PRECOMPUTED");

    // ============================================================
    // 1. 指标列表 (factory-scoped, 可按 category 筛选)
    // ============================================================

    @GetMapping
    @Operation(summary = "列出指标", description = "工厂内全部启用指标 (可按 category 筛选)")
    @RequireRole({"factory_super_admin", "permission_admin", "operator"})
    public ApiResponse<List<Map<String, Object>>> listIndicators(
            @PathVariable String factoryId,
            @RequestParam(required = false) String category) {
        log.debug("GET /canvas-indicators factoryId={} category={}", factoryId, category);
        List<Indicator> rows = (category == null || category.isBlank())
                ? indicatorRepository.findByFactoryIdAndIsActiveTrueOrderByDisplayOrderAscNameAsc(factoryId)
                : indicatorRepository.findByFactoryIdAndCategoryAndIsActiveTrueOrderByDisplayOrderAscNameAsc(
                        factoryId, category.toUpperCase());

        List<Map<String, Object>> data = new ArrayList<>(rows.size());
        for (Indicator i : rows) {
            data.add(serializeIndicator(i));
        }
        return ApiResponse.success("操作成功", data);
    }

    @GetMapping("/categories")
    @Operation(summary = "列出分类", description = "工厂内全部活跃指标的分类 (DISTINCT)")
    @RequireRole({"factory_super_admin", "permission_admin", "operator"})
    public ApiResponse<List<String>> listCategories(@PathVariable String factoryId) {
        return ApiResponse.success("操作成功", indicatorRepository.findDistinctCategories(factoryId));
    }

    @GetMapping("/{id}")
    @Operation(summary = "指标详情", description = "含 thresholds + computations + 最新 value")
    @RequireRole({"factory_super_admin", "permission_admin", "operator"})
    public ApiResponse<Map<String, Object>> getIndicator(
            @PathVariable String factoryId, @PathVariable String id) {
        Optional<Indicator> opt = indicatorRepository.findById(id);
        if (opt.isEmpty() || !factoryId.equals(opt.get().getFactoryId())) {
            return ApiResponse.errorWithHint(404, "指标不存在",
                    "请确认 id 与工厂归属", null, null);
        }
        Indicator i = opt.get();
        Map<String, Object> data = serializeIndicator(i);

        // Thresholds
        List<Map<String, Object>> thresholds = new ArrayList<>();
        for (IndicatorThreshold t : thresholdRepository.findByIndicatorIdAndIsActiveTrue(id)) {
            thresholds.add(serializeThreshold(t));
        }
        data.put("thresholds", thresholds);

        // Computations
        List<Map<String, Object>> comps = new ArrayList<>();
        for (IndicatorComputation c :
                computationRepository.findByIndicatorIdAndIsActiveTrueOrderByPriorityAsc(id)) {
            comps.add(serializeComputation(c));
        }
        data.put("computations", comps);

        // Latest version snapshot (if any)
        versionRepository.findFirstByIndicatorIdOrderByComputedAtDesc(id)
                .ifPresent(v -> data.put("latestVersion", serializeVersion(v)));

        return ApiResponse.success("操作成功", data);
    }

    // ============================================================
    // 2. 指标 CRUD (factory_super_admin / permission_admin)
    // ============================================================

    @PostMapping
    @Operation(summary = "创建指标")
    @RequireRole({"factory_super_admin", "permission_admin"})
    public ApiResponse<Map<String, Object>> createIndicator(
            @PathVariable String factoryId, @RequestBody Map<String, Object> body) {
        log.info("POST /canvas-indicators factoryId={} body keys={}", factoryId, body.keySet());

        String code = stringField(body, "code");
        String name = stringField(body, "name");
        String category = stringField(body, "category");

        if (code == null || code.isBlank()) {
            return ApiResponse.errorWithHint(400, "code 必填",
                    "请提供指标编码 (如 FACTORY_YIELD_RATE)", null, null);
        }
        if (name == null || name.isBlank()) {
            return ApiResponse.errorWithHint(400, "name 必填",
                    "请提供指标名称 (中文)", null, null);
        }
        if (name.length() > NAME_MAX_LENGTH) {
            return ApiResponse.errorWithCode(400, "VALIDATION",
                    "指标名称最长 " + NAME_MAX_LENGTH + " 字符",
                    "请缩短名称", "warning");
        }
        if (category == null || !ALLOWED_CATEGORIES.contains(category.toUpperCase())) {
            return ApiResponse.errorWithHint(400,
                    "category 不合法",
                    "允许值: " + String.join(" / ", ALLOWED_CATEGORIES),
                    null, null);
        }
        if (indicatorRepository.findByFactoryIdAndCode(factoryId, code).isPresent()) {
            return ApiResponse.errorWithHint(409,
                    "指标 code 已存在: " + code,
                    "请使用不同的 code 或编辑现有指标", null, null);
        }

        Indicator i = new Indicator();
        i.setId(UUID.randomUUID().toString());
        i.setFactoryId(factoryId);
        i.setCode(code);
        i.setName(name);
        i.setCategory(category.toUpperCase());
        i.setDescription(stringField(body, "description"));
        i.setUnit(stringField(body, "unit"));
        i.setIsActive(true);
        String strategy = stringField(body, "computeStrategy");
        i.setComputeStrategy(strategy != null && ALLOWED_STRATEGIES.contains(strategy.toUpperCase())
                ? strategy.toUpperCase() : "CACHED");
        Object ttl = body.get("cacheTtlSeconds");
        if (ttl instanceof Number n) {
            i.setCacheTtlSeconds(n.intValue());
        } else {
            i.setCacheTtlSeconds(3600);
        }
        Object order = body.get("displayOrder");
        if (order instanceof Number n) {
            i.setDisplayOrder(n.intValue());
        }
        Object config = body.get("config");
        if (config instanceof Map<?, ?> m) {
            //noinspection unchecked
            i.setConfig((Map<String, Object>) m);
        } else {
            i.setConfig(new HashMap<>());
        }

        Indicator saved = indicatorRepository.saveAndFlush(i);
        log.info("POST /canvas-indicators created id={} code={}", saved.getId(), saved.getCode());
        return ApiResponse.success("创建成功", serializeIndicator(saved));
    }

    @PutMapping("/{id}")
    @Operation(summary = "更新指标 (PATCH semantics)")
    @RequireRole({"factory_super_admin", "permission_admin"})
    public ApiResponse<Map<String, Object>> updateIndicator(
            @PathVariable String factoryId, @PathVariable String id,
            @RequestBody Map<String, Object> body) {
        Optional<Indicator> opt = indicatorRepository.findById(id);
        if (opt.isEmpty() || !factoryId.equals(opt.get().getFactoryId())) {
            return ApiResponse.errorWithHint(404, "指标不存在",
                    "请确认 id 与工厂归属", null, null);
        }
        Indicator i = opt.get();

        if (body.containsKey("name")) {
            String name = stringField(body, "name");
            if (name == null || name.isBlank() || name.length() > NAME_MAX_LENGTH) {
                return ApiResponse.errorWithHint(400, "name 非法",
                        "name 不能为空, 最长 " + NAME_MAX_LENGTH, null, null);
            }
            i.setName(name);
        }
        if (body.containsKey("description")) {
            i.setDescription(stringField(body, "description"));
        }
        if (body.containsKey("category")) {
            String cat = stringField(body, "category");
            if (cat == null || !ALLOWED_CATEGORIES.contains(cat.toUpperCase())) {
                return ApiResponse.errorWithHint(400, "category 非法",
                        "允许值: " + String.join(" / ", ALLOWED_CATEGORIES), null, null);
            }
            i.setCategory(cat.toUpperCase());
        }
        if (body.containsKey("unit")) {
            i.setUnit(stringField(body, "unit"));
        }
        if (body.containsKey("computeStrategy")) {
            String s = stringField(body, "computeStrategy");
            if (s != null && ALLOWED_STRATEGIES.contains(s.toUpperCase())) {
                i.setComputeStrategy(s.toUpperCase());
            }
        }
        if (body.containsKey("cacheTtlSeconds") && body.get("cacheTtlSeconds") instanceof Number n) {
            i.setCacheTtlSeconds(n.intValue());
        }
        if (body.containsKey("displayOrder") && body.get("displayOrder") instanceof Number n) {
            i.setDisplayOrder(n.intValue());
        }
        if (body.containsKey("isActive") && body.get("isActive") instanceof Boolean b) {
            i.setIsActive(b);
        }
        if (body.containsKey("config") && body.get("config") instanceof Map<?, ?> m) {
            //noinspection unchecked
            i.setConfig((Map<String, Object>) m);
        }

        Indicator saved = indicatorRepository.saveAndFlush(i);
        return ApiResponse.success("更新成功", serializeIndicator(saved));
    }

    @DeleteMapping("/{id}")
    @Operation(summary = "停用指标 (软删除 — is_active=false)")
    @RequireRole({"factory_super_admin", "permission_admin"})
    public ApiResponse<Map<String, Object>> deactivateIndicator(
            @PathVariable String factoryId, @PathVariable String id) {
        Optional<Indicator> opt = indicatorRepository.findById(id);
        if (opt.isEmpty() || !factoryId.equals(opt.get().getFactoryId())) {
            return ApiResponse.errorWithHint(404, "指标不存在", "请确认 id 与工厂归属", null, null);
        }
        Indicator i = opt.get();
        i.setIsActive(false);
        indicatorRepository.saveAndFlush(i);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("id", id);
        data.put("isActive", false);
        return ApiResponse.success("已停用", data);
    }

    // ============================================================
    // 3. 版本历史 + 趋势
    // ============================================================

    @GetMapping("/{id}/versions")
    @Operation(summary = "版本历史 (分页)")
    @RequireRole({"factory_super_admin", "permission_admin", "operator"})
    public ApiResponse<Map<String, Object>> listVersions(
            @PathVariable String factoryId, @PathVariable String id,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        Optional<Indicator> opt = indicatorRepository.findById(id);
        if (opt.isEmpty() || !factoryId.equals(opt.get().getFactoryId())) {
            return ApiResponse.errorWithHint(404, "指标不存在", "请确认 id 与工厂归属", null, null);
        }
        Pageable pageable = PageRequest.of(Math.max(0, page), Math.min(Math.max(1, size), 100));
        Page<IndicatorVersion> p = versionRepository.findByIndicatorIdOrderByComputedAtDesc(id, pageable);

        List<Map<String, Object>> rows = new ArrayList<>(p.getContent().size());
        for (IndicatorVersion v : p.getContent()) {
            rows.add(serializeVersion(v));
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("content", rows);
        data.put("totalElements", p.getTotalElements());
        data.put("totalPages", p.getTotalPages());
        data.put("number", p.getNumber());
        data.put("size", p.getSize());
        return ApiResponse.success("操作成功", data);
    }

    @GetMapping("/{id}/measurements")
    @Operation(summary = "趋势数据 (时间窗内全部 snapshot)")
    @RequireRole({"factory_super_admin", "permission_admin", "operator"})
    public ApiResponse<List<Map<String, Object>>> listMeasurements(
            @PathVariable String factoryId, @PathVariable String id,
            @RequestParam(required = false) String from,
            @RequestParam(required = false) String to) {
        Optional<Indicator> opt = indicatorRepository.findById(id);
        if (opt.isEmpty() || !factoryId.equals(opt.get().getFactoryId())) {
            return ApiResponse.errorWithHint(404, "指标不存在", "请确认 id 与工厂归属", null, null);
        }
        LocalDateTime fromTs;
        LocalDateTime toTs;
        try {
            fromTs = (from == null || from.isBlank())
                    ? LocalDateTime.now().minusDays(30)
                    : LocalDateTime.parse(from);
            toTs = (to == null || to.isBlank())
                    ? LocalDateTime.now()
                    : LocalDateTime.parse(to);
        } catch (Exception ex) {
            return ApiResponse.errorWithHint(400, "时间格式不合法",
                    "请使用 ISO-8601 格式 (例 2026-05-21T00:00:00)", null, null);
        }
        List<IndicatorVersion> rows = versionRepository.findInWindow(id, fromTs, toTs);
        List<Map<String, Object>> data = new ArrayList<>(rows.size());
        for (IndicatorVersion v : rows) {
            data.add(serializeVersion(v));
        }
        return ApiResponse.success("操作成功", data);
    }

    // ============================================================
    // 4. 公式 dry-run (SpEL sandbox)
    // ============================================================

    @PostMapping("/{id}/test-evaluate")
    @Operation(summary = "公式 dry-run", description = "用样本数据计算公式结果, 不落库")
    @RequireRole({"factory_super_admin", "permission_admin"})
    public ApiResponse<Map<String, Object>> testEvaluate(
            @PathVariable String factoryId, @PathVariable String id,
            @RequestBody Map<String, Object> body) {
        Optional<Indicator> opt = indicatorRepository.findById(id);
        if (opt.isEmpty() || !factoryId.equals(opt.get().getFactoryId())) {
            return ApiResponse.errorWithHint(404, "指标不存在", "请确认 id 与工厂归属", null, null);
        }
        String formula = stringField(body, "formula");
        if (formula == null || formula.isBlank()) {
            return ApiResponse.errorWithHint(400, "formula 必填",
                    "请提供 SpEL 公式 (例 #yield / #total * 100)", null, null);
        }
        if (formula.length() > FORMULA_MAX_LENGTH) {
            return ApiResponse.errorWithCode(400, "VALIDATION",
                    "公式过长 (最长 " + FORMULA_MAX_LENGTH + " 字符)",
                    "请简化公式", "warning");
        }
        Object varsObj = body.get("variables");
        Map<String, Object> variables = new HashMap<>();
        if (varsObj instanceof Map<?, ?> m) {
            for (Map.Entry<?, ?> e : m.entrySet()) {
                if (e.getKey() != null) {
                    variables.put(e.getKey().toString(), e.getValue());
                }
            }
        }

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("formula", formula);
        data.put("variables", variables);
        try {
            Object result = spelEvaluator.evaluate(formula, variables);
            data.put("status", "OK");
            data.put("result", result);
            data.put("resultType", result == null ? "null" : result.getClass().getSimpleName());
            return ApiResponse.success("公式求值成功", data);
        } catch (SpelEvaluationFailure ex) {
            data.put("status", "FAILED");
            data.put("error", ex.getMessage());
            return ApiResponse.errorWithHint(400, "公式求值失败",
                    ex.getMessage(), null, null);
        } catch (Exception ex) {
            log.warn("test-evaluate unexpected error indicatorId={}", id, ex);
            data.put("status", "FAILED");
            data.put("error", ex.getMessage());
            return ApiResponse.errorWithHint(500, "公式求值异常",
                    "请检查公式与样本数据", null, null);
        }
    }

    // ============================================================
    // 5. 重算 (写入新 IndicatorVersion 行 — append-only)
    // ============================================================

    @PostMapping("/{id}/recompute")
    @Operation(summary = "强制重算并落 snapshot",
            description = "立即基于当前 computation 策略重算并 append 一行版本 (本接口先返回 stub 0 — Day 4 wire 后切实数据源)")
    @RequireRole({"factory_super_admin", "permission_admin"})
    public ApiResponse<Map<String, Object>> recompute(
            @PathVariable String factoryId, @PathVariable String id) {
        Optional<Indicator> opt = indicatorRepository.findById(id);
        if (opt.isEmpty() || !factoryId.equals(opt.get().getFactoryId())) {
            return ApiResponse.errorWithHint(404, "指标不存在", "请确认 id 与工厂归属", null, null);
        }
        Indicator i = opt.get();
        // 注意: 这里 stub —— Day 4 IndicatorQueryService 落地后此处委托给 Service。
        // 当前: 写入 0 占位, 让 UI 流程可走通。
        BigDecimal value = i.getLastValue() != null ? i.getLastValue() : BigDecimal.ZERO;
        IndicatorVersion v = new IndicatorVersion();
        v.setId(UUID.randomUUID().toString());
        v.setFactoryId(factoryId);
        v.setIndicatorId(id);
        v.setPeriodStart(java.time.LocalDate.now());
        v.setPeriodEnd(java.time.LocalDate.now());
        v.setValue(value);
        v.setComputedAt(LocalDateTime.now());
        v.setComputeSource("CANVAS_MANUAL_RECOMPUTE_STUB");
        versionRepository.saveAndFlush(v);

        i.setLastValue(value);
        i.setLastComputedAt(v.getComputedAt());
        indicatorRepository.saveAndFlush(i);

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("id", id);
        data.put("value", value);
        data.put("computedAt", v.getComputedAt());
        data.put("note", "stub — Day 4 后切实计算引擎");
        return ApiResponse.success("重算完成", data);
    }

    // ============================================================
    // 6. 指标依赖图 (tree nodes → DAG)
    // ============================================================

    @GetMapping("/{id}/lineage")
    @Operation(summary = "指标依赖图", description = "返回该指标的 forward + backward dependencies")
    @RequireRole({"factory_super_admin", "permission_admin", "operator"})
    public ApiResponse<Map<String, Object>> indicatorLineage(
            @PathVariable String factoryId, @PathVariable String id) {
        Optional<Indicator> opt = indicatorRepository.findById(id);
        if (opt.isEmpty() || !factoryId.equals(opt.get().getFactoryId())) {
            return ApiResponse.errorWithHint(404, "指标不存在", "请确认 id 与工厂归属", null, null);
        }

        List<IndicatorTreeNode> myNodes = treeNodeRepository.findByIndicatorId(id);
        List<Map<String, Object>> ancestors = new ArrayList<>();
        List<Map<String, Object>> descendants = new ArrayList<>();

        // backward: my parents (every node referencing this indicator that has parent_id)
        for (IndicatorTreeNode n : myNodes) {
            if (n.getParentId() != null) {
                indicatorRepository.findById(n.getParentId()).ifPresent(p -> {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("id", p.getId());
                    row.put("code", p.getCode());
                    row.put("name", p.getName());
                    row.put("category", p.getCategory());
                    row.put("weight", n.getWeight());
                    row.put("depth", n.getDepth());
                    ancestors.add(row);
                });
            }
        }
        // forward: my children
        for (IndicatorTreeNode n : myNodes) {
            for (IndicatorTreeNode child : treeNodeRepository.findByParentId(n.getIndicatorId())) {
                indicatorRepository.findById(child.getIndicatorId()).ifPresent(c -> {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("id", c.getId());
                    row.put("code", c.getCode());
                    row.put("name", c.getName());
                    row.put("category", c.getCategory());
                    row.put("weight", child.getWeight());
                    row.put("depth", child.getDepth());
                    descendants.add(row);
                });
            }
        }

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("indicatorId", id);
        data.put("ancestors", ancestors);
        data.put("descendants", descendants);
        return ApiResponse.success("操作成功", data);
    }

    @GetMapping("/tree")
    @Operation(summary = "指标树 (DAG 全量)", description = "返回工厂全部节点 + 边, 供 vue-flow 渲染")
    @RequireRole({"factory_super_admin", "permission_admin", "operator"})
    public ApiResponse<Map<String, Object>> indicatorTree(@PathVariable String factoryId) {
        List<Indicator> indicators =
                indicatorRepository.findByFactoryIdAndIsActiveTrueOrderByDisplayOrderAscNameAsc(factoryId);
        List<IndicatorTreeNode> nodes = treeNodeRepository.findByFactoryId(factoryId);

        // Build vue-flow style nodes + edges
        List<Map<String, Object>> flowNodes = new ArrayList<>(indicators.size());
        Map<String, Integer> categoryCol = new HashMap<>();
        for (Indicator i : indicators) {
            int col = categoryCol.getOrDefault(i.getCategory(), 0);
            categoryCol.put(i.getCategory(), col + 1);
            int x = 50 + col * 220;
            int y = 50 + indexOfCategory(i.getCategory()) * 150;
            Map<String, Object> n = new LinkedHashMap<>();
            n.put("id", i.getId());
            n.put("label", i.getName());
            n.put("code", i.getCode());
            n.put("category", i.getCategory());
            n.put("unit", i.getUnit());
            n.put("lastValue", i.getLastValue());
            n.put("x", x);
            n.put("y", y);
            flowNodes.add(n);
        }

        List<Map<String, Object>> flowEdges = new ArrayList<>();
        for (IndicatorTreeNode tn : nodes) {
            if (tn.getParentId() == null) continue;
            Map<String, Object> e = new LinkedHashMap<>();
            e.put("id", "e-" + tn.getId());
            e.put("source", tn.getParentId());
            e.put("target", tn.getIndicatorId());
            e.put("weight", tn.getWeight());
            flowEdges.add(e);
        }

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("nodes", flowNodes);
        data.put("edges", flowEdges);
        return ApiResponse.success("操作成功", data);
    }

    // ============================================================
    // 7. 批次溯源 (lineage closure)
    // ============================================================

    @GetMapping("/batch-lineage/{batchType}/{batchId}")
    @Operation(summary = "批次溯源", description = "返回某批次的全部祖先 + 后代 (depth ≤ maxDepth)")
    @RequireRole({"factory_super_admin", "permission_admin", "operator"})
    public ApiResponse<Map<String, Object>> batchLineage(
            @PathVariable String factoryId,
            @PathVariable String batchType,
            @PathVariable String batchId,
            @RequestParam(defaultValue = "5") int maxDepth) {
        int cap = Math.min(Math.max(1, maxDepth), 10);
        List<BatchLineageClosure> graph =
                closureRepository.findFullGraph(factoryId, batchId, batchType.toUpperCase(), cap);

        List<Map<String, Object>> ancestors = new ArrayList<>();
        List<Map<String, Object>> descendants = new ArrayList<>();
        for (BatchLineageClosure c : graph) {
            if (c.getDepth() == 0) continue; // self-ref
            boolean isAncestor = c.getDescendantId().equals(batchId)
                    && c.getDescendantType().equals(batchType.toUpperCase());
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("ancestorId", c.getAncestorId());
            row.put("ancestorType", c.getAncestorType());
            row.put("descendantId", c.getDescendantId());
            row.put("descendantType", c.getDescendantType());
            row.put("depth", c.getDepth());
            if (isAncestor) ancestors.add(row); else descendants.add(row);
        }

        // Pull related edges (for unit + quantity + meta)
        List<BatchLineageEdge> upstream =
                edgeRepository.findByFactoryIdAndTargetIdAndTargetType(factoryId, batchId, batchType.toUpperCase());
        List<BatchLineageEdge> downstream =
                edgeRepository.findByFactoryIdAndSourceIdAndSourceType(factoryId, batchId, batchType.toUpperCase());

        List<Map<String, Object>> edges = new ArrayList<>(upstream.size() + downstream.size());
        for (BatchLineageEdge e : upstream) {
            edges.add(serializeEdge(e));
        }
        for (BatchLineageEdge e : downstream) {
            edges.add(serializeEdge(e));
        }

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("batchId", batchId);
        data.put("batchType", batchType.toUpperCase());
        data.put("ancestors", ancestors);
        data.put("descendants", descendants);
        data.put("edges", edges);
        data.put("maxDepth", cap);
        return ApiResponse.success("操作成功", data);
    }

    // ============================================================
    // Serialization helpers
    // ============================================================

    private Map<String, Object> serializeIndicator(Indicator i) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", i.getId());
        m.put("factoryId", i.getFactoryId());
        m.put("code", i.getCode());
        m.put("name", i.getName());
        m.put("description", i.getDescription());
        m.put("category", i.getCategory());
        m.put("unit", i.getUnit());
        m.put("isActive", i.getIsActive());
        m.put("computeStrategy", i.getComputeStrategy());
        m.put("cacheTtlSeconds", i.getCacheTtlSeconds());
        m.put("lastValue", i.getLastValue());
        m.put("lastComputedAt", i.getLastComputedAt());
        m.put("displayOrder", i.getDisplayOrder());
        m.put("config", i.getConfig());
        m.put("createdAt", i.getCreatedAt());
        m.put("updatedAt", i.getUpdatedAt());
        return m;
    }

    private Map<String, Object> serializeThreshold(IndicatorThreshold t) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", t.getId());
        m.put("indicatorId", t.getIndicatorId());
        m.put("alertLevel", t.getAlertLevel());
        m.put("operator", t.getOperator());
        m.put("thresholdValue", t.getThresholdValue());
        m.put("thresholdValueUpper", t.getThresholdValueUpper());
        m.put("isActive", t.getIsActive());
        return m;
    }

    private Map<String, Object> serializeComputation(IndicatorComputation c) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", c.getId());
        m.put("indicatorId", c.getIndicatorId());
        m.put("computeType", c.getComputeType());
        m.put("computeSource", c.getComputeSource());
        m.put("params", c.getParams());
        m.put("priority", c.getPriority());
        m.put("isActive", c.getIsActive());
        return m;
    }

    private Map<String, Object> serializeVersion(IndicatorVersion v) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", v.getId());
        m.put("indicatorId", v.getIndicatorId());
        m.put("periodStart", v.getPeriodStart());
        m.put("periodEnd", v.getPeriodEnd());
        m.put("value", v.getValue());
        m.put("computedAt", v.getComputedAt());
        m.put("alertLevel", v.getAlertLevel());
        m.put("computeSource", v.getComputeSource());
        return m;
    }

    private Map<String, Object> serializeEdge(BatchLineageEdge e) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", e.getId());
        m.put("edgeType", e.getEdgeType());
        m.put("sourceType", e.getSourceType());
        m.put("sourceId", e.getSourceId());
        m.put("targetType", e.getTargetType());
        m.put("targetId", e.getTargetId());
        m.put("quantityUsed", e.getQuantityUsed());
        m.put("unit", e.getUnit());
        m.put("eventTime", e.getEventTime());
        m.put("meta", e.getMeta());
        return m;
    }

    private static String stringField(Map<String, Object> body, String key) {
        Object v = body.get(key);
        if (v == null) return null;
        return Objects.toString(v).trim();
    }

    private static int indexOfCategory(String category) {
        // Deterministic Y layout per category for vue-flow nodes
        return switch (category == null ? "" : category) {
            case "FACTORY" -> 0;
            case "RESTAURANT" -> 1;
            case "QUALITY" -> 2;
            case "FINANCE" -> 3;
            case "INVENTORY" -> 4;
            case "SALES" -> 5;
            case "FOOD_SAFETY" -> 6;
            case "AI" -> 7;
            default -> 8;
        };
    }
}
