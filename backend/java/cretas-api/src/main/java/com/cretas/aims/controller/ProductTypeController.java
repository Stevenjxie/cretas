package com.cretas.aims.controller;

import com.cretas.aims.ai.tool.impl.workprocess.ProductWorkProcessRecommendTool;
import com.cretas.aims.dto.common.ApiResponse;
import com.cretas.aims.annotation.RequirePermission;
import com.cretas.aims.dto.common.PageRequest;
import com.cretas.aims.dto.common.PageResponse;
import com.cretas.aims.dto.producttype.ProductTypeDTO;
import com.cretas.aims.entity.ProductType;
import com.cretas.aims.service.MobileService;
import com.cretas.aims.service.ProductTypeService;
import com.cretas.aims.service.SkuAssemblyService;
import com.cretas.aims.utils.TokenUtils;
import io.swagger.v3.oas.annotations.tags.Tag;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import com.cretas.aims.entity.enums.ProcessingStageType;
import com.cretas.aims.exception.BusinessException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotEmpty;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 产品类型控制器
 *
 * @author Cretas Team
 * @version 1.0.0
 * @since 2025-01-09
 */
@Slf4j
@RestController
@RequestMapping("/api/mobile/{factoryId}/product-types")
@RequiredArgsConstructor
@Tag(name = "产品类型管理", description = "产品类型管理相关接口，包括产品类型的创建、查询、更新、删除，按类别筛选、关键字搜索、批量状态更新、编码唯一性校验、默认类型初始化，以及SKU调度配置（工时、加工步骤、技能要求、设备关联）等功能")
@Validated
public class ProductTypeController {

    private final ProductTypeService productTypeService;
    private final MobileService mobileService;
    private final SkuAssemblyService skuAssemblyService;
    private final ProductWorkProcessRecommendTool productWorkProcessRecommendTool;

    /**
     * SKU组装: 产品模板 + 客户 + 配方 → 独立SKU
     */
    @RequirePermission({"production:read_write", "rd:read_write"})
    @PostMapping("/assemble-sku")
    @Operation(summary = "组装SKU", description = "从产品模板+客户+配方创建SKU")
    public ApiResponse<ProductType> assembleSku(
            @PathVariable String factoryId,
            @RequestBody Map<String, Object> body,
            @RequestHeader("Authorization") String authorization) {
        String token = com.cretas.aims.utils.TokenUtils.extractToken(authorization);
        Long userId = mobileService.getUserFromToken(token).getId();

        String templateId = (String) body.get("templateId");
        String customerId = (String) body.get("customerId");
        String recipeVersion = (String) body.getOrDefault("recipeVersion", "default");
        @SuppressWarnings("unchecked")
        Map<String, Map<String, Object>> modifications =
                (Map<String, Map<String, Object>>) body.get("recipeModifications");

        log.info("组装SKU: factory={}, template={}, customer={}", factoryId, templateId, customerId);
        ProductType sku = skuAssemblyService.assemblesku(
                factoryId, templateId, customerId, recipeVersion, modifications, userId);
        return ApiResponse.success("SKU创建成功: " + sku.getCode(), sku);
    }

    /**
     * 查询产品模板列表 (非SKU)
     */
    @GetMapping("/templates")
    @Operation(summary = "获取产品模板列表")
    public ApiResponse<List<ProductType>> getTemplates(@PathVariable String factoryId) {
        return ApiResponse.success("查询成功", skuAssemblyService.getTemplates(factoryId));
    }

    /**
     * 查询模板下的SKU列表
     */
    @GetMapping("/templates/{templateId}/skus")
    @Operation(summary = "获取模板下的SKU")
    public ApiResponse<List<ProductType>> getSkusByTemplate(
            @PathVariable String factoryId, @PathVariable String templateId) {
        return ApiResponse.success("查询成功", skuAssemblyService.getSkusByTemplate(factoryId, templateId));
    }

    /**
     * 创建产品类型
     */
    @RequirePermission({"production:read_write", "rd:read_write"})
    @PostMapping
    @Operation(summary = "创建产品类型", description = "创建新的产品类型")
    public ApiResponse<ProductTypeDTO> createProductType(
            @PathVariable @Parameter(description = "工厂ID") String factoryId,
            @RequestBody @Valid @Parameter(description = "产品类型信息") ProductTypeDTO dto,
            @RequestHeader("Authorization") String authorization) {
        log.info("创建产品类型: factoryId={}, code={}", factoryId, dto.getCode());
        // 获取当前用户ID
        String token = TokenUtils.extractToken(authorization);
        Long userId = mobileService.getUserFromToken(token).getId();
        dto.setCreatedBy(userId.longValue());
        ProductTypeDTO result = productTypeService.createProductType(factoryId, dto);
        return ApiResponse.success(result);
    }

    /**
     * 更新产品类型
     */
    @RequirePermission({"production:read_write", "rd:read_write"})
    @PutMapping("/{id}")
    @Operation(summary = "更新产品类型", description = "更新产品类型信息")
    public ApiResponse<ProductTypeDTO> updateProductType(
            @PathVariable @Parameter(description = "工厂ID") String factoryId,
            @PathVariable @Parameter(description = "产品类型ID") String id,
            @RequestBody @Valid @Parameter(description = "产品类型信息") ProductTypeDTO dto) {
        log.info("更新产品类型: factoryId={}, id={}", factoryId, id);
        ProductTypeDTO result = productTypeService.updateProductType(factoryId, id, dto);
        return ApiResponse.success(result);
    }

    /**
     * 删除产品类型
     */
    @RequirePermission({"production:read_write", "rd:read_write"})
    @DeleteMapping("/{id}")
    @Operation(summary = "删除产品类型", description = "删除指定的产品类型")
    public ApiResponse<Void> deleteProductType(
            @PathVariable @Parameter(description = "工厂ID") String factoryId,
            @PathVariable @Parameter(description = "产品类型ID") String id) {
        log.info("删除产品类型: factoryId={}, id={}", factoryId, id);
        productTypeService.deleteProductType(factoryId, id);
        return ApiResponse.success();
    }

    /**
     * 获取产品类型详情
     */
    @GetMapping("/{id}")
    @Operation(summary = "获取产品类型详情", description = "根据ID获取产品类型详细信息")
    public ApiResponse<ProductTypeDTO> getProductTypeById(
            @PathVariable @Parameter(description = "工厂ID") String factoryId,
            @PathVariable @Parameter(description = "产品类型ID") String id) {
        log.info("获取产品类型详情: factoryId={}, id={}", factoryId, id);
        ProductTypeDTO result = productTypeService.getProductTypeById(factoryId, id);
        return ApiResponse.success(result);
    }

    @GetMapping("/{id}/work-process-recommendation")
    @Operation(summary = "推荐产品工序链", description = "基于同产品大类历史工序链推荐草稿；无历史时使用 LLM 冷启动建议，不自动写库")
    public ApiResponse<ProductWorkProcessRecommendTool.RecommendationResult> recommendWorkProcesses(
            @PathVariable @Parameter(description = "工厂ID") String factoryId,
            @PathVariable @Parameter(description = "产品类型ID") String id,
            @RequestParam(defaultValue = "5") @Parameter(description = "最多推荐几道工序") Integer limit) {
        ProductWorkProcessRecommendTool.RecommendationResult result =
                productWorkProcessRecommendTool.recommend(factoryId, id, limit == null ? 5 : limit);
        String message = result.recommendations().isEmpty()
                ? result.message()
                : String.format("AI 已推荐 %d 道工序，去查看前请核对", result.recommendations().size());
        return ApiResponse.success(message, result);
    }

    /**
     * 获取产品类型列表
     *
     * V3 P0-2 修复 (Apr 7) — 加 productCategory 参数支持按大类隔离查询.
     * 客户原话 (会议 1503-1510s): "选成品但能看到原料" — 此前后端 Service
     * 完全忽略前端传的 productCategory 参数, 4 个 tab 共享同一份数据.
     */
    @GetMapping
    @Operation(summary = "获取产品类型列表", description = "分页获取产品类型列表, 支持按大类和关键词过滤")
    public ApiResponse<PageResponse<ProductTypeDTO>> getProductTypes(
            @PathVariable @Parameter(description = "工厂ID") String factoryId,
            @RequestParam(required = false) @Parameter(description = "产品大类: FINISHED_PRODUCT/RAW_MATERIAL/PACKAGING/SEASONING/CUSTOMER_MATERIAL") String productCategory,
            @RequestParam(required = false) @Parameter(description = "关键词 (搜索 name/code)") String keyword,
            @RequestParam(defaultValue = "1") @Min(value = 1, message = "页码必须大于0") @Parameter(description = "页码 (1-indexed)") Integer page,
            @RequestParam(defaultValue = "20") @Min(value = 1, message = "每页大小必须大于0") @Parameter(description = "每页大小") Integer size) {
        log.info("获取产品类型列表: factoryId={}, productCategory={}, keyword={}, page={}, size={}",
                factoryId, productCategory, keyword, page, size);
        PageRequest pageRequest = new PageRequest();
        pageRequest.setPage(page);
        pageRequest.setSize(size);
        PageResponse<ProductTypeDTO> result = productTypeService.getProductTypes(
                factoryId, productCategory, keyword, pageRequest);
        return ApiResponse.success(result);
    }

    /**
     * 获取激活的产品类型
     */
    @GetMapping("/active")
    @Operation(summary = "获取激活的产品类型", description = "获取所有激活状态的产品类型")
    public ApiResponse<List<ProductTypeDTO>> getActiveProductTypes(
            @PathVariable @Parameter(description = "工厂ID") String factoryId) {
        log.info("获取激活的产品类型: factoryId={}", factoryId);
        List<ProductTypeDTO> result = productTypeService.getActiveProductTypes(factoryId);
        return ApiResponse.success(result);
    }

    /**
     * 根据类别获取产品类型
     */
    @GetMapping("/category/{category}")
    @Operation(summary = "根据类别获取产品类型", description = "获取指定类别的产品类型")
    public ApiResponse<List<ProductTypeDTO>> getProductTypesByCategory(
            @PathVariable @Parameter(description = "工厂ID") String factoryId,
            @PathVariable @Parameter(description = "产品类别") String category) {
        log.info("根据类别获取产品类型: factoryId={}, category={}", factoryId, category);
        List<ProductTypeDTO> result = productTypeService.getProductTypesByCategory(factoryId, category);
        return ApiResponse.success(result);
    }

    /**
     * 搜索产品类型
     */
    @GetMapping("/search")
    @Operation(summary = "搜索产品类型", description = "根据关键字搜索产品类型")
    public ApiResponse<PageResponse<ProductTypeDTO>> searchProductTypes(
            @PathVariable @Parameter(description = "工厂ID") String factoryId,
            @RequestParam @Parameter(description = "搜索关键字") String keyword,
            @RequestParam(defaultValue = "1") @Min(value = 1, message = "页码必须大于0") @Parameter(description = "页码 (1-indexed)") Integer page,
            @RequestParam(defaultValue = "20") @Min(value = 1, message = "每页大小必须大于0") @Parameter(description = "每页大小") Integer size) {
        log.info("搜索产品类型: factoryId={}, keyword={}", factoryId, keyword);
        PageRequest pageRequest = new PageRequest();
        pageRequest.setPage(page);
        pageRequest.setSize(size);
        PageResponse<ProductTypeDTO> result = productTypeService.searchProductTypes(factoryId, keyword, pageRequest);
        return ApiResponse.success(result);
    }

    /**
     * 获取产品类别列表
     */
    @GetMapping("/categories")
    @Operation(summary = "获取产品类别列表", description = "获取所有产品类别")
    public ApiResponse<List<String>> getProductCategories(
            @PathVariable @Parameter(description = "工厂ID") String factoryId) {
        log.info("获取产品类别列表: factoryId={}", factoryId);
        List<String> result = productTypeService.getProductCategories(factoryId);
        return ApiResponse.success(result);
    }

    /**
     * 批量更新状态
     */
    @RequirePermission({"production:read_write", "rd:read_write"})
    @PutMapping("/batch/status")
    @Operation(summary = "批量更新状态", description = "批量更新产品类型的激活状态")
    public ApiResponse<Void> updateProductTypesStatus(
            @PathVariable @Parameter(description = "工厂ID") String factoryId,
            @RequestBody @NotEmpty @Parameter(description = "产品类型ID列表") List<String> ids,
            @RequestParam @Parameter(description = "激活状态") Boolean isActive) {
        log.info("批量更新产品类型状态: factoryId={}, ids={}, isActive={}", factoryId, ids, isActive);
        productTypeService.updateProductTypesStatus(factoryId, ids, isActive);
        return ApiResponse.success();
    }

    /**
     * 检查产品编码是否存在
     * 兼容两种参数名：code 或 productCode
     */
    @GetMapping("/check-code")
    @Operation(summary = "检查产品编码", description = "检查产品编码是否已存在")
    public ApiResponse<Boolean> checkCodeExists(
            @PathVariable @Parameter(description = "工厂ID") String factoryId,
            @RequestParam(required = false) @Parameter(description = "产品编码") String code,
            @RequestParam(required = false) @Parameter(description = "产品编码 (前端兼容参数名)", hidden = true) String productCode,
            @RequestParam(required = false) @Parameter(description = "排除的产品ID") String excludeId) {

        // 兼容前端发送的 productCode 参数和后端的 code 参数
        String actualCode = code != null ? code : productCode;
        if (actualCode == null || actualCode.isBlank()) {
            throw new BusinessException(400, "产品编码参数不能为空 (code 或 productCode)");
        }

        log.info("检查产品编码: factoryId={}, code={}, excludeId={}", factoryId, actualCode, excludeId);
        boolean exists = productTypeService.checkCodeExists(factoryId, actualCode, excludeId);
        return ApiResponse.success(exists);
    }

    /**
     * T147 Fix2: 预览将自动生成的产品编号 (只读, 无副作用).
     * 前端新增表单在选定大类 + 客户后实时调用, 把「将生成」的编号展示在产品编号字段,
     * 复用 createProductType 的同一套生成逻辑 (前缀 + 客户首字母 + 4位序号).
     */
    @GetMapping("/preview-code")
    @Operation(summary = "预览产品编号", description = "按大类+客户预览将自动生成的产品编号, 不持久化")
    public ApiResponse<Map<String, String>> previewProductCode(
            @PathVariable @Parameter(description = "工厂ID") String factoryId,
            @RequestParam(required = false) @Parameter(description = "产品大类") String productCategory,
            @RequestParam(required = false) @Parameter(description = "客户ID (优先)") String customerId,
            @RequestParam(required = false) @Parameter(description = "客户名 (customerId 为空时后备)") String relatedCustomer) {
        log.info("预览产品编号: factoryId={}, productCategory={}, customerId={}", factoryId, productCategory, customerId);
        String code = productTypeService.previewGeneratedCode(factoryId, productCategory, customerId, relatedCustomer);
        Map<String, String> data = new HashMap<>();
        data.put("code", code);
        return ApiResponse.success(data);
    }

    /**
     * 初始化默认产品类型
     */
    @RequirePermission({"production:read_write", "rd:read_write"})
    @PostMapping("/init-defaults")
    @Operation(summary = "初始化默认产品类型", description = "为工厂初始化默认的产品类型")
    public ApiResponse<Void> initializeDefaultProductTypes(
            @PathVariable @Parameter(description = "工厂ID") String factoryId) {
        log.info("初始化默认产品类型: factoryId={}", factoryId);
        productTypeService.initializeDefaultProductTypes(factoryId);
        return ApiResponse.success();
    }

    // ==================== Phase 5: SKU Configuration Endpoints ====================

    /**
     * 获取所有可用的加工环节类型
     * 用于前端下拉选择
     */
    @GetMapping("/processing-stages")
    @Operation(summary = "获取加工环节类型列表", description = "获取所有可用的加工环节类型，用于配置产品加工步骤")
    public ApiResponse<List<Map<String, String>>> getProcessingStages(
            @PathVariable @Parameter(description = "工厂ID") String factoryId) {
        log.info("获取加工环节类型列表: factoryId={}", factoryId);

        List<Map<String, String>> stages = Arrays.stream(ProcessingStageType.values())
                .map(stage -> {
                    Map<String, String> map = new HashMap<>();
                    map.put("value", stage.name());
                    map.put("label", stage.getName());
                    map.put("description", stage.getDescription());
                    return map;
                })
                .collect(Collectors.toList());

        return ApiResponse.success(stages);
    }

    /**
     * 更新产品类型的 SKU 配置
     * 仅更新调度相关字段：workHours, processingSteps, skillRequirements, equipmentIds, qualityCheckIds, complexityScore
     */
    @RequirePermission({"production:read_write", "rd:read_write"})
    @PutMapping("/{id}/config")
    @Operation(summary = "更新SKU配置", description = "更新产品类型的调度相关配置（工时、加工步骤、技能要求等）")
    public ApiResponse<ProductTypeDTO> updateProductTypeConfig(
            @PathVariable @Parameter(description = "工厂ID") String factoryId,
            @PathVariable @Parameter(description = "产品类型ID") String id,
            @RequestBody @Valid @Parameter(description = "SKU配置信息") ProductTypeDTO configDto) {
        log.info("更新SKU配置: factoryId={}, id={}", factoryId, id);

        // 获取现有产品类型
        ProductTypeDTO existing = productTypeService.getProductTypeById(factoryId, id);
        if (existing == null) {
            throw new BusinessException(404, "产品类型不存在");
        }

        // 仅更新 SKU 配置字段，保留其他字段不变
        existing.setWorkHours(configDto.getWorkHours());
        existing.setProcessingSteps(configDto.getProcessingSteps());
        existing.setSkillRequirements(configDto.getSkillRequirements());
        existing.setEquipmentIds(configDto.getEquipmentIds());
        existing.setQualityCheckIds(configDto.getQualityCheckIds());
        existing.setComplexityScore(configDto.getComplexityScore());

        ProductTypeDTO result = productTypeService.updateProductType(factoryId, id, existing);
        return ApiResponse.success(result);
    }

    /**
     * 获取产品类型的调度信息
     * 返回调度系统所需的关键字段，格式化为调度服务可用的结构
     */
    @GetMapping("/{id}/scheduling-info")
    @Operation(summary = "获取调度信息", description = "获取产品类型的调度相关信息，供调度系统使用")
    public ApiResponse<Map<String, Object>> getSchedulingInfo(
            @PathVariable @Parameter(description = "工厂ID") String factoryId,
            @PathVariable @Parameter(description = "产品类型ID") String id) {
        log.info("获取产品类型调度信息: factoryId={}, id={}", factoryId, id);

        ProductTypeDTO productType = productTypeService.getProductTypeById(factoryId, id);
        if (productType == null) {
            throw new BusinessException(404, "产品类型不存在");
        }

        // 构建调度系统所需的信息
        Map<String, Object> schedulingInfo = new HashMap<>();
        schedulingInfo.put("productTypeId", productType.getId());
        schedulingInfo.put("productCode", productType.getCode());
        schedulingInfo.put("productName", productType.getName());
        schedulingInfo.put("category", productType.getCategory());

        // 调度相关字段
        schedulingInfo.put("workHours", productType.getWorkHours());
        schedulingInfo.put("productionTimeMinutes", productType.getProductionTimeMinutes());
        schedulingInfo.put("complexityScore", productType.getComplexityScore());

        // 加工步骤
        schedulingInfo.put("processingSteps", productType.getProcessingSteps());
        schedulingInfo.put("stepCount", productType.getProcessingSteps() != null
                ? productType.getProcessingSteps().size() : 0);

        // 技能要求
        schedulingInfo.put("skillRequirements", productType.getSkillRequirements());

        // 资源关联
        schedulingInfo.put("equipmentIds", productType.getEquipmentIds());
        schedulingInfo.put("qualityCheckIds", productType.getQualityCheckIds());

        return ApiResponse.success(schedulingInfo);
    }

    /**
     * 批量获取产品类型的调度信息
     * 用于调度系统一次性加载多个产品类型
     */
    @RequirePermission({"production:read_write", "rd:read_write"})
    @PostMapping("/scheduling-info/batch")
    @Operation(summary = "批量获取调度信息", description = "批量获取多个产品类型的调度相关信息")
    public ApiResponse<List<Map<String, Object>>> getSchedulingInfoBatch(
            @PathVariable @Parameter(description = "工厂ID") String factoryId,
            @RequestBody @NotEmpty @Parameter(description = "产品类型ID列表") List<String> productTypeIds) {
        log.info("批量获取产品类型调度信息: factoryId={}, count={}", factoryId, productTypeIds.size());

        List<Map<String, Object>> result = productTypeIds.stream()
                .map(id -> {
                    ProductTypeDTO productType = productTypeService.getProductTypeById(factoryId, id);
                    if (productType == null) {
                        Map<String, Object> notFound = new HashMap<>();
                        notFound.put("productTypeId", id);
                        notFound.put("error", "产品类型不存在");
                        return notFound;
                    }

                    Map<String, Object> info = new HashMap<>();
                    info.put("productTypeId", productType.getId());
                    info.put("productCode", productType.getCode());
                    info.put("productName", productType.getName());
                    info.put("workHours", productType.getWorkHours());
                    info.put("complexityScore", productType.getComplexityScore());
                    info.put("processingSteps", productType.getProcessingSteps());
                    info.put("skillRequirements", productType.getSkillRequirements());
                    info.put("equipmentIds", productType.getEquipmentIds());
                    return info;
                })
                .collect(Collectors.toList());

        return ApiResponse.success(result);
    }
}