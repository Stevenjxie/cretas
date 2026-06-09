package com.cretas.aims.controller;

import com.cretas.aims.annotation.RequirePermission;
import com.cretas.aims.dto.common.ApiResponse;
import com.cretas.aims.dto.common.PageRequest;
import com.cretas.aims.dto.common.PageResponse;
import com.cretas.aims.dto.material.*;
import com.cretas.aims.entity.enums.MaterialBatchStatus;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.security.PriceMaskResolver;
import com.cretas.aims.service.MaterialBatchService;
import com.cretas.aims.service.MobileService;
import com.cretas.aims.utils.TokenUtils;
import io.swagger.v3.oas.annotations.tags.Tag;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.*;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import com.cretas.aims.annotation.RequireModule;

/**
 * 原材料批次管理控制器
 *
 * <p>本控制器负责处理原材料批次相关的所有HTTP请求，提供完整的批次管理功能。</p>
 *
 * <h3>功能说明</h3>
 * <p>原材料批次管理是生产管理系统的核心模块之一，负责管理原材料的入库、出库、库存、过期处理等全生命周期。</p>
 *
 * <h3>API路径</h3>
 * <p>基础路径: <code>/api/mobile/{factoryId}/material-batches</code></p>
 *
 * <h3>提供的API端点 (共26个接口)</h3>
 * <ol>
 *   <li><b>GET</b>    /material-batches                          - 获取原材料批次列表（分页，支持关键词搜索）</li>
 *   <li><b>POST</b>   /material-batches                          - 创建原材料批次（入库）</li>
 *   <li><b>POST</b>   /material-batches/batch                    - 批量创建材料批次</li>
 *   <li><b>GET</b>    /material-batches/{batchId}                - 获取原材料批次详情</li>
 *   <li><b>PUT</b>    /material-batches/{batchId}                - 更新原材料批次</li>
 *   <li><b>DELETE</b> /material-batches/{batchId}                - 删除原材料批次</li>
 *   <li><b>GET</b>    /material-batches/material-type/{materialTypeId} - 按材料类型获取批次</li>
 *   <li><b>GET</b>    /material-batches/status/{status}          - 按状态获取批次</li>
 *   <li><b>GET</b>    /material-batches/fifo/{materialTypeId}    - 获取FIFO批次（先进先出）</li>
 *   <li><b>GET</b>    /material-batches/expiring                 - 获取即将过期的批次</li>
 *   <li><b>GET</b>    /material-batches/expired                  - 获取已过期的批次</li>
 *   <li><b>GET</b>    /material-batches/inventory/statistics     - 获取库存统计</li>
 *   <li><b>GET</b>    /material-batches/inventory/valuation      - 获取库存价值</li>
 *   <li><b>GET</b>    /material-batches/inventory/alerts         - 获取库存预警（综合）⭐新增</li>
 *   <li><b>GET</b>    /material-batches/low-stock                - 获取低库存警告</li>
 *   <li><b>POST</b>   /material-batches/{batchId}/use            - 使用批次材料</li>
 *   <li><b>POST</b>   /material-batches/{batchId}/adjust         - 调整批次数量</li>
 *   <li><b>PUT</b>    /material-batches/{batchId}/status         - 更新批次状态</li>
 *   <li><b>POST</b>   /material-batches/{batchId}/reserve        - 预留批次材料</li>
 *   <li><b>POST</b>   /material-batches/{batchId}/release        - 释放预留材料</li>
 *   <li><b>POST</b>   /material-batches/{batchId}/consume        - 消耗批次材料</li>
 *   <li><b>GET</b>    /material-batches/{batchId}/usage-history  - 获取批次使用历史</li>
 *   <li><b>POST</b>   /material-batches/handle-expired           - 处理过期批次</li>
 *   <li><b>POST</b>   /material-batches/{batchId}/convert-to-frozen - 将批次转为冻品</li>
 *   <li><b>POST</b>   /material-batches/{batchId}/undo-frozen    - 撤销转冻品操作</li>
 *   <li><b>GET</b>    /material-batches/export                   - 导出库存报表</li>
 * </ol>
 *
 * <h3>批次状态说明</h3>
 * <ul>
 *   <li><b>AVAILABLE</b>：可用 - 批次已入库，可以正常使用</li>
 *   <li><b>RESERVED</b>：已预留 - 批次已被生产计划预留，等待使用</li>
 *   <li><b>IN_USE</b>：使用中 - 批次正在被生产使用</li>
 *   <li><b>DEPLETED</b>：已耗尽 - 批次数量已用完</li>
 *   <li><b>EXPIRED</b>：已过期 - 批次已超过保质期</li>
 * </ul>
 *
 * <h3>核心业务功能</h3>
 * <ul>
 *   <li><b>批次入库</b>：创建新的原材料批次，记录入库信息</li>
 *   <li><b>FIFO出库</b>：按照先进先出原则推荐出库批次</li>
 *   <li><b>批次预留</b>：为生产计划预留原材料</li>
 *   <li><b>批次使用</b>：记录原材料的使用情况</li>
 *   <li><b>过期管理</b>：自动检测和处理过期批次</li>
 *   <li><b>库存统计</b>：提供库存数量、价值等统计信息</li>
 * </ul>
 *
 * <h3>业务规则</h3>
 * <ul>
 *   <li><b>唯一性</b>：批次号（batchNumber）必须全局唯一</li>
 *   <li><b>数量管理</b>：可用数量 = 入库数量 - 已用数量 - 预留数量</li>
 *   <li><b>状态流转</b>：AVAILABLE -> RESERVED -> IN_USE -> DEPLETED</li>
 *   <li><b>过期处理</b>：系统自动检测过期批次并更新状态</li>
 *   <li><b>数据隔离</b>：所有操作基于工厂ID进行数据隔离</li>
 * </ul>
 *
 * @author Cretas Team
 * @version 1.0.0
 * @since 2025-01-09
 * @see MaterialBatchService 业务逻辑层
 * @see MaterialBatch 实体类
 * @see MaterialBatchRepository 数据访问层
 */
@Slf4j
@RestController
@RequestMapping("/api/mobile/{factoryId}/material-batches")
@RequiredArgsConstructor
@Tag(name = "原材料批次管理", description = "原材料批次管理相关接口")
public class MaterialBatchController {

    private final MaterialBatchService materialBatchService;
    private final MobileService mobileService;
    private final PriceMaskResolver priceMaskResolver;

    /**
     * 创建原材料批次（入库）
     *
     * <p>创建新的原材料批次，记录入库信息。批次号如果不提供会自动生成。</p>
     *
     * <h4>请求示例</h4>
     * <pre>
     * POST /api/mobile/F001/material-batches
     * Content-Type: application/json
     *
     * {
     *   "materialTypeId": "MT001",
     *   "batchNumber": "MAT-20251120-001",  // 可选，不填自动生成
     *   "quantity": 100,
     *   "unit": "kg",
     *   "supplierId": "SUP001",
     *   "purchasePrice": 50.00,
     *   "receiveDate": "2025-11-20",
     *   "expiryDate": "2025-12-20",
     *   "storageLocation": "A区-01货架",
     *   "qualityGrade": "A"
     * }
     * </pre>
     *
     * <h4>业务规则</h4>
     * <ul>
     *   <li>批次号在系统内必须唯一</li>
     *   <li>过期日期必须在接收日期之后</li>
     *   <li>数量必须大于0</li>
     *   <li>初始状态为 AVAILABLE（可用）</li>
     * </ul>
     *
     * @param factoryId 工厂ID
     * @param authorization JWT令牌
     * @param request 创建请求参数
     * @return 创建成功的批次信息
     */
    @RequirePermission({"warehouse:read_write", "inventory:read_write"})
    @RequireModule("warehouse")
    @PostMapping
    @Operation(summary = "创建原材料批次", description = "创建新的原材料批次，记录入库信息")
    @com.cretas.aims.annotation.Loggable(module = "MATERIAL_BATCH", action = "CREATE",
            entityType = "MaterialBatch")
    public ApiResponse<MaterialBatchDTO> createMaterialBatch(
            @Parameter(description = "工厂ID", required = true, example = "F001")
            @PathVariable @NotBlank String factoryId,
            @Parameter(description = "访问令牌", required = true, example = "Bearer eyJhbGciOiJIUzI1NiJ9...")
            @RequestHeader("Authorization") String authorization,
            @Valid @RequestBody CreateMaterialBatchRequest request) {

        // 获取当前用户ID
        String token = TokenUtils.extractToken(authorization);
        Long userId = mobileService.getUserFromToken(token).getId();

        log.info("创建原材料批次: factoryId={}, materialTypeId={}", factoryId, request.getMaterialTypeId());
        MaterialBatchDTO batch = materialBatchService.createMaterialBatch(factoryId, request, userId);
        return ApiResponse.success("原材料批次创建成功", batch);
    }

    /**
     * 更新原材料批次
     *
     * <p>更新指定批次的信息，如存储位置、质量等级等。</p>
     *
     * <h4>可更新字段</h4>
     * <ul>
     *   <li>storageLocation: 存储位置</li>
     *   <li>qualityGrade: 质量等级</li>
     *   <li>notes: 备注信息</li>
     *   <li>expiryDate: 过期日期（仅在特殊情况下调整）</li>
     * </ul>
     *
     * @param factoryId 工厂ID
     * @param batchId 批次ID
     * @param request 更新请求参数
     * @return 更新后的批次信息
     */
    @RequirePermission({"warehouse:read_write", "inventory:read_write"})
    @RequireModule("warehouse")
    @PutMapping("/{batchId}")
    @Operation(summary = "更新原材料批次", description = "更新指定批次的信息 — 允许 partial body (qa-prompt Rule 17.6)")
    @com.cretas.aims.annotation.Loggable(module = "MATERIAL_BATCH", action = "UPDATE",
            entityType = "MaterialBatch", entityIdParam = "batchId")
    public ApiResponse<MaterialBatchDTO> updateMaterialBatch(
            @Parameter(description = "工厂ID", required = true, example = "F001")
            @PathVariable @NotBlank String factoryId,
            @Parameter(description = "批次ID", required = true, example = "MB-2025-001")
            @PathVariable @NotBlank String batchId,
            @Valid @RequestBody UpdateMaterialBatchRequest request) {

        log.info("更新原材料批次: factoryId={}, batchId={}", factoryId, batchId);
        MaterialBatchDTO batch = materialBatchService.updateMaterialBatch(factoryId, batchId, request);
        return ApiResponse.success("原材料批次更新成功", batch);
    }

    /**
     * 删除原材料批次
     */
    @RequirePermission({"warehouse:read_write", "inventory:read_write"})
    @RequireModule("warehouse")
    @DeleteMapping("/{batchId}")
    @Operation(summary = "删除原材料批次")
    @com.cretas.aims.annotation.Loggable(module = "MATERIAL_BATCH", action = "DELETE",
            entityType = "MaterialBatch", entityIdParam = "batchId")
    public ApiResponse<Void> deleteMaterialBatch(
            @Parameter(description = "工厂ID", required = true, example = "F001")
            @PathVariable @NotBlank String factoryId,
            @Parameter(description = "批次ID", required = true, example = "MB-2025-001")
            @PathVariable @NotBlank String batchId) {

        log.info("删除原材料批次: factoryId={}, batchId={}", factoryId, batchId);
        materialBatchService.deleteMaterialBatch(factoryId, batchId);
        return ApiResponse.success("原材料批次删除成功", null);
    }

    /**
     * 获取原材料批次详情
     */
    @GetMapping("/{batchId}")
    @Operation(summary = "获取原材料批次详情")
    public ApiResponse<MaterialBatchDTO> getMaterialBatchById(
            @Parameter(description = "工厂ID", required = true, example = "F001")
            @PathVariable @NotBlank String factoryId,
            @Parameter(description = "批次ID", required = true, example = "MB-2025-001")
            @PathVariable @NotBlank String batchId) {

        MaterialBatchDTO batch = materialBatchService.getMaterialBatchById(factoryId, batchId);
        return ApiResponse.success(batch);
    }

    /**
     * 获取原材料批次列表（分页）
     *
     * <p>根据工厂ID获取原材料批次列表，支持分页、排序和关键词搜索。</p>
     *
     * <h4>功能说明</h4>
     * <ul>
     *   <li>分页查询：通过page和size参数控制分页</li>
     *   <li>排序功能：通过sortBy和sortDirection参数自定义排序</li>
     *   <li>关键词搜索：通过keyword参数搜索批次号或材料类型名称</li>
     * </ul>
     *
     * <h4>请求示例</h4>
     * <pre>
     * GET /api/mobile/F001/material-batches?page=1&size=20&sortBy=createdAt&sortDirection=DESC&keyword=MT001
     * </pre>
     *
     * <h4>参数说明</h4>
     * <ul>
     *   <li><code>factoryId</code> (路径参数, 必填): 工厂ID</li>
     *   <li><code>page</code> (查询参数, 可选, 默认1): 页码（从1开始）</li>
     *   <li><code>size</code> (查询参数, 可选, 默认20): 每页大小</li>
     *   <li><code>sortBy</code> (查询参数, 可选, 默认createdAt): 排序字段</li>
     *   <li><code>sortDirection</code> (查询参数, 可选, 默认DESC): 排序方向（ASC/DESC）</li>
     *   <li><code>keyword</code> (查询参数, 可选): 搜索关键词（批次号或材料类型名称）</li>
     * </ul>
     *
     * <h4>响应示例</h4>
     * <pre>
     * {
     *   "success": true,
     *   "code": 200,
     *   "message": "操作成功",
     *   "data": {
     *     "content": [...],  // 批次列表
     *     "totalElements": 100,
     *     "totalPages": 5,
     *     "currentPage": 1,
     *     "pageSize": 20
     *   }
     * }
     * </pre>
     *
     * @param factoryId 工厂ID（路径参数，必填）
     * @param pageRequest 分页请求对象（包含page、size、sortBy、sortDirection、keyword）
     * @return 分页的批次列表
     */
    @GetMapping
    @Operation(summary = "获取原材料批次列表（分页）",
               description = "支持分页、排序和关键词搜索（批次号或材料类型名称）")
    public ApiResponse<PageResponse<MaterialBatchDTO>> getMaterialBatchList(
            @Parameter(description = "工厂ID", required = true, example = "F001")
            @PathVariable @NotBlank String factoryId,
            @Valid PageRequest pageRequest) {

        log.debug("获取原材料批次列表: factoryId={}, page={}, size={}, keyword={}", 
                 factoryId, pageRequest.getPage(), pageRequest.getSize(), pageRequest.getKeyword());
        PageResponse<MaterialBatchDTO> response = materialBatchService.getMaterialBatchList(factoryId, pageRequest);
        return ApiResponse.success(response);
    }

    /**
     * 按材料类型获取批次
     */
    @GetMapping("/material-type/{materialTypeId}")
    @Operation(summary = "按材料类型获取批次")
    public ApiResponse<List<MaterialBatchDTO>> getMaterialBatchesByType(
            @Parameter(description = "工厂ID", required = true, example = "F001")
            @PathVariable @NotBlank String factoryId,
            @Parameter(description = "材料类型ID", required = true, example = "RMT-F001-001")
            @PathVariable @NotBlank String materialTypeId) {

        List<MaterialBatchDTO> batches = materialBatchService.getMaterialBatchesByType(factoryId, materialTypeId);
        return ApiResponse.success(batches);
    }

    /**
     * 按状态获取批次，可选 BOM 过滤 (防呆).
     *
     * <p>当传入 {@code productTypeId} 时，仅返回该产品 ACTIVE BOM 中包含的原料类型对应的批次。
     * 若产品无 ACTIVE BOM 或 BOM 无明细，回退返回全部批次（不阻断操作员）。
     */
    @GetMapping("/status/{status}")
    @Operation(summary = "按状态获取批次", description = "可选传 productTypeId 按 BOM 过滤 (防呆)")
    public ApiResponse<List<MaterialBatchDTO>> getMaterialBatchesByStatus(
            @Parameter(description = "工厂ID", required = true, example = "F001")
            @PathVariable @NotBlank String factoryId,
            @Parameter(description = "状态", required = true, example = "AVAILABLE")
            @PathVariable MaterialBatchStatus status,
            @Parameter(description = "产品类型ID (可选, 传入则按 BOM 原料过滤)", example = "PT-F001-001")
            @RequestParam(required = false) String productTypeId) {

        List<MaterialBatchDTO> batches = materialBatchService.getMaterialBatchesByStatus(
                factoryId, status, productTypeId);
        return ApiResponse.success(batches);
    }

    /**
     * 获取在制品 (WIP) 批次列表 (Sprint 4 Wave 2 M-WIP-1).
     *
     * <p>"在制品" 指 status = PRODUCING_RESERVED 的原材料批次 —
     * 即已被进行中的生产批次 (ProductionBatch.status IN_PROGRESS) 占用的物料。</p>
     *
     * <p>当前为工厂作用域。跨工厂全局视图 (admin scope) 由 {@link MaterialBatchService#getAllWipBatches()} 提供,
     * 后续接入独立 admin endpoint 暴露 (需 platform_super_admin 角色)。</p>
     */
    @GetMapping("/wip")
    @Operation(summary = "获取在制品 (WIP) 批次列表", description = "查询本工厂 PRODUCING_RESERVED 状态批次 (在制品)。")
    public ApiResponse<List<MaterialBatchDTO>> getWipBatches(
            @Parameter(description = "工厂ID", required = true, example = "F001")
            @PathVariable @NotBlank String factoryId) {

        List<MaterialBatchDTO> batches = materialBatchService.getWipBatches(factoryId);
        return ApiResponse.success(batches);
    }

    /**
     * 获取FIFO批次（先进先出）
     *
     * <p>根据先进先出原则获取指定材料类型的批次，用于生产消耗推荐。</p>
     *
     * <h4>FIFO算法说明</h4>
     * <ol>
     *   <li>查询指定材料类型的所有可用批次（状态为AVAILABLE或FROZEN）</li>
     *   <li>按接收日期升序排列（最早入库的优先）</li>
     *   <li>从第一个批次开始分配，直到满足需求数量</li>
     *   <li>返回需要使用的批次列表及每个批次的建议使用量</li>
     * </ol>
     *
     * <h4>请求示例</h4>
     * <pre>
     * GET /api/mobile/F001/material-batches/fifo/MT001?requiredQuantity=200
     * </pre>
     *
     * <h4>响应示例</h4>
     * <pre>
     * {
     *   "success": true,
     *   "data": [
     *     {"batchNumber": "MAT-001", "currentQuantity": 150, "toUseQuantity": 150},
     *     {"batchNumber": "MAT-002", "currentQuantity": 100, "toUseQuantity": 50}
     *   ]
     * }
     * </pre>
     *
     * @param factoryId 工厂ID
     * @param materialTypeId 材料类型ID
     * @param requiredQuantity 需求数量
     * @return FIFO推荐的批次列表
     */
    @GetMapping("/fifo/{materialTypeId}")
    @Operation(summary = "获取FIFO批次（先进先出）",
               description = "根据先进先出原则获取指定材料类型的批次，用于生产消耗推荐")
    public ApiResponse<List<MaterialBatchDTO>> getFIFOBatches(
            @Parameter(description = "工厂ID", required = true, example = "F001")
            @PathVariable @NotBlank String factoryId,
            @Parameter(description = "材料类型ID", required = true, example = "RMT-F001-001")
            @PathVariable @NotBlank String materialTypeId,
            @Parameter(description = "需求数量 (兼容: quantity 或 requiredQuantity)")
            @RequestParam(required = false) BigDecimal requiredQuantity,
            @Parameter(description = "需求数量 (前端兼容参数名)", hidden = true)
            @RequestParam(required = false) BigDecimal quantity) {

        // 兼容前端发送的 quantity 参数和后端的 requiredQuantity 参数
        BigDecimal actualQuantity = requiredQuantity != null ? requiredQuantity : quantity;
        if (actualQuantity == null) {
            throw new BusinessException(400, "需求数量参数不能为空 (quantity 或 requiredQuantity)").withHint("请填写需求数量").withHintTarget("quantity");
        }

        List<MaterialBatchDTO> batches = materialBatchService.getFIFOBatches(factoryId, materialTypeId, actualQuantity);
        return ApiResponse.success(batches);
    }

    /**
     * 获取FEFO批次（先到期先出，食品行业合规）
     */
    @GetMapping("/fefo/{materialTypeId}")
    @Operation(summary = "获取FEFO批次（先到期先出）",
               description = "根据先到期先出原则获取指定材料类型的批次，适用于食品行业保质期管理")
    public ApiResponse<List<MaterialBatchDTO>> getFEFOBatches(
            @Parameter(description = "工厂ID", required = true, example = "F001")
            @PathVariable @NotBlank String factoryId,
            @Parameter(description = "材料类型ID", required = true, example = "RMT-F001-001")
            @PathVariable @NotBlank String materialTypeId,
            @Parameter(description = "需求数量")
            @RequestParam(required = false) BigDecimal requiredQuantity,
            @Parameter(description = "需求数量 (前端兼容参数名)", hidden = true)
            @RequestParam(required = false) BigDecimal quantity) {

        BigDecimal actualQuantity = requiredQuantity != null ? requiredQuantity : quantity;
        if (actualQuantity == null) {
            throw new BusinessException(400, "需求数量参数不能为空 (quantity 或 requiredQuantity)").withHint("请填写需求数量").withHintTarget("quantity");
        }

        List<MaterialBatchDTO> batches = materialBatchService.getFEFOBatches(factoryId, materialTypeId, actualQuantity);
        return ApiResponse.success(batches);
    }

    /**
     * 获取即将过期的批次
     */
    @GetMapping("/expiring")
    @Operation(summary = "获取即将过期的批次")
    public ApiResponse<List<MaterialBatchDTO>> getExpiringBatches(
            @Parameter(description = "工厂ID", required = true, example = "F001")
            @PathVariable @NotBlank String factoryId,
            @Parameter(description = "天数", required = true, example = "3")
            @RequestParam(defaultValue = "3") Integer days) {

        List<MaterialBatchDTO> batches = materialBatchService.getExpiringBatches(factoryId, days);
        return ApiResponse.success(batches);
    }

    /**
     * 获取已过期的批次
     */
    @GetMapping("/expired")
    @Operation(summary = "获取已过期的批次")
    public ApiResponse<List<MaterialBatchDTO>> getExpiredBatches(
            @Parameter(description = "工厂ID", required = true, example = "F001")
            @PathVariable @NotBlank String factoryId) {

        List<MaterialBatchDTO> batches = materialBatchService.getExpiredBatches(factoryId);
        return ApiResponse.success(batches);
    }

    /**
     * 使用批次材料
     * 兼容两种参数格式：
     * 1. RequestBody: {"quantity": 100, "productionPlanId": "xxx"}
     * 2. URL Params: ?quantity=100&productionPlanId=xxx
     */
    @RequirePermission({"warehouse:read_write", "inventory:read_write"})
    @RequireModule("warehouse")
    @PostMapping("/{batchId}/use")
    @Operation(summary = "使用批次材料")
    public ApiResponse<MaterialBatchDTO> useBatchMaterial(
            @Parameter(description = "工厂ID", example = "F001")
            @PathVariable @NotBlank String factoryId,
            @Parameter(description = "批次ID", example = "MB-2025-001")
            @PathVariable @NotBlank String batchId,
            @Parameter(description = "使用数量 (URL参数)", hidden = true)
            @RequestParam(required = false) BigDecimal quantity,
            @Parameter(description = "生产计划ID (URL参数)", hidden = true)
            @RequestParam(required = false) String productionPlanId,
            @RequestBody(required = false) UseMaterialBatchRequest request) {

        // 兼容URL参数和RequestBody两种格式
        BigDecimal actualQuantity = quantity;
        String actualPlanId = productionPlanId;

        if (request != null) {
            if (request.getQuantity() != null) {
                actualQuantity = request.getQuantity();
            }
            if (request.getProductionPlanId() != null) {
                actualPlanId = request.getProductionPlanId();
            }
        }

        if (actualQuantity == null) {
            throw new BusinessException(400, "使用数量不能为空").withHint("请填写使用数量").withHintTarget("usedQuantity");
        }

        log.info("使用批次材料: factoryId={}, batchId={}, quantity={}", factoryId, batchId, actualQuantity);
        MaterialBatchDTO batch = materialBatchService.useBatchMaterial(factoryId, batchId, actualQuantity, actualPlanId);
        return ApiResponse.success("材料使用成功", batch);
    }

    /**
     * 调整批次数量
     * 支持两种参数传递方式：
     * 1. URL参数：newQuantity, reason（前端当前使用）
     * 2. RequestBody：quantity, reason, adjustmentType
     */
    @RequirePermission({"warehouse:read_write", "inventory:read_write"})
    @RequireModule("warehouse")
    @PostMapping("/{batchId}/adjust")
    @Operation(summary = "调整批次数量")
    public ApiResponse<MaterialBatchDTO> adjustBatchQuantity(
            @Parameter(description = "工厂ID", example = "F001")
            @PathVariable @NotBlank String factoryId,
            @Parameter(description = "批次ID", example = "MB-2025-001")
            @PathVariable @NotBlank String batchId,
            @Parameter(description = "访问令牌", example = "Bearer eyJhbGciOiJIUzI1NiJ9...")
            @RequestHeader("Authorization") String authorization,
            @Parameter(description = "新数量（URL参数，与RequestBody二选一）")
            @RequestParam(required = false) BigDecimal newQuantity,
            @Parameter(description = "调整原因（URL参数）")
            @RequestParam(required = false) String reason,
            @RequestBody(required = false) AdjustMaterialBatchRequest request) {

        // 获取当前用户ID
        String token = TokenUtils.extractToken(authorization);
        Long userId = mobileService.getUserFromToken(token).getId();

        // 优先使用URL参数，其次使用RequestBody
        BigDecimal actualQuantity = newQuantity;
        String actualReason = reason;

        if (actualQuantity == null && request != null) {
            actualQuantity = request.getQuantity();
        }
        if (actualReason == null && request != null) {
            actualReason = request.getReason();
        }

        if (actualQuantity == null) {
            throw new BusinessException(400, "调整数量不能为空").withHint("请填写调整数量").withHintTarget("adjustQuantity");
        }

        log.info("调整批次数量: factoryId={}, batchId={}, quantity={}, reason={}",
                factoryId, batchId, actualQuantity, actualReason);
        MaterialBatchDTO batch = materialBatchService.adjustBatchQuantity(factoryId, batchId, actualQuantity, actualReason, userId);
        return ApiResponse.success("批次数量调整成功", batch);
    }

    /**
     * 更新批次状态
     */
    @RequirePermission({"warehouse:read_write", "inventory:read_write"})
    @RequireModule("warehouse")
    @PutMapping("/{batchId}/status")
    @Operation(summary = "更新批次状态")
    public ApiResponse<MaterialBatchDTO> updateBatchStatus(
            @Parameter(description = "工厂ID", example = "F001")
            @PathVariable @NotBlank String factoryId,
            @Parameter(description = "批次ID", example = "MB-2025-001")
            @PathVariable @NotBlank String batchId,
            @Valid @RequestBody UpdateBatchStatusRequest request) {

        MaterialBatchStatus status = MaterialBatchStatus.valueOf(request.getStatus());
        log.info("更新批次状态: factoryId={}, batchId={}, status={}", factoryId, batchId, status);
        MaterialBatchDTO batch = materialBatchService.updateBatchStatus(factoryId, batchId, status);
        return ApiResponse.success("批次状态更新成功", batch);
    }

    /**
     * 预留批次材料
     * 兼容两种参数格式：
     * 1. RequestBody: {"quantity": 100, "planId": "xxx"}
     * 2. URL Params: ?quantity=100&productionPlanId=xxx
     */
    @RequirePermission({"warehouse:read_write", "inventory:read_write"})
    @RequireModule("warehouse")
    @PostMapping("/{batchId}/reserve")
    @Operation(summary = "预留批次材料")
    public ApiResponse<Void> reserveBatchMaterial(
            @Parameter(description = "工厂ID", example = "F001")
            @PathVariable @NotBlank String factoryId,
            @Parameter(description = "批次ID", example = "MB-2025-001")
            @PathVariable @NotBlank String batchId,
            @Parameter(description = "预留数量 (URL参数)", hidden = true)
            @RequestParam(required = false) BigDecimal quantity,
            @Parameter(description = "生产计划ID (URL参数)", hidden = true)
            @RequestParam(required = false) String productionPlanId,
            @RequestBody(required = false) ReserveMaterialBatchRequest request) {

        // 兼容URL参数和RequestBody两种格式
        BigDecimal actualQuantity = quantity;
        String actualPlanId = productionPlanId;

        if (request != null) {
            if (request.getQuantity() != null) {
                actualQuantity = request.getQuantity();
            }
            if (request.getPlanId() != null) {
                actualPlanId = request.getPlanId();
            } else if (request.getProductionBatchId() != null) {
                actualPlanId = request.getProductionBatchId();
            }
        }

        if (actualQuantity == null) {
            throw new BusinessException(400, "预留数量不能为空").withHint("请填写预留数量").withHintTarget("reservedQuantity");
        }

        log.info("预留批次材料: factoryId={}, batchId={}, quantity={}, productionPlanId={}",
                factoryId, batchId, actualQuantity, actualPlanId);
        materialBatchService.reserveBatchMaterial(factoryId, batchId, actualQuantity, actualPlanId);
        return ApiResponse.success("材料预留成功", null);
    }

    /**
     * 释放预留材料
     * 兼容两种参数格式：
     * 1. RequestBody: {"quantity": 100, "productionPlanId": "xxx"}
     * 2. URL Params: ?quantity=100&productionPlanId=xxx
     */
    @RequirePermission({"warehouse:read_write", "inventory:read_write"})
    @RequireModule("warehouse")
    @PostMapping("/{batchId}/release")
    @Operation(summary = "释放预留材料")
    public ApiResponse<Void> releaseBatchReservation(
            @Parameter(description = "工厂ID", example = "F001")
            @PathVariable @NotBlank String factoryId,
            @Parameter(description = "批次ID", example = "MB-2025-001")
            @PathVariable @NotBlank String batchId,
            @Parameter(description = "释放数量 (URL参数)", hidden = true)
            @RequestParam(required = false) BigDecimal quantity,
            @Parameter(description = "生产计划ID (URL参数)", hidden = true)
            @RequestParam(required = false) String productionPlanId,
            @RequestBody(required = false) ReleaseMaterialBatchRequest request) {

        // 兼容URL参数和RequestBody两种格式
        BigDecimal actualQuantity = quantity;
        String actualPlanId = productionPlanId;

        if (request != null) {
            if (request.getQuantity() != null) {
                actualQuantity = request.getQuantity();
            }
            if (request.getProductionPlanId() != null) {
                actualPlanId = request.getProductionPlanId();
            }
        }

        if (actualQuantity == null) {
            throw new BusinessException(400, "释放数量不能为空").withHint("请填写释放数量").withHintTarget("releaseQuantity");
        }

        log.info("释放预留材料: factoryId={}, batchId={}, quantity={}, productionPlanId={}",
                factoryId, batchId, actualQuantity, actualPlanId);
        materialBatchService.releaseBatchReservation(factoryId, batchId, actualQuantity, actualPlanId);
        return ApiResponse.success("预留释放成功", null);
    }

    /**
     * 消耗批次材料
     * 兼容两种参数格式：
     * 1. RequestBody: {"quantity": 100, "processId": "xxx"}
     * 2. URL Params: ?quantity=100&processId=xxx
     */
    @RequirePermission({"warehouse:read_write", "inventory:read_write"})
    @RequireModule("warehouse")
    @PostMapping("/{batchId}/consume")
    @Operation(summary = "消耗批次材料")
    public ApiResponse<Void> consumeBatchMaterial(
            @Parameter(description = "工厂ID", example = "F001")
            @PathVariable @NotBlank String factoryId,
            @Parameter(description = "批次ID", example = "MB-2025-001")
            @PathVariable @NotBlank String batchId,
            @Parameter(description = "消耗数量 (URL参数)", hidden = true)
            @RequestParam(required = false) BigDecimal quantity,
            @Parameter(description = "加工流程ID (URL参数)", hidden = true)
            @RequestParam(required = false) String processId,
            @RequestBody(required = false) ConsumeMaterialBatchRequest request) {

        // 兼容URL参数和RequestBody两种格式
        BigDecimal actualQuantity = quantity;
        String actualProcessId = processId;

        if (request != null) {
            if (request.getQuantity() != null) {
                actualQuantity = request.getQuantity();
            }
            if (request.getProcessId() != null) {
                actualProcessId = request.getProcessId();
            }
        }

        if (actualQuantity == null) {
            throw new BusinessException(400, "消耗数量不能为空").withHint("请填写消耗数量").withHintTarget("consumedQuantity");
        }

        log.info("消耗批次材料: factoryId={}, batchId={}, quantity={}, processId={}",
                factoryId, batchId, actualQuantity, actualProcessId);
        materialBatchService.consumeBatchMaterial(factoryId, batchId, actualQuantity, actualProcessId);
        return ApiResponse.success("材料消耗成功", null);
    }

    /**
     * 获取库存统计
     */
    @GetMapping("/inventory/statistics")
    @Operation(summary = "获取库存统计")
    public ApiResponse<Map<String, Object>> getInventoryStatistics(
            @Parameter(description = "工厂ID", required = true, example = "F001")
            @PathVariable @NotBlank String factoryId) {

        Map<String, Object> statistics = materialBatchService.getInventoryStatistics(factoryId);
        return ApiResponse.success(statistics);
    }

    /**
     * 获取库存价值
     *
     * <p>RBAC: 仅授予 {@code procurement:price:view} 权限的角色可访问 (factory_super_admin /
     * procurement_manager / finance_manager / sales_manager / dispatcher / restaurant_manager).
     * 仓库/质检/操作员等无该权限的角色返回 403. 原因: 响应体是裸 {@code BigDecimal} 聚合金额,
     * {@code PriceFieldResponseAdvice} 的字段级 strip 无 field path 可作用; 且 per-batch
     * {@code totalValue} 已对这些角色 strip, 允许其访问 SUM 聚合自相矛盾.
     * 参考 {@code EquipmentController.calculateDepreciatedValue} 同形态先例.
     * 决策记录: {@code docs/qa-audits/2026-05-12-e5-valuation-rbac-decision.md}.
     */
    @RequirePermission({"procurement:price:view"})
    @GetMapping("/inventory/valuation")
    @Operation(summary = "获取库存价值")
    public ApiResponse<BigDecimal> getInventoryValuation(
            @Parameter(description = "工厂ID", required = true, example = "F001")
            @PathVariable @NotBlank String factoryId) {

        BigDecimal valuation = materialBatchService.getInventoryValuation(factoryId);
        return ApiResponse.success(valuation);
    }

    /**
     * 获取低库存警告
     *
     * <p>获取库存低于最低阈值的原材料列表。</p>
     *
     * <h4>请求示例</h4>
     * <pre>
     * GET /api/mobile/F001/material-batches/low-stock
     * </pre>
     *
     * <h4>响应示例</h4>
     * <pre>
     * {
     *   "success": true,
     *   "code": 200,
     *   "data": [
     *     {
     *       "materialTypeId": "MT001",
     *       "materialTypeName": "三文鱼",
     *       "currentQuantity": 50,
     *       "minQuantity": 100,
     *       "unit": "kg",
     *       "severity": "HIGH"
     *     }
     *   ]
     * }
     * </pre>
     *
     * @param factoryId 工厂ID
     * @return 低库存预警列表
     */
    @GetMapping("/low-stock")
    @Operation(summary = "获取低库存警告", description = "获取库存低于最低阈值的原材料列表")
    public ApiResponse<List<Map<String, Object>>> getLowStockWarnings(
            @Parameter(description = "工厂ID", required = true, example = "F001")
            @PathVariable @NotBlank String factoryId) {

        log.info("获取低库存警告: factoryId={}", factoryId);
        List<Map<String, Object>> warnings = materialBatchService.getLowStockWarnings(factoryId);
        return ApiResponse.success(warnings);
    }

    /**
     * 获取库存预警（综合）
     *
     * <p>获取所有类型的库存预警，包括：低库存、即将过期、已过期。</p>
     *
     * <h4>预警类型说明</h4>
     * <ul>
     *   <li><b>LOW_STOCK</b>：低库存预警 - 库存数量低于最低阈值</li>
     *   <li><b>EXPIRING</b>：即将过期预警 - 批次将在指定天数内过期</li>
     *   <li><b>EXPIRED</b>：已过期预警 - 批次已超过保质期</li>
     * </ul>
     *
     * <h4>请求示例</h4>
     * <pre>
     * GET /api/mobile/F001/material-batches/inventory/alerts?days=7
     * </pre>
     *
     * <h4>响应示例</h4>
     * <pre>
     * {
     *   "success": true,
     *   "code": 200,
     *   "data": [
     *     {
     *       "alertType": "LOW_STOCK",
     *       "materialTypeName": "三文鱼",
     *       "batchNumber": null,
     *       "currentQuantity": 50,
     *       "minQuantity": 100,
     *       "expiryDate": null,
     *       "daysUntilExpiry": null,
     *       "severity": "HIGH"
     *     },
     *     {
     *       "alertType": "EXPIRING",
     *       "materialTypeName": "带鱼",
     *       "batchNumber": "MAT-20251120-001",
     *       "currentQuantity": 30,
     *       "minQuantity": null,
     *       "expiryDate": "2025-11-25",
     *       "daysUntilExpiry": 5,
     *       "severity": "MEDIUM"
     *     }
     *   ]
     * }
     * </pre>
     *
     * @param factoryId 工厂ID
     * @param days 即将过期的天数阈值（默认7天）
     * @return 库存预警列表
     */
    @GetMapping("/inventory/alerts")
    @Operation(summary = "获取库存预警（综合）",
               description = "获取所有类型的库存预警，包括低库存、即将过期、已过期")
    public ApiResponse<List<Map<String, Object>>> getInventoryAlerts(
            @Parameter(description = "工厂ID", required = true, example = "F001")
            @PathVariable @NotBlank String factoryId,
            @Parameter(description = "即将过期天数阈值", example = "7")
            @RequestParam(defaultValue = "7") Integer days) {

        log.info("获取库存预警: factoryId={}, days={}", factoryId, days);
        
        List<Map<String, Object>> alerts = new java.util.ArrayList<>();
        
        // 1. 获取低库存预警
        List<Map<String, Object>> lowStockWarnings = materialBatchService.getLowStockWarnings(factoryId);
        for (Map<String, Object> warning : lowStockWarnings) {
            Map<String, Object> alert = new java.util.HashMap<>(warning);
            alert.put("alertType", "LOW_STOCK");
            alert.put("severity", determineSeverity(warning));
            alerts.add(alert);
        }
        
        // 2. 获取即将过期的批次
        List<MaterialBatchDTO> expiringBatches = materialBatchService.getExpiringBatches(factoryId, days);
        for (MaterialBatchDTO batch : expiringBatches) {
            Map<String, Object> alert = new java.util.HashMap<>();
            alert.put("alertType", "EXPIRING");
            alert.put("batchId", batch.getId());
            alert.put("materialTypeName", batch.getMaterialName());
            alert.put("batchNumber", batch.getBatchNumber());
            alert.put("currentQuantity", batch.getCurrentQuantity());
            alert.put("expiryDate", batch.getExpireDate());
            alert.put("daysUntilExpiry", batch.getRemainingDays());
            alert.put("storageLocation", batch.getStorageLocation());
            alert.put("severity", batch.getRemainingDays() != null && batch.getRemainingDays() <= 3 ? "HIGH" : "MEDIUM");
            alerts.add(alert);
        }

        // 3. 获取已过期的批次
        List<MaterialBatchDTO> expiredBatches = materialBatchService.getExpiredBatches(factoryId);
        for (MaterialBatchDTO batch : expiredBatches) {
            Map<String, Object> alert = new java.util.HashMap<>();
            alert.put("alertType", "EXPIRED");
            alert.put("batchId", batch.getId());
            alert.put("materialTypeName", batch.getMaterialName());
            alert.put("batchNumber", batch.getBatchNumber());
            alert.put("currentQuantity", batch.getCurrentQuantity());
            alert.put("expiryDate", batch.getExpireDate());
            alert.put("daysUntilExpiry", batch.getRemainingDays());
            alert.put("storageLocation", batch.getStorageLocation());
            alert.put("severity", "HIGH");
            alerts.add(alert);
        }
        
        return ApiResponse.success(alerts);
    }
    
    /**
     * 根据预警信息确定严重程度
     */
    private String determineSeverity(Map<String, Object> warning) {
        Object currentQty = warning.get("currentQuantity");
        Object minQty = warning.get("minQuantity");
        
        if (currentQty instanceof Number && minQty instanceof Number) {
            double current = ((Number) currentQty).doubleValue();
            double min = ((Number) minQty).doubleValue();
            
            if (current <= 0) {
                return "HIGH";
            } else if (current < min * 0.5) {
                return "HIGH";
            } else if (current < min * 0.8) {
                return "MEDIUM";
            }
        }
        return "LOW";
    }

    /**
     * 临期三级预警（食品行业合规）
     * - URGENT (红): < 30天
     * - WARNING (橙): 30-60天
     * - NOTICE (绿): 60-90天
     * - 距保质期不足1/3时标记需单独存放
     */
    @GetMapping("/inventory/expiry-warnings")
    @Operation(summary = "临期三级预警", description = "食品行业临期管理：30/60/90天三级预警 + 单独存放标记")
    public ApiResponse<Map<String, Object>> getExpiryWarnings(
            @Parameter(description = "工厂ID", required = true, example = "F001")
            @PathVariable @NotBlank String factoryId) {

        log.info("临期三级预警查询: factoryId={}", factoryId);

        // 查询90天内到期的批次
        java.time.LocalDate warningDate = java.time.LocalDate.now().plusDays(90);
        List<MaterialBatchDTO> expiringBatches = materialBatchService.getExpiringBatches(factoryId, 90);

        int urgent = 0, warning = 0, notice = 0, separateStorageCount = 0;
        List<Map<String, Object>> items = new java.util.ArrayList<>();

        for (MaterialBatchDTO batch : expiringBatches) {
            Integer daysRemaining = batch.getRemainingDays();
            if (daysRemaining == null) continue;

            String tier;
            if (daysRemaining <= 30) {
                tier = "URGENT";
                urgent++;
            } else if (daysRemaining <= 60) {
                tier = "WARNING";
                warning++;
            } else {
                tier = "NOTICE";
                notice++;
            }

            // 检查是否需要单独存放：剩余保质期 < 总保质期的1/3
            // 用入库日期(receiptDate)近似生产日期来估算总保质期
            boolean separateStorage = false;
            if (batch.getReceiptDate() != null && batch.getExpireDate() != null) {
                long totalShelfDays = java.time.temporal.ChronoUnit.DAYS.between(
                        batch.getReceiptDate(), batch.getExpireDate());
                if (totalShelfDays > 0 && daysRemaining < totalShelfDays / 3) {
                    separateStorage = true;
                    separateStorageCount++;
                }
            }

            Map<String, Object> item = new java.util.LinkedHashMap<>();
            item.put("batchId", batch.getId());
            item.put("batchNumber", batch.getBatchNumber());
            item.put("materialTypeName", batch.getMaterialName());
            item.put("daysRemaining", daysRemaining);
            item.put("expireDate", batch.getExpireDate());
            item.put("tier", tier);
            item.put("currentQuantity", batch.getCurrentQuantity());
            item.put("unit", batch.getUnit());
            item.put("separateStorage", separateStorage);
            item.put("storageLocation", batch.getStorageLocation());
            items.add(item);
        }

        Map<String, Object> summary = new java.util.LinkedHashMap<>();
        summary.put("urgent", urgent);
        summary.put("warning", warning);
        summary.put("notice", notice);
        summary.put("separateStorage", separateStorageCount);

        Map<String, Object> result = new java.util.LinkedHashMap<>();
        result.put("summary", summary);
        result.put("items", items);

        return ApiResponse.success(result);
    }

    /**
     * 批量创建材料批次
     */
    @RequirePermission({"warehouse:read_write", "inventory:read_write"})
    @RequireModule("warehouse")
    @PostMapping("/batch")
    @Operation(summary = "批量创建材料批次")
    public ApiResponse<List<MaterialBatchDTO>> batchCreateMaterialBatches(
            @Parameter(description = "工厂ID", required = true, example = "F001")
            @PathVariable @NotBlank String factoryId,
            @Parameter(description = "访问令牌", required = true, example = "Bearer eyJhbGciOiJIUzI1NiJ9...")
            @RequestHeader("Authorization") String authorization,
            @Valid @RequestBody List<CreateMaterialBatchRequest> requests) {

        // 获取当前用户ID
        String token = TokenUtils.extractToken(authorization);
        Long userId = mobileService.getUserFromToken(token).getId();

        log.info("批量创建材料批次: factoryId={}, count={}", factoryId, requests.size());
        List<MaterialBatchDTO> batches = materialBatchService.batchCreateMaterialBatches(factoryId, requests, userId);
        return ApiResponse.success("批量创建成功", batches);
    }

    /**
     * 获取批次使用历史
     */
    @GetMapping("/{batchId}/usage-history")
    @Operation(summary = "获取批次使用历史")
    public ApiResponse<List<Map<String, Object>>> getBatchUsageHistory(
            @Parameter(description = "工厂ID", required = true, example = "F001")
            @PathVariable @NotBlank String factoryId,
            @Parameter(description = "批次ID", required = true, example = "MB-2025-001")
            @PathVariable @NotBlank String batchId) {

        List<Map<String, Object>> history = materialBatchService.getBatchUsageHistory(factoryId, batchId);
        return ApiResponse.success(history);
    }

    /**
     * 导出库存报表
     */
    @GetMapping("/export")
    @Operation(summary = "导出库存报表",
            description = "导出原材料批次库存报表为Excel文件。"
                    + "RBAC: 无 procurement:price:view 权限的角色, Excel 中 采购单价 / 库存价值 列显示 '—'.")
    public byte[] exportInventoryReport(
            @Parameter(description = "工厂ID", required = true, example = "F001")
            @PathVariable @NotBlank String factoryId,
            @Parameter(description = "开始日期", example = "2025-01-01")
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
            @Parameter(description = "结束日期", example = "2025-01-31")
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate,
            @RequestHeader("Authorization") String authorization) {

        // RBAC defense-in-depth (P0-C sweep, 2026-05-12): mirror PR #450 pattern.
        boolean maskPrice = priceMaskResolver.shouldMaskPrice(authorization);

        log.info("导出库存报表: factoryId={}, startDate={}, endDate={}, maskPrice={}",
                factoryId, startDate, endDate, maskPrice);
        return materialBatchService.exportInventoryReport(factoryId, startDate, endDate, maskPrice);
    }

    /**
     * 处理过期批次
     */
    @RequirePermission({"warehouse:read_write", "inventory:read_write"})
    @RequireModule("warehouse")
    @PostMapping("/handle-expired")
    @Operation(summary = "处理过期批次")
    public ApiResponse<Integer> handleExpiredBatches(
            @Parameter(description = "工厂ID", required = true, example = "F001")
            @PathVariable @NotBlank String factoryId) {

        log.info("处理过期批次: factoryId={}", factoryId);
        int count = materialBatchService.handleExpiredBatches(factoryId);
        return ApiResponse.success(String.format("已处理%d个过期批次", count), count);
    }

    /**
     * 转冻品 ⭐E2E测试已验证
     *
     * <p>将原材料批次从鲜品（FRESH/AVAILABLE）转换为冻品（FROZEN）状态。</p>
     *
     * <h4>业务规则</h4>
     * <ul>
     *   <li>批次当前状态必须是 FRESH 或 AVAILABLE</li>
     *   <li>转换后状态变为 FROZEN</li>
     *   <li>存储位置更新为新的冷冻库位置</li>
     *   <li>原存储位置记录到notes字段（用于撤销时恢复）</li>
     *   <li>转换后10分钟内可撤销</li>
     * </ul>
     *
     * <h4>请求示例</h4>
     * <pre>
     * POST /api/mobile/F001/material-batches/{batchId}/convert-to-frozen
     * Content-Type: application/json
     *
     * {
     *   "convertedBy": 1,
     *   "convertedDate": "2025-11-20",
     *   "storageLocation": "冷冻库-F区",
     *   "notes": "原料质量良好，转冻保存"
     * }
     * </pre>
     *
     * <h4>响应示例</h4>
     * <pre>
     * {
     *   "success": true,
     *   "code": 200,
     *   "message": "已成功转为冻品",
     *   "data": {
     *     "id": "batch-uuid",
     *     "batchNumber": "MAT-20251120-001",
     *     "status": "FROZEN",
     *     "storageLocation": "冷冻库-F区"
     *   }
     * }
     * </pre>
     *
     * @param factoryId 工厂ID
     * @param batchId 批次ID
     * @param request 转换请求参数（包含操作人、转换日期、新存储位置、备注）
     * @return 转换后的批次信息
     * @since 2025-11-20
     * @see #undoFrozen 撤销转冻品操作
     */
    @RequirePermission({"warehouse:read_write", "inventory:read_write"})
    @RequireModule("warehouse")
    @PostMapping("/{batchId}/convert-to-frozen")
    @Operation(summary = "将原材料批次转为冻品",
               description = "将鲜品批次转换为冻品，更新批次状态和存储条件。转换后10分钟内可撤销。")
    public ApiResponse<MaterialBatchDTO> convertToFrozen(
            @Parameter(description = "工厂ID", required = true, example = "F001")
            @PathVariable @NotBlank String factoryId,
            @Parameter(description = "批次ID", required = true, example = "MB-2025-001")
            @PathVariable @NotBlank String batchId,
            @Parameter(description = "转换请求参数", required = true)
            @RequestBody @Valid ConvertToFrozenRequest request) {

        log.info("转冻品: factoryId={}, batchId={}, convertedBy={}",
                factoryId, batchId, request.getConvertedBy());
        MaterialBatchDTO result = materialBatchService.convertToFrozen(factoryId, batchId, request);
        return ApiResponse.success("已成功转为冻品", result);
    }

    /**
     * 撤销转冻品操作 ⭐E2E测试已验证
     *
     * <p>撤销误操作的转冻品，将批次状态恢复为鲜品。</p>
     *
     * <h4>业务规则（10分钟时间窗口保护）</h4>
     * <ul>
     *   <li>批次当前状态必须是 FROZEN</li>
     *   <li>转换时间必须在10分钟内</li>
     *   <li>超过10分钟无法撤销，需联系管理员处理</li>
     *   <li>撤销后状态恢复为 FRESH/AVAILABLE</li>
     *   <li>存储位置恢复为原位置（从notes字段解析）</li>
     * </ul>
     *
     * <h4>错误场景</h4>
     * <ul>
     *   <li><b>400</b>: 批次状态不是FROZEN</li>
     *   <li><b>400</b>: 转换已超过10分钟</li>
     *   <li><b>400</b>: 无法解析转换时间</li>
     *   <li><b>404</b>: 批次不存在</li>
     * </ul>
     *
     * <h4>请求示例</h4>
     * <pre>
     * POST /api/mobile/F001/material-batches/{batchId}/undo-frozen
     * Content-Type: application/json
     *
     * {
     *   "operatorId": 1,
     *   "reason": "误操作，该批次应保持鲜品状态"
     * }
     * </pre>
     *
     * <h4>响应示例</h4>
     * <pre>
     * // 成功响应
     * {
     *   "success": true,
     *   "code": 200,
     *   "message": "已成功撤销转冻品操作",
     *   "data": {
     *     "status": "FRESH",
     *     "storageLocation": "A区-01货架"  // 恢复原位置
     *   }
     * }
     *
     * // 超时错误响应
     * {
     *   "success": false,
     *   "code": 400,
     *   "message": "转换已超过10分钟（已过15分钟），无法撤销"
     * }
     * </pre>
     *
     * @param factoryId 工厂ID
     * @param batchId 批次ID
     * @param request 撤销请求参数（包含操作人ID和撤销原因）
     * @return 撤销后的批次信息
     * @since 2025-11-20
     * @see #convertToFrozen 转冻品操作
     */
    @RequirePermission({"warehouse:read_write", "inventory:read_write"})
    @RequireModule("warehouse")
    @PostMapping("/{batchId}/undo-frozen")
    @Operation(summary = "撤销转冻品操作",
               description = "撤销误操作的转冻品，仅允许转换后10分钟内撤销。超时需联系管理员。")
    public ApiResponse<MaterialBatchDTO> undoFrozen(
            @Parameter(description = "工厂ID", required = true, example = "F001")
            @PathVariable @NotBlank String factoryId,
            @Parameter(description = "批次ID", required = true, example = "MB-2025-001")
            @PathVariable @NotBlank String batchId,
            @Parameter(description = "撤销请求参数", required = true)
            @RequestBody @Valid UndoFrozenRequest request) {

        log.info("撤销转冻品: factoryId={}, batchId={}, operatorId={}, reason={}",
                factoryId, batchId, request.getOperatorId(), request.getReason());
        MaterialBatchDTO result = materialBatchService.undoFrozen(factoryId, batchId, request);
        return ApiResponse.success("已成功撤销转冻品操作", result);
    }
}