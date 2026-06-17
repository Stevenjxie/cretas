package com.cretas.aims.controller;

import com.cretas.aims.dto.common.ApiResponse;
import com.cretas.aims.annotation.RequirePermission;
import com.cretas.aims.dto.smartbi.ConfigOperationResult;
import com.cretas.aims.dto.smartbi.CreateAlertThresholdRequest;
import com.cretas.aims.dto.smartbi.CreateIncentiveRuleRequest;
import com.cretas.aims.dto.smartbi.DataSourceDTO;
import com.cretas.aims.dto.smartbi.UpdateIncentiveRuleRequest;
import com.cretas.aims.entity.smartbi.*;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.repository.smartbi.AiIntentConfigRepository;
import com.cretas.aims.repository.smartbi.SmartBiAlertThresholdRepository;
import com.cretas.aims.repository.smartbi.SmartBiChartTemplateRepository;
import com.cretas.aims.repository.smartbi.SmartBiDictionaryRepository;
import com.cretas.aims.repository.smartbi.SmartBiIncentiveRuleRepository;
import com.cretas.aims.repository.smartbi.SmartBiMetricFormulaRepository;
import com.cretas.aims.service.smartbi.DataSourceRegistryService;
import com.cretas.aims.service.smartbi.SmartBIConfigService;
import org.springframework.data.domain.Page;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import com.cretas.aims.util.ErrorSanitizer;

/**
 * SmartBI 配置管理控制器
 *
 * <p>提供统一的 REST API 管理 SmartBI 所有动态配置：
 * <ul>
 *   <li>意图配置（CRUD + reload）</li>
 *   <li>告警阈值（CRUD + reload）</li>
 *   <li>激励规则（CRUD + reload）</li>
 *   <li>字段映射（CRUD + reload）</li>
 *   <li>指标公式（CRUD + reload）</li>
 *   <li>图表模板（CRUD + reload + 推荐）</li>
 *   <li>全局配置重载和状态查询</li>
 * </ul>
 *
 * <p>安全说明：此 API 仅限管理员访问，请在 SecurityConfig 中配置权限控制。
 *
 * @author Cretas Team
 * @version 1.0.0
 * @since 2026-01-21
 */
@Slf4j
@RestController
@RequestMapping("/api/mobile/smartbi-config")
@RequiredArgsConstructor
@Tag(name = "SmartBI 配置管理", description = "SmartBI 动态配置管理 API（管理员专用）")
public class SmartBIConfigController {

    private final SmartBIConfigService configService;
    private final DataSourceRegistryService dataSourceService;

    // 🔒 多租户隔离守卫用 — 按 id load entity 校验 factoryId (read-only)。
    private final AiIntentConfigRepository intentConfigRepository;
    private final SmartBiAlertThresholdRepository alertThresholdRepository;
    private final SmartBiIncentiveRuleRepository incentiveRuleRepository;
    private final SmartBiDictionaryRepository dictionaryRepository;
    private final SmartBiMetricFormulaRepository metricFormulaRepository;
    private final SmartBiChartTemplateRepository chartTemplateRepository;

    // ==================== 🔒 多租户隔离守卫 (2026-06-17) ====================
    // 镜像 PR #982 (ScheduledTaskController) 修法。smartbi-config 路径被
    // JwtAuthInterceptor 的 path-factoryId gate 排除 (path 第一段是 smartbi-config 不是
    // factoryId), list 端点不按工厂过滤 → 读泄露 + id 泄露; by-id 写端点 findById(id)
    // 不校验 existing.factoryId == caller 工厂 → 跨租户篡改 (AI 意图路由 / 告警阈值 /
    // 激励规则=提成薪酬 / 字段映射 / 指标公式 / 图表模板)。
    //
    // 配置实体的 factoryId 语义:
    //   - 某工厂id  = per-factory 覆盖配置
    //   - null      = global 共享默认配置 (平台专属)
    // 策略:
    //   - 平台角色 (platform_admin/super_admin): 跨工厂 + 可改 global, 全放行 (by design)。
    //   - 非平台 (工厂级): list 只返回 本厂 或 global(null); by-id 写只允许 本厂
    //     (global → 平台专属 403, 别家 → 403); create 阻止 body.factoryId 指向别家。

    private String callerFactoryId(HttpServletRequest request) {
        return (String) request.getAttribute("factoryId");
    }

    private boolean isPlatformRole(HttpServletRequest request) {
        String role = (String) request.getAttribute("role");
        return "platform_admin".equals(role) || "super_admin".equals(role);
    }

    /**
     * 🔒 list 过滤: 非平台角色只保留 本厂(caller工厂) 或 global(factoryId == null) 的配置。
     * 平台角色原样返回。
     */
    private <T> List<T> filterReadable(HttpServletRequest request, List<T> items,
                                       Function<T, String> factoryIdGetter) {
        if (items == null) {
            return items;
        }
        if (isPlatformRole(request)) {
            return items;
        }
        final String cf = callerFactoryId(request);
        return items.stream()
                .filter(it -> {
                    String fid = factoryIdGetter.apply(it);
                    return fid == null || fid.equals(cf); // null = global 共享, 或本厂
                })
                .collect(Collectors.toList());
    }

    /**
     * 🔒 by-id 写守卫: 校验 caller 有权改这条配置。
     * 平台角色放行; 否则 entity 不存在 404, global(null) → 平台专属 403, 别家 → 403。
     *
     * @param existing 已 load 的 entity (null 表示不存在)
     * @param entityFactoryId existing 的 factoryId
     */
    private void guardWriteFactory(HttpServletRequest request, Object existing, String entityFactoryId) {
        if (isPlatformRole(request)) {
            return; // 平台角色跨工厂 + 可改 global (by design)
        }
        if (existing == null) {
            throw new BusinessException(404, "配置不存在").withSeverity("error");
        }
        if (entityFactoryId == null) {
            throw new BusinessException(403, "全局共享配置仅平台管理员可修改").withSeverity("error");
        }
        String cf = callerFactoryId(request);
        if (cf == null || !cf.equals(entityFactoryId)) {
            throw new BusinessException(403, "无权修改其他工厂的配置").withSeverity("error");
        }
    }

    /**
     * 🔒 create 守卫: 非平台角色只能为本厂创建。
     * body.factoryId 为 null → 缺省补本厂; != 本厂 → 403。返回最终 factoryId (caller 工厂)。
     */
    private String guardCreateFactory(HttpServletRequest request, String bodyFactoryId) {
        if (isPlatformRole(request)) {
            return bodyFactoryId; // 平台角色可指定任意工厂或 global(null)
        }
        String cf = callerFactoryId(request);
        if (bodyFactoryId == null) {
            return cf; // 缺省补本厂
        }
        if (!bodyFactoryId.equals(cf)) {
            throw new BusinessException(403, "无权为其他工厂创建配置").withSeverity("error");
        }
        return bodyFactoryId;
    }

    // ==================== 意图配置 ====================

    @RequirePermission({"analytics:read"})
    @GetMapping("/intents")
    @Operation(summary = "获取意图配置列表", description = "获取所有意图配置，可按分类筛选")
    public ResponseEntity<ApiResponse<List<AiIntentConfig>>> listIntents(
            HttpServletRequest request,
            @Parameter(description = "意图分类: QUERY/ANALYSIS/ALERT/ACTION")
            @RequestParam(required = false) String category) {

        log.info("获取意图配置列表: category={}", category);
        try {
            List<AiIntentConfig> intents = filterReadable(request,
                    configService.listIntents(category), AiIntentConfig::getFactoryId);
            return ResponseEntity.ok(ApiResponse.success(intents));
        } catch (Exception e) {
            log.error("获取意图配置失败: {}", e.getMessage(), e);
            return ResponseEntity.ok(ApiResponse.error("获取失败: " + ErrorSanitizer.sanitize(e)));
        }
    }

    @RequirePermission({"analytics:read_write"})
    @PostMapping("/intents")
    @Operation(summary = "创建意图配置", description = "创建新的意图配置")
    public ResponseEntity<ApiResponse<ConfigOperationResult>> createIntent(
            HttpServletRequest request,
            @RequestBody @Valid AiIntentConfig config) {

        log.info("创建意图配置: intentCode={}", config.getIntentCode());
        // 🔒 非平台角色只能为本厂创建 (阻止 body.factoryId 指向别家; try 外抛 → 403)。
        config.setFactoryId(guardCreateFactory(request, config.getFactoryId()));
        try {
            ConfigOperationResult result = configService.createIntent(config);
            if (result.isSuccess()) {
                return ResponseEntity.ok(ApiResponse.success("创建成功", result));
            } else {
                return ResponseEntity.ok(ApiResponse.error(result.getMessage()));
            }
        } catch (Exception e) {
            log.error("创建意图配置失败: {}", e.getMessage(), e);
            return ResponseEntity.ok(ApiResponse.error("创建失败: " + ErrorSanitizer.sanitize(e)));
        }
    }

    @RequirePermission({"analytics:read_write"})
    @PutMapping("/intents/{id}")
    @Operation(summary = "更新意图配置", description = "更新指定的意图配置")
    public ResponseEntity<ApiResponse<ConfigOperationResult>> updateIntent(
            HttpServletRequest request,
            @Parameter(description = "配置ID") @PathVariable String id,
            @RequestBody @Valid AiIntentConfig config) {

        log.info("更新意图配置: id={}", id);
        // 🔒 校验该意图属本厂 (跨厂/global 403, 不存在 404)。在 try 外抛, 让 GlobalExceptionHandler 映射 403/404。
        AiIntentConfig existing = intentConfigRepository.findById(id).orElse(null);
        guardWriteFactory(request, existing, existing == null ? null : existing.getFactoryId());
        // 🔒 阻止把意图转移到别家工厂 (body.factoryId 篡改)。existing 仅在平台角色 + 不存在时为 null。
        if (existing != null) {
            config.setFactoryId(existing.getFactoryId());
        }
        try {
            ConfigOperationResult result = configService.updateIntent(id, config);
            if (result.isSuccess()) {
                return ResponseEntity.ok(ApiResponse.success("更新成功", result));
            } else {
                return ResponseEntity.ok(ApiResponse.error(result.getMessage()));
            }
        } catch (Exception e) {
            log.error("更新意图配置失败: id={}, error={}", id, e.getMessage(), e);
            return ResponseEntity.ok(ApiResponse.error("更新失败: " + ErrorSanitizer.sanitize(e)));
        }
    }

    @RequirePermission({"analytics:read_write"})
    @DeleteMapping("/intents/{id}")
    @Operation(summary = "删除意图配置", description = "删除指定的意图配置（软删除）")
    public ResponseEntity<ApiResponse<ConfigOperationResult>> deleteIntent(
            HttpServletRequest request,
            @Parameter(description = "配置ID") @PathVariable String id) {

        log.info("删除意图配置: id={}", id);
        // 🔒 校验该意图属本厂 (try 外抛, 让 GlobalExceptionHandler 映射 403/404)。
        AiIntentConfig existing = intentConfigRepository.findById(id).orElse(null);
        guardWriteFactory(request, existing, existing == null ? null : existing.getFactoryId());
        try {
            ConfigOperationResult result = configService.deleteIntent(id);
            if (result.isSuccess()) {
                return ResponseEntity.ok(ApiResponse.success("删除成功", result));
            } else {
                return ResponseEntity.ok(ApiResponse.error(result.getMessage()));
            }
        } catch (Exception e) {
            log.error("删除意图配置失败: id={}, error={}", id, e.getMessage(), e);
            return ResponseEntity.ok(ApiResponse.error("删除失败: " + ErrorSanitizer.sanitize(e)));
        }
    }

    @RequirePermission({"analytics:read_write"})
    @PostMapping("/intents/reload")
    @Operation(summary = "重载意图配置", description = "重新加载意图配置缓存")
    public ResponseEntity<ApiResponse<ConfigOperationResult>> reloadIntents() {

        log.info("重载意图配置");
        try {
            ConfigOperationResult result = configService.reloadIntents();
            return ResponseEntity.ok(ApiResponse.success("重载成功", result));
        } catch (Exception e) {
            log.error("重载意图配置失败: {}", e.getMessage(), e);
            return ResponseEntity.ok(ApiResponse.error("重载失败: " + ErrorSanitizer.sanitize(e)));
        }
    }

    // ==================== 告警阈值 ====================

    @RequirePermission({"analytics:read"})
    @GetMapping("/thresholds")
    @Operation(summary = "获取告警阈值列表", description = "获取所有告警阈值配置，可按类型筛选")
    public ResponseEntity<ApiResponse<List<SmartBiAlertThreshold>>> listThresholds(
            HttpServletRequest request,
            @Parameter(description = "阈值类型: SALES/FINANCE/DEPARTMENT/PRODUCTION/QUALITY")
            @RequestParam(required = false) String type) {

        log.info("获取告警阈值列表: type={}", type);
        try {
            List<SmartBiAlertThreshold> thresholds = filterReadable(request,
                    configService.listThresholds(type), SmartBiAlertThreshold::getFactoryId);
            return ResponseEntity.ok(ApiResponse.success(thresholds));
        } catch (Exception e) {
            log.error("获取告警阈值失败: {}", e.getMessage(), e);
            return ResponseEntity.ok(ApiResponse.error("获取失败: " + ErrorSanitizer.sanitize(e)));
        }
    }

    @RequirePermission({"analytics:read_write"})
    @PostMapping("/thresholds")
    @Operation(summary = "创建告警阈值", description = "创建新的告警阈值配置")
    public ResponseEntity<ApiResponse<ConfigOperationResult>> createThreshold(
            HttpServletRequest httpRequest,
            @RequestBody @Valid CreateAlertThresholdRequest request) {

        log.info("创建告警阈值: type={}, metricCode={}",
                request.getThresholdType(), request.getMetricCode());
        // 🔒 非平台角色只能为本厂创建 (try 外抛 → 403)。
        request.setFactoryId(guardCreateFactory(httpRequest, request.getFactoryId()));
        try {
            ConfigOperationResult result = configService.createThreshold(request);
            if (result.isSuccess()) {
                return ResponseEntity.ok(ApiResponse.success("创建成功", result));
            } else {
                return ResponseEntity.ok(ApiResponse.error(result.getMessage()));
            }
        } catch (Exception e) {
            log.error("创建告警阈值失败: {}", e.getMessage(), e);
            return ResponseEntity.ok(ApiResponse.error("创建失败: " + ErrorSanitizer.sanitize(e)));
        }
    }

    @RequirePermission({"analytics:read_write"})
    @PutMapping("/thresholds/{id}")
    @Operation(summary = "更新告警阈值", description = "更新指定的告警阈值配置")
    public ResponseEntity<ApiResponse<ConfigOperationResult>> updateThreshold(
            HttpServletRequest request,
            @Parameter(description = "配置ID") @PathVariable String id,
            @RequestBody @Valid SmartBiAlertThreshold threshold) {

        log.info("更新告警阈值: id={}", id);
        // 🔒 校验该阈值属本厂 (try 外抛 → 403/404)。
        SmartBiAlertThreshold existing = alertThresholdRepository.findById(id).orElse(null);
        guardWriteFactory(request, existing, existing == null ? null : existing.getFactoryId());
        try {
            ConfigOperationResult result = configService.updateThreshold(id, threshold);
            if (result.isSuccess()) {
                return ResponseEntity.ok(ApiResponse.success("更新成功", result));
            } else {
                return ResponseEntity.ok(ApiResponse.error(result.getMessage()));
            }
        } catch (Exception e) {
            log.error("更新告警阈值失败: id={}, error={}", id, e.getMessage(), e);
            return ResponseEntity.ok(ApiResponse.error("更新失败: " + ErrorSanitizer.sanitize(e)));
        }
    }

    @RequirePermission({"analytics:read_write"})
    @DeleteMapping("/thresholds/{id}")
    @Operation(summary = "删除告警阈值", description = "删除指定的告警阈值配置（软删除）")
    public ResponseEntity<ApiResponse<ConfigOperationResult>> deleteThreshold(
            HttpServletRequest request,
            @Parameter(description = "配置ID") @PathVariable String id) {

        log.info("删除告警阈值: id={}", id);
        // 🔒 校验该阈值属本厂 (try 外抛 → 403/404)。
        SmartBiAlertThreshold existing = alertThresholdRepository.findById(id).orElse(null);
        guardWriteFactory(request, existing, existing == null ? null : existing.getFactoryId());
        try {
            ConfigOperationResult result = configService.deleteThreshold(id);
            if (result.isSuccess()) {
                return ResponseEntity.ok(ApiResponse.success("删除成功", result));
            } else {
                return ResponseEntity.ok(ApiResponse.error(result.getMessage()));
            }
        } catch (Exception e) {
            log.error("删除告警阈值失败: id={}, error={}", id, e.getMessage(), e);
            return ResponseEntity.ok(ApiResponse.error("删除失败: " + ErrorSanitizer.sanitize(e)));
        }
    }

    @RequirePermission({"analytics:read_write"})
    @PostMapping("/thresholds/reload")
    @Operation(summary = "重载告警阈值", description = "重新加载告警阈值缓存")
    public ResponseEntity<ApiResponse<ConfigOperationResult>> reloadThresholds() {

        log.info("重载告警阈值");
        try {
            ConfigOperationResult result = configService.reloadThresholds();
            return ResponseEntity.ok(ApiResponse.success("重载成功", result));
        } catch (Exception e) {
            log.error("重载告警阈值失败: {}", e.getMessage(), e);
            return ResponseEntity.ok(ApiResponse.error("重载失败: " + ErrorSanitizer.sanitize(e)));
        }
    }

    // ==================== 激励规则 ====================

    @RequirePermission({"analytics:read"})
    @GetMapping("/incentive-rules")
    @Operation(summary = "获取激励规则列表", description = "获取所有激励规则配置，可按规则代码筛选")
    public ResponseEntity<ApiResponse<List<SmartBiIncentiveRule>>> listIncentiveRules(
            HttpServletRequest request,
            @Parameter(description = "规则代码: SALES_TARGET/QUALITY_SCORE/ATTENDANCE_RATE")
            @RequestParam(required = false) String ruleCode) {

        log.info("获取激励规则列表: ruleCode={}", ruleCode);
        try {
            List<SmartBiIncentiveRule> rules = filterReadable(request,
                    configService.listIncentiveRules(ruleCode), SmartBiIncentiveRule::getFactoryId);
            return ResponseEntity.ok(ApiResponse.success(rules));
        } catch (Exception e) {
            log.error("获取激励规则失败: {}", e.getMessage(), e);
            return ResponseEntity.ok(ApiResponse.error("获取失败: " + ErrorSanitizer.sanitize(e)));
        }
    }

    @RequirePermission({"analytics:read_write"})
    @PostMapping("/incentive-rules")
    @Operation(summary = "创建激励规则", description = "创建新的激励规则配置")
    public ResponseEntity<ApiResponse<ConfigOperationResult>> createIncentiveRule(
            HttpServletRequest httpRequest,
            @RequestBody @Valid CreateIncentiveRuleRequest request) {

        log.info("创建激励规则: ruleCode={}, levelName={}",
                request.getRuleCode(), request.getLevelName());
        // 🔒 非平台角色只能为本厂创建 (激励规则 = 提成/薪酬, 跨厂篡改高危; try 外抛 → 403)。
        request.setFactoryId(guardCreateFactory(httpRequest, request.getFactoryId()));
        try {
            ConfigOperationResult result = configService.createIncentiveRule(request);
            if (result.isSuccess()) {
                return ResponseEntity.ok(ApiResponse.success("创建成功", result));
            } else {
                return ResponseEntity.ok(ApiResponse.error(result.getMessage()));
            }
        } catch (Exception e) {
            log.error("创建激励规则失败: {}", e.getMessage(), e);
            return ResponseEntity.ok(ApiResponse.error("创建失败: " + ErrorSanitizer.sanitize(e)));
        }
    }

    @RequirePermission({"analytics:read_write"})
    @PutMapping("/incentive-rules/{id}")
    @Operation(summary = "更新激励规则", description = "更新指定的激励规则配置")
    public ResponseEntity<ApiResponse<ConfigOperationResult>> updateIncentiveRule(
            HttpServletRequest httpRequest,
            @Parameter(description = "配置ID") @PathVariable Long id,
            @RequestBody @Valid UpdateIncentiveRuleRequest request) {

        log.info("更新激励规则: id={}", id);
        // 🔒 校验该激励规则属本厂 (try 外抛 → 403/404)。
        SmartBiIncentiveRule existing = incentiveRuleRepository.findById(id).orElse(null);
        guardWriteFactory(httpRequest, existing, existing == null ? null : existing.getFactoryId());
        try {
            ConfigOperationResult result = configService.updateIncentiveRule(id, request);
            if (result.isSuccess()) {
                return ResponseEntity.ok(ApiResponse.success("更新成功", result));
            } else {
                return ResponseEntity.ok(ApiResponse.error(result.getMessage()));
            }
        } catch (Exception e) {
            log.error("更新激励规则失败: id={}, error={}", id, e.getMessage(), e);
            return ResponseEntity.ok(ApiResponse.error("更新失败: " + ErrorSanitizer.sanitize(e)));
        }
    }

    @RequirePermission({"analytics:read_write"})
    @DeleteMapping("/incentive-rules/{id}")
    @Operation(summary = "删除激励规则", description = "删除指定的激励规则配置（软删除）")
    public ResponseEntity<ApiResponse<ConfigOperationResult>> deleteIncentiveRule(
            HttpServletRequest request,
            @Parameter(description = "配置ID") @PathVariable Long id) {

        log.info("删除激励规则: id={}", id);
        // 🔒 校验该激励规则属本厂 (try 外抛 → 403/404)。
        SmartBiIncentiveRule existing = incentiveRuleRepository.findById(id).orElse(null);
        guardWriteFactory(request, existing, existing == null ? null : existing.getFactoryId());
        try {
            ConfigOperationResult result = configService.deleteIncentiveRule(id);
            if (result.isSuccess()) {
                return ResponseEntity.ok(ApiResponse.success("删除成功", result));
            } else {
                return ResponseEntity.ok(ApiResponse.error(result.getMessage()));
            }
        } catch (Exception e) {
            log.error("删除激励规则失败: id={}, error={}", id, e.getMessage(), e);
            return ResponseEntity.ok(ApiResponse.error("删除失败: " + ErrorSanitizer.sanitize(e)));
        }
    }

    @RequirePermission({"analytics:read_write"})
    @PostMapping("/incentive-rules/reload")
    @Operation(summary = "重载激励规则", description = "重新加载激励规则缓存")
    public ResponseEntity<ApiResponse<ConfigOperationResult>> reloadIncentiveRules() {

        log.info("重载激励规则");
        try {
            ConfigOperationResult result = configService.reloadIncentiveRules();
            return ResponseEntity.ok(ApiResponse.success("重载成功", result));
        } catch (Exception e) {
            log.error("重载激励规则失败: {}", e.getMessage(), e);
            return ResponseEntity.ok(ApiResponse.error("重载失败: " + ErrorSanitizer.sanitize(e)));
        }
    }

    // ==================== 字段映射 ====================

    @RequirePermission({"analytics:read"})
    @GetMapping("/field-mappings")
    @Operation(summary = "获取字段映射列表", description = "获取所有字段映射配置，可按字典类型筛选")
    public ResponseEntity<ApiResponse<List<SmartBiDictionary>>> listFieldMappings(
            HttpServletRequest request,
            @Parameter(description = "字典类型: region/department/metric/time/dimension")
            @RequestParam(required = false) String dictType) {

        log.info("获取字段映射列表: dictType={}", dictType);
        try {
            List<SmartBiDictionary> mappings = filterReadable(request,
                    configService.listFieldMappings(dictType), SmartBiDictionary::getFactoryId);
            return ResponseEntity.ok(ApiResponse.success(mappings));
        } catch (Exception e) {
            log.error("获取字段映射失败: {}", e.getMessage(), e);
            return ResponseEntity.ok(ApiResponse.error("获取失败: " + ErrorSanitizer.sanitize(e)));
        }
    }

    @RequirePermission({"analytics:read_write"})
    @PostMapping("/field-mappings")
    @Operation(summary = "创建字段映射", description = "创建新的字段映射配置")
    public ResponseEntity<ApiResponse<ConfigOperationResult>> createFieldMapping(
            HttpServletRequest request,
            @RequestBody @Valid SmartBiDictionary mapping) {

        log.info("创建字段映射: dictType={}, name={}",
                mapping.getDictType(), mapping.getName());
        // 🔒 非平台角色只能为本厂创建 (try 外抛 → 403)。
        mapping.setFactoryId(guardCreateFactory(request, mapping.getFactoryId()));
        try {
            ConfigOperationResult result = configService.createFieldMapping(mapping);
            if (result.isSuccess()) {
                return ResponseEntity.ok(ApiResponse.success("创建成功", result));
            } else {
                return ResponseEntity.ok(ApiResponse.error(result.getMessage()));
            }
        } catch (Exception e) {
            log.error("创建字段映射失败: {}", e.getMessage(), e);
            return ResponseEntity.ok(ApiResponse.error("创建失败: " + ErrorSanitizer.sanitize(e)));
        }
    }

    @RequirePermission({"analytics:read_write"})
    @PutMapping("/field-mappings/{id}")
    @Operation(summary = "更新字段映射", description = "更新指定的字段映射配置")
    public ResponseEntity<ApiResponse<ConfigOperationResult>> updateFieldMapping(
            HttpServletRequest request,
            @Parameter(description = "配置ID") @PathVariable Long id,
            @RequestBody @Valid SmartBiDictionary mapping) {

        log.info("更新字段映射: id={}", id);
        // 🔒 校验该字段映射属本厂 (try 外抛 → 403/404)。
        SmartBiDictionary existing = dictionaryRepository.findById(id).orElse(null);
        guardWriteFactory(request, existing, existing == null ? null : existing.getFactoryId());
        try {
            ConfigOperationResult result = configService.updateFieldMapping(id, mapping);
            if (result.isSuccess()) {
                return ResponseEntity.ok(ApiResponse.success("更新成功", result));
            } else {
                return ResponseEntity.ok(ApiResponse.error(result.getMessage()));
            }
        } catch (Exception e) {
            log.error("更新字段映射失败: id={}, error={}", id, e.getMessage(), e);
            return ResponseEntity.ok(ApiResponse.error("更新失败: " + ErrorSanitizer.sanitize(e)));
        }
    }

    @RequirePermission({"analytics:read_write"})
    @DeleteMapping("/field-mappings/{id}")
    @Operation(summary = "删除字段映射", description = "删除指定的字段映射配置（软删除）")
    public ResponseEntity<ApiResponse<ConfigOperationResult>> deleteFieldMapping(
            HttpServletRequest request,
            @Parameter(description = "配置ID") @PathVariable Long id) {

        log.info("删除字段映射: id={}", id);
        // 🔒 校验该字段映射属本厂 (try 外抛 → 403/404)。
        SmartBiDictionary existing = dictionaryRepository.findById(id).orElse(null);
        guardWriteFactory(request, existing, existing == null ? null : existing.getFactoryId());
        try {
            ConfigOperationResult result = configService.deleteFieldMapping(id);
            if (result.isSuccess()) {
                return ResponseEntity.ok(ApiResponse.success("删除成功", result));
            } else {
                return ResponseEntity.ok(ApiResponse.error(result.getMessage()));
            }
        } catch (Exception e) {
            log.error("删除字段映射失败: id={}, error={}", id, e.getMessage(), e);
            return ResponseEntity.ok(ApiResponse.error("删除失败: " + ErrorSanitizer.sanitize(e)));
        }
    }

    @RequirePermission({"analytics:read_write"})
    @PostMapping("/field-mappings/reload")
    @Operation(summary = "重载字段映射", description = "重新加载字段映射缓存")
    public ResponseEntity<ApiResponse<ConfigOperationResult>> reloadFieldMappings() {

        log.info("重载字段映射");
        try {
            ConfigOperationResult result = configService.reloadFieldMappings();
            return ResponseEntity.ok(ApiResponse.success("重载成功", result));
        } catch (Exception e) {
            log.error("重载字段映射失败: {}", e.getMessage(), e);
            return ResponseEntity.ok(ApiResponse.error("重载失败: " + ErrorSanitizer.sanitize(e)));
        }
    }

    // ==================== 指标公式 ====================

    @RequirePermission({"analytics:read"})
    @GetMapping("/metric-formulas")
    @Operation(summary = "获取指标公式列表", description = "获取所有指标公式配置，可按公式类型筛选")
    public ResponseEntity<ApiResponse<List<SmartBiMetricFormula>>> listMetricFormulas(
            HttpServletRequest request,
            @Parameter(description = "公式类型: SIMPLE/DERIVED/CUSTOM")
            @RequestParam(required = false) String formulaType) {

        log.info("获取指标公式列表: formulaType={}", formulaType);
        try {
            List<SmartBiMetricFormula> formulas = filterReadable(request,
                    configService.listMetricFormulas(formulaType), SmartBiMetricFormula::getFactoryId);
            return ResponseEntity.ok(ApiResponse.success(formulas));
        } catch (Exception e) {
            log.error("获取指标公式失败: {}", e.getMessage(), e);
            return ResponseEntity.ok(ApiResponse.error("获取失败: " + ErrorSanitizer.sanitize(e)));
        }
    }

    @RequirePermission({"analytics:read_write"})
    @PostMapping("/metric-formulas")
    @Operation(summary = "创建指标公式", description = "创建新的指标公式配置")
    public ResponseEntity<ApiResponse<ConfigOperationResult>> createMetricFormula(
            HttpServletRequest request,
            @RequestBody @Valid SmartBiMetricFormula formula) {

        log.info("创建指标公式: metricCode={}", formula.getMetricCode());
        // 🔒 非平台角色只能为本厂创建 (try 外抛 → 403)。
        formula.setFactoryId(guardCreateFactory(request, formula.getFactoryId()));
        try {
            ConfigOperationResult result = configService.createMetricFormula(formula);
            if (result.isSuccess()) {
                return ResponseEntity.ok(ApiResponse.success("创建成功", result));
            } else {
                return ResponseEntity.ok(ApiResponse.error(result.getMessage()));
            }
        } catch (Exception e) {
            log.error("创建指标公式失败: {}", e.getMessage(), e);
            return ResponseEntity.ok(ApiResponse.error("创建失败: " + ErrorSanitizer.sanitize(e)));
        }
    }

    @RequirePermission({"analytics:read_write"})
    @PutMapping("/metric-formulas/{id}")
    @Operation(summary = "更新指标公式", description = "更新指定的指标公式配置")
    public ResponseEntity<ApiResponse<ConfigOperationResult>> updateMetricFormula(
            HttpServletRequest request,
            @Parameter(description = "配置ID") @PathVariable Long id,
            @RequestBody @Valid SmartBiMetricFormula formula) {

        log.info("更新指标公式: id={}", id);
        // 🔒 校验该指标公式属本厂 (try 外抛 → 403/404)。
        SmartBiMetricFormula existing = metricFormulaRepository.findById(id).orElse(null);
        guardWriteFactory(request, existing, existing == null ? null : existing.getFactoryId());
        try {
            ConfigOperationResult result = configService.updateMetricFormula(id, formula);
            if (result.isSuccess()) {
                return ResponseEntity.ok(ApiResponse.success("更新成功", result));
            } else {
                return ResponseEntity.ok(ApiResponse.error(result.getMessage()));
            }
        } catch (Exception e) {
            log.error("更新指标公式失败: id={}, error={}", id, e.getMessage(), e);
            return ResponseEntity.ok(ApiResponse.error("更新失败: " + ErrorSanitizer.sanitize(e)));
        }
    }

    @RequirePermission({"analytics:read_write"})
    @DeleteMapping("/metric-formulas/{id}")
    @Operation(summary = "删除指标公式", description = "删除指定的指标公式配置（软删除）")
    public ResponseEntity<ApiResponse<ConfigOperationResult>> deleteMetricFormula(
            HttpServletRequest request,
            @Parameter(description = "配置ID") @PathVariable Long id) {

        log.info("删除指标公式: id={}", id);
        // 🔒 校验该指标公式属本厂 (try 外抛 → 403/404)。
        SmartBiMetricFormula existing = metricFormulaRepository.findById(id).orElse(null);
        guardWriteFactory(request, existing, existing == null ? null : existing.getFactoryId());
        try {
            ConfigOperationResult result = configService.deleteMetricFormula(id);
            if (result.isSuccess()) {
                return ResponseEntity.ok(ApiResponse.success("删除成功", result));
            } else {
                return ResponseEntity.ok(ApiResponse.error(result.getMessage()));
            }
        } catch (Exception e) {
            log.error("删除指标公式失败: id={}, error={}", id, e.getMessage(), e);
            return ResponseEntity.ok(ApiResponse.error("删除失败: " + ErrorSanitizer.sanitize(e)));
        }
    }

    @RequirePermission({"analytics:read_write"})
    @PostMapping("/metric-formulas/reload")
    @Operation(summary = "重载指标公式", description = "重新加载指标公式缓存")
    public ResponseEntity<ApiResponse<ConfigOperationResult>> reloadMetricFormulas() {

        log.info("重载指标公式");
        try {
            ConfigOperationResult result = configService.reloadMetricFormulas();
            return ResponseEntity.ok(ApiResponse.success("重载成功", result));
        } catch (Exception e) {
            log.error("重载指标公式失败: {}", e.getMessage(), e);
            return ResponseEntity.ok(ApiResponse.error("重载失败: " + ErrorSanitizer.sanitize(e)));
        }
    }

    // ==================== 图表模板 ====================

    @RequirePermission({"analytics:read"})
    @GetMapping("/chart-templates")
    @Operation(summary = "获取图表模板列表", description = "获取所有图表模板配置，可按分类或图表类型筛选")
    public ResponseEntity<ApiResponse<List<SmartBiChartTemplate>>> listChartTemplates(
            HttpServletRequest request,
            @Parameter(description = "模板分类: SALES/FINANCE/PRODUCTION/QUALITY/HR")
            @RequestParam(required = false) String category,
            @Parameter(description = "图表类型: LINE/BAR/PIE/AREA/SCATTER/GAUGE/TABLE")
            @RequestParam(required = false) String chartType) {

        log.info("获取图表模板列表: category={}, chartType={}", category, chartType);
        try {
            List<SmartBiChartTemplate> templates = filterReadable(request,
                    configService.listChartTemplates(category, chartType), SmartBiChartTemplate::getFactoryId);
            return ResponseEntity.ok(ApiResponse.success(templates));
        } catch (Exception e) {
            log.error("获取图表模板失败: {}", e.getMessage(), e);
            return ResponseEntity.ok(ApiResponse.error("获取失败: " + ErrorSanitizer.sanitize(e)));
        }
    }

    @RequirePermission({"analytics:read"})
    @GetMapping("/chart-templates/{code}")
    @Operation(summary = "获取指定图表模板", description = "根据模板代码获取图表模板，支持工厂级覆盖")
    public ResponseEntity<ApiResponse<SmartBiChartTemplate>> getChartTemplate(
            @Parameter(description = "模板代码") @PathVariable String code,
            @Parameter(description = "工厂ID（可选，用于获取工厂级覆盖配置）")
            @RequestParam(required = false) String factoryId) {

        log.info("获取图表模板: code={}, factoryId={}", code, factoryId);
        try {
            SmartBiChartTemplate template = configService.getChartTemplate(code, factoryId);
            if (template != null) {
                return ResponseEntity.ok(ApiResponse.success(template));
            } else {
                return ResponseEntity.ok(ApiResponse.error("图表模板不存在: " + code));
            }
        } catch (Exception e) {
            log.error("获取图表模板失败: code={}, error={}", code, e.getMessage(), e);
            return ResponseEntity.ok(ApiResponse.error("获取失败: " + ErrorSanitizer.sanitize(e)));
        }
    }

    @RequirePermission({"analytics:read_write"})
    @PostMapping("/chart-templates")
    @Operation(summary = "创建图表模板", description = "创建新的图表模板配置")
    public ResponseEntity<ApiResponse<ConfigOperationResult>> createChartTemplate(
            HttpServletRequest request,
            @RequestBody @Valid SmartBiChartTemplate template) {

        log.info("创建图表模板: templateCode={}, chartType={}",
                template.getTemplateCode(), template.getChartType());
        // 🔒 非平台角色只能为本厂创建 (try 外抛 → 403)。
        template.setFactoryId(guardCreateFactory(request, template.getFactoryId()));
        try {
            ConfigOperationResult result = configService.createChartTemplate(template);
            if (result.isSuccess()) {
                return ResponseEntity.ok(ApiResponse.success("创建成功", result));
            } else {
                return ResponseEntity.ok(ApiResponse.error(result.getMessage()));
            }
        } catch (Exception e) {
            log.error("创建图表模板失败: {}", e.getMessage(), e);
            return ResponseEntity.ok(ApiResponse.error("创建失败: " + ErrorSanitizer.sanitize(e)));
        }
    }

    @RequirePermission({"analytics:read_write"})
    @PutMapping("/chart-templates/{id}")
    @Operation(summary = "更新图表模板", description = "更新指定的图表模板配置")
    public ResponseEntity<ApiResponse<ConfigOperationResult>> updateChartTemplate(
            HttpServletRequest request,
            @Parameter(description = "配置ID") @PathVariable Long id,
            @RequestBody @Valid SmartBiChartTemplate template) {

        log.info("更新图表模板: id={}", id);
        // 🔒 校验该图表模板属本厂 (try 外抛 → 403/404)。
        SmartBiChartTemplate existing = chartTemplateRepository.findById(id).orElse(null);
        guardWriteFactory(request, existing, existing == null ? null : existing.getFactoryId());
        try {
            ConfigOperationResult result = configService.updateChartTemplate(id, template);
            if (result.isSuccess()) {
                return ResponseEntity.ok(ApiResponse.success("更新成功", result));
            } else {
                return ResponseEntity.ok(ApiResponse.error(result.getMessage()));
            }
        } catch (Exception e) {
            log.error("更新图表模板失败: id={}, error={}", id, e.getMessage(), e);
            return ResponseEntity.ok(ApiResponse.error("更新失败: " + ErrorSanitizer.sanitize(e)));
        }
    }

    @RequirePermission({"analytics:read_write"})
    @DeleteMapping("/chart-templates/{id}")
    @Operation(summary = "删除图表模板", description = "删除指定的图表模板配置（软删除）")
    public ResponseEntity<ApiResponse<ConfigOperationResult>> deleteChartTemplate(
            HttpServletRequest request,
            @Parameter(description = "配置ID") @PathVariable Long id) {

        log.info("删除图表模板: id={}", id);
        // 🔒 校验该图表模板属本厂 (try 外抛 → 403/404)。
        SmartBiChartTemplate existing = chartTemplateRepository.findById(id).orElse(null);
        guardWriteFactory(request, existing, existing == null ? null : existing.getFactoryId());
        try {
            ConfigOperationResult result = configService.deleteChartTemplate(id);
            if (result.isSuccess()) {
                return ResponseEntity.ok(ApiResponse.success("删除成功", result));
            } else {
                return ResponseEntity.ok(ApiResponse.error(result.getMessage()));
            }
        } catch (Exception e) {
            log.error("删除图表模板失败: id={}, error={}", id, e.getMessage(), e);
            return ResponseEntity.ok(ApiResponse.error("删除失败: " + ErrorSanitizer.sanitize(e)));
        }
    }

    @RequirePermission({"analytics:read_write"})
    @PostMapping("/chart-templates/reload")
    @Operation(summary = "重载图表模板", description = "重新加载图表模板缓存")
    public ResponseEntity<ApiResponse<ConfigOperationResult>> reloadChartTemplates() {

        log.info("重载图表模板");
        try {
            ConfigOperationResult result = configService.reloadChartTemplates();
            return ResponseEntity.ok(ApiResponse.success("重载成功", result));
        } catch (Exception e) {
            log.error("重载图表模板失败: {}", e.getMessage(), e);
            return ResponseEntity.ok(ApiResponse.error("重载失败: " + ErrorSanitizer.sanitize(e)));
        }
    }

    @RequirePermission({"analytics:read"})
    @GetMapping("/chart-templates/recommend")
    @Operation(summary = "推荐图表类型", description = "根据数据特征推荐最适合的图表类型")
    public ResponseEntity<ApiResponse<Map<String, Object>>> recommendChart(
            @Parameter(description = "指标代码") @RequestParam String metricCode,
            @Parameter(description = "数据点数量") @RequestParam(defaultValue = "10") int dataPoints,
            @Parameter(description = "是否包含时间维度") @RequestParam(defaultValue = "false") boolean hasTimeDimension) {

        log.info("推荐图表类型: metricCode={}, dataPoints={}, hasTimeDimension={}",
                metricCode, dataPoints, hasTimeDimension);
        try {
            String recommendedType = configService.recommendChartType(metricCode, dataPoints, hasTimeDimension);
            Map<String, Object> result = new java.util.HashMap<>();
            result.put("metricCode", metricCode);
            result.put("dataPoints", dataPoints);
            result.put("hasTimeDimension", hasTimeDimension);
            result.put("recommendedChartType", recommendedType);
            return ResponseEntity.ok(ApiResponse.success(result));
        } catch (Exception e) {
            log.error("推荐图表类型失败: metricCode={}, error={}", metricCode, e.getMessage(), e);
            return ResponseEntity.ok(ApiResponse.error("推荐失败: " + ErrorSanitizer.sanitize(e)));
        }
    }

    @RequirePermission({"analytics:read"})
    @GetMapping("/chart-templates/for-metric/{metricCode}")
    @Operation(summary = "获取指标适用的图表模板", description = "获取指定指标可用的所有图表模板")
    public ResponseEntity<ApiResponse<List<SmartBiChartTemplate>>> getChartTemplatesForMetric(
            @Parameter(description = "指标代码") @PathVariable String metricCode) {

        log.info("获取指标适用的图表模板: metricCode={}", metricCode);
        try {
            List<SmartBiChartTemplate> templates = configService.getChartTemplatesForMetric(metricCode);
            return ResponseEntity.ok(ApiResponse.success(templates));
        } catch (Exception e) {
            log.error("获取指标图表模板失败: metricCode={}, error={}", metricCode, e.getMessage(), e);
            return ResponseEntity.ok(ApiResponse.error("获取失败: " + ErrorSanitizer.sanitize(e)));
        }
    }

    @RequirePermission({"analytics:read_write"})
    @PostMapping("/chart-templates/{code}/build-with-analysis")
    @Operation(summary = "构建带AI分析的图表", description = "根据模板代码和数据构建图表配置，并生成AI分析文本")
    public ResponseEntity<ApiResponse<Map<String, Object>>> buildChartWithAnalysis(
            @Parameter(description = "模板代码") @PathVariable String code,
            @Parameter(description = "图表数据") @RequestBody Map<String, Object> data,
            @Parameter(description = "工厂ID（可选）") @RequestParam(required = false) String factoryId) {

        log.info("构建带AI分析的图表: code={}, factoryId={}", code, factoryId);
        try {
            Map<String, Object> result = configService.buildChartWithAnalysis(code, data, factoryId);
            return ResponseEntity.ok(ApiResponse.success(result));
        } catch (Exception e) {
            log.error("构建图表失败: code={}, error={}", code, e.getMessage(), e);
            return ResponseEntity.ok(ApiResponse.error("构建失败: " + ErrorSanitizer.sanitize(e)));
        }
    }

    // ==================== 全局操作 ====================

    @RequirePermission({"analytics:read_write"})
    @PostMapping("/reload-all")
    @Operation(summary = "重载所有配置", description = "重新加载所有 SmartBI 配置缓存")
    public ResponseEntity<ApiResponse<ConfigOperationResult>> reloadAll() {

        log.info("重载所有 SmartBI 配置");
        try {
            ConfigOperationResult result = configService.reloadAll();
            return ResponseEntity.ok(ApiResponse.success("重载成功", result));
        } catch (Exception e) {
            log.error("重载所有配置失败: {}", e.getMessage(), e);
            return ResponseEntity.ok(ApiResponse.error("重载失败: " + ErrorSanitizer.sanitize(e)));
        }
    }

    @RequirePermission({"analytics:read"})
    @GetMapping("/status")
    @Operation(summary = "获取配置状态", description = "获取所有配置的状态摘要（数量、缓存大小、最后更新时间等）")
    public ResponseEntity<ApiResponse<Map<String, Object>>> getConfigStatus() {

        log.info("获取配置状态");
        try {
            Map<String, Object> status = configService.getConfigStatus();
            return ResponseEntity.ok(ApiResponse.success(status));
        } catch (Exception e) {
            log.error("获取配置状态失败: {}", e.getMessage(), e);
            return ResponseEntity.ok(ApiResponse.error("获取失败: " + ErrorSanitizer.sanitize(e)));
        }
    }

    // ==================== 数据源配置 (Apr 16 2026) ====================

    /**
     * Helper: build Spring Page response shape the frontend expects: { content, totalElements, ... }
     */
    private Map<String, Object> pageToMap(Page<DataSourceDTO> page) {
        Map<String, Object> body = new java.util.HashMap<>();
        body.put("content", page.getContent());
        body.put("totalElements", page.getTotalElements());
        body.put("totalPages", page.getTotalPages());
        body.put("size", page.getSize());
        body.put("number", page.getNumber());
        return body;
    }

    @RequirePermission({"analytics:read"})
    @GetMapping("/data-sources")
    @Operation(summary = "数据源列表", description = "分页 + 过滤 (按 keyword/type/isActive)")
    public ResponseEntity<ApiResponse<Map<String, Object>>> listDataSources(
            HttpServletRequest request,
            @RequestParam(required = false) String factoryId,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String type,
            @RequestParam(required = false) Boolean isActive,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        try {
            // 🔒 非平台角色强制只看本厂数据源 (覆盖入参 factoryId, 防全工厂泄露)。
            final String fid = isPlatformRole(request) ? factoryId : callerFactoryId(request);
            Page<DataSourceDTO> result = dataSourceService.list(fid, keyword, type, isActive, page, size);
            return ResponseEntity.ok(ApiResponse.success(pageToMap(result)));
        } catch (Exception e) {
            log.error("数据源列表失败: {}", e.getMessage(), e);
            return ResponseEntity.ok(ApiResponse.error("查询失败: " + ErrorSanitizer.sanitize(e)));
        }
    }

    @RequirePermission({"analytics:read"})
    @GetMapping("/data-sources/{id}")
    @Operation(summary = "单个数据源详情")
    public ResponseEntity<ApiResponse<DataSourceDTO>> getDataSource(
            HttpServletRequest request,
            @PathVariable Long id,
            @RequestParam(required = false) String factoryId) {
        // 🔒 非平台角色强制本厂 (覆盖入参, 防按 id 读别家数据源)。
        final String fid = isPlatformRole(request) ? factoryId : callerFactoryId(request);
        DataSourceDTO dto = dataSourceService.getById(fid, id);
        return ResponseEntity.ok(dto != null ? ApiResponse.success(dto) : ApiResponse.error("未找到数据源"));
    }

    @RequirePermission({"analytics:read_write"})
    @PostMapping("/data-sources")
    @Operation(summary = "创建数据源")
    public ResponseEntity<ApiResponse<DataSourceDTO>> createDataSource(
            HttpServletRequest request,
            @RequestBody @Valid DataSourceDTO dto,
            @RequestParam(required = false) String factoryId) {
        try {
            String fid = factoryId != null ? factoryId : dto.getFactoryId();
            // 🔒 非平台角色只能为本厂创建 (覆盖任何入参 factoryId)。
            if (!isPlatformRole(request)) {
                fid = callerFactoryId(request);
            }
            if (fid == null || fid.isBlank()) {
                return ResponseEntity.ok(ApiResponse.error("factoryId 必填"));
            }
            DataSourceDTO saved = dataSourceService.create(fid, dto);
            return ResponseEntity.ok(ApiResponse.success("创建成功", saved));
        } catch (Exception e) {
            log.error("创建数据源失败: {}", e.getMessage(), e);
            return ResponseEntity.ok(ApiResponse.error("创建失败: " + ErrorSanitizer.sanitize(e)));
        }
    }

    @RequirePermission({"analytics:read_write"})
    @PutMapping("/data-sources/{id}")
    @Operation(summary = "更新数据源")
    public ResponseEntity<ApiResponse<DataSourceDTO>> updateDataSource(
            HttpServletRequest request,
            @PathVariable Long id,
            @RequestBody DataSourceDTO dto,
            @RequestParam(required = false) String factoryId) {
        try {
            String fid = factoryId != null ? factoryId : dto.getFactoryId();
            // 🔒 非平台角色只能改本厂 (update 用 findByIdAndFactoryId 已按此 fid 过滤 → 跨厂返 null)。
            if (!isPlatformRole(request)) {
                fid = callerFactoryId(request);
            }
            if (fid == null || fid.isBlank()) {
                return ResponseEntity.ok(ApiResponse.error("factoryId 必填"));
            }
            DataSourceDTO updated = dataSourceService.update(fid, id, dto);
            return ResponseEntity.ok(updated != null
                    ? ApiResponse.success("更新成功", updated)
                    : ApiResponse.error("未找到数据源"));
        } catch (Exception e) {
            log.error("更新数据源失败: {}", e.getMessage(), e);
            return ResponseEntity.ok(ApiResponse.error("更新失败: " + ErrorSanitizer.sanitize(e)));
        }
    }

    @RequirePermission({"analytics:read_write"})
    @DeleteMapping("/data-sources/{id}")
    @Operation(summary = "删除数据源")
    public ResponseEntity<ApiResponse<Void>> deleteDataSource(
            HttpServletRequest request,
            @PathVariable Long id,
            @RequestParam(required = false) String factoryId) {
        try {
            // 🔒 非平台角色只能删本厂 (覆盖入参; delete 用 findByIdAndFactoryId 过滤)。
            final String fid = isPlatformRole(request) ? factoryId : callerFactoryId(request);
            if (fid == null || fid.isBlank()) {
                return ResponseEntity.ok(ApiResponse.error("factoryId 必填"));
            }
            boolean ok = dataSourceService.delete(fid, id);
            return ResponseEntity.ok(ok
                    ? ApiResponse.success("删除成功", null)
                    : ApiResponse.error("未找到数据源"));
        } catch (Exception e) {
            log.error("删除数据源失败: {}", e.getMessage(), e);
            return ResponseEntity.ok(ApiResponse.error("删除失败: " + ErrorSanitizer.sanitize(e)));
        }
    }
}
