package com.cretas.aims.service.impl;

import com.cretas.aims.dto.common.PageRequest;
import com.cretas.aims.dto.common.PageResponse;
import com.cretas.aims.dto.material.ConvertToFrozenRequest;
import com.cretas.aims.dto.material.UndoFrozenRequest;
import com.cretas.aims.dto.material.CreateMaterialBatchRequest;
import com.cretas.aims.dto.material.UpdateMaterialBatchRequest;
import com.cretas.aims.dto.material.MaterialBatchDTO;
import com.cretas.aims.dto.material.MaterialBatchExportDTO;
import com.cretas.aims.dto.material.MaterialStockSummaryDTO;
import com.cretas.aims.utils.ExcelUtil;
import com.cretas.aims.entity.MaterialBatch;
import com.cretas.aims.entity.MaterialBatchAdjustment;
import com.cretas.aims.entity.MaterialConsumption;
import com.cretas.aims.entity.ProductionPlanBatchUsage;
import com.cretas.aims.entity.RawMaterialType;
import com.cretas.aims.entity.User;
import com.cretas.aims.entity.enums.InboundType;
import com.cretas.aims.entity.enums.MaterialBatchStatus;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.exception.ResourceNotFoundException;
import com.cretas.aims.mapper.MaterialBatchMapper;
import com.cretas.aims.repository.MaterialBatchAdjustmentRepository;
import com.cretas.aims.repository.MaterialBatchRepository;
import com.cretas.aims.repository.MaterialConsumptionRepository;
import com.cretas.aims.repository.ProductionPlanBatchUsageRepository;
import com.cretas.aims.repository.RawMaterialTypeRepository;
import com.cretas.aims.repository.UserRepository;
import com.cretas.aims.repository.bom.BomRecipeItemRepository;
import com.cretas.aims.repository.bom.BomRecipeRepository;
import com.cretas.aims.entity.bom.BomRecipe;
import com.cretas.aims.repository.factory.FactoryMaterialRequisitionRepository;
import com.cretas.aims.repository.inventory.PurchaseReceiveRecordRepository;
import org.springframework.beans.factory.annotation.Autowired;
import com.cretas.aims.security.DataScopeContext;
import com.cretas.aims.security.DataScopeResolver;
import com.cretas.aims.service.FuturePlanMatchingService;
import com.cretas.aims.service.MaterialBatchService;
import com.cretas.aims.service.alerts.InventoryLowStockEventPublisher;
import com.cretas.aims.service.PermissionService;
import com.cretas.aims.annotation.DataScope;
import com.cretas.aims.service.rules.annotation.RuleEvaluate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Sort;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 原材料批次服务实现
 *
 * <p>本服务类负责原材料批次相关的所有业务逻辑处理，包括批次创建、更新、查询、FIFO出库、过期处理等核心功能。</p>
 *
 * <h3>核心功能模块</h3>
 * <ol>
 *   <li><b>批次管理</b>
 *     <ul>
 *       <li>创建批次：入库操作，自动生成批次号，计算到期日期</li>
 *       <li>更新批次：修改批次信息（仅限可用状态）</li>
 *       <li>删除批次：删除未使用的批次</li>
 *       <li>查询批次：支持多种条件查询和分页</li>
 *     </ul>
 *   </li>
 *   <li><b>库存管理</b>
 *     <ul>
 *       <li>FIFO出库：按先进先出原则推荐出库批次</li>
 *       <li>批次预留：为生产计划预留原材料</li>
 *       <li>批次使用：记录原材料使用，更新数量</li>
 *       <li>数量调整：调整批次数量（如损耗、盘点等）</li>
 *     </ul>
 *   </li>
 *   <li><b>过期管理</b>
 *     <ul>
 *       <li>过期检测：自动检测即将过期和已过期的批次</li>
 *       <li>过期处理：批量更新过期批次状态</li>
 *       <li>预警提醒：提供过期预警功能</li>
 *     </ul>
 *   </li>
 *   <li><b>统计分析</b>
 *     <ul>
 *       <li>库存统计：统计库存数量、价值等</li>
 *       <li>低库存预警：检测低于安全库存的材料</li>
 *       <li>使用历史：记录批次使用历史</li>
 *     </ul>
 *   </li>
 * </ol>
 *
 * <h3>业务规则</h3>
 * <ul>
 *   <li><b>批次号生成</b>：自动生成唯一批次号，格式：MT-YYYYMMDD-XXXX</li>
 *   <li><b>到期日期计算</b>：如果未提供，根据原材料类型的保质期自动计算</li>
 *   <li><b>数量管理</b>：可用数量 = 入库数量 - 已用数量 - 预留数量</li>
 *   <li><b>状态流转</b>：AVAILABLE -> RESERVED -> IN_USE -> DEPLETED</li>
 *   <li><b>FIFO原则</b>：出库时优先使用最早入库的批次</li>
 *   <li><b>权限控制</b>：所有操作都基于工厂ID进行数据隔离</li>
 * </ul>
 *
 * <h3>事务管理</h3>
 * <p>关键业务方法使用@Transactional注解，确保数据一致性：</p>
 * <ul>
 *   <li>创建、更新、删除操作：使用@Transactional确保原子性</li>
 *   <li>数量调整：使用@Transactional确保数量计算的准确性</li>
 *   <li>批量操作：使用@Transactional确保批量操作的一致性</li>
 * </ul>
 *
 * <h3>异常处理</h3>
 * <ul>
 *   <li><b>ResourceNotFoundException</b>：当查询的资源不存在时抛出</li>
 *   <li><b>BusinessException</b>：当业务规则不满足时抛出（如数量不足、状态不允许等）</li>
 *   <li><b>IllegalArgumentException</b>：当参数不合法时抛出</li>
 * </ul>
 *
 * @author Cretas Team
 * @version 1.0.0
 * @since 2025-01-09
 * @see MaterialBatchService 服务接口
 * @see MaterialBatchRepository 数据访问层
 * @see MaterialBatch 实体类
 */
@Service
public class MaterialBatchServiceImpl implements MaterialBatchService {
    private static final Logger log = LoggerFactory.getLogger(MaterialBatchServiceImpl.class);
    private static final String SOURCE_DOC_REQUIRED_MESSAGE =
            "创建批次必须指定来源单据(采购入库/退货/盘点),仓库无单不可建库存";
    private static final String SOURCE_DOC_REQUIRED_HINT =
            "请从采购入库、退货入库或盘点单发起；历史数据或批量迁移请走 LEGACY_IMPORT 并申请 inventory:legacy_import 授权";
    private static final String LEGACY_IMPORT_SOURCE_DOC_TYPE = "LEGACY_IMPORT";
    private static final String LEGACY_IMPORT_PERMISSION = "inventory:legacy_import";

    private final MaterialBatchRepository materialBatchRepository;
    private final MaterialBatchAdjustmentRepository materialBatchAdjustmentRepository;
    private final RawMaterialTypeRepository materialTypeRepository;
    private final MaterialBatchMapper materialBatchMapper;
    private final MaterialConsumptionRepository materialConsumptionRepository;
    private final ProductionPlanBatchUsageRepository productionPlanBatchUsageRepository;
    private final ExcelUtil excelUtil;
    private final FuturePlanMatchingService futurePlanMatchingService;

    @Autowired(required = false)
    private PurchaseReceiveRecordRepository purchaseReceiveRecordRepository;
    @Autowired(required = false)
    private FactoryMaterialRequisitionRepository factoryMaterialRequisitionRepository;
    @Autowired(required = false)
    private PermissionService permissionService;
    @Autowired(required = false)
    private UserRepository userRepository;
    /** Canvas V2: DB-driven validation rules */
    @Autowired(required = false)
    private com.cretas.aims.engine.ValidationRuleEvaluator validationRuleEvaluator;

    /** Round 9 Fix (R8-α Gap #3): Canvas dynamic field persistence. */
    @Autowired(required = false)
    private com.cretas.aims.engine.DynamicFieldService dynamicFieldService;

    /**
     * Round 10 Task 3 — 3rd hook of Canvas Integration Template for material_batch.
     * Publishes MaterialBatchCreatedEvent so factory-configured trigger chains on the
     * material_batch module can react to all batch sources (not just purchase receive).
     */
    @Autowired(required = false)
    private org.springframework.context.ApplicationEventPublisher applicationEventPublisher;

    /** D1 双仓流转 (2026-05-10 spec, PR #309 A1=A) — 入库默认 WH-LOG. */
    @Autowired
    private com.cretas.aims.service.factory.WarehouseResolver warehouseResolver;

    /**
     * SP7 §3.3 仓库库存守卫 (W1 红线 #03): 入库前校验仓库类型 ↔ 物料大类匹配.
     * 仓管员无库存自主权 — 不能往错类型仓库收货.
     * optional + fail-open: 守卫对 legacy (LOGISTICS/WORKSHOP/null) 仓库自动放行,
     * 仅对已设阶段语义类型 (RAW/WIP/FINISHED/SALTED) 的仓库强制约束,
     * 防止误拦 F006 现有 legacy 仓库入库.
     */
    @Autowired(required = false)
    private com.cretas.aims.service.factory.WarehouseInventoryGuardService warehouseInventoryGuardService;

    /** T159-B R4: 写入时维度单位校验 (optional — null → fail-open). */
    @Autowired(required = false)
    private com.cretas.aims.service.uom.MaterialUomConverter materialUomConverter;

    /** Sprint 6 W2-B: 数据权限解析器 (optional). */
    @Autowired(required = false)
    private DataScopeResolver dataScopeResolver;

    /** BOM 过滤 (领料批次防呆, T-BOM-FILTER): 查产品 ACTIVE BOM. */
    @Autowired
    private BomRecipeRepository bomRecipeRepository;

    /** BOM 过滤 (领料批次防呆, T-BOM-FILTER): 取 BOM 明细原料类型列表. */
    @Autowired
    private BomRecipeItemRepository bomRecipeItemRepository;

    /** C-074/C-075/X-10: 补录时效锁 — T-3 及更早拒绝 (optional, fail-open 向后兼容). */
    @Autowired(required = false)
    private com.cretas.aims.util.BackdateWindowValidator backdateWindowValidator;

    @Autowired
    private InventoryLowStockEventPublisher inventoryLowStockEventPublisher;

    // Manual constructor (Lombok @RequiredArgsConstructor not working)
    public MaterialBatchServiceImpl(
            MaterialBatchRepository materialBatchRepository,
            MaterialBatchAdjustmentRepository materialBatchAdjustmentRepository,
            RawMaterialTypeRepository materialTypeRepository,
            MaterialBatchMapper materialBatchMapper,
            MaterialConsumptionRepository materialConsumptionRepository,
            ProductionPlanBatchUsageRepository productionPlanBatchUsageRepository,
            ExcelUtil excelUtil,
            FuturePlanMatchingService futurePlanMatchingService) {
        this.materialBatchRepository = materialBatchRepository;
        this.materialBatchAdjustmentRepository = materialBatchAdjustmentRepository;
        this.materialTypeRepository = materialTypeRepository;
        this.materialBatchMapper = materialBatchMapper;
        this.materialConsumptionRepository = materialConsumptionRepository;
        this.productionPlanBatchUsageRepository = productionPlanBatchUsageRepository;
        this.excelUtil = excelUtil;
        this.futurePlanMatchingService = futurePlanMatchingService;
    }

    private void runConfiguredValidation(String factoryId, String operation, java.util.Map<String, Object> context) {
        if (validationRuleEvaluator == null) return;
        try {
            validationRuleEvaluator.validate(factoryId, "material_batch", operation, context);
        } catch (com.cretas.aims.exception.BusinessException e) {
            throw e;
        } catch (Exception e) {
            log.warn("Canvas validation non-blocking error: {}", e.getMessage());
        }
    }

    @Override
    @RuleEvaluate(value = "INVENTORY", target = "request")
    @Transactional
    public MaterialBatchDTO createMaterialBatch(String factoryId, CreateMaterialBatchRequest request, Long userId) {
        // C-074/C-075/X-10: 补录时效锁 — 入库日期不得早于 T-maxDays (默认 T-2)
        if (backdateWindowValidator != null) {
            backdateWindowValidator.assertWithinWindow(request.getReceiptDate(), "原料入库");
        }
        runConfiguredValidation(factoryId, "CREATE", java.util.Map.of(
            "quantity", request.getReceiptQuantity() != null ? request.getReceiptQuantity() : java.math.BigDecimal.ZERO,
            "materialTypeId", request.getMaterialTypeId() != null ? request.getMaterialTypeId() : "",
            "productionDate", request.getReceiptDate() != null ? request.getReceiptDate().toString() : ""));
        // P0-17: 入库必须有发起单校验
        validateSourceDoc(request, factoryId, userId);
        // 验证并获取原材料类型
        var materialType = materialTypeRepository.findById(request.getMaterialTypeId())
            .orElseThrow(() -> new ResourceNotFoundException("原材料类型不存在"));

        // T159-B R4: UoM dimension guard at 入库 (receipt) write time.
        checkInboundUnitCompatible(materialType, request.getQuantityUnit());

        // 创建批次
        MaterialBatch batch = materialBatchMapper.toEntity(request, factoryId, userId.longValue());
        // 生成UUID作为ID
        batch.setId(java.util.UUID.randomUUID().toString());
        if (LEGACY_IMPORT_SOURCE_DOC_TYPE.equals(request.getSourceDocType())) {
            batch.setInboundType(InboundType.LEGACY_IMPORT);
        }

        // D1 双仓流转 (PR #310 §5.5): 入库默认 WH-LOG (物流仓). DTO 显式传则用 DTO 值.
        if (batch.getWarehouseId() == null) {
            batch.setWarehouseId(warehouseResolver.resolveLogisticsId(factoryId));
        }

        // SP7 §3.3 (W1 红线 #03): 原料入库前校验目标仓库类型 — 原料只能入 RAW/SALTED/legacy 仓.
        // 守卫在 save 之前抛 422, 不污染事务. legacy/null 类型仓库自动放行.
        if (warehouseInventoryGuardService != null) {
            warehouseInventoryGuardService.assertCanReceive(batch.getWarehouseId(), factoryId, "RAW");
        }

        // 自动计算到期日期（如果未提供）
        if (batch.getExpireDate() == null && materialType.getShelfLifeDays() != null) {
            LocalDate expireDate = request.getReceiptDate().plusDays(materialType.getShelfLifeDays());
            batch.setExpireDate(expireDate);
            log.info("自动计算到期日期: receiptDate={}, shelfLifeDays={}, expireDate={}",
                request.getReceiptDate(), materialType.getShelfLifeDays(), expireDate);
        }

        // 生成唯一批次号
        String batchNumber = generateUniqueBatchNumber(batch.getBatchNumber());
        batch.setBatchNumber(batchNumber);
        batch = materialBatchRepository.save(batch);
        log.info("创建原材料批次成功: batchNumber={}", batch.getBatchNumber());

        // Round 9 Fix (R8-α Gap #3 per-module template): persist Canvas V3 dynamic fields.
        // Customer-configured fields like 农残检测结果, 供应商批次证明, 运输温度记录
        // now land in the cf_* columns of material_batches. Previously silently dropped.
        if (dynamicFieldService != null && request.getCustomFields() != null && !request.getCustomFields().isEmpty()) {
            try {
                dynamicFieldService.setDynamicFields(factoryId, "material_batch", batch.getId(), request.getCustomFields());
            } catch (Exception e) {
                log.warn("Canvas dynamic fields save failed for material batch {}: {}", batch.getId(), e.getMessage());
            }
        }

        // Round 10 Fix (R8-α Gap #1 template 3rd hook): publish MaterialBatchCreatedEvent so
        // factory-configured trigger chains on the material_batch module can react to all
        // batch sources (return/gain/manual), not just the purchase-receive path which
        // already emits MaterialReceivedEvent. Silent failure here must not break batch creation.
        if (applicationEventPublisher != null) {
            try {
                applicationEventPublisher.publishEvent(new com.cretas.aims.event.MaterialBatchCreatedEvent(
                        this, factoryId, batch.getId(), batch.getBatchNumber(),
                        batch.getMaterialTypeId(), batch.getReceiptQuantity(),
                        request.getSourceDocType(), request.getSourceDocId()));
            } catch (Exception e) {
                log.warn("Publish MaterialBatchCreatedEvent failed for batch {}: {}", batch.getId(), e.getMessage());
            }
        }

        // 更新物料类型移动平均价
        updateMovingAvgPrice(materialType, batch.getReceiptQuantity(), batch.getUnitPrice(), batch.getId());

        // 自动匹配到未来生产计划
        try {
            var matchResults = futurePlanMatchingService.matchBatchToFuturePlans(batch);
            if (!matchResults.isEmpty()) {
                log.info("批次 {} 自动匹配到 {} 个未来计划", batch.getBatchNumber(), matchResults.size());
            }
        } catch (Exception e) {
            // 匹配失败不影响批次创建，只记录日志
            log.warn("批次 {} 自动匹配未来计划失败: {}", batch.getBatchNumber(), e.getMessage());
        }

        return materialBatchMapper.toDTO(batch);
    }

    /**
     * P0-17: 入库必须关联有效的发起单
     * - sourceDocType == null: 向后兼容历史数据, warn log 但允许
     * - MANUAL_ADJUST: 必填 remark (notes)
     * - 其他类型: sourceDocId 必填, 且验证单据存在
     */
    /**
     * T159-B R4: 防呆 — 校验入库单位与原料主数据规范单位的计量维度.
     *
     * <p>如 {@code materialUomConverter} 未注入 (旧测试环境) → fail-open.
     * 如 {@code quantityUnit} 为空 → fail-open (兼容未填单位的存量数据).
     *
     * @param materialType  已加载的原料主数据
     * @param quantityUnit  入库请求中的单位
     */
    private void checkInboundUnitCompatible(RawMaterialType materialType, String quantityUnit) {
        if (materialUomConverter == null) return;   // fail-OPEN: converter not wired
        if (quantityUnit == null || quantityUnit.isBlank()) return;  // fail-OPEN: unit missing
        if (!materialUomConverter.isWriteUnitCompatible(materialType.getId(), quantityUnit)) {
            String materialName = materialType.getName() != null ? materialType.getName() : materialType.getId();
            String canonicalUnit = materialType.getUnit() != null ? materialType.getUnit() : "?";
            throw new BusinessException(409,
                    String.format("「%s」入库单位(%s)与原料主数据单位(%s)计量维度不符，" +
                                    "请改为同维度单位（如该原料按%s计量）",
                            materialName, quantityUnit, canonicalUnit, canonicalUnit))
                    .withHint(String.format("请将入库单位改为与「%s」主数据单位(%s)同维度的单位",
                            materialName, canonicalUnit))
                    .withSeverity("BLOCKING");
        }
    }

    private void validateSourceDoc(CreateMaterialBatchRequest request, String factoryId, Long userId) {
        String type = request.getSourceDocType();
        String id = request.getSourceDocId();

        if (type == null || type.isBlank()) {
            throw new BusinessException(400, SOURCE_DOC_REQUIRED_MESSAGE)
                    .withHint(SOURCE_DOC_REQUIRED_HINT)
                    .withHintTarget("sourceDocType")
                    .withSeverity("BLOCKING");
        }

        switch (type) {
            case "MANUAL_ADJUST":
                if (request.getNotes() == null || request.getNotes().isBlank()) {
                    throw new BusinessException(400, "手工调整入库必须填写备注作为凭证")
                            .withHint("请在备注栏填写调整原因/凭证号").withHintTarget("notes");
                }
                break;
            case "PURCHASE_RECEIVE":
                if (id == null || id.isBlank()) {
                    throw new BusinessException(400, "入库必须关联有效的发起单 (PURCHASE_RECEIVE sourceDocId 为空)")
                            .withHint("请选择关联的采购到货通知").withHintTarget("sourceDocId");
                }
                if (purchaseReceiveRecordRepository == null || !purchaseReceiveRecordRepository.existsById(id)) {
                    throw new BusinessException(404, "入库必须关联有效的发起单: 采购到货通知 " + id + " 不存在")
                            .withHint("请确认到货通知单号或重新选择");
                }
                break;
            case "MATERIAL_REQUISITION_RETURN":
                if (id == null || id.isBlank()) {
                    throw new BusinessException(400, "入库必须关联有效的发起单 (MATERIAL_REQUISITION_RETURN sourceDocId 为空)")
                            .withHint("请选择关联的领料退料单").withHintTarget("sourceDocId");
                }
                if (factoryMaterialRequisitionRepository == null || !factoryMaterialRequisitionRepository.existsById(id)) {
                    throw new BusinessException(404, "入库必须关联有效的发起单: 领料退料单 " + id + " 不存在")
                            .withHint("请确认领料退料单号或重新选择");
                }
                break;
            case "SALES_RETURN":
                if (id == null || id.isBlank()) {
                    throw new BusinessException(400, "入库必须关联有效的发起单 (SALES_RETURN sourceDocId 为空)")
                            .withHint("请选择关联的销售退货单").withHintTarget("sourceDocId");
                }
                // SalesReturn entity 暂未建, 仅记录引用, 不强校验存在性
                log.info("P0-17: 销售退货入库 sourceDocId={} (SalesReturn 单据校验暂 skip)", id);
                break;
            case "INVENTORY_GAIN":
                // B9 (客户原话 4850s): 仓库盘点产生的盘盈入库, 不需 sourceDocId, 但 notes 必填说明盘点单号/原因
                if (request.getNotes() == null || request.getNotes().isBlank()) {
                    throw new BusinessException(400, "盘盈入库必须在备注中说明盘点单号或原因")
                            .withHint("请在备注栏填写盘点单号/原因").withHintTarget("notes");
                }
                log.info("B9: 盘盈入库 notes={}", request.getNotes());
                break;
            case "FREE_GIFT":
                // B10 (客户原话 4929s): 供应商赠品入库, 不需 sourceDocId, 但 notes 必填说明来源供应商
                if (request.getNotes() == null || request.getNotes().isBlank()) {
                    throw new BusinessException(400, "赠品入库必须在备注中说明来源供应商")
                            .withHint("请在备注栏填写来源供应商").withHintTarget("notes");
                }
                log.info("B10: 赠品入库 notes={}", request.getNotes());
                break;
            // TODO 出库类型 (客户原话 4947s): INVENTORY_LOSS 盘亏出库 / INTERNAL_USE 领用出库
            //   出库链路不走 MaterialBatchService.createMaterialBatch (这里只管入库),
            //   需在 sales shipment / warehouse outbound service 另开分支, 本轮范围外.
            case LEGACY_IMPORT_SOURCE_DOC_TYPE:
                requireLegacyImportPermission(factoryId, userId);
                break;
            default:
                throw new BusinessException(400, "不支持的 sourceDocType: " + type)
                        .withHint("请使用支持的入库类型 (MANUAL_ADJUST/PURCHASE_RECEIVE/MATERIAL_REQUISITION_RETURN/SALES_RETURN/INVENTORY_GAIN/FREE_GIFT/LEGACY_IMPORT)");
        }
    }

    /**
     * W4 红线: 有库存自主权的角色 (可经 PUT 改入库量/单价/重量, 可删批次)。
     *
     * <p>张权 (F006 仓管员) 转录铁律: 纯操作员 (warehouse_worker / operator) 无库存自主权,
     * 库存变动必须走单据 + 审批。本集合是 allowlist (fail-closed): 不在集合内的角色一律拦截
     * 库存量变更与批次删除。库存量调整应走盘点 (Stocktake) / 调整 (MaterialBatchAdjustment) 审批流。
     *
     * <p>平台管理员单独 bypass (见 isPrivilegedInventoryRole), 与 RequireRoleInterceptor 策略一致。
     */
    private static final java.util.Set<String> INVENTORY_MUTATION_ROLES = java.util.Set.of(
            "factory_super_admin", "warehouse_manager");

    /** 平台级管理员角色, 始终放行 (与 JwtAuthInterceptor / RequireRoleInterceptor 一致)。 */
    private static final java.util.Set<String> PLATFORM_ADMIN_ROLES = java.util.Set.of(
            "platform_admin", "super_admin", "developer", "platform_super_admin");

    private void requireLegacyImportPermission(String factoryId, Long userId) {
        User user = userRepository != null && userId != null
                ? userRepository.findById(userId).orElse(null)
                : null;
        boolean allowed = user != null
                && Objects.equals(factoryId, user.getFactoryId())
                && permissionService != null
                && permissionService.hasPermission(user, LEGACY_IMPORT_PERMISSION);
        if (!allowed) {
            throw new BusinessException(403, "LEGACY_IMPORT 创建批次需要专门权限 " + LEGACY_IMPORT_PERMISSION)
                    .withHint("请联系管理员授权 inventory:legacy_import，或改从采购入库、退货入库、盘点单发起")
                    .withHintTarget("sourceDocType")
                    .withSeverity("BLOCKING");
        }
    }

    /**
     * 判断角色是否有库存自主权 (可改入库量 / 删批次)。
     * null 角色 = 内部/AI-tool 调用 (写操作另由 W0 WriteGuard 把关) → 放行。
     */
    private boolean isPrivilegedInventoryRole(String callerRole) {
        if (callerRole == null) {
            return true; // 内部调用路径, 跳过角色守卫
        }
        String normalized = callerRole.toLowerCase();
        return PLATFORM_ADMIN_ROLES.contains(normalized)
                || INVENTORY_MUTATION_ROLES.contains(normalized);
    }

    /**
     * W4 红线: 判断本次 PUT 是否试图改动"影响库存账/成本"的字段。
     *
     * <p>这些字段直接进库存台账、出成率分母 (receiptQuantity)、成本核算 (unitPrice/totalValue)
     * 或重量换算 (weightPerUnit/totalWeight)。只要请求带了与现值不同的值就算"库存变更"。
     * 非库存字段 (storageLocation/notes/expireDate/qualityCertificate/factoryNumber/originPlace 等)
     * 不在此列, 任何仓管角色都可改。
     */
    private boolean requestChangesInventoryFields(MaterialBatch batch, UpdateMaterialBatchRequest request) {
        if (request.getReceiptQuantity() != null
                && !numericEquals(request.getReceiptQuantity(), batch.getReceiptQuantity())) {
            return true;
        }
        if (request.getUnitPrice() != null
                && !numericEquals(request.getUnitPrice(), batch.getUnitPrice())) {
            return true;
        }
        if (request.getWeightPerUnit() != null
                && !numericEquals(request.getWeightPerUnit(), batch.getWeightPerUnit())) {
            return true;
        }
        // totalWeight / totalValue 是计算属性 (mapper 借它们反推 weightPerUnit / unitPrice),
        // 仓管发这两个 = 间接改重量/成本 → 同样视为库存变更。
        if (request.getTotalWeight() != null
                && !numericEquals(request.getTotalWeight(), batch.getTotalWeight())) {
            return true;
        }
        if (request.getTotalValue() != null
                && !numericEquals(request.getTotalValue(), batch.getTotalValue())) {
            return true;
        }
        return false;
    }

    /** BigDecimal 值相等比较 (忽略 scale, null 安全)。 */
    private boolean numericEquals(BigDecimal a, BigDecimal b) {
        if (a == null && b == null) {
            return true;
        }
        if (a == null || b == null) {
            return false;
        }
        return a.compareTo(b) == 0;
    }

    @Override
    @Transactional
    public MaterialBatchDTO updateMaterialBatch(String factoryId, String batchId, UpdateMaterialBatchRequest request) {
        return updateMaterialBatch(factoryId, batchId, request, null);
    }

    @Override
    @Transactional
    public MaterialBatchDTO updateMaterialBatch(String factoryId, String batchId,
                                                UpdateMaterialBatchRequest request, String callerRole) {
        runConfiguredValidation(factoryId, "UPDATE", java.util.Map.of(
            "batchId", batchId,
            "quantity", request.getReceiptQuantity() != null ? request.getReceiptQuantity() : java.math.BigDecimal.ZERO,
            "materialTypeId", request.getMaterialTypeId() != null ? request.getMaterialTypeId() : ""));
        MaterialBatch batch = materialBatchRepository.findById(batchId)
                .orElseThrow(() -> new ResourceNotFoundException("原材料批次", "id", batchId));

        // 验证工厂ID
        if (!batch.getFactoryId().equals(factoryId)) {
            throw new BusinessException(403, "无权操作该批次")
                    .withHint("请联系管理员确认批次归属或切换工厂账号");
        }

        // W4 红线: 纯操作员不能经 PUT 改"影响库存账/成本"的字段 (入库量/单价/重量/总价值)。
        // 非库存字段 (storageLocation/notes/expireDate 等) 仍放行。库存量变更走盘点/调整审批流。
        if (!isPrivilegedInventoryRole(callerRole)
                && requestChangesInventoryFields(batch, request)) {
            throw new BusinessException(403,
                    "仓管员无权直接修改入库量/单价/重量/总价值等库存字段")
                    .withHint("库存量变更请走盘点或库存调整审批流; 存储位置/备注/效期等可直接修改");
        }

        // 只能更新可用状态的批次
        if (batch.getStatus() != MaterialBatchStatus.AVAILABLE) {
            throw new BusinessException(409, "只能修改可用状态的批次")
                    .withHint("请刷新批次列表查看最新状态");
        }

        // 更新批次信息
        BigDecimal previousCurrentQuantity = batch.getCurrentQuantity();
        materialBatchMapper.updateEntity(batch, request);
        batch = materialBatchRepository.save(batch);
        if (batch.getCurrentQuantity().compareTo(previousCurrentQuantity) < 0) {
            publishStockChangedEventIfApplicable(factoryId, batch, "ADJUST");
        }
        log.info("更新原材料批次成功: batchId={}, callerRole={}", batchId, callerRole);
        return materialBatchMapper.toDTO(batch);
    }

    @Override
    @Transactional
    public void deleteMaterialBatch(String factoryId, String batchId) {
        deleteMaterialBatch(factoryId, batchId, null);
    }

    @Override
    @Transactional
    public void deleteMaterialBatch(String factoryId, String batchId, String callerRole) {
        MaterialBatch batch = materialBatchRepository.findById(batchId)
                .orElseThrow(() -> new ResourceNotFoundException("原材料批次", "id", batchId));

        // 验证工厂ID
        if (!batch.getFactoryId().equals(factoryId)) {
            throw new BusinessException(403, "无权操作该批次")
                    .withHint("请联系管理员确认批次归属或切换工厂账号");
        }

        // W4 红线: 删除批次 = 无单据移除库存。纯操作员不可删, 需管理员。
        if (!isPrivilegedInventoryRole(callerRole)) {
            throw new BusinessException(403,
                    "仓管员无权删除批次 (无单据移除库存)")
                    .withHint("误录入批次请联系工厂管理员或仓储主管删除; 库存差异请走盘亏处理");
        }

        // 只能删除未使用的批次
        if (!batch.getCurrentQuantity().equals(batch.getReceiptQuantity())) {
            throw new BusinessException(409, "已使用的批次不能删除")
                    .withHint("已部分消耗的批次不可删除, 请联系管理员调整或盘亏处理");
        }

        materialBatchRepository.delete(batch);
        publishStockChangedEventIfApplicable(factoryId, batch, "DELETE");
        log.info("删除原材料批次成功: batchId={}, callerRole={}", batchId, callerRole);
    }

    @Override
    public MaterialBatchDTO getMaterialBatchById(String factoryId, String batchId) {
        MaterialBatch batch = materialBatchRepository.findById(batchId)
                .orElseThrow(() -> new ResourceNotFoundException("原材料批次", "id", batchId));

        // 验证工厂ID
        if (!batch.getFactoryId().equals(factoryId)) {
            throw new BusinessException(403, "无权查看该批次")
                    .withHint("请联系管理员确认批次归属或切换工厂账号");
        }

        return materialBatchMapper.toDTO(batch);
    }

    /**
     * 获取原材料批次列表（分页）
     *
     * <p>根据工厂ID获取原材料批次列表，支持分页、排序和关键词搜索。</p>
     *
     * <h4>功能说明</h4>
     * <ul>
     *   <li>支持分页查询：通过page和size参数控制分页</li>
     *   <li>支持排序：通过sortBy和sortDirection参数自定义排序</li>
     *   <li>支持关键词搜索：如果提供了keyword，会搜索批次号或材料类型名称</li>
     * </ul>
     *
     * <h4>搜索功能</h4>
     * <p>当提供keyword参数时，会在以下字段中搜索：</p>
     * <ul>
     *   <li>批次号（batchNumber）：精确或模糊匹配</li>
     *   <li>材料类型名称（materialType.name）：模糊匹配</li>
     * </ul>
     *
     * @param factoryId 工厂ID（必填，用于数据隔离）
     * @param pageRequest 分页请求对象（包含page、size、sortBy、sortDirection、keyword）
     * @return 分页的批次列表
     */
    @Override
    @Transactional(readOnly = true)
    @DataScope("created_by")  // Sprint 6 W2-B — RBAC 第 2 维 (数据权限) sweep
    public PageResponse<MaterialBatchDTO> getMaterialBatchList(String factoryId, PageRequest pageRequest) {
        Sort sort = Sort.by(
                pageRequest.getSortDirection().equalsIgnoreCase("DESC") ?
                Sort.Direction.DESC : Sort.Direction.ASC,
                pageRequest.getSortBy()
        );

        org.springframework.data.domain.PageRequest pageable =
            org.springframework.data.domain.PageRequest.of(
                pageRequest.getPage() - 1,
                pageRequest.getSize(),
                sort
            );

        // Sprint 6 W2-B: 应用 DataScope chain. SELF / SELF_AND_BELOW / DEPT_AND_BELOW
        // 走 createdByIn variant. ALL 保持原行为.
        DataScopeContext dsCtx = DataScopeContext.current();
        List<Long> chain = null;
        if (dsCtx != null && dsCtx.isFiltered() && dsCtx.getUserId() != null) {
            com.cretas.aims.entity.enums.DataScope scope = dsCtx.getScope();
            if (scope == com.cretas.aims.entity.enums.DataScope.SELF) {
                chain = List.of(dsCtx.getUserId());
            } else if (scope == com.cretas.aims.entity.enums.DataScope.SELF_AND_BELOW
                    || scope == com.cretas.aims.entity.enums.DataScope.DEPT_AND_BELOW) {
                chain = dataScopeResolver != null
                        ? dataScopeResolver.resolveCreatedByChain(dsCtx)
                        : List.of(dsCtx.getUserId());
                if (chain == null || chain.isEmpty()) chain = List.of(dsCtx.getUserId());
            }
            // CUSTOM → chain stays null → fallback ALL
        }

        Page<MaterialBatch> batchPage;
        boolean hasKeyword = pageRequest.getKeyword() != null && !pageRequest.getKeyword().trim().isEmpty();

        // 如果提供了关键词，使用搜索方法；否则使用普通查询
        if (hasKeyword) {
            String escaped = com.cretas.aims.util.SqlLikeEscaper.escape(pageRequest.getKeyword().trim());
            log.debug("搜索原材料批次: factoryId={}, keyword={}", factoryId, pageRequest.getKeyword());
            if (chain != null) {
                batchPage = materialBatchRepository.searchByKeywordAndCreatedByIn(
                        factoryId, escaped, chain, pageable);
            } else {
                batchPage = materialBatchRepository.searchByKeyword(factoryId, escaped, pageable);
            }
        } else if (chain != null) {
            log.debug("DataScope filter for material batches: chain size={}", chain.size());
            batchPage = materialBatchRepository.findByFactoryIdAndCreatedByIn(factoryId, chain, pageable);
        } else {
            batchPage = materialBatchRepository.findByFactoryId(factoryId, pageable);
        }

        List<MaterialBatchDTO> batchDTOs = batchPage.getContent().stream()
                .map(materialBatchMapper::toDTO)
                .collect(Collectors.toList());

        return PageResponse.of(
                batchDTOs,
                pageRequest.getPage(),
                pageRequest.getSize(),
                batchPage.getTotalElements()
        );
    }

    @Override
    public List<MaterialBatchDTO> getMaterialBatchesByStatus(String factoryId, MaterialBatchStatus status) {
        return materialBatchRepository.findByFactoryIdAndStatus(factoryId, status)
                .stream()
                .map(materialBatchMapper::toDTO)
                .collect(Collectors.toList());
    }

    @Override
    public List<MaterialBatchDTO> getMaterialBatchesByStatus(String factoryId, MaterialBatchStatus status,
                                                              String productTypeId) {
        return getMaterialBatchesByStatus(factoryId, status, productTypeId, null);
    }

    @Override
    public List<MaterialBatchDTO> getMaterialBatchesByStatus(String factoryId, MaterialBatchStatus status,
                                                              String productTypeId, String warehouseId) {
        List<MaterialBatch> statusBatches = materialBatchRepository.findByFactoryIdAndStatus(factoryId, status)
                .stream()
                .filter(batch -> warehouseId == null || warehouseId.isBlank() || warehouseId.equals(batch.getWarehouseId()))
                .collect(Collectors.toList());

        // 无 productTypeId → 只按状态/仓库过滤
        if (productTypeId == null || productTypeId.isBlank()) {
            return statusBatches.stream()
                    .map(materialBatchMapper::toDTO)
                    .collect(Collectors.toList());
        }

        // 1. 查产品的当前 BOM (is_current, 不论 DRAFT/ACTIVE — 定义即生效, 无需"激活"仪式)
        Optional<BomRecipe> activeRecipe = bomRecipeRepository
                .findByFactoryIdAndProductTypeIdAndIsCurrentTrue(
                        factoryId, productTypeId);

        // 2. 无当前 BOM → 返回空, 由前端明确提示先维护 BOM, 避免选错料仍可结单
        if (activeRecipe.isEmpty()) {
            log.debug("BOM 过滤: 产品 {} 无当前 BOM, 返回空 {} 批次",
                    productTypeId, status);
            return Collections.emptyList();
        }

        // 3. 取 BOM 明细原料类型集合
        List<String> materialTypeIds = bomRecipeItemRepository
                .findByRecipeIdOrderBySortOrderAsc(activeRecipe.get().getId())
                .stream()
                .map(item -> item.getMaterialTypeId())
                .collect(Collectors.toList());

        // 4. BOM 无明细行 → 返回空, 避免文员随意选非 BOM 原料
        if (materialTypeIds.isEmpty()) {
            log.debug("BOM 过滤: 产品 {} 的当前 BOM {} 无明细行, 返回空批次",
                    productTypeId, activeRecipe.get().getId());
            return Collections.emptyList();
        }

        // 5. 按状态/仓库结果, 再按 BOM 原料类型过滤
        Set<String> bomMaterialTypeIds = new HashSet<>(materialTypeIds);
        List<MaterialBatchDTO> filtered = statusBatches.stream()
                .filter(b -> bomMaterialTypeIds.contains(b.getMaterialTypeId()))
                .map(materialBatchMapper::toDTO)
                .collect(Collectors.toList());

        log.debug("BOM 过滤: 产品 {} BOM 含 {} 原料类型, 从全部 AVAILABLE 批次中筛出 {} 批",
                productTypeId, bomMaterialTypeIds.size(), filtered.size());

        return filtered;
    }

    @Override
    public List<MaterialBatchDTO> getWipBatches(String factoryId) {
        return materialBatchRepository.findByFactoryIdAndStatus(factoryId, MaterialBatchStatus.PRODUCING_RESERVED)
                .stream()
                .map(materialBatchMapper::toDTO)
                .collect(Collectors.toList());
    }

    @Override
    public List<MaterialBatchDTO> getAllWipBatches() {
        return materialBatchRepository.findByStatus(MaterialBatchStatus.PRODUCING_RESERVED)
                .stream()
                .map(materialBatchMapper::toDTO)
                .collect(Collectors.toList());
    }

    @Override
    public List<MaterialBatchDTO> getAvailableBatchesFIFO(String factoryId, String materialTypeId) {
        List<MaterialBatch> batches = materialBatchRepository.findAvailableBatchesFIFO(factoryId, materialTypeId);
        return batches.stream()
                .map(materialBatchMapper::toDTO)
                .collect(Collectors.toList());
    }

    @Override
    public List<MaterialBatchDTO> getExpiringBatches(String factoryId, Integer warningDays) {
        LocalDate warningDate = LocalDate.now().plusDays(warningDays);
        List<MaterialBatch> batches = materialBatchRepository.findExpiringBatches(factoryId, warningDate);
        return batches.stream()
                .map(materialBatchMapper::toDTO)
                .collect(Collectors.toList());
    }

    @Override
    public List<MaterialBatchDTO> getExpiredBatches(String factoryId) {
        List<MaterialBatch> batches = materialBatchRepository.findExpiredBatches(factoryId);
        return batches.stream()
                .map(materialBatchMapper::toDTO)
                .collect(Collectors.toList());
    }

    @Override
    public List<MaterialBatchDTO> getMaterialBatchesBySupplier(String factoryId, String supplierId) {
        List<MaterialBatch> batches = materialBatchRepository.findByFactoryIdAndSupplierId(factoryId, supplierId);
        return batches.stream()
                .map(materialBatchMapper::toDTO)
                .collect(Collectors.toList());
    }

    @Override
    @Transactional
    public MaterialBatchDTO applyBatchQuantityDelta(String factoryId, String batchId, BigDecimal adjustmentQuantity, String reason) {
        MaterialBatch batch = materialBatchRepository.findById(batchId)
                .orElseThrow(() -> new ResourceNotFoundException("原材料批次", "id", batchId));

        // 验证工厂ID
        if (!batch.getFactoryId().equals(factoryId)) {
            throw new BusinessException(403, "无权操作该批次")
                    .withHint("请联系管理员确认批次归属或切换工厂账号");
        }

        // 计算新数量
        BigDecimal newQuantity = batch.getCurrentQuantity().add(adjustmentQuantity);
        if (newQuantity.compareTo(BigDecimal.ZERO) < 0) {
            throw new BusinessException(400, "调整后数量不能为负数")
                    .withHint("请检查输入数量, 必须 ≥ 0");
        }

        // 记录调整
        MaterialBatchAdjustment adjustment = new MaterialBatchAdjustment();
        adjustment.setId(java.util.UUID.randomUUID().toString());
        adjustment.setMaterialBatchId(batchId);
        adjustment.setAdjustmentType(adjustmentQuantity.compareTo(BigDecimal.ZERO) > 0 ? "increase" : "decrease");
        adjustment.setQuantityBefore(batch.getCurrentQuantity());
        adjustment.setAdjustmentQuantity(adjustmentQuantity.abs());
        adjustment.setQuantityAfter(newQuantity);
        adjustment.setReason(reason);
        adjustment.setAdjustmentTime(LocalDateTime.now());
        adjustment.setAdjustedBy(1L); // TODO: 从上下文获取用户ID
        materialBatchAdjustmentRepository.save(adjustment);

        // 更新批次数量
        // 注意: currentQuantity 现在是计算属性 (receiptQuantity - usedQuantity - reservedQuantity)
        // 要调整可用数量，需要调整 receiptQuantity
        BigDecimal qtyAdjustment = newQuantity.subtract(batch.getCurrentQuantity());
        batch.setReceiptQuantity(batch.getReceiptQuantity().add(qtyAdjustment));

        if (newQuantity.compareTo(BigDecimal.ZERO) == 0) {
            batch.setStatus(MaterialBatchStatus.USED_UP);
        }

        batch = materialBatchRepository.save(batch);
        log.info("调整批次数量: batchId={}, adjustment={}, reason={}", batchId, adjustmentQuantity, reason);
        return materialBatchMapper.toDTO(batch);
    }

    @Override
    @Transactional
    public void markBatchAsExpired(String factoryId, String batchId) {
        MaterialBatch batch = materialBatchRepository.findById(batchId)
                .orElseThrow(() -> new ResourceNotFoundException("原材料批次", "id", batchId));

        // 验证工厂ID
        if (!batch.getFactoryId().equals(factoryId)) {
            throw new BusinessException(403, "无权操作该批次")
                    .withHint("请联系管理员确认批次归属或切换工厂账号");
        }

        batch.setStatus(MaterialBatchStatus.EXPIRED);
        materialBatchRepository.save(batch);
        log.info("标记批次过期: batchId={}", batchId);
    }

    @Override
    @Transactional
    public void markBatchAsUsedUp(String factoryId, String batchId) {
        MaterialBatch batch = materialBatchRepository.findById(batchId)
                .orElseThrow(() -> new ResourceNotFoundException("原材料批次", "id", batchId));

        // 验证工厂ID
        if (!batch.getFactoryId().equals(factoryId)) {
            throw new BusinessException(403, "无权操作该批次")
                    .withHint("请联系管理员确认批次归属或切换工厂账号");
        }

        batch.setStatus(MaterialBatchStatus.USED_UP);
        // 注意: currentQuantity 是计算属性，不能直接设置
        // 标记为用完：设置 usedQuantity = receiptQuantity - reservedQuantity
        batch.setUsedQuantity(batch.getReceiptQuantity().subtract(batch.getReservedQuantity()));
        materialBatchRepository.save(batch);
        publishStockChangedEventIfApplicable(factoryId, batch, "OUT");
        log.info("标记批次用完: batchId={}", batchId);
    }

    @Override
    @Transactional
    public void reserveBatchQuantity(String factoryId, String batchId, BigDecimal quantity) {
        MaterialBatch batch = materialBatchRepository.findById(batchId)
                .orElseThrow(() -> new ResourceNotFoundException("原材料批次", "id", batchId));

        // 验证工厂ID
        if (!batch.getFactoryId().equals(factoryId)) {
            throw new BusinessException(403, "无权操作该批次")
                    .withHint("请联系管理员确认批次归属或切换工厂账号");
        }

        // 检查可用数量
        if (batch.getCurrentQuantity().compareTo(quantity) < 0) {
            throw new BusinessException(409, "批次可用数量不足")
                    .withHint("请刷新批次库存或选择其他批次");
        }

        // 增加预留数量，减少可用库存
        BigDecimal currentReserved = batch.getReservedQuantity() != null ? batch.getReservedQuantity() : BigDecimal.ZERO;
        batch.setReservedQuantity(currentReserved.add(quantity));

        materialBatchRepository.save(batch);
        publishStockChangedEventIfApplicable(factoryId, batch, "RESERVE");
        log.info("预留批次数量: batchId={}, quantity={}, reservedTotal={}", batchId, quantity, batch.getReservedQuantity());
    }

    @Override
    @Transactional
    public void releaseBatchQuantity(String factoryId, String batchId, BigDecimal quantity) {
        MaterialBatch batch = materialBatchRepository.findById(batchId)
                .orElseThrow(() -> new ResourceNotFoundException("原材料批次", "id", batchId));

        // 验证工厂ID
        if (!batch.getFactoryId().equals(factoryId)) {
            throw new BusinessException(403, "无权操作该批次")
                    .withHint("请联系管理员确认批次归属或切换工厂账号");
        }

        // 释放预留数量
        BigDecimal currentReserved = batch.getReservedQuantity() != null ? batch.getReservedQuantity() : BigDecimal.ZERO;
        if (currentReserved.compareTo(quantity) < 0) {
            throw new BusinessException(409, "释放数量超过已预留数量")
                    .withHint("请刷新批次预留数据");
        }
        batch.setReservedQuantity(currentReserved.subtract(quantity));

        materialBatchRepository.save(batch);
        log.info("释放批次预留: batchId={}, quantity={}, reservedTotal={}", batchId, quantity, batch.getReservedQuantity());
    }

    @Override
    @Transactional
    public void useBatchQuantity(String factoryId, String batchId, BigDecimal quantity) {
        MaterialBatch batch = materialBatchRepository.findById(batchId)
                .orElseThrow(() -> new ResourceNotFoundException("原材料批次", "id", batchId));

        // 验证工厂ID
        if (!batch.getFactoryId().equals(factoryId)) {
            throw new BusinessException(403, "无权操作该批次")
                    .withHint("请联系管理员确认批次归属或切换工厂账号");
        }

        // 检查可用数量是否充足
        if (batch.getCurrentQuantity().compareTo(quantity) < 0) {
            throw new BusinessException(String.format("批次可用数量不足，当前可用: %s, 请求使用: %s",
                    batch.getCurrentQuantity().toPlainString(), quantity.toPlainString()));
        }

        // 使用数量
        // 注意: currentQuantity 是计算属性，通过增加 usedQuantity 来减少 currentQuantity
        batch.setUsedQuantity(batch.getUsedQuantity().add(quantity));
        batch.setLastUsedAt(LocalDateTime.now());

        if (batch.getCurrentQuantity().compareTo(BigDecimal.ZERO) == 0) {
            batch.setStatus(MaterialBatchStatus.USED_UP);
        }

        materialBatchRepository.save(batch);
        log.info("使用批次数量: batchId={}, quantity={}", batchId, quantity);

        // F-034: 领料消耗后发布库存变更事件 → 触发低库存双向报警检测
        publishStockChangedEventIfApplicable(factoryId, batch, "OUT");
    }

    @Override
    public BigDecimal calculateInventoryValue(String factoryId) {
        BigDecimal value = materialBatchRepository.calculateInventoryValue(factoryId);
        return value != null ? value : BigDecimal.ZERO;
    }

    @Override
    public Map<String, BigDecimal> getInventoryByMaterialType(String factoryId) {
        List<Object[]> results = materialBatchRepository.sumQuantityByMaterialType(factoryId);
        Map<String, BigDecimal> inventory = new HashMap<>();

        for (Object[] result : results) {
            String materialTypeId = (String) result[0];
            BigDecimal quantity = (BigDecimal) result[1];
            // TODO: 获取原材料类型名称
            inventory.put("MaterialType-" + materialTypeId, quantity);
        }

        return inventory;
    }

    @Override
    public List<Map<String, Object>> getLowStockWarnings(String factoryId) {
        List<Map<String, Object>> warnings = new ArrayList<>();

        // 1. 获取所有激活的原材料类型（含 minStock 阈值）
        List<com.cretas.aims.entity.RawMaterialType> materialTypes =
                materialTypeRepository.findByFactoryIdAndIsActive(factoryId, true);

        // 2. 获取各原材料类型的当前库存汇总
        List<Object[]> stockSummary = materialBatchRepository.sumQuantityByMaterialType(factoryId);
        Map<String, BigDecimal> stockMap = new HashMap<>();
        for (Object[] row : stockSummary) {
            stockMap.put((String) row[0], (BigDecimal) row[1]);
        }

        // 3. 对比阈值，生成预警
        for (com.cretas.aims.entity.RawMaterialType mt : materialTypes) {
            BigDecimal minStock = mt.getMinStock();
            if (minStock == null || minStock.compareTo(BigDecimal.ZERO) <= 0) {
                continue; // 未设置安全库存
            }

            BigDecimal currentStock = stockMap.getOrDefault(mt.getId(), BigDecimal.ZERO);
            if (currentStock.compareTo(minStock) < 0) {
                BigDecimal gap = minStock.subtract(currentStock);
                double stockRatio = minStock.compareTo(BigDecimal.ZERO) > 0
                        ? currentStock.doubleValue() / minStock.doubleValue() * 100 : 0;

                String warningLevel;
                if (currentStock.compareTo(BigDecimal.ZERO) == 0) {
                    warningLevel = "CRITICAL";
                } else if (stockRatio < 30) {
                    warningLevel = "CRITICAL";
                } else if (stockRatio < 60) {
                    warningLevel = "WARNING";
                } else {
                    warningLevel = "INFO";
                }

                Map<String, Object> warning = new LinkedHashMap<>();
                warning.put("materialTypeId", mt.getId());
                warning.put("materialName", mt.getName());
                warning.put("materialCode", mt.getCode());
                warning.put("category", mt.getCategory());
                warning.put("currentStock", currentStock);
                warning.put("safetyStock", minStock);
                warning.put("unit", mt.getUnit());
                warning.put("gap", gap);
                warning.put("stockRatio", Math.round(stockRatio));
                warning.put("warningLevel", warningLevel);
                warnings.add(warning);
            }
        }

        // 按严重程度排序：CRITICAL > WARNING > INFO
        warnings.sort((a, b) -> {
            int priority = getLevelPriority((String) a.get("warningLevel"))
                    - getLevelPriority((String) b.get("warningLevel"));
            return priority;
        });

        log.info("低库存预警查询: factoryId={}, 预警数量={}", factoryId, warnings.size());
        return warnings;
    }

    private int getLevelPriority(String level) {
        if ("CRITICAL".equals(level)) return 0;
        if ("WARNING".equals(level)) return 1;
        return 2;
    }

    /**
     * F-034: after stock decreases, delegate minStock checks and deduped event publishing.
     */
    private void publishStockChangedEventIfApplicable(String factoryId, MaterialBatch batch, String changeType) {
        if (batch == null) {
            return;
        }
        inventoryLowStockEventPublisher.publishIfLowStock(factoryId, batch, changeType);
    }

    @Override
    @Transactional
    public List<MaterialBatchDTO> batchCreateMaterialBatches(String factoryId, List<CreateMaterialBatchRequest> requests, Long userId) {
        return requests.stream()
                .map(request -> createMaterialBatch(factoryId, request, userId))
                .collect(Collectors.toList());
    }

    @Override
    public byte[] exportInventoryReport(String factoryId) {
        return exportInventoryReport(factoryId, null, null, false);
    }

    @Override
    public byte[] exportInventoryReport(String factoryId, LocalDate startDate, LocalDate endDate) {
        return exportInventoryReport(factoryId, startDate, endDate, false);
    }

    @Override
    public List<Map<String, Object>> getBatchUsageHistory(String factoryId, String batchId) {
        // TODO: 从消耗记录和调整记录中获取使用历史
        return new ArrayList<>();
    }

    @Override
    public boolean checkBatchNumberExists(String batchNumber) {
        return materialBatchRepository.existsByBatchNumber(batchNumber);
    }

    @Override
    @Scheduled(cron = "0 0 2 * * ?") // 每天凌晨2点执行
    @SchedulerLock(name = "MaterialBatchServiceImpl.autoCheckAndUpdateExpiredBatches", lockAtMostFor = "PT30M", lockAtLeastFor = "PT1M")
    public void autoCheckAndUpdateExpiredBatches() {
        log.info("开始自动检查过期批次");

        // 使用优化查询直接获取过期批次，避免全表扫描后过滤
        List<MaterialBatch> expiredBatches = materialBatchRepository.findAllExpiredAvailableBatches(LocalDate.now());

        for (MaterialBatch batch : expiredBatches) {
            batch.setStatus(MaterialBatchStatus.EXPIRED);
            materialBatchRepository.save(batch);
            log.info("自动标记批次过期: batchNumber={}", batch.getBatchNumber());
        }

        log.info("完成自动检查过期批次，共处理 {} 个批次", expiredBatches.size());
    }

    @Override
    public byte[] exportInventoryReport(String factoryId, LocalDate startDate, LocalDate endDate,
                                        boolean maskPrice) {
        log.info("开始导出库存报表: factoryId={}, startDate={}, endDate={}, maskPrice={}",
                factoryId, startDate, endDate, maskPrice);

        // 使用分页查询避免内存问题，每页1000条
        org.springframework.data.domain.Pageable pageable = org.springframework.data.domain.PageRequest.of(0, 10000);
        Page<MaterialBatch> batchPage = materialBatchRepository.findByFactoryId(factoryId, pageable);
        List<MaterialBatch> batches = batchPage.getContent();

        // 如果有日期范围，过滤批次
        if (startDate != null || endDate != null) {
            batches = batches.stream()
                    .filter(batch -> {
                        LocalDate receiptDate = batch.getReceiptDate();
                        if (receiptDate == null) return true;
                        if (startDate != null && receiptDate.isBefore(startDate)) return false;
                        if (endDate != null && receiptDate.isAfter(endDate)) return false;
                        return true;
                    })
                    .collect(Collectors.toList());
        }

        // 转换为导出DTO
        List<MaterialBatchExportDTO> exportData = batches.stream()
                .map(this::convertToExportDTO)
                .collect(Collectors.toList());

        log.info("准备导出 {} 条批次记录, maskPrice={}", exportData.size(), maskPrice);

        // RBAC defense-in-depth (P0-C sweep, 2026-05-12): mask purchasePrice + inventoryValue
        // via masked DTO when caller lacks procurement:price:view.
        if (maskPrice) {
            List<com.cretas.aims.dto.material.MaterialBatchMaskedExportDTO> maskedData = exportData.stream()
                    .map(com.cretas.aims.dto.material.MaterialBatchMaskedExportDTO::fromExportDTO)
                    .collect(Collectors.toList());
            return excelUtil.exportToExcel(maskedData,
                    com.cretas.aims.dto.material.MaterialBatchMaskedExportDTO.class, "库存报表");
        }
        return excelUtil.exportToExcel(exportData, MaterialBatchExportDTO.class, "库存报表");
    }

    /**
     * 将MaterialBatch转换为导出DTO
     */
    private MaterialBatchExportDTO convertToExportDTO(MaterialBatch batch) {
        // 获取关联的原材料类型名称（避免N+1，已通过@BatchSize优化）
        String materialTypeName = null;
        if (batch.getMaterialType() != null) {
            materialTypeName = batch.getMaterialType().getName();
        }

        // 获取关联的供应商名称
        String supplierName = null;
        if (batch.getSupplier() != null) {
            supplierName = batch.getSupplier().getName();
        }

        MaterialBatchExportDTO dto = MaterialBatchExportDTO.builder()
                .batchNumber(batch.getBatchNumber())
                .materialTypeName(materialTypeName)
                .supplierName(supplierName)
                .initialQuantity(batch.getInitialQuantity())
                .currentQuantity(batch.getCurrentQuantity())
                .usedQuantity(batch.getUsedQuantity())
                .reservedQuantity(batch.getReservedQuantity())
                .unit(batch.getQuantityUnit())
                .status(batch.getStatus() != null ? batch.getStatus().name() : "UNKNOWN")
                .storageLocation(batch.getStorageLocation())
                .purchasePrice(batch.getUnitPrice())
                .receiveDate(batch.getReceiptDate())
                .expiryDate(batch.getExpireDate())
                .qualityGrade(batch.getQualityCertificate())
                .notes(batch.getNotes())
                .build();

        // 计算库存价值和剩余天数
        dto.calculateInventoryValue();
        dto.calculateRemainingDays();

        return dto;
    }

    @Override
    public List<MaterialBatchDTO> getMaterialBatchesByType(String factoryId, String materialTypeId) {
        List<MaterialBatch> batches = materialBatchRepository.findByFactoryIdAndMaterialTypeId(factoryId, materialTypeId);
        return batches.stream()
                .map(materialBatchMapper::toDTO)
                .collect(Collectors.toList());
    }

    @Override
    public List<MaterialBatchDTO> getFIFOBatches(String factoryId, String materialTypeId, BigDecimal requiredQuantity) {
        List<MaterialBatch> availableBatches = materialBatchRepository.findAvailableBatchesFIFOByStatus(
                factoryId, materialTypeId, MaterialBatchStatus.AVAILABLE);

        List<MaterialBatchDTO> result = new ArrayList<>();
        BigDecimal totalQuantity = BigDecimal.ZERO;

        for (MaterialBatch batch : availableBatches) {
            if (totalQuantity.compareTo(requiredQuantity) >= 0) {
                break;
            }
            result.add(materialBatchMapper.toDTO(batch));
            totalQuantity = totalQuantity.add(batch.getRemainingQuantity());
        }

        return result;
    }

    @Override
    public List<MaterialBatchDTO> getFEFOBatches(String factoryId, String materialTypeId, BigDecimal requiredQuantity) {
        List<MaterialBatch> availableBatches = materialBatchRepository.findAvailableBatchesFEFO(
                factoryId, materialTypeId);

        List<MaterialBatchDTO> result = new ArrayList<>();
        BigDecimal totalQuantity = BigDecimal.ZERO;

        for (MaterialBatch batch : availableBatches) {
            if (totalQuantity.compareTo(requiredQuantity) >= 0) {
                break;
            }
            result.add(materialBatchMapper.toDTO(batch));
            totalQuantity = totalQuantity.add(batch.getRemainingQuantity());
        }

        return result;
    }

    /**
     * C-B2: 校验原料批次可用于生产。
     *
     * <p>食品安全防呆: 已过期/已报废/不良品的批次即便有剩余量也不可投入生产。
     * DEPLETED/USED_UP 已被剩余量检查挡 (remaining=0); 此处补防"有量但状态坏"的批次。</p>
     */
    private void assertMaterialBatchUsable(MaterialBatch batch) {
        MaterialBatchStatus st = batch.getStatus();
        if (st == MaterialBatchStatus.EXPIRED
                || st == MaterialBatchStatus.SCRAPPED
                || st == MaterialBatchStatus.DEFECTIVE) {
            throw new BusinessException(409, "批次已" + st.getDisplayName() + "，不可用于生产")
                    .withHint("请选择可用批次");
        }
    }

    @Override
    @Transactional
    public MaterialBatchDTO useBatchMaterial(String factoryId, String batchId, BigDecimal quantity, String productionPlanId, Long operatorId) {
        MaterialBatch batch = materialBatchRepository.findByIdAndFactoryId(batchId, factoryId)
                .orElseThrow(() -> new ResourceNotFoundException("原材料批次不存在"));

        // C-B2: 食品安全防呆 — 过期/报废/不良品批次不可投产。
        assertMaterialBatchUsable(batch);

        if (batch.getRemainingQuantity().compareTo(quantity) < 0) {
            throw new BusinessException(409, "批次剩余数量不足")
                    .withHint("请刷新批次库存或选择其他批次");
        }

        // 更新已使用数量 (remainingQuantity 会自动重新计算)
        batch.setUsedQuantity(batch.getUsedQuantity().add(quantity));
        batch.setLastUsedAt(LocalDateTime.now());

        // 如果用完了，更新状态
        // 注意: getRemainingQuantity() 现在是计算属性
        if (batch.getRemainingQuantity().compareTo(BigDecimal.ZERO) == 0) {
            batch.setStatus(MaterialBatchStatus.USED_UP);
        }

        // 记录消耗（如果提供了生产计划ID）
        if (productionPlanId != null) {
            // C-B1 fix: 补齐 NOT NULL 字段 (unitPrice/totalCost/recordedBy), 否则 INSERT 失败 (500)。
            // recordedBy 走 FK→users, 必须是真实操作人; 由 controller/AI tool 线程进 operatorId。
            // 镜像 FactoryMaterialRequisitionServiceImpl 既有约定: 批次单价兜底 ZERO。
            if (operatorId == null) {
                throw new BusinessException(401, "无法识别操作人，无法记录领料消耗")
                        .withHint("请重新登录后重试");
            }
            BigDecimal unitPrice = batch.getUnitPrice() != null ? batch.getUnitPrice() : BigDecimal.ZERO;
            MaterialConsumption consumption = new MaterialConsumption();
            consumption.setFactoryId(factoryId);
            consumption.setProductionPlanId(productionPlanId);
            consumption.setBatchId(batchId);
            consumption.setQuantity(quantity);
            consumption.setUnitPrice(unitPrice);
            consumption.setTotalCost(quantity.multiply(unitPrice));
            consumption.setRecordedBy(operatorId);
            consumption.setConsumptionTime(LocalDateTime.now());
            materialConsumptionRepository.save(consumption);
        }

        MaterialBatchDTO result = materialBatchMapper.toDTO(materialBatchRepository.save(batch));

        // F-034: 报工领料消耗后发布库存变更事件 → 触发低库存双向报警检测
        publishStockChangedEventIfApplicable(factoryId, batch, "OUT");

        return result;
    }

    @Override
    @Transactional
    public MaterialBatchDTO adjustBatchQuantity(String factoryId, String batchId, BigDecimal newQuantity,
                                                String reason, Long adjustedBy) {
        MaterialBatch batch = materialBatchRepository.findById(batchId)
                .orElseThrow(() -> new ResourceNotFoundException("原材料批次", "id", batchId));

        // 验证工厂ID
        if (!batch.getFactoryId().equals(factoryId)) {
            throw new BusinessException(403, "无权操作该批次")
                    .withHint("请联系管理员确认批次归属或切换工厂账号");
        }

        // W-03 defense (Round 7): reject negative newQuantity before it reaches the
        // receipt_quantity arithmetic — otherwise it propagated to a DB constraint
        // and surfaced as a generic 500. Matches the guard in applyBatchQuantityDelta
        // (the 4-arg DELTA overload, renamed T-R5-4 / 2026-05-12).
        if (newQuantity == null || newQuantity.compareTo(BigDecimal.ZERO) < 0) {
            throw new BusinessException(400, "调整后数量不能为负数")
                    .withHint("请检查输入数量, 必须 ≥ 0");
        }

        BigDecimal oldQuantity = batch.getRemainingQuantity();
        BigDecimal adjustment = newQuantity.subtract(oldQuantity);

        // 注意: remainingQuantity 和 totalQuantity 都是计算属性
        // 要调整剩余数量，需要调整 receiptQuantity
        batch.setReceiptQuantity(batch.getReceiptQuantity().add(adjustment));

        // 记录调整
        MaterialBatchAdjustment adjustmentRecord = new MaterialBatchAdjustment();
        adjustmentRecord.setId(java.util.UUID.randomUUID().toString());
        adjustmentRecord.setMaterialBatchId(batchId);
        adjustmentRecord.setAdjustmentType(adjustment.compareTo(BigDecimal.ZERO) > 0 ? "INCREASE" : "DECREASE");
        adjustmentRecord.setQuantityBefore(oldQuantity);
        adjustmentRecord.setQuantityAfter(newQuantity);
        adjustmentRecord.setAdjustmentQuantity(adjustment.abs());
        adjustmentRecord.setReason(reason);
        adjustmentRecord.setAdjustedBy(adjustedBy.longValue());
        adjustmentRecord.setAdjustmentTime(LocalDateTime.now());
        materialBatchAdjustmentRepository.save(adjustmentRecord);

        batch = materialBatchRepository.save(batch);
        if (adjustment.compareTo(BigDecimal.ZERO) < 0) {
            publishStockChangedEventIfApplicable(factoryId, batch, "ADJUST");
        }
        return materialBatchMapper.toDTO(batch);
    }

    @Override
    @Transactional
    public MaterialBatchDTO updateBatchStatus(String factoryId, String batchId, MaterialBatchStatus status) {
        MaterialBatch batch = materialBatchRepository.findById(batchId)
                .orElseThrow(() -> new ResourceNotFoundException("原材料批次", "id", batchId));

        // 验证工厂ID
        if (!batch.getFactoryId().equals(factoryId)) {
            throw new BusinessException(403, "无权操作该批次")
                    .withHint("请联系管理员确认批次归属或切换工厂账号");
        }

        batch.setStatus(status);
        batch = materialBatchRepository.save(batch);
        return materialBatchMapper.toDTO(batch);
    }

    @Override
    @Transactional
    public void reserveBatchMaterial(String factoryId, String batchId, BigDecimal quantity, String productionPlanId) {
        MaterialBatch batch = materialBatchRepository.findById(batchId)
                .orElseThrow(() -> new ResourceNotFoundException("原材料批次", "id", batchId));

        // 验证工厂ID
        if (!batch.getFactoryId().equals(factoryId)) {
            throw new BusinessException(403, "无权操作该批次")
                    .withHint("请联系管理员确认批次归属或切换工厂账号");
        }

        if (batch.getRemainingQuantity().compareTo(quantity) < 0) {
            throw new BusinessException(409, "批次剩余数量不足以预留")
                    .withHint("请刷新批次库存或减少预留数量");
        }

        // 更新预留数量 (remainingQuantity 会自动重新计算)
        batch.setReservedQuantity(batch.getReservedQuantity().add(quantity));

        // 如果剩余量为0，更新状态为DEPLETED
        // 注意: getRemainingQuantity() 现在是计算属性
        if (batch.getRemainingQuantity().compareTo(BigDecimal.ZERO) == 0) {
            batch.setStatus(MaterialBatchStatus.DEPLETED);
        }

        materialBatchRepository.save(batch);
        publishStockChangedEventIfApplicable(factoryId, batch, "RESERVE");
        log.info("预留批次材料成功: batchId={}, quantity={}, remainingAfter={}, reservedTotal={}",
                batchId, quantity, batch.getRemainingQuantity(), batch.getReservedQuantity());

        // 记录批次使用关联
        ProductionPlanBatchUsage usage = new ProductionPlanBatchUsage();
        usage.setId(java.util.UUID.randomUUID().toString());
        usage.setProductionPlanId(productionPlanId);
        usage.setMaterialBatchId(batchId);
        usage.setReservedQuantity(quantity);
        usage.setUsedQuantity(BigDecimal.ZERO);
        usage.setPlannedQuantity(quantity); // 设置计划数量
        productionPlanBatchUsageRepository.save(usage);
    }

    @Override
    @Transactional
    public void releaseBatchReservation(String factoryId, String batchId, BigDecimal quantity, String productionPlanId) {
        MaterialBatch batch = materialBatchRepository.findById(batchId)
                .orElseThrow(() -> new ResourceNotFoundException("原材料批次", "id", batchId));

        // 验证工厂ID
        if (!batch.getFactoryId().equals(factoryId)) {
            throw new BusinessException(403, "无权操作该批次")
                    .withHint("请联系管理员确认批次归属或切换工厂账号");
        }

        // 验证预留数量是否充足
        if (batch.getReservedQuantity().compareTo(quantity) < 0) {
            throw new BusinessException(409, "预留数量不足以释放")
                    .withHint("请刷新批次预留数据");
        }

        // 释放预留数量 (remainingQuantity 会自动增加)
        batch.setReservedQuantity(batch.getReservedQuantity().subtract(quantity));

        // 如果之前是DEPLETED状态，恢复为AVAILABLE
        // 注意: getRemainingQuantity() 现在是计算属性
        if (batch.getStatus() == MaterialBatchStatus.DEPLETED &&
            batch.getRemainingQuantity().compareTo(BigDecimal.ZERO) > 0) {
            batch.setStatus(MaterialBatchStatus.AVAILABLE);
        }

        materialBatchRepository.save(batch);
        log.info("释放预留材料成功: batchId={}, quantity={}, remainingAfter={}, reservedTotal={}",
                batchId, quantity, batch.getRemainingQuantity(), batch.getReservedQuantity());

        // 更新批次使用关联
        ProductionPlanBatchUsage usage = productionPlanBatchUsageRepository
                .findByProductionPlanIdAndMaterialBatchId(productionPlanId, batchId)
                .orElse(null);

        if (usage != null) {
            usage.setReservedQuantity(usage.getReservedQuantity().subtract(quantity));
            if (usage.getReservedQuantity().compareTo(BigDecimal.ZERO) <= 0) {
                productionPlanBatchUsageRepository.delete(usage);
            } else {
                productionPlanBatchUsageRepository.save(usage);
            }
        }
    }

    @Override
    @Transactional
    public void consumeBatchMaterial(String factoryId, String batchId, BigDecimal quantity, String productionPlanId, Long operatorId) {
        MaterialBatch batch = materialBatchRepository.findById(batchId)
                .orElseThrow(() -> new ResourceNotFoundException("原材料批次", "id", batchId));

        if (!batch.getFactoryId().equals(factoryId)) {
            throw new BusinessException(403, "无权操作该批次")
                    .withHint("请联系管理员确认批次归属或切换工厂账号");
        }

        // C-B1 fix: recordedBy 走 FK→users 必须真实操作人, 提前校验避免 INSERT 失败。
        if (operatorId == null) {
            throw new BusinessException(401, "无法识别操作人，无法记录消耗")
                    .withHint("请重新登录后重试");
        }

        // C-B2: 食品安全防呆 — 过期/报废/不良品批次不可投产。
        assertMaterialBatchUsable(batch);

        runConfiguredValidation(factoryId, "CONSUME",
                java.util.Map.of("batchId", batchId, "quantity", quantity != null ? quantity : BigDecimal.ZERO));

        if (batch.getReservedQuantity().compareTo(quantity) < 0) {
            throw new BusinessException(409, "预留数量不足以消耗")
                    .withHint("请确认预留数量或重新预留");
        }

        batch.setReservedQuantity(batch.getReservedQuantity().subtract(quantity));
        batch.setUsedQuantity(batch.getUsedQuantity().add(quantity));
        batch.setLastUsedAt(LocalDateTime.now());

        if (batch.getReservedQuantity().compareTo(BigDecimal.ZERO) == 0 &&
            batch.getRemainingQuantity().compareTo(BigDecimal.ZERO) == 0) {
            batch.setStatus(MaterialBatchStatus.DEPLETED);
        }

        materialBatchRepository.save(batch);
        log.info("消耗批次材料成功: batchId={}, quantity={}, reservedRemaining={}, usedTotal={}",
                batchId, quantity, batch.getReservedQuantity(), batch.getUsedQuantity());

        // C-B1 fix: 补齐 NOT NULL 字段 (unitPrice/totalCost/recordedBy), 否则 INSERT 失败 500。
        // 镜像 FactoryMaterialRequisitionServiceImpl 既有约定: 批次单价兜底 ZERO。recordedBy=真实操作人(上方已校验非空)。
        BigDecimal consumeUnitPrice = batch.getUnitPrice() != null ? batch.getUnitPrice() : BigDecimal.ZERO;
        MaterialConsumption consumption = new MaterialConsumption();
        consumption.setFactoryId(factoryId);
        consumption.setProductionPlanId(productionPlanId);
        consumption.setBatchId(batchId);
        consumption.setQuantity(quantity);
        consumption.setUnitPrice(consumeUnitPrice);
        consumption.setTotalCost(quantity.multiply(consumeUnitPrice));
        consumption.setRecordedBy(operatorId);
        consumption.setConsumptionTime(LocalDateTime.now());
        materialConsumptionRepository.save(consumption);

        ProductionPlanBatchUsage usage = productionPlanBatchUsageRepository
                .findByProductionPlanIdAndMaterialBatchId(productionPlanId, batchId)
                .orElse(null);
        if (usage != null) {
            usage.setReservedQuantity(usage.getReservedQuantity().subtract(quantity));
            usage.setUsedQuantity(usage.getUsedQuantity().add(quantity));
            productionPlanBatchUsageRepository.save(usage);
        }

        if (applicationEventPublisher != null) {
            try {
                applicationEventPublisher.publishEvent(new com.cretas.aims.event.BatchMaterialConsumedEvent(
                        this, factoryId, batchId, quantity, productionPlanId));
            } catch (Exception e) { log.warn("Publish BatchMaterialConsumedEvent failed: {}", e.getMessage()); }
        }
        publishStockChangedEventIfApplicable(factoryId, batch, "OUT");
    }

    @Override
    public Map<String, Object> getInventoryStatistics(String factoryId) {
        Map<String, Object> statistics = new HashMap<>();

        // 总批次数
        long totalBatches = materialBatchRepository.countByFactoryId(factoryId);
        statistics.put("totalBatches", totalBatches);

        // 可用批次数
        long availableBatches = materialBatchRepository.countByFactoryIdAndStatus(factoryId, MaterialBatchStatus.AVAILABLE);
        statistics.put("availableBatches", availableBatches);

        // 过期批次数
        long expiredBatches = materialBatchRepository.countByFactoryIdAndStatus(factoryId, MaterialBatchStatus.EXPIRED);
        statistics.put("expiredBatches", expiredBatches);

        // 总库存价值
        BigDecimal totalValue = calculateInventoryValue(factoryId);
        statistics.put("totalValue", totalValue);

        // 按材料类型统计
        Map<String, BigDecimal> inventoryByType = getInventoryByMaterialType(factoryId);
        statistics.put("inventoryByType", inventoryByType);

        // 即将过期批次数（7天内）
        List<MaterialBatch> expiringBatches = materialBatchRepository.findExpiringBatchesByStatus(
                factoryId, LocalDate.now().plusDays(7), MaterialBatchStatus.AVAILABLE);
        statistics.put("expiringBatchesCount", expiringBatches.size());

        return statistics;
    }

    @Override
    public BigDecimal getInventoryValuation(String factoryId) {
        return calculateInventoryValue(factoryId);
    }

    @Override
    @Transactional(readOnly = true)
    public List<MaterialStockSummaryDTO> listStockSummary(String factoryId) {
        List<MaterialStockSummaryDTO> summaries = materialBatchRepository.findStockSummaryByFactory(factoryId);
        // avgUnitPrice 在 SQL 之外计算回填: totalValue / totalQuantity (除零保护 → null)。
        // scale=4 + HALF_UP 对齐 moving_avg_price (precision 12 scale 4) 与 recalculateMovingAvgPrice。
        for (MaterialStockSummaryDTO s : summaries) {
            BigDecimal qty = s.getTotalQuantity();
            BigDecimal value = s.getTotalValue();
            if (qty != null && value != null && qty.compareTo(BigDecimal.ZERO) != 0) {
                s.setAvgUnitPrice(value.divide(qty, 4, java.math.RoundingMode.HALF_UP));
            } else {
                s.setAvgUnitPrice(null);
            }
        }
        return summaries;
    }

    @Override
    @Transactional
    public int handleExpiredBatches(String factoryId) {
        List<MaterialBatch> expiredBatches = materialBatchRepository.findExpiredBatchesByDate(
                factoryId, LocalDate.now());

        int count = 0;
        for (MaterialBatch batch : expiredBatches) {
            if (batch.getStatus() != MaterialBatchStatus.EXPIRED) {
                batch.setStatus(MaterialBatchStatus.EXPIRED);
                materialBatchRepository.save(batch);
                count++;
            }
        }

        return count;
    }

    @Override
    @Transactional
    public MaterialBatchDTO convertToFrozen(String factoryId, String batchId, ConvertToFrozenRequest request) {
        log.info("开始转冻品: factoryId={}, batchId={}", factoryId, batchId);

        // 1. 查询原材料批次
        MaterialBatch batch = materialBatchRepository.findById(batchId)
                .orElseThrow(() -> new ResourceNotFoundException("批次不存在: " + batchId));

        // 2. 验证工厂ID
        if (!factoryId.equals(batch.getFactoryId())) {
            throw new BusinessException(403, "批次不属于该工厂")
                    .withHint("请联系管理员确认批次归属或切换工厂账号");
        }

        // 3. 验证批次状态（只有鲜品可以转冻品）
        if (batch.getStatus() != MaterialBatchStatus.FRESH) {
            throw new BusinessException(409, "只有鲜品批次可以转为冻品，当前状态: " + batch.getStatus())
                    .withHint("请刷新批次状态或选择鲜品批次");
        }

        // 4. 保存原始存储位置（用于撤销时恢复）
        String originalStorageLocation = batch.getStorageLocation();

        // 5. 更新批次状态和存储位置
        batch.setStatus(MaterialBatchStatus.FROZEN);
        batch.setStorageLocation(request.getStorageLocation());

        // 6. 在notes中记录转换信息（包括原始存储位置）
        String existingNotes = batch.getNotes() != null ? batch.getNotes() : "";
        String convertNote = String.format("\n[%s] 转冻品操作 - 操作人ID:%d, 转换日期:%s, 原存储位置:%s",
                LocalDateTime.now().toString(),
                request.getConvertedBy(),
                request.getConvertedDate().toString(),
                originalStorageLocation != null ? originalStorageLocation : "未知");

        if (request.getNotes() != null && !request.getNotes().isEmpty()) {
            convertNote += ", 备注: " + request.getNotes();
        }

        batch.setNotes(existingNotes + convertNote);

        // 6. 保存批次
        MaterialBatch savedBatch = materialBatchRepository.save(batch);

        log.info("转冻品成功: batchId={}, newStatus={}", batchId, savedBatch.getStatus());

        // 7. 转换为DTO返回
        return materialBatchMapper.toDTO(savedBatch);
    }

    @Override
    @Transactional
    public MaterialBatchDTO undoFrozen(String factoryId, String batchId, UndoFrozenRequest request) {
        log.info("开始撤销转冻品: factoryId={}, batchId={}", factoryId, batchId);

        // 1. 查询原材料批次
        MaterialBatch batch = materialBatchRepository.findById(batchId)
                .orElseThrow(() -> new ResourceNotFoundException("批次不存在: " + batchId));

        // 2. 验证工厂ID
        if (!factoryId.equals(batch.getFactoryId())) {
            throw new BusinessException(403, "批次不属于该工厂")
                    .withHint("请联系管理员确认批次归属或切换工厂账号");
        }

        // 3. 验证批次状态（只有冻品可以撤销）
        if (batch.getStatus() != MaterialBatchStatus.FROZEN) {
            throw new BusinessException(409, "只有冻品批次可以撤销，当前状态: " + batch.getStatus())
                    .withHint("请刷新批次状态或选择冻品批次");
        }

        // 4. 从notes中解析最后转换时间并验证时间窗口
        String notes = batch.getNotes() != null ? batch.getNotes() : "";
        LocalDateTime convertedTime = extractLastConvertTime(notes);

        if (convertedTime == null) {
            throw new BusinessException(409, "无法找到转换时间记录，无法撤销")
                    .withHint("数据缺失, 请联系管理员手工处理");
        }

        LocalDateTime now = LocalDateTime.now();
        long minutesPassed = java.time.Duration.between(convertedTime, now).toMinutes();

        // 防御性检查：如果时间为负数（转换时间在未来），也视为超时
        if (minutesPassed < 0) {
            throw new BusinessException(
                "转换时间异常（时间戳在未来），无法撤销。请检查系统时间设置。"
            );
        }

        if (minutesPassed > 10) {
            throw new BusinessException(
                String.format("转换已超过10分钟（已过%d分钟），无法撤销", minutesPassed)
            );
        }

        log.info("转换时间: {}, 当前时间: {}, 已过: {}分钟", convertedTime, now, minutesPassed);

        // 5. 恢复为FRESH状态
        batch.setStatus(MaterialBatchStatus.FRESH);

        // 6. 恢复存储位置（优先使用请求中指定的位置，其次从notes中提取原始位置）
        String targetStorageLocation = request.getStorageLocation();
        if (targetStorageLocation == null || targetStorageLocation.isBlank()) {
            targetStorageLocation = extractOriginalStorageLocation(notes);
        }
        if (targetStorageLocation != null && !targetStorageLocation.equals("未知")) {
            batch.setStorageLocation(targetStorageLocation);
            log.info("恢复存储位置: {}", targetStorageLocation);
        }

        // 7. 在notes中记录撤销信息（使用兼容方法获取有效值）
        Integer effectiveOperatorId = request.getEffectiveOperatorId();
        String effectiveReason = request.getEffectiveReason();
        String undoNote = String.format("\n[%s] 撤销转冻品操作 - 操作人ID:%s, 原因: %s",
                LocalDateTime.now().toString(),
                effectiveOperatorId != null ? effectiveOperatorId.toString() : "未知",
                effectiveReason);
        batch.setNotes(notes + undoNote);

        // 7. 保存批次
        MaterialBatch savedBatch = materialBatchRepository.save(batch);
        log.info("撤销转冻品成功: batchId={}, newStatus={}", batchId, savedBatch.getStatus());

        // 8. 转换为DTO返回
        return materialBatchMapper.toDTO(savedBatch);
    }

    /**
     * 从notes中提取最后一次转冻品的时间
     */
    private LocalDateTime extractLastConvertTime(String notes) {
        if (notes == null || notes.isEmpty()) {
            return null;
        }

        try {
            // 查找最后一个转冻品记录的时间戳
            // 格式: [2025-11-20T16:53:39.766951] 转冻品操作 - ...
            String[] lines = notes.split("\n");
            for (int i = lines.length - 1; i >= 0; i--) {
                String line = lines[i];
                if (line.contains("转冻品操作")) {
                    int start = line.indexOf('[');
                    int end = line.indexOf(']');
                    if (start >= 0 && end > start) {
                        String timeStr = line.substring(start + 1, end);
                        return LocalDateTime.parse(timeStr);
                    }
                }
            }
        } catch (Exception e) {
            log.warn("解析转换时间失败: {}", e.getMessage());
        }

        return null;
    }

    /**
     * 从notes中提取原始存储位置
     */
    private String extractOriginalStorageLocation(String notes) {
        if (notes == null || notes.isEmpty()) {
            return null;
        }

        try {
            // 查找最后一个转冻品记录中的原存储位置
            // 格式: [2025-11-20T16:53:39.766951] 转冻品操作 - 操作人ID:1, 转换日期:2025-11-20, 原存储位置:A区-01货架
            String[] lines = notes.split("\n");
            for (int i = lines.length - 1; i >= 0; i--) {
                String line = lines[i];
                if (line.contains("转冻品操作") && line.contains("原存储位置:")) {
                    int start = line.indexOf("原存储位置:") + 6;  // "原存储位置:".length() = 6
                    // 查找下一个逗号或行尾
                    int comma = line.indexOf(",", start);
                    int end = comma > 0 ? comma : line.length();
                    String location = line.substring(start, end).trim();
                    log.info("从notes中提取到原存储位置: {}", location);
                    return location;
                }
            }
        } catch (Exception e) {
            log.warn("解析原存储位置失败: {}", e.getMessage());
        }

        return null;
    }

    /**
     * 生成唯一批次号
     */
    private String generateUniqueBatchNumber(String baseNumber) {
        String batchNumber = baseNumber;
        int counter = 0;

        while (materialBatchRepository.existsByBatchNumber(batchNumber)) {
            counter++;
            batchNumber = baseNumber + "-" + counter;
        }

        return batchNumber;
    }

    @Override
    @Transactional
    public void recalculateMovingAvgPrice(String materialTypeId, java.math.BigDecimal receiptQty,
                                          java.math.BigDecimal receiptPrice, String newBatchId) {
        com.cretas.aims.entity.RawMaterialType materialType =
                materialTypeRepository.findById(materialTypeId).orElse(null);
        if (materialType == null) {
            log.warn("recalculateMovingAvgPrice: materialType {} not found, skip", materialTypeId);
            return;
        }
        updateMovingAvgPrice(materialType, receiptQty, receiptPrice, newBatchId);
    }

    /**
     * 入库时更新物料类型的移动平均价
     * 公式: 新均价 = (现有总量 × 现均价 + 入库数量 × 入库价) / (现有总量 + 入库数量)
     */
    private void updateMovingAvgPrice(com.cretas.aims.entity.RawMaterialType materialType,
                                      java.math.BigDecimal receiptQty, java.math.BigDecimal receiptPrice,
                                      String newBatchId) {
        if (receiptQty == null || receiptPrice == null
                || receiptQty.compareTo(java.math.BigDecimal.ZERO) <= 0
                || receiptPrice.compareTo(java.math.BigDecimal.ZERO) <= 0) {
            return;
        }
        try {
            // 直接用增量公式: newAvg = (existingQty * currentAvg + receiptQty * receiptPrice) / (existingQty + receiptQty)
            // 不依赖 findAvailableBatchesFEFO 查询（该查询有 enum 状态匹配问题）
            java.math.BigDecimal currentAvg = materialType.getMovingAvgPrice();
            java.math.BigDecimal existingQty;

            if (currentAvg != null && currentAvg.compareTo(java.math.BigDecimal.ZERO) > 0) {
                // 有现有均价 — 用当前在库量 (receiptQty - usedQty - reservedQty) 加权。
                // 注意: 早期实现用 getTotalWeight() = weightPerUnit × receiptQuantity, 但 weightPerUnit
                // 是可选字段, 大部分批次未填, 退化成 0 → 加权公式失效成"最新单价覆盖"。
                // getCurrentQuantity 是 @Transient 计算属性, 直接反映当前在库存货量, 跟会计标准
                // 移动加权平均成本 (Moving Weighted Average Cost) 公式语义一致。
                List<MaterialBatch> allBatches = materialBatchRepository
                        .findByFactoryIdAndMaterialTypeId(
                                materialType.getFactoryId(), materialType.getId());
                existingQty = allBatches.stream()
                        .filter(b -> !b.getId().equals(newBatchId))
                        .map(b -> b.getCurrentQuantity() != null ? b.getCurrentQuantity() : java.math.BigDecimal.ZERO)
                        .reduce(java.math.BigDecimal.ZERO, java.math.BigDecimal::add);
            } else {
                // 首批入库 — 均价直接等于本批单价
                currentAvg = java.math.BigDecimal.ZERO;
                existingQty = java.math.BigDecimal.ZERO;
            }

            java.math.BigDecimal totalValue = existingQty.multiply(currentAvg)
                    .add(receiptQty.multiply(receiptPrice));
            java.math.BigDecimal totalQty = existingQty.add(receiptQty);

            if (totalQty.compareTo(java.math.BigDecimal.ZERO) > 0) {
                java.math.BigDecimal newAvg = totalValue.divide(totalQty, 4, java.math.RoundingMode.HALF_UP);
                materialType.setMovingAvgPrice(newAvg);
                materialTypeRepository.save(materialType);
                log.info("更新移动均价: materialType={}, 现有量={}, 入库量={}, 新均价={}",
                        materialType.getName(), existingQty, receiptQty, newAvg);
            }
        } catch (Exception e) {
            log.warn("更新移动均价失败(不影响入库): materialType={}, error={}",
                    materialType.getId(), e.getMessage());
        }
    }
}
