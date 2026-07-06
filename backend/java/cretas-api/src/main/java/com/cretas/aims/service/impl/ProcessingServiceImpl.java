package com.cretas.aims.service.impl;

import com.cretas.aims.dto.batch.TeamBatchReportRequest;
import com.cretas.aims.dto.common.PageRequest;
import com.cretas.aims.dto.common.PageResponse;
import com.cretas.aims.entity.*;
import com.cretas.aims.entity.enums.*;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.exception.EntityNotFoundException;
import com.cretas.aims.exception.ResourceNotFoundException;
import com.cretas.aims.repository.*;
import com.cretas.aims.service.ProcessingService;
import com.cretas.aims.service.ProcessingStageRecordService;
import com.cretas.aims.service.QualityInspectionService;
import com.cretas.aims.service.AIAnalysisService;
import com.cretas.aims.service.CacheService;
import com.cretas.aims.service.WageRecordTriggerService;
import com.cretas.aims.service.alerts.InventoryLowStockEventPublisher;
import com.cretas.aims.service.canvas.ThresholdKeys;
import com.cretas.aims.service.canvas.ThresholdResolverService;
import com.cretas.aims.dto.processing.ProcessingStageRecordDTO;
import com.cretas.aims.event.BatchCompletedEvent;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.HashSet;
import java.util.Set;
import java.util.stream.Collectors;
/**
 * 生产加工服务实现类
 *
 * @author Cretas Team
 * @version 1.0.0
 * @since 2025-01-09
 */
@Service
@RequiredArgsConstructor
public class ProcessingServiceImpl implements ProcessingService {
    private static final Logger log = LoggerFactory.getLogger(ProcessingServiceImpl.class);

    private final ProductionBatchRepository productionBatchRepository;
    private final MaterialBatchRepository materialBatchRepository;
    private final MaterialConsumptionRepository materialConsumptionRepository;
    private final QualityInspectionRepository qualityInspectionRepository;
    private final EquipmentRepository equipmentRepository;
    private final EquipmentAlertRepository equipmentAlertRepository;
    private final BatchEquipmentUsageRepository batchEquipmentUsageRepository;
    private final ProductionPlanRepository productionPlanRepository;
    private final SystemLogRepository systemLogRepository;
    private final RawMaterialTypeRepository rawMaterialTypeRepository;
    private final BatchWorkSessionRepository batchWorkSessionRepository;
    private final UserRepository userRepository;
    private final SupplierRepository supplierRepository;
    private final ProductTypeRepository productTypeRepository;
    private final AIAnalysisService aiAnalysisService;
    private final CacheService cacheService;
    private final ProcessingStageRecordService processingStageRecordService;
    private final ApplicationEventPublisher applicationEventPublisher;
    private final QualityInspectionService qualityInspectionService;
    private final ProductionAlertRepository productionAlertRepository;
    private final ProductionPlanBatchUsageRepository productionPlanBatchUsageRepository;
    @Autowired
    private InventoryLowStockEventPublisher inventoryLowStockEventPublisher;
    // Sprint 5 Track E (M-WAGE-INTEGRATION-1): 生产→工资 自动 trigger
    private final WageRecordTriggerService wageRecordTriggerService;
    // 审计 round2: 班组报工补录时效锁 (六扇门"补录只能 T/T-1, 极限 T-2, T-3 锁死"硬规则)。
    // optional/fail-open: 未配置 bean 时不阻塞 (与 YieldReportServiceImpl 同款)。
    @Autowired(required = false)
    private com.cretas.aims.util.BackdateWindowValidator backdateWindowValidator;

    /**
     * Canvas-Thresholds resolver (Phase A P0-3) — overlays FALLBACK_* with per-factory config.
     * Optional injection so Mockito @InjectMocks tests without resolver still construct.
     */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private ThresholdResolverService thresholdResolver;

    /**
     * 将字符串 batchId 安全转换为 Long。
     * production_batches.id 是 bigint，前端传入的 batchId 应为数字字符串。
     * 如果传入了非数字（如 UUID "MB-F001-001" 或 "4b6728e3-..."），
     * 返回友好的错误信息而非原始 NumberFormatException 堆栈。
     */
    private Long parseBatchId(String batchId) {
        try {
            return Long.parseLong(batchId);
        } catch (NumberFormatException e) {
            throw new BusinessException(400, "无效的生产批次ID: \"" + batchId + "\"，生产批次ID应为数字")
                    .withHint("请刷新批次列表后重新选择").withHintTarget("batchId");
        }
    }

    // ========== 批次管理 ==========
    @Override
    @Transactional(rollbackFor = Exception.class)
    public ProductionBatch createBatch(String factoryId, ProductionBatch batch) {
        log.info("创建生产批次: factoryId={}, batchNumber={}", factoryId, batch.getBatchNumber());
        // 验证批次号唯一性
        if (productionBatchRepository.existsByFactoryIdAndBatchNumber(factoryId, batch.getBatchNumber())) {
            throw new BusinessException(409, "批次号已存在: " + batch.getBatchNumber())
                    .withHint("请使用不同的批次号或刷新列表");
        }

        // ID is auto-generated by database (IDENTITY strategy), no need to set manually
        batch.setFactoryId(factoryId);
        batch.setStatus(ProductionBatchStatus.PLANNED);
        batch.setCreatedAt(LocalDateTime.now());

        // P0-1 产品名绑定 (F006 报工审计修复):
        //   若传了 productTypeId, 从 product_types 解析真实品名设为 productName,
        //   消除"待设置产品名称"占位符 (生产批次/报工界面显示真实品名)。
        //   防呆 (fool-proof 快速失败): productTypeId 传了但查不到 → 抛 400 业务异常,
        //   不静默落占位符 (避免用户错填一个无效的 productTypeId 而无感知)。
        //   仅当用户未显式提供 productName 时才用 productType 的 name 覆盖
        //   (尊重用户传入的自定义品名, 向后兼容)。
        boolean productNameMissing = batch.getProductName() == null || batch.getProductName().isEmpty();
        if (batch.getProductTypeId() != null && !batch.getProductTypeId().isEmpty()) {
            ProductType productType = productTypeRepository
                    .findByIdAndFactoryId(batch.getProductTypeId(), factoryId)
                    .orElseThrow(() -> new BusinessException(400,
                            "产品类型不存在: " + batch.getProductTypeId())
                            .withHint("请刷新产品列表后重新选择产品").withHintTarget("productTypeId"));
            if (productNameMissing) {
                batch.setProductName(productType.getName());
            }
        } else if (productNameMissing) {
            // productTypeId 和 productName 都没传 → 保留原占位逻辑 (向后兼容, 不硬断)
            batch.setProductName("待设置产品名称");
        }
        if (batch.getQuantity() == null) {
            batch.setQuantity(batch.getPlannedQuantity() != null ? batch.getPlannedQuantity() : BigDecimal.ZERO);
        }
        if (batch.getUnit() == null || batch.getUnit().isEmpty()) {
            batch.setUnit("kg");
        }

        return productionBatchRepository.save(batch);
    }
    public ProductionBatch startProduction(String factoryId, String batchId, Integer supervisorId) {
        log.info("开始生产: factoryId={}, batchId={}, supervisorId={}", factoryId, batchId, supervisorId);
        Long id = parseBatchId(batchId);
        ProductionBatch batch = productionBatchRepository.findByIdAndFactoryId(id, factoryId)
                .orElseThrow(() -> new ResourceNotFoundException("批次不存在"));
        if (ProductionBatchStatus.PLANNED != batch.getStatus()) {
            throw new BusinessException(409, "批次状态不允许开始生产: " + batch.getStatus())
                    .withHint("请刷新批次列表查看最新状态");
        }
        batch.setStatus(ProductionBatchStatus.IN_PROGRESS);
        batch.setStartTime(LocalDateTime.now());
        batch.setSupervisorId(supervisorId != null ? supervisorId.longValue() : null);
        ProductionBatch saved = productionBatchRepository.save(batch);
        transitionLinkedMaterialBatchesToWip(factoryId, saved.getProductionPlanId(), saved.getId());
        return saved;
    }
    public ProductionBatch pauseProduction(String factoryId, String batchId, String reason) {
        log.info("暂停生产: factoryId={}, batchId={}, reason={}", factoryId, batchId, reason);
        Long id = parseBatchId(batchId);
        ProductionBatch batch = productionBatchRepository.findByIdAndFactoryId(id, factoryId)
                .orElseThrow(() -> new ResourceNotFoundException("批次不存在"));
        if (ProductionBatchStatus.IN_PROGRESS != batch.getStatus()) {
            throw new BusinessException(409, "只有进行中的批次可以暂停")
                    .withHint("请刷新批次列表查看最新状态");
        }
        batch.setStatus(ProductionBatchStatus.PAUSED);
        batch.setNotes(batch.getNotes() != null ? batch.getNotes() + "\n暂停原因: " + reason : "暂停原因: " + reason);
        return productionBatchRepository.save(batch);
    }
    @Override
    public ProductionBatch resumeProduction(String factoryId, String batchId) {
        log.info("恢复生产: factoryId={}, batchId={}", factoryId, batchId);
        Long id = parseBatchId(batchId);
        ProductionBatch batch = productionBatchRepository.findByIdAndFactoryId(id, factoryId)
                .orElseThrow(() -> new ResourceNotFoundException("批次不存在"));
        if (ProductionBatchStatus.PAUSED != batch.getStatus()) {
            throw new BusinessException(409, "只有暂停中的批次可以恢复生产")
                    .withHint("请刷新批次列表查看最新状态");
        }
        batch.setStatus(ProductionBatchStatus.IN_PROGRESS);
        batch.setNotes(batch.getNotes() != null ? batch.getNotes() + "\n恢复生产: " + LocalDateTime.now() : "恢复生产: " + LocalDateTime.now());
        return productionBatchRepository.save(batch);
    }
    public ProductionBatch completeProduction(String factoryId, String batchId, BigDecimal actualQuantity,
                                             BigDecimal goodQuantity, BigDecimal defectQuantity) {
        // 默认重载: 不改批次成品单位 (沿用 batch.unit)
        return completeProduction(factoryId, batchId, actualQuantity, goodQuantity, defectQuantity, null);
    }

    @Override
    public ProductionBatch completeProduction(String factoryId, String batchId, BigDecimal actualQuantity,
                                             BigDecimal goodQuantity, BigDecimal defectQuantity, String finishedUnit) {
        log.info("完成生产: factoryId={}, batchId={}, actualQuantity={}, finishedUnit={}",
                factoryId, batchId, actualQuantity, finishedUnit);
        Long id = parseBatchId(batchId);
        ProductionBatch batch = productionBatchRepository.findByIdAndFactoryId(id, factoryId)
                .orElseThrow(() -> new ResourceNotFoundException("批次不存在"));
        if (ProductionBatchStatus.IN_PROGRESS != batch.getStatus() &&
            ProductionBatchStatus.PAUSED != batch.getStatus()) {
            throw new BusinessException(409, "批次状态不允许完成生产: " + batch.getStatus())
                    .withHint("请刷新批次列表查看最新状态");
        }
        batch.setStatus(ProductionBatchStatus.COMPLETED);
        batch.setEndTime(LocalDateTime.now());
        batch.setActualQuantity(actualQuantity);
        batch.setGoodQuantity(goodQuantity);
        batch.setDefectQuantity(defectQuantity);
        // P0-2: 末道产出单位非空时, 成品按"末道产出单位"(如 份/盒)入库, 而非批次原单位 (kg)
        // → createFinishedGoodsFromBatch 的 fg.setUnit(batch.getUnit()) 自动拿到正确单位.
        // 覆盖 unit 前先把原计划单位记到瞬态 plannedUnit, 供 calculateMetrics 检测跨单位:
        // 跨单位 (产出"份" ≠ 原计划"kg") 时 efficiency/unitCost 会被置 null 诚实留空,
        // 不算出 actualQuantity(份)/plannedQuantity(kg) 这类无意义值 (镜像 yield cumulative=null).
        if (finishedUnit != null && !finishedUnit.isBlank()) {
            batch.setPlannedUnit(batch.getUnit());  // 记录原单位 (kg), 须在 setUnit 之前
            batch.setUnit(finishedUnit);
        }
        // 计算指标
        batch.calculateMetrics();
        ProductionBatch saved = productionBatchRepository.save(batch);

        // 释放 WIP 状态 (M-WIP-1): PRODUCING_RESERVED → 按剩余量回到 AVAILABLE / DEPLETED / USED_UP
        releaseLinkedMaterialBatchesFromWip(factoryId, saved.getProductionPlanId(), saved.getId());

        // 发布批次完成事件 → 触发供应链联动（自动创建成品、更新PP、通知SO）
        try {
            applicationEventPublisher.publishEvent(new BatchCompletedEvent(this, saved));
            log.info("已发布BatchCompletedEvent: batchId={}, goodQuantity={}", saved.getId(), saved.getGoodQuantity());
        } catch (Exception e) {
            log.error("发布BatchCompletedEvent失败(不影响主流程): batchId={}", saved.getId(), e);
        }

        return saved;
    }
    public ProductionBatch cancelProduction(String factoryId, String batchId, String reason) {
        log.info("取消生产: factoryId={}, batchId={}, reason={}", factoryId, batchId, reason);
        Long id = parseBatchId(batchId);
        ProductionBatch batch = productionBatchRepository.findByIdAndFactoryId(id, factoryId)
                .orElseThrow(() -> new ResourceNotFoundException("批次不存在"));
        if (ProductionBatchStatus.COMPLETED == batch.getStatus()) {
            throw new BusinessException(409, "已完成的批次不能取消")
                    .withHint("请刷新批次列表查看最新状态");
        }
        batch.setStatus(ProductionBatchStatus.CANCELLED);
        batch.setNotes(batch.getNotes() != null ? batch.getNotes() + "\n取消原因: " + reason : "取消原因: " + reason);
        ProductionBatch saved = productionBatchRepository.save(batch);
        // 释放 WIP 状态 (M-WIP-1): PRODUCING_RESERVED → RESERVED (取消时按预留量恢复)
        releaseLinkedMaterialBatchesFromWip(factoryId, saved.getProductionPlanId(), saved.getId());
        return saved;
    }

    /**
     * Sprint 4 Wave 2 M-WIP-1: 进入 WIP 时, 将关联物料批次状态 RESERVED → PRODUCING_RESERVED.
     *
     * <p>关联路径: ProductionBatch.productionPlanId → ProductionPlanBatchUsage.materialBatchId → MaterialBatch.id</p>
     *
     * <p>失败时记 WARN 但不抛 — 不阻塞 startProduction 主流程 (与 BatchCompletedEvent 相同的防御模式).</p>
     */
    private void transitionLinkedMaterialBatchesToWip(String factoryId, String productionPlanId, Long productionBatchId) {
        if (productionPlanId == null || productionPlanId.isBlank()) {
            return;  // 无生产计划关联 (standalone batch), 无 WIP 物料可标记
        }
        try {
            List<ProductionPlanBatchUsage> usages = productionPlanBatchUsageRepository.findByProductionPlanId(productionPlanId);
            int flipped = 0;
            for (ProductionPlanBatchUsage usage : usages) {
                String materialBatchId = usage.getMaterialBatchId();
                if (materialBatchId == null) continue;
                MaterialBatch mb = materialBatchRepository.findByIdAndFactoryId(materialBatchId, factoryId).orElse(null);
                if (mb == null) continue;
                if (mb.getStatus() == MaterialBatchStatus.RESERVED || mb.getStatus() == MaterialBatchStatus.AVAILABLE) {
                    mb.setStatus(MaterialBatchStatus.PRODUCING_RESERVED);
                    materialBatchRepository.save(mb);
                    flipped++;
                }
            }
            log.info("[M-WIP-1] WIP 转换完成: productionBatchId={}, productionPlanId={}, flipped={}/{} 物料批次",
                    productionBatchId, productionPlanId, flipped, usages.size());
        } catch (Exception e) {
            log.warn("[M-WIP-1] WIP 转换失败 (不影响主流程): productionBatchId={}, productionPlanId={}, err={}",
                    productionBatchId, productionPlanId, e.getMessage());
        }
    }

    /**
     * Sprint 4 Wave 2 M-WIP-1: 离开 WIP (完成 / 取消) 时, 将物料批次状态从 PRODUCING_RESERVED 释放回正常状态.
     *
     * <p>恢复规则:</p>
     * <ul>
     *   <li>currentQuantity = 0 → DEPLETED (耗尽)</li>
     *   <li>currentQuantity &gt; 0 + reservedQuantity &gt; 0 → RESERVED (仍有预留)</li>
     *   <li>currentQuantity &gt; 0 + reservedQuantity = 0 → AVAILABLE (无预留可用)</li>
     * </ul>
     */
    private void releaseLinkedMaterialBatchesFromWip(String factoryId, String productionPlanId, Long productionBatchId) {
        if (productionPlanId == null || productionPlanId.isBlank()) {
            return;
        }
        try {
            List<ProductionPlanBatchUsage> usages = productionPlanBatchUsageRepository.findByProductionPlanId(productionPlanId);
            int flipped = 0;
            for (ProductionPlanBatchUsage usage : usages) {
                String materialBatchId = usage.getMaterialBatchId();
                if (materialBatchId == null) continue;
                MaterialBatch mb = materialBatchRepository.findByIdAndFactoryId(materialBatchId, factoryId).orElse(null);
                if (mb == null) continue;
                if (mb.getStatus() != MaterialBatchStatus.PRODUCING_RESERVED) continue;

                MaterialBatchStatus next;
                BigDecimal current = mb.getCurrentQuantity();
                BigDecimal reserved = mb.getReservedQuantity() != null ? mb.getReservedQuantity() : BigDecimal.ZERO;
                if (current.compareTo(BigDecimal.ZERO) <= 0) {
                    next = MaterialBatchStatus.DEPLETED;
                } else if (reserved.compareTo(BigDecimal.ZERO) > 0) {
                    next = MaterialBatchStatus.RESERVED;
                } else {
                    next = MaterialBatchStatus.AVAILABLE;
                }
                mb.setStatus(next);
                materialBatchRepository.save(mb);
                flipped++;
            }
            log.info("[M-WIP-1] WIP 释放完成: productionBatchId={}, productionPlanId={}, flipped={}/{} 物料批次",
                    productionBatchId, productionPlanId, flipped, usages.size());
        } catch (Exception e) {
            log.warn("[M-WIP-1] WIP 释放失败 (不影响主流程): productionBatchId={}, productionPlanId={}, err={}",
                    productionBatchId, productionPlanId, e.getMessage());
        }
    }

    public ProductionBatch getBatchById(String factoryId, String batchId) {
        Long id = parseBatchId(batchId);
        ProductionBatch batch = productionBatchRepository.findByIdAndFactoryId(id, factoryId)
                .orElseThrow(() -> new ResourceNotFoundException("批次不存在"));
        // BUG-2 修复: 填充 batchSourceType (@Transient) 从关联计划的 planSourceType。
        // null-safe: productionPlanId 为 null 或计划已删除时 batchSourceType 保持 null。
        if (batch.getProductionPlanId() != null) {
            productionPlanRepository.findById(batch.getProductionPlanId())
                    .ifPresent(plan -> batch.setBatchSourceType(plan.getPlanSourceType()));
        }
        enrichBatchDisplayFields(batch);
        return batch;
    }

    /**
     * headed-audit 修复 (2026-07-03): 生产批次列表显示 GUID + 空数量列.
     *
     * <p>根因: 部分批次创建路径 (文员逐道录入 {@code ClerkProcessEntryServiceImpl},
     * AI dataop 工具 {@code ProcessingBatchCreateToolDataOp}) 没有在创建时写入
     * {@code productName}/{@code plannedQuantity}, 导致这些历史行落库即为 null。
     * 与生产计划列表同根因 (sibling GUID-resolution bug, 见 ProductionPlanMapper.toDTO
     * 71-76 行 "Bug #1b" 修复) — 这里同样在读时兜底解析, 不需要数据回填迁移:
     * <ul>
     *   <li>productName 为空 → 按 productTypeId 查 product_types 表回填 (只读, 不落库)</li>
     *   <li>plannedQuantity 为空 → 退回 quantity (NOT NULL, 创建时必填的"初始数量")</li>
     * </ul>
     * 批次对象此时已 detached (repository 方法各自独立事务、已提交关闭), 内存赋值不会
     * 被 Hibernate 脏检查回写数据库, 与 {@link #getBatchById} 已有的
     * {@code batchSourceType} 回填模式一致。
     */
    private void enrichBatchDisplayFields(ProductionBatch batch) {
        if (batch == null) {
            return;
        }
        if ((batch.getProductName() == null || batch.getProductName().isBlank())
                && batch.getProductTypeId() != null) {
            productTypeRepository.findById(batch.getProductTypeId())
                    .ifPresent(pt -> batch.setProductName(pt.getName()));
        }
        if (batch.getPlannedQuantity() == null) {
            batch.setPlannedQuantity(batch.getQuantity());
        }
    }

    public PageResponse<ProductionBatch> getBatches(String factoryId, String status, PageRequest pageRequest) {
        org.springframework.data.domain.PageRequest pageable = org.springframework.data.domain.PageRequest.of(
                pageRequest.getPage() - 1,
                pageRequest.getSize(),
                Sort.by(Sort.Direction.DESC, "createdAt")
        );
        Page<ProductionBatch> page;
        if (status != null && !status.isEmpty()) {
            // 将字符串状态转换为枚举
            ProductionBatchStatus statusEnum = ProductionBatchStatus.valueOf(status.toUpperCase());
            page = productionBatchRepository.findByFactoryIdAndStatus(factoryId, statusEnum, pageable);
        } else {
            page = productionBatchRepository.findByFactoryId(factoryId, pageable);
        }
        page.getContent().forEach(this::enrichBatchDisplayFields);
        return PageResponse.of(
                page.getContent(),
                pageRequest.getPage(),
                pageRequest.getSize(),
                page.getTotalElements()
        );
    }

    @Override
    public PageResponse<ProductionBatch> getBatches(String factoryId, String status, Long supervisorId, Boolean isTrial, PageRequest pageRequest) {
        // 无试制过滤 → 走原有逻辑 (主管过滤 / 全量)
        if (isTrial == null) {
            return getBatches(factoryId, status, supervisorId, pageRequest);
        }
        // SP10: 试制批次过滤 (中报价下拉). 与主管过滤不叠加 (试制场景无主管语义).
        org.springframework.data.domain.PageRequest pageable = org.springframework.data.domain.PageRequest.of(
                pageRequest.getPage() - 1,
                pageRequest.getSize(),
                Sort.by(Sort.Direction.DESC, "createdAt")
        );
        Page<ProductionBatch> page;
        if (status != null && !status.isEmpty()) {
            ProductionBatchStatus statusEnum = ProductionBatchStatus.valueOf(status.toUpperCase());
            page = productionBatchRepository.findByFactoryIdAndStatusAndIsTrial(factoryId, statusEnum, isTrial, pageable);
        } else {
            page = productionBatchRepository.findByFactoryIdAndIsTrial(factoryId, isTrial, pageable);
        }
        page.getContent().forEach(this::enrichBatchDisplayFields);
        return PageResponse.of(
                page.getContent(),
                pageRequest.getPage(),
                pageRequest.getSize(),
                page.getTotalElements()
        );
    }

    @Override
    public PageResponse<ProductionBatch> getBatches(String factoryId, String status, Long supervisorId, PageRequest pageRequest) {
        if (supervisorId == null) {
            return getBatches(factoryId, status, pageRequest);
        }
        org.springframework.data.domain.PageRequest pageable = org.springframework.data.domain.PageRequest.of(
                pageRequest.getPage() - 1,
                pageRequest.getSize(),
                Sort.by(Sort.Direction.DESC, "createdAt")
        );
        Page<ProductionBatch> page;
        if (status != null && !status.isEmpty()) {
            ProductionBatchStatus statusEnum = ProductionBatchStatus.valueOf(status.toUpperCase());
            page = productionBatchRepository.findByFactoryIdAndSupervisorIdAndStatus(factoryId, supervisorId, statusEnum, pageable);
        } else {
            page = productionBatchRepository.findByFactoryIdAndSupervisorId(factoryId, supervisorId, pageable);
        }
        page.getContent().forEach(this::enrichBatchDisplayFields);
        return PageResponse.of(
                page.getContent(),
                pageRequest.getPage(),
                pageRequest.getSize(),
                page.getTotalElements()
        );
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> submitTeamBatchReport(String factoryId, TeamBatchReportRequest request) {
        // 验证批次存在且属于该工厂
        ProductionBatch batch = productionBatchRepository.findByIdAndFactoryId(request.getBatchId(), factoryId)
                .orElseThrow(() -> new ResourceNotFoundException("批次不存在或不属于该工厂"));

        // A-F1/F2 同族: 班组报工同样须批次已开工 (开工→报工 是六扇门明确顺序; 对齐
        // YieldReportServiceImpl.submitReport 守卫)。放行 IN_PROGRESS/PRODUCING/PAUSED;
        // 拦 PLANNED/PLANNING(未开工) + COMPLETED/CANCELLED(终态, 防累加产出到已结算/取消批次)。
        ProductionBatchStatus st = batch.getStatus();
        if (st == ProductionBatchStatus.PLANNED || st == ProductionBatchStatus.PLANNING) {
            throw new BusinessException(409, "批次尚未开始生产，无法报工")
                    .withHint("请先由主管在该批次点击「开始生产」后再报工");
        }
        if (st == ProductionBatchStatus.COMPLETED || st == ProductionBatchStatus.CANCELLED) {
            throw new BusinessException(409, "批次已" + st.getDescription() + "，不可再报工")
                    .withHint("如需调整请走撤回/重开流程");
        }

        // 团队总产出作为主数据源
        int teamOutput = request.getTotalOutput();
        int teamGood = request.getTotalGoodQuantity() != null ? request.getTotalGoodQuantity() : teamOutput;
        int teamDefect = request.getTotalDefectQuantity() != null ? request.getTotalDefectQuantity() : 0;

        // 解析报工时间
        LocalDateTime reportTime = LocalDateTime.now();
        boolean reportTimeProvided = false;
        if (request.getReportTime() != null && !request.getReportTime().isEmpty()) {
            try {
                reportTime = LocalDateTime.parse(request.getReportTime().replace("Z", ""));
                reportTimeProvided = true;
            } catch (Exception e) {
                log.warn("无法解析reportTime: {}, 使用当前时间", request.getReportTime());
            }
        }
        // 审计 round2: 补录时效锁 — 班组报工业务日期不得早于 T-maxDays (对齐 submitReport/recordMaterialInput)。
        // 仅当显式传 reportTime 时校验 (默认 now() 必在窗口内)。
        if (reportTimeProvided && backdateWindowValidator != null) {
            backdateWindowValidator.assertWithinWindow(reportTime.toLocalDate(), "班组报工");
        }

        // 处理可选的个人明细
        int recordedMembers = 0;
        if (request.getMembers() != null && !request.getMembers().isEmpty()) {
            for (TeamBatchReportRequest.MemberReport member : request.getMembers()) {
                if (member.getUserId() == null || member.getUserId() <= 0) {
                    continue;
                }
                int output = member.getOutputQuantity() != null ? member.getOutputQuantity() : 0;
                int good = member.getGoodQuantity() != null ? member.getGoodQuantity() : output;
                int defect = member.getDefectQuantity() != null ? member.getDefectQuantity() : 0;

                BatchWorkSession session = new BatchWorkSession();
                session.setBatchId(request.getBatchId());
                session.setEmployeeId(member.getUserId());
                session.setCheckInTime(reportTime);
                session.setCheckOutTime(reportTime);
                session.setStatus("completed");
                session.setNotes(member.getNotes() != null ? member.getNotes()
                        : String.format("班组报工: 产出%d, 良品%d, 不良品%d", output, good, defect));
                batchWorkSessionRepository.save(session);
                recordedMembers++;

                // Sprint 5 Track E (M-WAGE-INTEGRATION-1): 生产报工 → 计件工资自动 trigger
                // 仅当个人产量 > 0 时 trigger (本次班组成员有报产, 才计入计件 efficiency).
                // 失败不阻塞主流程 (per WageRecordTriggerService 内部已 catch-all).
                if (output > 0) {
                    wageRecordTriggerService.recordPieceWage(factoryId, batch, member.getUserId(), output, good);
                }
            }
        }

        // 累加团队总产出到批次（不再用 sum(个人)）
        BigDecimal currentActual = batch.getActualQuantity() != null ? batch.getActualQuantity() : BigDecimal.ZERO;
        BigDecimal currentGood = batch.getGoodQuantity() != null ? batch.getGoodQuantity() : BigDecimal.ZERO;
        BigDecimal currentDefect = batch.getDefectQuantity() != null ? batch.getDefectQuantity() : BigDecimal.ZERO;

        batch.setActualQuantity(currentActual.add(BigDecimal.valueOf(teamOutput)));
        batch.setGoodQuantity(currentGood.add(BigDecimal.valueOf(teamGood)));
        batch.setDefectQuantity(currentDefect.add(BigDecimal.valueOf(teamDefect)));
        productionBatchRepository.save(batch);

        Map<String, Object> result = new HashMap<>();
        result.put("recordedMembers", recordedMembers);
        result.put("totalOutput", teamOutput);
        result.put("totalGood", teamGood);
        result.put("totalDefect", teamDefect);
        result.put("batchId", request.getBatchId());
        result.put("reportTime", reportTime.toString());
        if (request.getNotes() != null) {
            result.put("notes", request.getNotes());
        }
        return result;
    }

    public List<Map<String, Object>> getBatchTimeline(String factoryId, String batchId) {
        ProductionBatch batch = getBatchById(factoryId, batchId);
        List<Map<String, Object>> timeline = new ArrayList<>();
        // 创建时间点
        Map<String, Object> created = new HashMap<>();
        created.put("time", batch.getCreatedAt());
        created.put("event", "批次创建");
        created.put("status", "PLANNED");
        timeline.add(created);
        // 开始生产时间点
        if (batch.getStartTime() != null) {
            Map<String, Object> started = new HashMap<>();
            started.put("time", batch.getStartTime());
            started.put("event", "开始生产");
            started.put("status", "IN_PROGRESS");
            timeline.add(started);
        }
        // 结束时间点
        if (batch.getEndTime() != null) {
            Map<String, Object> ended = new HashMap<>();
            ended.put("time", batch.getEndTime());
            ended.put("event", "COMPLETED".equals(batch.getStatus()) ? "生产完成" : "生产取消");
            ended.put("status", batch.getStatus());
            timeline.add(ended);
        }
        return timeline;
    }
    // ========== 原材料管理 ==========
    public MaterialBatch createMaterialReceipt(String factoryId, MaterialBatch materialBatch) {
        log.info("创建原材料接收记录: factoryId={}, batchNumber={}", factoryId, materialBatch.getBatchNumber());
        if (materialBatchRepository.existsByFactoryIdAndBatchNumber(factoryId, materialBatch.getBatchNumber())) {
            throw new BusinessException(409, "原材料批次号已存在: " + materialBatch.getBatchNumber())
                    .withHint("请使用其他批次号").withHintTarget("batchNumber");
        }

        // BUG-MR500 修复 (2026-06-12): MaterialBatch 主键非 @GeneratedValue, 必须显式赋值后才能 persist.
        // 采购入库路径 (createMaterialBatchFromReceiveItem) 已 setId, 此直收路径漏了 →
        // "Identifier must be manually assigned before calling persist()" 500. 镜像采购路径补 UUID.
        if (materialBatch.getId() == null || materialBatch.getId().isEmpty()) {
            materialBatch.setId(java.util.UUID.randomUUID().toString());
        }

        // 获取初始数量（支持多种字段名）
        BigDecimal initialQty = materialBatch.getInitialQuantity();
        if (initialQty == null) {
            throw new BusinessException(400, "原材料数量不能为空")
                    .withHint("请填写原材料初始数量").withHintTarget("initialQuantity");
        }

        // 防呆 (2026-06-12): warehouse_id 是 NOT NULL 列, 缺失会落到 DB 约束抛 generic 409"数据处理异常"
        // (用户看不懂). 提前显式校验, 返回明确 400 + hintTarget 引导填仓库 (fool-proof Rule a/5).
        if (materialBatch.getWarehouseId() == null || materialBatch.getWarehouseId().isEmpty()) {
            throw new BusinessException(400, "请指定入库仓库")
                    .withHint("原材料接收必须选择目标仓库").withHintTarget("warehouseId");
        }

        // 从原材料类型获取单位
        if (materialBatch.getMaterialType() != null && materialBatch.getMaterialType().getId() != null) {
            RawMaterialType materialType = rawMaterialTypeRepository.findById(materialBatch.getMaterialType().getId())
                    .orElseThrow(() -> new ResourceNotFoundException("原材料类型不存在"));
            materialBatch.setQuantityUnit(materialType.getUnit());  // 设置数量单位
        } else if (materialBatch.getQuantityUnit() == null) {
            // 如果没有原材料类型且没有单位，使用默认值
            materialBatch.setQuantityUnit("公斤");
        }

        materialBatch.setFactoryId(factoryId);
        materialBatch.setStatus(MaterialBatchStatus.AVAILABLE);
        // 注意: initialQuantity, remainingQuantity, currentQuantity, totalQuantity 都是计算属性
        // 只需设置核心字段: receiptQuantity, usedQuantity, reservedQuantity
        materialBatch.setReceiptQuantity(initialQty);  // receiptQuantity 是核心字段
        materialBatch.setUsedQuantity(BigDecimal.ZERO);
        materialBatch.setReservedQuantity(BigDecimal.ZERO);

        // 同步purchase_date遗留字段
        if (materialBatch.getPurchaseDate() == null && materialBatch.getReceiptDate() != null) {
            materialBatch.setPurchaseDate(materialBatch.getReceiptDate());
        }

        // 注意: totalWeight 是计算属性 (weightPerUnit × receiptQuantity)
        // 如果没有提供 weightPerUnit，使用默认值1.0 (表示1kg/单位)
        if (materialBatch.getWeightPerUnit() == null) {
            materialBatch.setWeightPerUnit(BigDecimal.ONE);
        }

        // 注意: totalPrice 和 totalValue 都是计算属性
        // totalPrice = unitPrice × receiptQuantity，会自动计算
        // 只需确保设置了 unitPrice 即可
        if (materialBatch.getUnitPrice() == null) {
            // 如果没有单价，设置默认值0
            materialBatch.setUnitPrice(BigDecimal.ZERO);
        }

        return materialBatchRepository.save(materialBatch);
    }
    public PageResponse<MaterialBatch> getMaterialReceipts(String factoryId, PageRequest pageRequest) {
        org.springframework.data.domain.PageRequest pageable = org.springframework.data.domain.PageRequest.of(
                pageRequest.getPage() - 1,
                pageRequest.getSize(),
                Sort.by(Sort.Direction.DESC, "purchaseDate")
        );
        Page<MaterialBatch> page = materialBatchRepository.findByFactoryId(factoryId, pageable);
        return PageResponse.of(
                page.getContent(),
                pageRequest.getPage(),
                pageRequest.getSize(),
                page.getTotalElements()
        );
    }
    public MaterialBatch updateMaterialReceipt(String factoryId, String batchId, MaterialBatch updates) {
        MaterialBatch batch = materialBatchRepository.findById(batchId)
                .orElseThrow(() -> new ResourceNotFoundException("原材料批次不存在"));
        if (!batch.getFactoryId().equals(factoryId)) {
            throw new BusinessException(403, "无权操作此批次")
                    .withHint("当前批次不属于该工厂, 无法操作");
        }
        // 更新允许修改的字段
        if (updates.getStorageLocation() != null) {
            batch.setStorageLocation(updates.getStorageLocation());
        }
        if (updates.getQualityCertificate() != null) {
            batch.setQualityCertificate(updates.getQualityCertificate());
        }
        if (updates.getNotes() != null) {
            batch.setNotes(updates.getNotes());
        }
        return materialBatchRepository.save(batch);
    }
    /**
     * 记录原材料消耗 (向后兼容，使用supervisorId或默认值)
     */
    public void recordMaterialConsumption(String factoryId, String productionBatchId,
                                         List<Map<String, Object>> consumptions) {
        recordMaterialConsumption(factoryId, productionBatchId, consumptions, null);
    }

    /**
     * 记录原材料消耗 (带用户ID参数)
     * N+1 修复：合并两个循环，使用缓存 Map 避免重复查询
     */
    public void recordMaterialConsumption(String factoryId, String productionBatchId,
                                         List<Map<String, Object>> consumptions, Long userId) {
        log.info("记录原材料消耗: factoryId={}, productionBatchId={}, userId={}", factoryId, productionBatchId, userId);
        ProductionBatch productionBatch = getBatchById(factoryId, productionBatchId);

        // N+1 修复：使用缓存 Map 避免重复查询
        Map<String, MaterialBatch> batchCache = new HashMap<>();
        BigDecimal totalMaterialCost = BigDecimal.ZERO;

        for (Map<String, Object> consumption : consumptions) {
            String materialBatchId = (String) consumption.get("materialBatchId");
            BigDecimal quantity = new BigDecimal(consumption.get("quantity").toString());

            // 使用 computeIfAbsent 确保每个 materialBatchId 只查询一次
            MaterialBatch materialBatch = batchCache.computeIfAbsent(materialBatchId,
                    id -> materialBatchRepository.findById(id)
                            .orElseThrow(() -> new ResourceNotFoundException("原材料批次不存在: " + id)));

            // 检查库存
            if (materialBatch.getRemainingQuantity().compareTo(quantity) < 0) {
                throw new BusinessException(409, "原材料库存不足: " + materialBatch.getBatchNumber())
                        .withHint("请减少消耗数量, 或选择其他批次");
            }
            // 更新库存
            // 注意: remainingQuantity 和 currentQuantity 是计算属性，会自动重新计算
            materialBatch.setUsedQuantity(materialBatch.getUsedQuantity().add(quantity));
            materialBatch.setLastUsedAt(LocalDateTime.now());
            // 创建消耗记录
            MaterialConsumption consumptionRecord = new MaterialConsumption();
            // ID使用自动生成策略，不需要手动设置
            consumptionRecord.setFactoryId(factoryId);  // 设置工厂ID (必填字段)
            consumptionRecord.setBatch(materialBatch);
            consumptionRecord.setBatchId(materialBatchId);  // 设置原料批次ID
            // 设置生产计划ID (从生产批次获取，如果没有则保持null，避免FK约束违反)
            if (productionBatch.getProductionPlanId() != null) {
                consumptionRecord.setProductionPlanId(productionBatch.getProductionPlanId());
            }
            // 如果productionPlanId为null，则不设置 (字段允许null)
            consumptionRecord.setProductionBatchId(parseBatchId(productionBatchId));
            consumptionRecord.setQuantity(quantity);
            // 安全获取单价，避免 NPE (如果没有设置单价，默认为 0)
            BigDecimal unitPrice = materialBatch.getUnitPrice() != null ? materialBatch.getUnitPrice() : BigDecimal.ZERO;
            consumptionRecord.setUnitPrice(unitPrice);  // 从原料批次获取单价 (必填字段)
            consumptionRecord.setTotalCost(quantity.multiply(unitPrice));  // 计算总成本 (必填字段)
            consumptionRecord.setConsumptionTime(LocalDateTime.now());  // 设置消耗时间 (必填字段)
            consumptionRecord.setConsumedAt(LocalDateTime.now());
            // 设置记录人: 优先使用传入的userId，其次使用supervisorId，最后使用批次创建者
            Long recordedBy = userId != null ? userId :
                (productionBatch.getSupervisorId() != null ? productionBatch.getSupervisorId() :
                 productionBatch.getCreatedBy());
            consumptionRecord.setRecordedBy(recordedBy);
            // 🔒 全局兜底: 增加 usedQuantity 后断言 used+reserved≤receipt, 防超扣 (误伤-proof 后置校验)
            materialBatch.assertConsumptionInvariant();
            materialBatchRepository.save(materialBatch);
            inventoryLowStockEventPublisher.publishIfLowStock(factoryId, materialBatch, "OUT");
            materialConsumptionRepository.save(consumptionRecord);

            // 同时计算成本（合并原第二个循环的逻辑）
            BigDecimal cost = quantity.multiply(unitPrice);
            totalMaterialCost = totalMaterialCost.add(cost);
        }

        // 更新生产批次的原材料成本
        productionBatch.setMaterialCost(totalMaterialCost);
        productionBatchRepository.save(productionBatch);
    }
    // ========== 质量检验 ==========
    /**
     * PASS 阈值 fallback (合格率 >=) — 与 {@link QualityInspection#getQualityGrade()} A 级阈值对齐.
     * Issue #813 (2026-05-17). Per-factory override via ThresholdKeys.PROCESSING_PASS_THRESHOLD.
     */
    private static final BigDecimal FALLBACK_PASS_THRESHOLD = new BigDecimal("95");
    /**
     * FAIL 阈值 fallback (合格率 <) — 与 QualityInspection.getQualityGrade() C/D 分界对齐.
     * Issue #813 (2026-05-17). Per-factory override via ThresholdKeys.PROCESSING_FAIL_THRESHOLD.
     */
    private static final BigDecimal FALLBACK_FAIL_THRESHOLD = new BigDecimal("70");

    private BigDecimal resolvePassThreshold(String factoryId) {
        if (thresholdResolver == null) return FALLBACK_PASS_THRESHOLD;
        return thresholdResolver.getBigDecimal(factoryId, ThresholdKeys.PROCESSING_PASS_THRESHOLD, FALLBACK_PASS_THRESHOLD);
    }

    private BigDecimal resolveFailThreshold(String factoryId) {
        if (thresholdResolver == null) return FALLBACK_FAIL_THRESHOLD;
        return thresholdResolver.getBigDecimal(factoryId, ThresholdKeys.PROCESSING_FAIL_THRESHOLD, FALLBACK_FAIL_THRESHOLD);
    }

    public Map<String, Object> submitInspection(String factoryId, String batchId, Map<String, Object> inspection) {
        log.info("提交质检记录: factoryId={}, batchId={}", factoryId, batchId);
        BigDecimal sampleSize = new BigDecimal(inspection.get("sampleSize").toString());
        BigDecimal passCount = new BigDecimal(inspection.get("passCount").toString());
        BigDecimal failCount = new BigDecimal(inspection.get("failCount").toString());
        if (sampleSize.compareTo(BigDecimal.ZERO) <= 0) {
            throw new com.cretas.aims.exception.BusinessException("抽样数量必须大于 0");
        }
        if (passCount.compareTo(BigDecimal.ZERO) < 0 || failCount.compareTo(BigDecimal.ZERO) < 0) {
            throw new com.cretas.aims.exception.BusinessException("合格数/不合格数不能为负");
        }
        if (passCount.add(failCount).compareTo(sampleSize) > 0) {
            throw new com.cretas.aims.exception.BusinessException("合格数+不合格数不能超过抽样数量");
        }
        // Issue #813 fix A (2026-05-17): reject result=null. FE form already requires
        // it; this guards API-direct callers (curl, dev console, future mobile app).
        Object resultRaw = inspection.get("result");
        String resultStr = resultRaw == null ? null : resultRaw.toString().trim();
        if (resultStr == null || resultStr.isEmpty()) {
            throw new com.cretas.aims.exception.BusinessException("检验结果 (result) 不能为空, 需为 PASS / FAIL / CONDITIONAL / PENDING 之一");
        }
        // Issue #813 fix B (2026-05-17): reject user-supplied result that contradicts
        // computed passRate. Auditor's nightmare avoided: a 43% defective batch
        // can no longer be marked PASS.
        //   PASS         requires passRate >= 95   (A grade)
        //   FAIL         requires passRate <  70   (D grade)
        //   CONDITIONAL  requires 70 <= passRate < 95 (B/C grade)
        //   PENDING      always allowed (待复检, no verdict yet)
        BigDecimal passRate = passCount
                .divide(sampleSize, 2, RoundingMode.HALF_UP)
                .multiply(new BigDecimal(100));
        validateResultVsPassRate(factoryId, resultStr, passRate);
        QualityInspection qualityInspection = new QualityInspection();
        qualityInspection.setFactoryId(factoryId);
        qualityInspection.setProductionBatchId(parseBatchId(batchId));
        qualityInspection.setInspectorId(inspection.get("inspectorId") != null ?
                Long.valueOf(inspection.get("inspectorId").toString()) : null);
        qualityInspection.setInspectionDate(LocalDate.now());
        qualityInspection.setSampleSize(sampleSize);
        qualityInspection.setPassCount(passCount);
        qualityInspection.setFailCount(failCount);
        qualityInspection.setPassRate(passRate);
        qualityInspection.setResult(resultStr);
        qualityInspection.setNotes((String) inspection.get("notes"));
        // Round 10 Fix Task 4 (R8-α Gap #3 per-module template): forward customFields
        // from the incoming payload to the entity so QualityInspectionServiceImpl's
        // dynamicFieldService hook can persist them into the cf_* columns on
        // quality_inspections. Without this the factory's configured dynamic fields
        // (农残二次检验结果, 温度偏差记录, etc.) would silently drop at the controller
        // boundary even though the frontend sent them.
        @SuppressWarnings("unchecked")
        Map<String, Object> customFieldsFromPayload = (Map<String, Object>) inspection.get("customFields");
        if (customFieldsFromPayload != null && !customFieldsFromPayload.isEmpty()) {
            qualityInspection.setCustomFields(customFieldsFromPayload);
        }
        // 通过 QualityInspectionService 保存，FAIL 时自动创建告警 + 发布事件
        QualityInspection saved = qualityInspectionService.createInspection(factoryId, qualityInspection);
        Map<String, Object> result = new HashMap<>();
        result.put("inspection", saved);
        result.put("passRate", passRate);
        return result;
    }

    /**
     * Issue #813 fix B (2026-05-17) — 校验用户填的 result 与计算 passRate 不冲突.
     *
     * 阈值 (与 {@link QualityInspection#getQualityGrade()} 对齐):
     *   PASS         需 passRate >= 95   (A 级)
     *   FAIL         需 passRate <  70   (D 级)
     *   CONDITIONAL  需 70 <= passRate < 95 (B/C 级)
     *   PENDING      永远允许 (待复检, 无最终结论)
     *
     * 不在白名单的字符串 (大小写不敏感) 抛 BusinessException —
     * 避免诡异值如 result="UNKNOWN" 静默落库.
     */
    private void validateResultVsPassRate(String factoryId, String result, BigDecimal passRate) {
        if (result == null) return;  // 上游已 null-check
        String r = result.toUpperCase(Locale.ROOT);
        // 允许 "PENDING" (待复检) 无视 passRate
        if ("PENDING".equals(r)) return;
        // PASSED / FAILED 是旧式 alias, 与 PASS / FAIL 等价处理
        if ("PASSED".equals(r)) r = "PASS";
        if ("FAILED".equals(r)) r = "FAIL";
        if (!"PASS".equals(r) && !"FAIL".equals(r) && !"CONDITIONAL".equals(r)) {
            throw new com.cretas.aims.exception.BusinessException(
                    "无效的检验结果 [" + result + "], 需为 PASS / FAIL / CONDITIONAL / PENDING 之一");
        }
        BigDecimal passThreshold = resolvePassThreshold(factoryId);
        BigDecimal failThreshold = resolveFailThreshold(factoryId);
        if ("PASS".equals(r) && passRate.compareTo(passThreshold) < 0) {
            throw new com.cretas.aims.exception.BusinessException(
                    "合格率 " + passRate + "% < " + passThreshold + "% 不能判为 PASS, 请选择 CONDITIONAL 或 FAIL");
        }
        if ("FAIL".equals(r) && passRate.compareTo(failThreshold) >= 0) {
            throw new com.cretas.aims.exception.BusinessException(
                    "合格率 " + passRate + "% >= " + failThreshold + "% 不能判为 FAIL, 请选择 CONDITIONAL 或 PASS");
        }
        if ("CONDITIONAL".equals(r)
                && (passRate.compareTo(failThreshold) < 0
                    || passRate.compareTo(passThreshold) >= 0)) {
            throw new com.cretas.aims.exception.BusinessException(
                    "合格率 " + passRate + "% 不在 CONDITIONAL 区间 [" + failThreshold + "%, " + passThreshold + "%), 请选择 PASS 或 FAIL");
        }
    }

    public PageResponse<Map<String, Object>> getInspections(String factoryId, String batchId, PageRequest pageRequest) {
        org.springframework.data.domain.PageRequest pageable = org.springframework.data.domain.PageRequest.of(
                pageRequest.getPage() - 1,
                pageRequest.getSize(),
                Sort.by(Sort.Direction.DESC, "inspectionDate")
        );
        Page<QualityInspection> page;
        if (batchId != null) {
            page = qualityInspectionRepository.findByFactoryIdAndProductionBatchId(factoryId, parseBatchId(batchId), pageable);
        } else {
            page = qualityInspectionRepository.findByFactoryId(factoryId, pageable);
        }
        List<Map<String, Object>> inspectionMaps = page.getContent().stream()
                .map(this::convertInspectionToMap)
                .collect(Collectors.toList());
        return PageResponse.of(
                inspectionMaps,
                pageRequest.getPage(),
                pageRequest.getSize(),
                page.getTotalElements()
        );
    }

    /**
     * 获取质检详情
     */
    public Map<String, Object> getInspectionById(String factoryId, String inspectionId) {
        QualityInspection inspection = qualityInspectionRepository.findById(inspectionId)
                .filter(i -> factoryId.equals(i.getFactoryId()))
                .orElseThrow(() -> new EntityNotFoundException("QualityInspection", inspectionId));
        return convertInspectionToMap(inspection);
    }

    public Map<String, Object> getQualityStatistics(String factoryId, LocalDate startDate, LocalDate endDate) {
        List<QualityInspection> inspections = qualityInspectionRepository.findByFactoryIdAndDateRange(
                factoryId, startDate, endDate);
        Map<String, Object> statistics = new HashMap<>();
        statistics.put("totalInspections", inspections.size());
        if (!inspections.isEmpty()) {
            // 过滤掉 passRate 为 null 的记录（如 pending 状态）
            List<BigDecimal> validPassRates = inspections.stream()
                    .map(QualityInspection::getPassRate)
                    .filter(rate -> rate != null)
                    .collect(Collectors.toList());

            BigDecimal averagePassRate = BigDecimal.ZERO;
            if (!validPassRates.isEmpty()) {
                averagePassRate = validPassRates.stream()
                        .reduce(BigDecimal.ZERO, BigDecimal::add)
                        .divide(new BigDecimal(validPassRates.size()), 2, RoundingMode.HALF_UP);
            }
            statistics.put("averagePassRate", averagePassRate);
            long passedCount = inspections.stream()
                    .filter(i -> "PASS".equalsIgnoreCase(i.getResult()))
                    .count();
            statistics.put("passedBatches", passedCount);
            statistics.put("failedBatches", inspections.size() - passedCount);
        }
        return statistics;
    }
    public List<Map<String, Object>> getQualityTrends(String factoryId, Integer days) {
        LocalDate endDate = LocalDate.now();
        LocalDate startDate = endDate.minusDays(days);
        List<QualityInspection> inspections = qualityInspectionRepository.findByFactoryIdAndDateRange(
                factoryId, startDate, endDate);
        Map<LocalDate, List<QualityInspection>> groupedByDate = inspections.stream()
                .collect(Collectors.groupingBy(QualityInspection::getInspectionDate));
        List<Map<String, Object>> trends = new ArrayList<>();
        for (LocalDate date = startDate; !date.isAfter(endDate); date = date.plusDays(1)) {
            Map<String, Object> dayTrend = new HashMap<>();
            dayTrend.put("date", date);
            List<QualityInspection> dayInspections = groupedByDate.getOrDefault(date, new ArrayList<>());
            if (!dayInspections.isEmpty()) {
                // 过滤掉 passRate 为 null 的记录
                List<BigDecimal> validRates = dayInspections.stream()
                        .map(QualityInspection::getPassRate)
                        .filter(rate -> rate != null)
                        .collect(Collectors.toList());

                BigDecimal avgPassRate = BigDecimal.ZERO;
                if (!validRates.isEmpty()) {
                    avgPassRate = validRates.stream()
                            .reduce(BigDecimal.ZERO, BigDecimal::add)
                            .divide(new BigDecimal(validRates.size()), 2, RoundingMode.HALF_UP);
                }
                dayTrend.put("passRate", avgPassRate);
                dayTrend.put("inspectionCount", dayInspections.size());
            } else {
                dayTrend.put("passRate", BigDecimal.ZERO);
                dayTrend.put("inspectionCount", 0);
            }
            trends.add(dayTrend);
        }
        return trends;
    }
    // ========== 设备监控 ==========
    public void recordEquipmentUsage(String factoryId, String batchId, Long equipmentId,
                                    LocalDate startTime, LocalDate endTime) {
        // equipmentId 现在是 Long 类型，与 FactoryEquipment.id 一致
        FactoryEquipment equipment = equipmentRepository.findById(equipmentId)
                .orElseThrow(() -> new ResourceNotFoundException("设备不存在"));
        // 使用 BatchEquipmentUsage 替代 EquipmentUsage
        BatchEquipmentUsage usage = new BatchEquipmentUsage();
        usage.setEquipmentId(equipmentId);
        usage.setBatchId(parseBatchId(batchId));  // 转换为Long类型
        usage.setStartTime(startTime.atStartOfDay());
        usage.setEndTime(endTime.atTime(23, 59, 59));
        long hours = ChronoUnit.HOURS.between(usage.getStartTime(), usage.getEndTime());
        usage.setUsageHours(new BigDecimal(hours));
        // 使用设备的实际小时成本，如果没有则使用默认值50元/小时
        BigDecimal hourlyCost = equipment.getHourlyCost() != null ? equipment.getHourlyCost() : new BigDecimal("50");
        usage.setEquipmentCost(new BigDecimal(hours).multiply(hourlyCost));
        batchEquipmentUsageRepository.save(usage);
        // 更新设备使用时长
        equipment.setTotalRunningHours(
                (equipment.getTotalRunningHours() != null ? equipment.getTotalRunningHours() : 0) + (int) hours
        );
        equipment.setLastMaintenanceDate(LocalDate.now());
        equipmentRepository.save(equipment);
    }
    public List<Map<String, Object>> getEquipmentMonitoring(String factoryId) {
        List<FactoryEquipment> equipments = equipmentRepository.findByFactoryId(factoryId,
                org.springframework.data.domain.PageRequest.of(0, 1000)).getContent();
        return equipments.stream().map(equipment -> {
            Map<String, Object> monitoring = new HashMap<>();
            monitoring.put("equipmentId", equipment.getId());
            monitoring.put("name", equipment.getEquipmentName());
            monitoring.put("status", equipment.getStatus());
            monitoring.put("totalOperatingHours", equipment.getTotalRunningHours());
            monitoring.put("lastMaintenanceDate", equipment.getLastMaintenanceDate());
            // 计算利用率 - 使用 BatchEquipmentUsage
            LocalDateTime weekAgo = LocalDateTime.now().minusDays(7);
            List<BatchEquipmentUsage> recentUsages = batchEquipmentUsageRepository.findByEquipmentIdAndStartTimeAfter(
                    equipment.getId(), weekAgo);
            BigDecimal totalUsageHours = recentUsages.stream()
                    .map(BatchEquipmentUsage::getUsageHours)
                    .filter(h -> h != null)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            double utilizationRate = (totalUsageHours.doubleValue() / 168.0) * 100; // 168 = 7天 * 24小时
            monitoring.put("weeklyUtilizationRate", utilizationRate);
            return monitoring;
        }).collect(Collectors.toList());
    }
    public Map<String, Object> getEquipmentMetrics(String factoryId, Long equipmentId, Integer days) {
        // equipmentId 现在是 Long 类型，与 FactoryEquipment.id 一致
        FactoryEquipment equipment = equipmentRepository.findById(equipmentId)
                .orElseThrow(() -> new ResourceNotFoundException("设备不存在"));
        LocalDateTime startDate = LocalDateTime.now().minusDays(days);
        // 使用 BatchEquipmentUsage 替代 EquipmentUsage
        List<BatchEquipmentUsage> usages = batchEquipmentUsageRepository.findByEquipmentIdAndStartTimeAfter(
                equipmentId, startDate);
        Map<String, Object> metrics = new HashMap<>();
        metrics.put("equipment", equipment);
        BigDecimal totalUsageHours = usages.stream()
                .map(BatchEquipmentUsage::getUsageHours)
                .filter(h -> h != null)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        metrics.put("totalUsageHours", totalUsageHours);
        metrics.put("usageCount", usages.size());
        BigDecimal avgUsageHours = usages.isEmpty() ? BigDecimal.ZERO :
                totalUsageHours.divide(new BigDecimal(usages.size()), 2, RoundingMode.HALF_UP);
        metrics.put("averageUsageHours", avgUsageHours);
        return metrics;
    }
    public void recordEquipmentMaintenance(String factoryId, Long equipmentId, Map<String, Object> maintenance) {
        // equipmentId 现在是 Long 类型，与 FactoryEquipment.id 一致
        FactoryEquipment equipment = equipmentRepository.findById(equipmentId)
                .orElseThrow(() -> new ResourceNotFoundException("设备不存在"));
        equipment.setLastMaintenanceDate(LocalDate.now());
        equipment.setStatus("running");
        equipment.setNotes((String) maintenance.get("notes"));
        equipmentRepository.save(equipment);
    }
    // ========== 成本分析 ==========
    public Map<String, Object> getBatchCostAnalysis(String factoryId, String batchId) {
        ProductionBatch batch = getBatchById(factoryId, batchId);
        Map<String, Object> analysis = new HashMap<>();

        // 添加batch基本信息（作为Map而不是对象）
        Map<String, Object> batchInfo = new HashMap<>();
        batchInfo.put("batchNumber", batch.getBatchNumber());
        batchInfo.put("productName", batch.getProductName());
        batchInfo.put("actualQuantity", batch.getActualQuantity());
        batchInfo.put("goodQuantity", batch.getGoodQuantity());
        batchInfo.put("defectQuantity", batch.getDefectQuantity());
        batchInfo.put("yieldRate", batch.getYieldRate());
        batchInfo.put("startTime", batch.getStartTime());
        batchInfo.put("endTime", batch.getEndTime());
        analysis.put("batch", batchInfo);

        analysis.put("materialCost", batch.getMaterialCost());
        analysis.put("laborCost", batch.getLaborCost());
        analysis.put("equipmentCost", batch.getEquipmentCost());
        analysis.put("otherCost", batch.getOtherCost());
        analysis.put("totalCost", batch.getTotalCost());
        analysis.put("unitCost", batch.getUnitCost());
        // 成本构成比例 (null 安全处理)
        if (batch.getTotalCost() != null && batch.getTotalCost().compareTo(BigDecimal.ZERO) > 0) {
            BigDecimal totalCost = batch.getTotalCost();
            BigDecimal materialCost = batch.getMaterialCost() != null ? batch.getMaterialCost() : BigDecimal.ZERO;
            BigDecimal laborCost = batch.getLaborCost() != null ? batch.getLaborCost() : BigDecimal.ZERO;
            BigDecimal equipmentCost = batch.getEquipmentCost() != null ? batch.getEquipmentCost() : BigDecimal.ZERO;
            BigDecimal otherCost = batch.getOtherCost() != null ? batch.getOtherCost() : BigDecimal.ZERO;

            analysis.put("materialCostRatio",
                    materialCost.divide(totalCost, 2, RoundingMode.HALF_UP).multiply(new BigDecimal(100)));
            analysis.put("laborCostRatio",
                    laborCost.divide(totalCost, 2, RoundingMode.HALF_UP).multiply(new BigDecimal(100)));
            analysis.put("equipmentCostRatio",
                    equipmentCost.divide(totalCost, 2, RoundingMode.HALF_UP).multiply(new BigDecimal(100)));
            analysis.put("otherCostRatio",
                    otherCost.divide(totalCost, 2, RoundingMode.HALF_UP).multiply(new BigDecimal(100)));
        }
        return analysis;
    }

    /**
     * 获取增强的批次成本分析（包含完整业务链数据）
     * 整合原材料、设备、人工、质检等全维度信息
     */
    public Map<String, Object> getEnhancedBatchCostAnalysis(String factoryId, String batchId) {
        log.info("获取增强的批次成本分析: factoryId={}, batchId={}", factoryId, batchId);

        ProductionBatch batch = getBatchById(factoryId, batchId);
        Map<String, Object> analysis = new HashMap<>();

        // ========== 1. 基本信息 ==========
        Map<String, Object> batchInfo = new HashMap<>();
        batchInfo.put("batchNumber", batch.getBatchNumber());
        batchInfo.put("productName", batch.getProductName());
        batchInfo.put("plannedQuantity", batch.getPlannedQuantity());
        batchInfo.put("actualQuantity", batch.getActualQuantity());
        batchInfo.put("goodQuantity", batch.getGoodQuantity());
        batchInfo.put("defectQuantity", batch.getDefectQuantity());
        batchInfo.put("yieldRate", batch.getYieldRate());
        batchInfo.put("efficiency", batch.getEfficiency());
        batchInfo.put("status", batch.getStatus());
        batchInfo.put("startTime", batch.getStartTime());
        batchInfo.put("endTime", batch.getEndTime());

        // 计算生产时长
        if (batch.getStartTime() != null && batch.getEndTime() != null) {
            long hours = ChronoUnit.HOURS.between(batch.getStartTime(), batch.getEndTime());
            batchInfo.put("productionHours", hours);
        }
        analysis.put("batchInfo", batchInfo);

        // ========== 2. 生产计划对比 ==========
        if (batch.getProductionPlanId() != null) {
            productionPlanRepository.findById(batch.getProductionPlanId()).ifPresent(plan -> {
                Map<String, Object> planComparison = new HashMap<>();
                planComparison.put("planId", plan.getId());
                planComparison.put("planNumber", plan.getPlanNumber());
                // planComparison.put("plannedDate", plan.getPlannedDate());  // 暂时注释 - 数据库表中没有此字段
                planComparison.put("plannedQuantity", plan.getPlannedQuantity());
                planComparison.put("actualQuantity", batch.getActualQuantity());

                if (plan.getPlannedQuantity() != null && batch.getActualQuantity() != null) {
                    BigDecimal completionRate = batch.getActualQuantity()
                        .divide(plan.getPlannedQuantity(), 4, RoundingMode.HALF_UP)
                        .multiply(new BigDecimal(100));
                    planComparison.put("completionRate", completionRate);
                }

                planComparison.put("planStartTime", plan.getStartTime());
                planComparison.put("planEndTime", plan.getEndTime());
                planComparison.put("actualStartTime", batch.getStartTime());
                planComparison.put("actualEndTime", batch.getEndTime());
                planComparison.put("status", plan.getStatus());

                analysis.put("productionPlanComparison", planComparison);
            });
        }

        // ========== 3. 原材料消耗详情 ==========
        Long batchIdLong = parseBatchId(batchId);
        List<MaterialConsumption> consumptions = materialConsumptionRepository.findByProductionBatchId(batchIdLong);
        List<Map<String, Object>> materialDetails = new ArrayList<>();
        BigDecimal totalMaterialCost = BigDecimal.ZERO;

        for (MaterialConsumption consumption : consumptions) {
            Map<String, Object> materialDetail = new HashMap<>();
            MaterialBatch materialBatch = consumption.getBatch();
            BigDecimal quantity = consumption.getQuantity() != null ? consumption.getQuantity() : BigDecimal.ZERO;
            BigDecimal unitPrice = consumption.getUnitPrice();
            if (unitPrice == null && materialBatch != null) {
                unitPrice = materialBatch.getUnitPrice();
            }
            BigDecimal cost = consumption.getTotalCost();
            if (cost == null && unitPrice != null) {
                cost = quantity.multiply(unitPrice);
            }
            if (cost == null) {
                cost = BigDecimal.ZERO;
            }
            if (materialBatch == null) {
                materialBatch = new MaterialBatch();
                materialBatch.setBatchNumber(consumption.getBatchId());
                materialBatch.setUnitPrice(unitPrice);
            }

            materialDetail.put("consumptionId", consumption.getId());
            materialDetail.put("batchNumber", materialBatch != null ? materialBatch.getBatchNumber() : consumption.getBatchId());

            // 获取原材料名称（通过materialType关联）
            String materialName = materialBatch.getMaterialType() != null ?
                materialBatch.getMaterialType().getName() : "未知原材料";
            materialDetail.put("materialName", materialName);

            materialDetail.put("quantity", quantity);
            materialDetail.put("unit", materialBatch != null ? materialBatch.getQuantityUnit() : null);
            materialDetail.put("unitPrice", unitPrice);

            materialDetail.put("cost", cost);
            totalMaterialCost = totalMaterialCost.add(cost);

            // 供应商信息
            if (materialBatch.getSupplier() != null) {
                Map<String, Object> supplierInfo = new HashMap<>();
                supplierInfo.put("id", materialBatch.getSupplier().getId());
                supplierInfo.put("name", materialBatch.getSupplier().getName());
                supplierInfo.put("contactPerson", materialBatch.getSupplier().getContactPerson());
                materialDetail.put("supplier", supplierInfo);
            }

            // FIFO信息
            materialDetail.put("receiptDate", materialBatch.getReceiptDate());
            materialDetail.put("expireDate", materialBatch.getExpireDate());

            // 库存状态
            materialDetail.put("initialQuantity", materialBatch.getInitialQuantity());
            materialDetail.put("remainingQuantity", materialBatch.getRemainingQuantity());

            materialDetail.put("consumedAt", consumption.getConsumedAt());

            materialDetails.add(materialDetail);
        }

        if (materialDetails.isEmpty() && batch.getMaterialCost() != null) {
            totalMaterialCost = batch.getMaterialCost();
            Map<String, Object> materialSummary = new HashMap<>();
            materialSummary.put("cost", totalMaterialCost);
            materialSummary.put("note", "from batch summary cost");
            materialDetails.add(materialSummary);
        }

        analysis.put("materialConsumptions", materialDetails);
        analysis.put("materialConsumptionCount", materialDetails.size());
        analysis.put("totalMaterialCost", totalMaterialCost);

        // ========== 4. 设备使用详情 ==========
        // 使用 BatchEquipmentUsage 替代 EquipmentUsage (batchId 是 Long 类型)
        // 注: batchIdLong 已在上面定义
        List<BatchEquipmentUsage> usages = batchEquipmentUsageRepository.findByBatchId(batchIdLong);
        List<Map<String, Object>> equipmentDetails = new ArrayList<>();
        BigDecimal totalEquipmentCost = BigDecimal.ZERO;
        BigDecimal totalEquipmentHours = BigDecimal.ZERO;

        // N+1 修复：预先批量查询所有设备，使用 Map 缓存
        Set<Long> equipmentIds = usages.stream()
                .map(BatchEquipmentUsage::getEquipmentId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        Map<Long, FactoryEquipment> equipmentCache = equipmentRepository.findByIdIn(equipmentIds)
                .stream()
                .collect(Collectors.toMap(FactoryEquipment::getId, e -> e));

        for (BatchEquipmentUsage usage : usages) {
            Map<String, Object> equipmentDetail = new HashMap<>();

            // N+1 修复：使用预加载的 Map 进行查找
            Long equipmentIdLong = usage.getEquipmentId();
            BigDecimal hourlyCost = new BigDecimal("50"); // 默认值
            FactoryEquipment equipment = equipmentCache.get(equipmentIdLong);
            if (equipment != null) {
                equipmentDetail.put("equipmentId", equipment.getId());
                equipmentDetail.put("equipmentName", equipment.getEquipmentName());
                equipmentDetail.put("equipmentCode", equipment.getEquipmentCode());
                equipmentDetail.put("model", equipment.getModel());
                equipmentDetail.put("status", equipment.getStatus());
                equipmentDetail.put("hourlyCost", equipment.getHourlyCost());
                // 获取设备的实际小时成本
                if (equipment.getHourlyCost() != null) {
                    hourlyCost = equipment.getHourlyCost();
                }
            }

            equipmentDetail.put("usageId", usage.getId());
            equipmentDetail.put("startTime", usage.getStartTime());
            equipmentDetail.put("endTime", usage.getEndTime());
            equipmentDetail.put("usageHours", usage.getUsageHours());

            // 使用记录中已存储的设备成本，如果没有则按设备实际小时成本计算
            BigDecimal equipmentCost = usage.getEquipmentCost() != null ? usage.getEquipmentCost() :
                    (usage.getUsageHours() != null ? usage.getUsageHours().multiply(hourlyCost) : BigDecimal.ZERO);
            equipmentDetail.put("cost", equipmentCost);

            totalEquipmentCost = totalEquipmentCost.add(equipmentCost);
            if (usage.getUsageHours() != null) {
                totalEquipmentHours = totalEquipmentHours.add(usage.getUsageHours());
            }

            equipmentDetails.add(equipmentDetail);
        }

        if (equipmentDetails.isEmpty() && batch.getEquipmentCost() != null) {
            totalEquipmentCost = batch.getEquipmentCost();
            Map<String, Object> equipmentSummary = new HashMap<>();
            equipmentSummary.put("cost", totalEquipmentCost);
            equipmentSummary.put("note", "from batch summary cost");
            equipmentDetails.add(equipmentSummary);
        }

        analysis.put("equipmentUsages", equipmentDetails);
        analysis.put("equipmentUsageCount", equipmentDetails.size());
        analysis.put("totalEquipmentHours", totalEquipmentHours);
        analysis.put("totalEquipmentCost", totalEquipmentCost);

        // ========== 5. 人工工时详情 ==========
        // 查询 BatchWorkSession 获取详细的工时数据
        List<BatchWorkSession> workSessions = batchWorkSessionRepository.findByBatchId(batchIdLong);
        List<Map<String, Object>> laborDetails = new ArrayList<>();
        BigDecimal totalLaborCost = BigDecimal.ZERO;
        int totalWorkMinutes = 0;
        Set<Long> uniqueEmployeeIds = new HashSet<>();

        for (BatchWorkSession session : workSessions) {
            Map<String, Object> laborDetail = new HashMap<>();
            laborDetail.put("sessionId", session.getId());
            laborDetail.put("workMinutes", session.getWorkMinutes());
            laborDetail.put("laborCost", session.getLaborCost());
            laborDetail.put("employeeId", session.getEmployeeId());

            // 获取员工信息
            if (session.getEmployeeId() != null) {
                userRepository.findById(session.getEmployeeId()).ifPresent(employee -> {
                    laborDetail.put("employeeName", employee.getFullName());
                    laborDetail.put("employeeRole", employee.getRole());
                });
                uniqueEmployeeIds.add(session.getEmployeeId());
            }

            // 获取工作会话详情
            if (session.getWorkSession() != null) {
                EmployeeWorkSession ws = session.getWorkSession();
                // 从startTime提取日期
                laborDetail.put("workDate", ws.getStartTime() != null ? ws.getStartTime().toLocalDate() : null);
                laborDetail.put("startTime", ws.getStartTime());
                laborDetail.put("endTime", ws.getEndTime());
            }

            laborDetails.add(laborDetail);

            // 累加总计
            if (session.getLaborCost() != null) {
                totalLaborCost = totalLaborCost.add(session.getLaborCost());
            }
            if (session.getWorkMinutes() != null) {
                totalWorkMinutes += session.getWorkMinutes();
            }
        }

        // 如果没有 BatchWorkSession 数据，使用 ProductionBatch 中的汇总数据作为兜底
        if (workSessions.isEmpty()) {
            totalLaborCost = batch.getLaborCost() != null ? batch.getLaborCost() : BigDecimal.ZERO;
            totalWorkMinutes = batch.getWorkDurationMinutes() != null ? batch.getWorkDurationMinutes() : 0;

            if (totalLaborCost.compareTo(BigDecimal.ZERO) > 0 || totalWorkMinutes > 0) {
                Map<String, Object> laborSummary = new HashMap<>();
                laborSummary.put("workMinutes", totalWorkMinutes);
                laborSummary.put("laborCost", totalLaborCost);
                laborSummary.put("workerCount", batch.getWorkerCount());
                laborSummary.put("note", "来自批次汇总数据");
                laborDetails.add(laborSummary);
            }
        }

        analysis.put("laborSessions", laborDetails);
        analysis.put("laborSessionCount", laborDetails.size());
        analysis.put("totalWorkMinutes", totalWorkMinutes);
        analysis.put("totalWorkHours", totalWorkMinutes / 60.0);
        analysis.put("totalLaborCost", totalLaborCost);
        analysis.put("uniqueWorkerCount", workSessions.isEmpty() ?
            (batch.getWorkerCount() != null ? batch.getWorkerCount() : 0) : uniqueEmployeeIds.size());

        // ========== 6. 质量检验详情 ==========
        List<QualityInspection> inspections = qualityInspectionRepository.findByFactoryIdAndProductionBatchId(
            factoryId, batchIdLong, org.springframework.data.domain.PageRequest.of(0, 100)).getContent();

        List<Map<String, Object>> qualityDetails = new ArrayList<>();

        for (QualityInspection inspection : inspections) {
            Map<String, Object> qualityDetail = new HashMap<>();

            qualityDetail.put("inspectionId", inspection.getId());
            qualityDetail.put("inspectionDate", inspection.getInspectionDate());
            qualityDetail.put("sampleSize", inspection.getSampleSize());
            qualityDetail.put("passCount", inspection.getPassCount());
            qualityDetail.put("failCount", inspection.getFailCount());
            qualityDetail.put("passRate", inspection.getPassRate());
            qualityDetail.put("result", inspection.getResult());
            qualityDetail.put("notes", inspection.getNotes());

            // 检验员信息
            if (inspection.getInspectorId() != null) {
                userRepository.findById(inspection.getInspectorId()).ifPresent(inspector -> {
                    Map<String, Object> inspectorInfo = new HashMap<>();
                    inspectorInfo.put("id", inspector.getId());
                    inspectorInfo.put("fullName", inspector.getFullName());
                    qualityDetail.put("inspector", inspectorInfo);
                });
            }

            qualityDetails.add(qualityDetail);
        }

        analysis.put("qualityInspections", qualityDetails);
        analysis.put("qualityInspectionCount", qualityDetails.size());

        // 计算平均合格率
        List<BigDecimal> validPassRates = inspections.stream()
            .map(QualityInspection::getPassRate)
            .filter(Objects::nonNull)
            .toList();
        if (!validPassRates.isEmpty()) {
            BigDecimal avgPassRate = validPassRates.stream()
                .reduce(BigDecimal.ZERO, BigDecimal::add)
                .divide(new BigDecimal(validPassRates.size()), 2, RoundingMode.HALF_UP);
            analysis.put("averagePassRate", avgPassRate);
        }

        // ========== 7. 加工环节详情 ==========
        try {
            List<ProcessingStageRecordDTO> stageRecords = processingStageRecordService
                .getByBatchIdWithComparison(factoryId, batchIdLong);

            List<Map<String, Object>> stageDetails = new ArrayList<>();

            for (ProcessingStageRecordDTO stage : stageRecords) {
                Map<String, Object> stageDetail = new HashMap<>();

                // 基本信息
                stageDetail.put("id", stage.getId());
                stageDetail.put("stageType", stage.getStageType());
                stageDetail.put("stageName", stage.getStageName());
                stageDetail.put("stageOrder", stage.getStageOrder());

                // 时间数据
                stageDetail.put("startTime", stage.getStartTime());
                stageDetail.put("endTime", stage.getEndTime());
                stageDetail.put("durationMinutes", stage.getDurationMinutes());
                stageDetail.put("avgDuration", stage.getAvgDuration());

                // 重量/数量数据
                stageDetail.put("inputWeight", stage.getInputWeight());
                stageDetail.put("outputWeight", stage.getOutputWeight());
                stageDetail.put("lossWeight", stage.getLossWeight());
                stageDetail.put("lossRate", stage.getLossRate());
                stageDetail.put("avgLossRate", stage.getAvgLossRate());

                // 温度数据
                stageDetail.put("ambientTemperature", stage.getAmbientTemperature());
                stageDetail.put("productTemperature", stage.getProductTemperature());
                stageDetail.put("targetTemperature", stage.getTargetTemperature());

                // 质量数据
                stageDetail.put("passCount", stage.getPassCount());
                stageDetail.put("failCount", stage.getFailCount());
                stageDetail.put("passRate", stage.getPassRate());
                stageDetail.put("avgPassRate", stage.getAvgPassRate());
                stageDetail.put("reworkCount", stage.getReworkCount());

                // 设备数据
                stageDetail.put("equipmentId", stage.getEquipmentId());
                stageDetail.put("equipmentName", stage.getEquipmentName());
                stageDetail.put("equipmentRunTime", stage.getEquipmentRunTime());
                stageDetail.put("equipmentDowntime", stage.getEquipmentDowntime());
                stageDetail.put("equipmentOee", stage.getEquipmentOee());

                // 资源消耗数据
                stageDetail.put("waterUsage", stage.getWaterUsage());
                stageDetail.put("powerUsage", stage.getPowerUsage());
                stageDetail.put("auxiliaryUsage", stage.getAuxiliaryUsage());
                stageDetail.put("auxiliaryName", stage.getAuxiliaryName());

                // 特殊环节数据
                stageDetail.put("dripLoss", stage.getDripLoss());
                stageDetail.put("thicknessSd", stage.getThicknessSd());
                stageDetail.put("marinadeAbsorption", stage.getMarinadeAbsorption());
                stageDetail.put("phValue", stage.getPhValue());
                stageDetail.put("salinity", stage.getSalinity());
                stageDetail.put("atpResult", stage.getAtpResult());
                stageDetail.put("microPassRate", stage.getMicroPassRate());
                stageDetail.put("ccpPassRate", stage.getCcpPassRate());

                // 操作员信息
                stageDetail.put("operatorId", stage.getOperatorId());
                stageDetail.put("operatorName", stage.getOperatorName());
                stageDetail.put("workerCount", stage.getWorkerCount());

                // 其他
                stageDetail.put("notes", stage.getNotes());

                stageDetails.add(stageDetail);
            }

            analysis.put("processingStages", stageDetails);
            analysis.put("processingStageCount", stageDetails.size());

            // 添加AI分析格式化的环节数据
            if (!stageRecords.isEmpty()) {
                Map<String, String> aiFormatData = processingStageRecordService
                    .formatForAIAnalysis(factoryId, batchIdLong);
                analysis.put("processingStageAIFormat", aiFormatData);
            }
        } catch (Exception e) {
            log.warn("获取加工环节数据失败: {}", e.getMessage());
            analysis.put("processingStages", new ArrayList<>());
            analysis.put("processingStageCount", 0);
        }

        // ========== 8. 成本汇总 ==========
        Map<String, Object> costSummary = new HashMap<>();
        costSummary.put("materialCost", totalMaterialCost);
        costSummary.put("laborCost", totalLaborCost);
        costSummary.put("equipmentCost", totalEquipmentCost);
        costSummary.put("otherCost", batch.getOtherCost() != null ? batch.getOtherCost() : BigDecimal.ZERO);

        BigDecimal totalCost = totalMaterialCost.add(totalLaborCost).add(totalEquipmentCost)
            .add(batch.getOtherCost() != null ? batch.getOtherCost() : BigDecimal.ZERO);
        costSummary.put("totalCost", totalCost);

        // 单位成本 - 优先使用良品数量
        BigDecimal quantityForUnitCost = batch.getGoodQuantity() != null && batch.getGoodQuantity().compareTo(BigDecimal.ZERO) > 0
                ? batch.getGoodQuantity() : batch.getActualQuantity();
        if (quantityForUnitCost != null && quantityForUnitCost.compareTo(BigDecimal.ZERO) > 0) {
            BigDecimal unitCost = totalCost.divide(quantityForUnitCost, 4, RoundingMode.HALF_UP);
            costSummary.put("unitCost", unitCost);
            costSummary.put("unitCostBasis", batch.getGoodQuantity() != null && batch.getGoodQuantity().compareTo(BigDecimal.ZERO) > 0
                    ? "goodQuantity" : "actualQuantity");
        }

        // 成本占比
        if (totalCost.compareTo(BigDecimal.ZERO) > 0) {
            costSummary.put("materialCostRatio",
                totalMaterialCost.divide(totalCost, 4, RoundingMode.HALF_UP).multiply(new BigDecimal(100)));
            costSummary.put("laborCostRatio",
                totalLaborCost.divide(totalCost, 4, RoundingMode.HALF_UP).multiply(new BigDecimal(100)));
            costSummary.put("equipmentCostRatio",
                totalEquipmentCost.divide(totalCost, 4, RoundingMode.HALF_UP).multiply(new BigDecimal(100)));
        }

        analysis.put("costSummary", costSummary);

        // ========== 8.1 前端兼容的costBreakdown结构（CostAnalysisDashboard使用） ==========
        Map<String, Object> costBreakdown = new HashMap<>();
        costBreakdown.put("rawMaterialCost", totalMaterialCost);
        costBreakdown.put("laborCost", totalLaborCost);
        costBreakdown.put("equipmentCost", totalEquipmentCost);
        costBreakdown.put("totalCost", totalCost);

        // 百分比计算
        if (totalCost.compareTo(BigDecimal.ZERO) > 0) {
            BigDecimal rawMaterialPct = totalMaterialCost.divide(totalCost, 4, RoundingMode.HALF_UP).multiply(new BigDecimal(100));
            BigDecimal laborPct = totalLaborCost.divide(totalCost, 4, RoundingMode.HALF_UP).multiply(new BigDecimal(100));
            BigDecimal equipmentPct = totalEquipmentCost.divide(totalCost, 4, RoundingMode.HALF_UP).multiply(new BigDecimal(100));
            costBreakdown.put("rawMaterialPercentage", rawMaterialPct.setScale(1, RoundingMode.HALF_UP));
            costBreakdown.put("laborPercentage", laborPct.setScale(1, RoundingMode.HALF_UP));
            costBreakdown.put("equipmentPercentage", equipmentPct.setScale(1, RoundingMode.HALF_UP));
        } else {
            costBreakdown.put("rawMaterialPercentage", 0);
            costBreakdown.put("laborPercentage", 0);
            costBreakdown.put("equipmentPercentage", 0);
        }
        analysis.put("costBreakdown", costBreakdown);

        // ========== 9. 风险预警 ==========
        List<String> risks = new ArrayList<>();

        // 原材料过期风险
        for (MaterialConsumption consumption : consumptions) {
            MaterialBatch mb = consumption.getBatch();
            if (mb != null && mb.getExpireDate() != null && mb.getExpireDate().isBefore(LocalDate.now().plusDays(7))) {
                String matName = mb.getMaterialType() != null ?
                    mb.getMaterialType().getName() : "未知原材料";
                risks.add("原材料 " + matName + " 批次 " + mb.getBatchNumber() + " 即将过期");
            }
        }

        // 良品率风险
        if (batch.getYieldRate() != null && batch.getYieldRate().compareTo(new BigDecimal("90")) < 0) {
            risks.add("良品率偏低（" + batch.getYieldRate() + "%），建议加强质量控制");
        }

        // 成本占比风险
        if (totalCost.compareTo(BigDecimal.ZERO) > 0) {
            BigDecimal materialRatio = totalMaterialCost.divide(totalCost, 4, RoundingMode.HALF_UP);
            if (materialRatio.compareTo(new BigDecimal("0.65")) > 0) {
                risks.add("原材料成本占比过高（" + materialRatio.multiply(new BigDecimal(100)) + "%），建议优化采购");
            }
        }

        analysis.put("risks", risks);
        analysis.put("riskCount", risks.size());

        log.info("增强的批次成本分析完成: batchId={}, 原材料{}种, 设备{}台, 人工{}人次, 质检{}次, 加工环节{}个",
                 batchId, materialDetails.size(), equipmentDetails.size(), laborDetails.size(), qualityDetails.size(),
                 analysis.get("processingStageCount"));

        return analysis;
    }
    public ProductionBatch recalculateBatchCost(String factoryId, String batchId) {
        ProductionBatch batch = getBatchById(factoryId, batchId);
        Long batchIdLong = parseBatchId(batchId);

        // 重新计算原材料成本
        List<MaterialConsumption> consumptions = materialConsumptionRepository.findByProductionBatchId(batchIdLong);
        BigDecimal materialCost = consumptions.stream()
                .map(c -> c.getQuantity().multiply(c.getBatch().getUnitPrice()))
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        batch.setMaterialCost(materialCost);

        // 重新计算设备成本 - 使用 BatchEquipmentUsage
        List<BatchEquipmentUsage> usages = batchEquipmentUsageRepository.findByBatchId(batchIdLong);
        // 优先使用记录中已存储的成本，如果没有则按设备实际小时成本计算
        BigDecimal equipmentCost = BigDecimal.ZERO;
        for (BatchEquipmentUsage usage : usages) {
            if (usage.getEquipmentCost() != null) {
                equipmentCost = equipmentCost.add(usage.getEquipmentCost());
            } else if (usage.getUsageHours() != null) {
                // 获取设备的实际小时成本
                BigDecimal hourlyCost = new BigDecimal("50"); // 默认值
                Long equipmentId = usage.getEquipmentId();
                if (equipmentId != null) {
                    FactoryEquipment equipment = equipmentRepository.findById(equipmentId).orElse(null);
                    if (equipment != null && equipment.getHourlyCost() != null) {
                        hourlyCost = equipment.getHourlyCost();
                    }
                }
                equipmentCost = equipmentCost.add(usage.getUsageHours().multiply(hourlyCost));
            }
        }
        batch.setEquipmentCost(equipmentCost);

        // 重新计算人工成本 - 使用 BatchWorkSession
        List<BatchWorkSession> workSessions = batchWorkSessionRepository.findByBatchId(batchIdLong);
        BigDecimal laborCost = workSessions.stream()
                .map(s -> s.getLaborCost() != null ? s.getLaborCost() : BigDecimal.ZERO)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        if (laborCost.compareTo(BigDecimal.ZERO) > 0) {
            batch.setLaborCost(laborCost);
        }

        // 重新计算总成本
        batch.calculateMetrics();
        return productionBatchRepository.save(batch);
    }
    public Map<String, Object> getAICostAnalysis(String factoryId, String batchId) {
        ProductionBatch batch = getBatchById(factoryId, batchId);
        Map<String, Object> aiAnalysis = new HashMap<>();
        aiAnalysis.put("batch", batch);
        List<String> suggestions = new ArrayList<>();
        // 基于成本分析提供建议 (null 和零值安全处理)
        if (batch.getMaterialCost() != null && batch.getTotalCost() != null
                && batch.getTotalCost().compareTo(BigDecimal.ZERO) > 0) {
            BigDecimal materialRatio = batch.getMaterialCost().divide(batch.getTotalCost(), 2, RoundingMode.HALF_UP);
            if (materialRatio.compareTo(new BigDecimal("0.6")) > 0) {
                suggestions.add("原材料成本占比过高（" + materialRatio.multiply(new BigDecimal(100)) + "%），建议优化采购策略或寻找替代供应商");
            }
        }
        if (batch.getYieldRate() != null && batch.getYieldRate().compareTo(new BigDecimal("90")) < 0) {
            suggestions.add("良品率偏低（" + batch.getYieldRate() + "%），建议加强质量控制和工艺优化");
        }
        if (batch.getEfficiency() != null && batch.getEfficiency().compareTo(new BigDecimal("80")) < 0) {
            suggestions.add("生产效率偏低（" + batch.getEfficiency() + "%），建议优化生产流程和人员配置");
        }
        aiAnalysis.put("suggestions", suggestions);
        aiAnalysis.put("potentialSavings", calculatePotentialSavings(batch));
        return aiAnalysis;
    }
    // ========== 仪表盘 ==========
    public Map<String, Object> getDashboardOverview(String factoryId) {
        log.info("===== getDashboardOverview 开始: factoryId={} =====", factoryId);
        long startTime = System.currentTimeMillis();
        long lastQueryTime = startTime;

        Map<String, Object> overview = new HashMap<>();
        LocalDateTime todayStart = LocalDate.now().atStartOfDay();
        LocalDateTime monthStart = LocalDate.now().withDayOfMonth(1).atStartOfDay();

        // ===== summary (概览) =====
        Map<String, Object> summary = new HashMap<>();

        // 查询1: 今日批次总数
        log.info("[查询1] countByFactoryIdAndCreatedAtAfter 开始");
        long todayBatches = productionBatchRepository.countByFactoryIdAndCreatedAtAfter(factoryId, todayStart);
        log.info("[查询1] 完成: todayBatches={}, 耗时={}ms", todayBatches, System.currentTimeMillis() - lastQueryTime);
        lastQueryTime = System.currentTimeMillis();
        summary.put("totalBatches", todayBatches);

        // 查询2: 进行中批次数
        log.info("[查询2] countByFactoryIdAndStatus 开始");
        long activeBatches = productionBatchRepository.countByFactoryIdAndStatus(
                factoryId, ProductionBatchStatus.IN_PROGRESS);
        log.info("[查询2] 完成: activeBatches={}, 耗时={}ms", activeBatches, System.currentTimeMillis() - lastQueryTime);
        lastQueryTime = System.currentTimeMillis();
        summary.put("activeBatches", activeBatches);

        // 查询3: 已完成批次数
        log.info("[查询3] countByFactoryIdAndStatusAndCreatedAtAfter 开始");
        long completedBatches = productionBatchRepository.countByFactoryIdAndStatusAndCreatedAtAfter(
                factoryId, ProductionBatchStatus.COMPLETED, todayStart);
        log.info("[查询3] 完成: completedBatches={}, 耗时={}ms", completedBatches, System.currentTimeMillis() - lastQueryTime);
        lastQueryTime = System.currentTimeMillis();
        summary.put("completedBatches", completedBatches);

        // 查询4: 今日质检数
        log.info("[查询4] countByFactoryIdAndInspectionDateAfter 开始");
        long qualityInspections = qualityInspectionRepository.countByFactoryIdAndInspectionDateAfter(
                factoryId, todayStart.toLocalDate());
        log.info("[查询4] 完成: qualityInspections={}, 耗时={}ms", qualityInspections, System.currentTimeMillis() - lastQueryTime);
        lastQueryTime = System.currentTimeMillis();
        summary.put("qualityInspections", qualityInspections);

        // 查询5: 活跃告警数
        log.info("[查询5] findByFactoryIdAndStatusOrderByTriggeredAtDesc 开始");
        List<EquipmentAlert> activeAlerts = equipmentAlertRepository.findByFactoryIdAndStatusOrderByTriggeredAtDesc(
                factoryId, AlertStatus.ACTIVE);
        log.info("[查询5] 完成: activeAlerts.size={}, 耗时={}ms", activeAlerts.size(), System.currentTimeMillis() - lastQueryTime);
        lastQueryTime = System.currentTimeMillis();
        summary.put("activeAlerts", activeAlerts.size());

        // 查询6: 工人数量统计
        log.info("[查询6] countByFactoryId(user) 开始");
        long totalWorkers = userRepository.countByFactoryId(factoryId);
        log.info("[查询6] 完成: totalWorkers={}, 耗时={}ms", totalWorkers, System.currentTimeMillis() - lastQueryTime);
        lastQueryTime = System.currentTimeMillis();
        summary.put("totalWorkers", totalWorkers);
        summary.put("onDutyWorkers", totalWorkers);
        overview.put("summary", summary);

        // ===== todayStats (今日统计) =====
        Map<String, Object> todayStats = new HashMap<>();
        todayStats.put("productionCount", todayBatches);
        todayStats.put("qualityCheckCount", qualityInspections);

        // 查询7: 今日入库原材料数
        log.info("[查询7] countByFactoryIdAndReceiptDateAfter 开始");
        long materialReceived = materialBatchRepository.countByFactoryIdAndReceiptDateAfter(factoryId, todayStart);
        log.info("[查询7] 完成: materialReceived={}, 耗时={}ms", materialReceived, System.currentTimeMillis() - lastQueryTime);
        lastQueryTime = System.currentTimeMillis();
        todayStats.put("materialReceived", materialReceived);

        // 查询8: 总原材料批次数
        log.info("[查询8] countByFactoryId(material) 开始");
        long totalMaterialBatches = materialBatchRepository.countByFactoryId(factoryId);
        log.info("[查询8] 完成: totalMaterialBatches={}, 耗时={}ms", totalMaterialBatches, System.currentTimeMillis() - lastQueryTime);
        lastQueryTime = System.currentTimeMillis();
        todayStats.put("totalMaterialBatches", totalMaterialBatches);
        todayStats.put("ordersCompleted", completedBatches);

        // 查询9: 生产效率
        log.info("[查询9] calculateAverageEfficiency 开始");
        BigDecimal avgEfficiency = productionBatchRepository.calculateAverageEfficiency(factoryId, todayStart);
        log.info("[查询9] 完成: avgEfficiency={}, 耗时={}ms", avgEfficiency, System.currentTimeMillis() - lastQueryTime);
        lastQueryTime = System.currentTimeMillis();
        todayStats.put("productionEfficiency", avgEfficiency != null ? avgEfficiency : BigDecimal.ZERO);
        todayStats.put("activeWorkers", totalWorkers);

        // 查询10: 今日产量
        log.info("[查询10] calculateTotalOutputAfter 开始");
        BigDecimal todayOutput = productionBatchRepository.calculateTotalOutputAfter(factoryId, todayStart);
        log.info("[查询10] 完成: todayOutput={}, 耗时={}ms", todayOutput, System.currentTimeMillis() - lastQueryTime);
        lastQueryTime = System.currentTimeMillis();
        todayStats.put("todayOutputKg", todayOutput != null ? todayOutput : BigDecimal.ZERO);
        todayStats.put("totalBatches", todayBatches);
        todayStats.put("totalWorkers", totalWorkers);

        // 查询11: 设备总数
        log.info("[查询11] countByFactoryId(equipment) 开始");
        long totalEquipment = equipmentRepository.countByFactoryId(factoryId);
        log.info("[查询11] 完成: totalEquipment={}, 耗时={}ms", totalEquipment, System.currentTimeMillis() - lastQueryTime);
        lastQueryTime = System.currentTimeMillis();

        // 查询12: 活跃设备数
        log.info("[查询12] countByFactoryIdAndStatus(equipment) 开始");
        long activeEquipment = equipmentRepository.countByFactoryIdAndStatus(factoryId, "active");
        log.info("[查询12] 完成: activeEquipment={}, 耗时={}ms", activeEquipment, System.currentTimeMillis() - lastQueryTime);
        lastQueryTime = System.currentTimeMillis();
        todayStats.put("totalEquipment", totalEquipment);
        todayStats.put("activeEquipment", activeEquipment);
        overview.put("todayStats", todayStats);

        // ===== yesterdayStats (昨日统计，用于计算涨幅) =====
        LocalDateTime yesterdayStart = LocalDate.now().minusDays(1).atStartOfDay();
        LocalDateTime yesterdayEnd = LocalDate.now().atStartOfDay();
        Map<String, Object> yesterdayStats = new HashMap<>();

        // 查询13: 昨日批次总数
        log.info("[查询13] countByFactoryIdAndCreatedAtBetween 开始");
        long yesterdayBatches = productionBatchRepository.countByFactoryIdAndCreatedAtBetween(
                factoryId, yesterdayStart, yesterdayEnd);
        log.info("[查询13] 完成: yesterdayBatches={}, 耗时={}ms", yesterdayBatches, System.currentTimeMillis() - lastQueryTime);
        lastQueryTime = System.currentTimeMillis();
        yesterdayStats.put("totalBatches", yesterdayBatches);

        // 查询14: 昨日产量
        log.info("[查询14] calculateTotalOutputBetween 开始");
        BigDecimal yesterdayOutput = productionBatchRepository.calculateTotalOutputBetween(
                factoryId, yesterdayStart, yesterdayEnd);
        log.info("[查询14] 完成: yesterdayOutput={}, 耗时={}ms", yesterdayOutput, System.currentTimeMillis() - lastQueryTime);
        lastQueryTime = System.currentTimeMillis();
        yesterdayStats.put("outputKg", yesterdayOutput != null ? yesterdayOutput : BigDecimal.ZERO);

        // 查询15: 昨日完成批次
        log.info("[查询15] countByFactoryIdAndStatusAndCreatedAtBetween 开始");
        long yesterdayCompleted = productionBatchRepository.countByFactoryIdAndStatusAndCreatedAtBetween(
                factoryId, ProductionBatchStatus.COMPLETED, yesterdayStart, yesterdayEnd);
        log.info("[查询15] 完成: yesterdayCompleted={}, 耗时={}ms", yesterdayCompleted, System.currentTimeMillis() - lastQueryTime);
        lastQueryTime = System.currentTimeMillis();
        yesterdayStats.put("completedBatches", yesterdayCompleted);
        overview.put("yesterdayStats", yesterdayStats);

        // ===== kpi (关键绩效指标) =====
        Map<String, Object> kpi = new HashMap<>();
        kpi.put("productionEfficiency", avgEfficiency != null ? avgEfficiency : BigDecimal.ZERO);

        // 查询16: 质量合格率
        log.info("[查询16] calculateAverageYieldRate 开始");
        BigDecimal monthlyYieldRate = productionBatchRepository.calculateAverageYieldRate(factoryId, monthStart);
        log.info("[查询16] 完成: monthlyYieldRate={}, 耗时={}ms", monthlyYieldRate, System.currentTimeMillis() - lastQueryTime);
        lastQueryTime = System.currentTimeMillis();
        kpi.put("qualityPassRate", monthlyYieldRate != null ? monthlyYieldRate : BigDecimal.ZERO);

        // 设备利用率（计算，无查询）
        BigDecimal equipmentUtilization = totalEquipment > 0 ?
                BigDecimal.valueOf(activeEquipment * 100.0 / totalEquipment).setScale(2, RoundingMode.HALF_UP) :
                BigDecimal.ZERO;
        kpi.put("equipmentUtilization", equipmentUtilization);

        // 查询17: 单位成本 - 本月成本总和
        log.info("[查询17] calculateTotalCostAfter 开始");
        BigDecimal monthlyTotalCost = productionBatchRepository.calculateTotalCostAfter(factoryId, monthStart);
        log.info("[查询17] 完成: monthlyTotalCost={}, 耗时={}ms", monthlyTotalCost, System.currentTimeMillis() - lastQueryTime);
        lastQueryTime = System.currentTimeMillis();

        // 查询18: 本月产量总和
        log.info("[查询18] calculateTotalOutputAfter(month) 开始");
        BigDecimal monthlyTotalOutput = productionBatchRepository.calculateTotalOutputAfter(factoryId, monthStart);
        log.info("[查询18] 完成: monthlyTotalOutput={}, 耗时={}ms", monthlyTotalOutput, System.currentTimeMillis() - lastQueryTime);
        lastQueryTime = System.currentTimeMillis();

        BigDecimal unitCost = BigDecimal.ZERO;
        if (monthlyTotalOutput != null && monthlyTotalOutput.compareTo(BigDecimal.ZERO) > 0) {
            unitCost = (monthlyTotalCost != null ? monthlyTotalCost : BigDecimal.ZERO)
                    .divide(monthlyTotalOutput, 2, RoundingMode.HALF_UP);
        }
        kpi.put("unitCost", unitCost);

        // 查询19: 平均生产周期
        log.info("[查询19] findCompletedBatchesWithTimes 开始");
        BigDecimal avgCycleHours = BigDecimal.ZERO;
        java.util.List<ProductionBatch> batchesWithTimes = productionBatchRepository.findCompletedBatchesWithTimes(factoryId, monthStart);
        log.info("[查询19] 完成: batchesWithTimes.size={}, 耗时={}ms", batchesWithTimes != null ? batchesWithTimes.size() : 0, System.currentTimeMillis() - lastQueryTime);
        lastQueryTime = System.currentTimeMillis();

        if (batchesWithTimes != null && !batchesWithTimes.isEmpty()) {
            long totalHours = 0;
            int cycleCount = 0;
            for (ProductionBatch batch : batchesWithTimes) {
                if (batch.getStartTime() != null && batch.getEndTime() != null) {
                    long hours = java.time.Duration.between(batch.getStartTime(), batch.getEndTime()).toHours();
                    totalHours += hours;
                    cycleCount++;
                }
            }
            if (cycleCount > 0) {
                avgCycleHours = BigDecimal.valueOf(totalHours).divide(BigDecimal.valueOf(cycleCount), 1, RoundingMode.HALF_UP);
            }
        }
        kpi.put("avgCycleHours", avgCycleHours);

        overview.put("kpi", kpi);

        // ===== alerts (告警状态) =====
        Map<String, Object> alerts = new HashMap<>();
        alerts.put("active", activeAlerts.size());
        alerts.put("status", activeAlerts.isEmpty() ? "normal" : "warning");
        overview.put("alerts", alerts);

        // ===== period =====
        overview.put("period", "today");

        // ===== 保留旧字段（向后兼容） =====
        overview.put("todayBatches", todayBatches);
        overview.put("inProgressBatches", activeBatches);

        // 查询20: 月产量
        log.info("[查询20] calculateMonthlyOutput 开始");
        BigDecimal monthlyOutput = productionBatchRepository.calculateMonthlyOutput(factoryId, monthStart);
        log.info("[查询20] 完成: monthlyOutput={}, 耗时={}ms", monthlyOutput, System.currentTimeMillis() - lastQueryTime);
        lastQueryTime = System.currentTimeMillis();
        overview.put("monthlyOutput", monthlyOutput != null ? monthlyOutput : BigDecimal.ZERO);
        overview.put("monthlyYieldRate", monthlyYieldRate != null ? monthlyYieldRate : BigDecimal.ZERO);

        // 查询21: 低库存原材料
        log.info("[查询21] countLowStockMaterials 开始");
        Long lowStockMaterials = materialBatchRepository.countLowStockMaterials(factoryId);
        log.info("[查询21] 完成: lowStockMaterials={}, 耗时={}ms", lowStockMaterials, System.currentTimeMillis() - lastQueryTime);

        overview.put("lowStockMaterials", lowStockMaterials != null ? lowStockMaterials : 0L);

        log.info("===== getDashboardOverview 完成: 总耗时={}ms =====", System.currentTimeMillis() - startTime);
        return overview;
    }
    public Map<String, Object> getProductionStatistics(String factoryId, String period) {
        Map<String, Object> statistics = new HashMap<>();
        LocalDateTime startDate;
        switch (period.toLowerCase()) {
            case "today":
                startDate = LocalDate.now().atStartOfDay();
                break;
            case "week":
                startDate = LocalDate.now().minusDays(7).atStartOfDay();
                break;
            case "month":
                startDate = LocalDate.now().withDayOfMonth(1).atStartOfDay();
                break;
            default:
                startDate = LocalDate.now().minusDays(30).atStartOfDay();
        }
        // 批次统计
        long totalBatches = productionBatchRepository.countByFactoryIdAndCreatedAtAfter(factoryId, startDate);
        statistics.put("totalBatches", totalBatches);
        // 已完成批次统计
        long completedBatches = productionBatchRepository.countByFactoryIdAndStatusAndCreatedAtAfter(
                factoryId,
                ProductionBatchStatus.COMPLETED,
                startDate);
        statistics.put("completedBatches", completedBatches);
        // 产量统计
        BigDecimal totalOutput = productionBatchRepository.calculateTotalOutputAfter(factoryId, startDate);
        statistics.put("totalOutput", totalOutput != null ? totalOutput : BigDecimal.ZERO);
        // 成本统计
        BigDecimal totalCost = productionBatchRepository.calculateTotalCostAfter(factoryId, startDate);
        statistics.put("totalCost", totalCost != null ? totalCost : BigDecimal.ZERO);
        // 效率统计
        BigDecimal avgEfficiency = productionBatchRepository.calculateAverageEfficiency(factoryId, startDate);
        statistics.put("averageEfficiency", avgEfficiency != null ? avgEfficiency : BigDecimal.ZERO);

        // ===== 添加批次状态分布 (batchStatusDistribution) =====
        List<ProductionBatch> batches = productionBatchRepository.findByFactoryIdAndCreatedAtAfter(factoryId, startDate);
        List<Map<String, Object>> batchStatusDistribution = new ArrayList<>();
        Map<ProductionBatchStatus, List<ProductionBatch>> byStatus = batches.stream()
                .collect(Collectors.groupingBy(ProductionBatch::getStatus));
        for (Map.Entry<ProductionBatchStatus, List<ProductionBatch>> entry : byStatus.entrySet()) {
            Map<String, Object> item = new HashMap<>();
            item.put("status", entry.getKey().name());
            item.put("count", entry.getValue().size());
            double totalQuantity = entry.getValue().stream()
                    .mapToDouble(b -> b.getActualQuantity() != null ? b.getActualQuantity().doubleValue() : 0)
                    .sum();
            item.put("totalQuantity", totalQuantity);
            batchStatusDistribution.add(item);
        }
        statistics.put("batchStatusDistribution", batchStatusDistribution);

        // ===== 添加产品类型统计 (productTypeStats) =====
        List<Map<String, Object>> productTypeStats = new ArrayList<>();
        Map<String, List<ProductionBatch>> byProductType = batches.stream()
                .filter(b -> b.getProductTypeId() != null)
                .collect(Collectors.groupingBy(ProductionBatch::getProductTypeId));

        // N+1 修复：使用批量查询预先加载所有产品类型名称
        Set<String> productTypeIds = byProductType.keySet();
        Map<String, String> productTypeNames = productTypeRepository.findByIdIn(productTypeIds)
                .stream()
                .collect(Collectors.toMap(ProductType::getId, ProductType::getName));

        for (Map.Entry<String, List<ProductionBatch>> entry : byProductType.entrySet()) {
            Map<String, Object> item = new HashMap<>();
            String productTypeId = entry.getKey();
            item.put("productTypeId", productTypeId);
            // 添加产品名称，如果没找到则使用ID
            String productName = productTypeNames.getOrDefault(productTypeId, productTypeId);
            item.put("productTypeName", productName);
            item.put("productType", productName);  // 兼容前端字段
            item.put("count", entry.getValue().size());
            double quantity = entry.getValue().stream()
                    .mapToDouble(b -> b.getActualQuantity() != null ? b.getActualQuantity().doubleValue() : 0)
                    .sum();
            item.put("totalQuantity", quantity);
            productTypeStats.add(item);
        }
        statistics.put("productTypeStats", productTypeStats);

        return statistics;
    }
    public Map<String, Object> getQualityDashboard(String factoryId) {
        Map<String, Object> dashboard = new HashMap<>();
        LocalDate today = LocalDate.now();
        LocalDate monthStart = today.withDayOfMonth(1);
        // 本月质检统计
        Map<String, Object> monthlyStats = getQualityStatistics(factoryId, monthStart, today);
        dashboard.put("monthlyStatistics", monthlyStats);

        // 将关键统计字段提升到顶层（用于E2E测试兼容性）
        dashboard.put("totalInspections", monthlyStats.getOrDefault("totalInspections", 0));
        dashboard.put("passedInspections", monthlyStats.getOrDefault("passedBatches", 0));
        dashboard.put("failedInspections", monthlyStats.getOrDefault("failedBatches", 0));
        dashboard.put("avgPassRate", monthlyStats.getOrDefault("averagePassRate", BigDecimal.ZERO));

        // B3-fix (2026-07-06): web-admin 首页质量统计卡片 (DashboardAdmin.vue) 读取
        // qualityStats.todayInspections / qualityStats.failedBatches, 但本方法此前从未
        // 输出这两个字段名(只有月度的 totalInspections/failedInspections) → 前端恒 "-"。
        // failedBatches: 与已建立的 failedInspections/passRate 同口径(本月), 复用同一份
        // monthlyStats, 不新增查询, 也不改动既有字段。
        // todayInspections: 卡片语义是"今日检验批次数", 单独按"今天"这一天查询
        // (与 monthlyStatistics 的月度范围区分, 是其子集)。
        Map<String, Object> todayStats = getQualityStatistics(factoryId, today, today);
        dashboard.put("todayInspections", todayStats.getOrDefault("totalInspections", 0));
        dashboard.put("failedBatches", monthlyStats.getOrDefault("failedBatches", 0));

        // 计算缺陷率 (defectRate = 100 - passRate)
        BigDecimal passRate = (BigDecimal) monthlyStats.getOrDefault("averagePassRate", BigDecimal.ZERO);
        BigDecimal defectRate = BigDecimal.valueOf(100).subtract(passRate);
        dashboard.put("defectRate", defectRate);
        dashboard.put("passRate", passRate);

        // 添加质量等级 (基于 passRate)
        String qualityGrade;
        if (passRate.compareTo(BigDecimal.valueOf(95)) >= 0) {
            qualityGrade = "A";
        } else if (passRate.compareTo(BigDecimal.valueOf(90)) >= 0) {
            qualityGrade = "B";
        } else if (passRate.compareTo(BigDecimal.valueOf(80)) >= 0) {
            qualityGrade = "C";
        } else {
            qualityGrade = "D";
        }
        dashboard.put("qualityGrade", qualityGrade);

        // 质量趋势
        List<Map<String, Object>> trends = getQualityTrends(factoryId, 30);
        dashboard.put("trends", trends);
        // 最新质检记录
        PageRequest pageRequest = new PageRequest();
        pageRequest.setPage(1);
        pageRequest.setSize(10);
        PageResponse<Map<String, Object>> recentInspections = getInspections(factoryId, null, pageRequest);
        dashboard.put("recentInspections", recentInspections.getContent());
        return dashboard;
    }
    public Map<String, Object> getEquipmentDashboard(String factoryId) {
        Map<String, Object> dashboard = new HashMap<>();
        // 设备监控
        List<Map<String, Object>> monitoring = getEquipmentMonitoring(factoryId);
        dashboard.put("monitoring", monitoring);
        // 设备统计
        long totalEquipments = equipmentRepository.countByFactoryId(factoryId);
        long runningEquipments = equipmentRepository.countByFactoryIdAndStatus(factoryId, "running");
        long maintenanceEquipments = equipmentRepository.countByFactoryIdAndStatus(factoryId, "maintenance");
        dashboard.put("totalEquipments", totalEquipments);
        dashboard.put("runningEquipments", runningEquipments);
        dashboard.put("maintenanceEquipments", maintenanceEquipments);
        // 平均利用率
        double avgUtilization = monitoring.stream()
                .mapToDouble(m -> (double) m.get("weeklyUtilizationRate"))
                .average()
                .orElse(0);
        dashboard.put("averageUtilization", avgUtilization);
        return dashboard;
    }
    public Map<String, Object> getAlertsDashboard(String factoryId) {
        Map<String, Object> result = new HashMap<>();
        List<Map<String, Object>> alertList = new ArrayList<>();

        // 低库存预警
        List<Object> lowStockMaterials = materialBatchRepository.findLowStockMaterials(factoryId);
        for (Object material : lowStockMaterials) {
            Map<String, Object> alert = new HashMap<>();
            alert.put("type", "LOW_STOCK");
            alert.put("level", "WARNING");
            alert.put("status", "ACTIVE");
            alert.put("message", "原材料库存低");
            alert.put("data", material);
            alert.put("createdAt", LocalDateTime.now());
            alertList.add(alert);
        }

        // 过期预警
        List<MaterialBatch> expiringSoon = materialBatchRepository.findExpiringSoon(factoryId, LocalDate.now().plusDays(7));
        for (MaterialBatch batch : expiringSoon) {
            Map<String, Object> alert = new HashMap<>();
            alert.put("type", "EXPIRING");
            alert.put("level", "CRITICAL");
            alert.put("status", "ACTIVE");
            alert.put("message", "原材料即将过期: " + batch.getBatchNumber());
            alert.put("data", batch);
            alert.put("expireDate", batch.getExpireDate());
            alert.put("createdAt", LocalDateTime.now());
            alertList.add(alert);
        }

        // 设备维护提醒
        List<FactoryEquipment> maintenanceDue = equipmentRepository.findMaintenanceDue(factoryId, LocalDate.now().minusDays(30));
        for (FactoryEquipment equipment : maintenanceDue) {
            Map<String, Object> alert = new HashMap<>();
            alert.put("type", "MAINTENANCE");
            alert.put("level", "INFO");
            alert.put("status", "ACTIVE");
            alert.put("message", "设备需要维护: " + equipment.getEquipmentName());
            alert.put("data", equipment);
            alert.put("lastMaintenance", equipment.getLastMaintenanceDate());
            alert.put("createdAt", LocalDateTime.now());
            alertList.add(alert);
        }

        // 质检不合格告警（来自 ProductionAlert 表）
        List<ProductionAlert> qualityAlerts = productionAlertRepository.findByFactoryIdAndStatus(factoryId, "ACTIVE");
        for (ProductionAlert qa : qualityAlerts) {
            Map<String, Object> alert = new HashMap<>();
            alert.put("type", qa.getAlertType());
            alert.put("level", qa.getLevel());
            alert.put("status", qa.getStatus());
            alert.put("message", qa.getDescription());
            alert.put("data", qa);
            alert.put("createdAt", qa.getCreatedAt());
            alertList.add(alert);
        }

        // ===== 构建 summary 对象 =====
        int totalAlerts = alertList.size();
        int activeAlerts = (int) alertList.stream()
                .filter(a -> "ACTIVE".equals(a.get("status")))
                .count();
        int resolvedAlerts = totalAlerts - activeAlerts;
        int criticalAlerts = (int) alertList.stream()
                .filter(a -> "CRITICAL".equals(a.get("level")))
                .count();

        Map<String, Object> summary = new HashMap<>();
        summary.put("totalAlerts", totalAlerts);
        summary.put("activeAlerts", activeAlerts);
        summary.put("resolvedAlerts", resolvedAlerts);
        summary.put("criticalAlerts", criticalAlerts);
        result.put("summary", summary);

        // ===== 构建 byType 数组 =====
        Map<String, Long> typeCount = alertList.stream()
                .collect(Collectors.groupingBy(
                        a -> (String) a.get("type"),
                        Collectors.counting()
                ));
        List<Map<String, Object>> byType = new ArrayList<>();
        for (Map.Entry<String, Long> entry : typeCount.entrySet()) {
            Map<String, Object> typeItem = new HashMap<>();
            typeItem.put("type", entry.getKey());
            typeItem.put("count", entry.getValue().intValue());
            byType.add(typeItem);
        }
        result.put("byType", byType);

        // ===== 其他字段 =====
        result.put("period", "week");
        result.put("recent", alertList);  // 重命名 alerts → recent
        // 保留旧字段以便向后兼容
        result.put("alerts", alertList);
        result.put("totalAlerts", totalAlerts);

        return result;
    }
    public Map<String, Object> getTrendAnalysis(String factoryId, String metric, Integer days) {
        // Apr 21 2026: return a MULTI-metric bundle, not a single metric.
        // FE /analytics/trends destructures
        // {productionTrend, qualityTrend, costTrend, materialTrend, equipmentTrend}.
        // Old shape {metric, period, data:[]} left FE with undefined on every
        // chart (empty skeleton page for 100% of users).
        Map<String, Object> analysis = new HashMap<>();
        LocalDate endDate = LocalDate.now();
        LocalDate startDate = endDate.minusDays(days);
        List<Map<String, Object>> productionTrend = new ArrayList<>();
        List<Map<String, Object>> qualityTrend = new ArrayList<>();
        List<Map<String, Object>> costTrend = new ArrayList<>();
        for (LocalDate date = startDate; !date.isAfter(endDate); date = date.plusDays(1)) {
            LocalDateTime dayStart = date.atStartOfDay();
            LocalDateTime dayEnd = date.atTime(23, 59, 59);
            BigDecimal dayOutput = productionBatchRepository.calculateDailyOutput(
                    factoryId, dayStart, dayEnd);
            BigDecimal dayYieldRate = productionBatchRepository.calculateDailyYieldRate(
                    factoryId, dayStart, dayEnd);
            BigDecimal dayCost = productionBatchRepository.calculateDailyCost(
                    factoryId, dayStart, dayEnd);
            productionTrend.add(Map.of(
                    "date", date.toString(),
                    "value", dayOutput != null ? dayOutput : BigDecimal.ZERO,
                    "output", dayOutput != null ? dayOutput : BigDecimal.ZERO
            ));
            qualityTrend.add(Map.of(
                    "date", date.toString(),
                    "value", dayYieldRate != null ? dayYieldRate : BigDecimal.ZERO,
                    "passRate", dayYieldRate != null ? dayYieldRate : BigDecimal.ZERO
            ));
            costTrend.add(Map.of(
                    "date", date.toString(),
                    "value", dayCost != null ? dayCost : BigDecimal.ZERO,
                    "totalCost", dayCost != null ? dayCost : BigDecimal.ZERO
            ));
        }
        analysis.put("productionTrend", productionTrend);
        analysis.put("qualityTrend", qualityTrend);
        analysis.put("costTrend", costTrend);
        // Reserved for future material / equipment metrics; empty now so FE
        // doesn't crash on `.map()` over undefined.
        analysis.put("materialTrend", new ArrayList<>());
        analysis.put("equipmentTrend", new ArrayList<>());
        // Keep legacy keys for any old callers
        analysis.put("metric", metric);
        analysis.put("period", days);
        return analysis;
    }
    // 辅助方法
    private Map<String, Object> convertInspectionToMap(QualityInspection inspection) {
        Map<String, Object> map = new HashMap<>();
        map.put("id", inspection.getId());
        map.put("productionBatchId", inspection.getProductionBatchId());
        map.put("inspectorId", inspection.getInspectorId());
        map.put("inspectionDate", inspection.getInspectionDate());
        map.put("sampleSize", inspection.getSampleSize());
        map.put("passCount", inspection.getPassCount());
        map.put("failCount", inspection.getFailCount());
        map.put("passRate", inspection.getPassRate());
        map.put("result", inspection.getResult());
        map.put("notes", inspection.getNotes());
        // 添加计算字段
        map.put("qualityGrade", inspection.getQualityGrade());
        map.put("defectRate", inspection.getDefectRate());
        return map;
    }
    private BigDecimal calculatePotentialSavings(ProductionBatch batch) {
        BigDecimal savings = BigDecimal.ZERO;
        // 如果良品率提升到95%能节省的成本
        if (batch.getYieldRate() != null && batch.getYieldRate().compareTo(new BigDecimal("95")) < 0) {
            BigDecimal currentWaste = batch.getDefectQuantity().multiply(batch.getUnitCost());
            BigDecimal targetWaste = batch.getActualQuantity().multiply(new BigDecimal("0.05")).multiply(batch.getUnitCost());
            savings = savings.add(currentWaste.subtract(targetWaste));
        }
        return savings;
    }

    // ========== AI智能成本分析（新版本） ==========

    /**
     * AI智能成本分析（带缓存优化 + 完整业务链数据）
     */
    @Override
    public Map<String, Object> analyzeWithAI(String factoryId, String batchId,
                                             String sessionId, String customMessage) {
        log.info("AI成本分析(增强版): factoryId={}, batchId={}, sessionId={}, customMessage={}",
                 factoryId, batchId, sessionId, customMessage != null ? "有追问" : "初次分析");

        // 1. 检查缓存（仅对初次分析且无自定义消息的情况使用缓存）
        if (sessionId == null && customMessage == null) {
            Map<String, Object> cachedResult = cacheService.getAIAnalysisCache(factoryId, batchId);
            if (cachedResult != null) {
                log.info("返回缓存的AI分析结果: factoryId={}, batchId={}", factoryId, batchId);
                cachedResult.put("fromCache", true);
                return cachedResult;
            }
        }

        // 2. 获取增强的批次成本数据（包含完整业务链数据）
        Map<String, Object> enhancedCostData = getEnhancedBatchCostAnalysis(factoryId, batchId);

        // 3. 调用AI服务
        Map<String, Object> aiResult = aiAnalysisService.analyzeCost(
            factoryId, batchId, enhancedCostData, sessionId, customMessage);

        // 4. 组合结果
        Map<String, Object> result = new HashMap<>();
        ProductionBatch batch = getBatchById(factoryId, batchId);

        result.put("batchId", batchId);
        result.put("batchNumber", batch.getBatchNumber());
        result.put("productName", batch.getProductName());
        result.put("enhancedData", enhancedCostData); // 使用增强数据
        result.put("aiAnalysis", aiResult.get("aiAnalysis"));
        result.put("sessionId", aiResult.get("sessionId"));
        result.put("messageCount", aiResult.get("messageCount"));
        result.put("success", aiResult.get("success"));
        result.put("fromCache", false);
        result.put("dataVersion", "enhanced_v2"); // 标记使用增强版数据

        // 如果AI调用失败，添加错误信息
        if (aiResult.containsKey("error")) {
            result.put("error", aiResult.get("error"));
            result.put("errorDetail", aiResult.get("errorDetail"));
        }

        // 5. 保存到缓存（仅对成功的初次分析）
        if (sessionId == null && customMessage == null && Boolean.TRUE.equals(aiResult.get("success"))) {
            cacheService.setAIAnalysisCache(factoryId, batchId, result);
        }

        log.info("AI成本分析完成(增强版): batchId={}, 包含原材料{}种, 设备{}台, 人工{}人次, 质检{}次",
                 batchId,
                 enhancedCostData.get("materialConsumptionCount"),
                 enhancedCostData.get("equipmentUsageCount"),
                 enhancedCostData.get("laborSessionCount"),
                 enhancedCostData.get("qualityInspectionCount"));

        return result;
    }

    /**
     * 获取AI对话历史
     */
    @Override
    public List<Map<String, Object>> getAISessionHistory(String sessionId) {
        log.info("获取AI会话历史: sessionId={}", sessionId);
        return aiAnalysisService.getSessionHistory(sessionId);
    }

    /**
     * 获取时间范围内的批次成本分析数据
     */
    @Override
    public List<Map<String, Object>> getTimeRangeBatchesCostAnalysis(
            String factoryId,
            java.time.LocalDateTime startDate,
            java.time.LocalDateTime endDate) {

        log.info("获取时间范围批次成本数据: factoryId={}, startDate={}, endDate={}",
                factoryId, startDate, endDate);

        // 1. 查询时间范围内的所有批次
        List<ProductionBatch> batches = productionBatchRepository
                .findByFactoryIdAndCreatedAtBetween(factoryId, startDate, endDate);

        log.info("查询到{}个批次", batches.size());

        // 2. 限制最大批次数量（避免Token消耗过大）
        final int MAX_BATCHES = 50;
        if (batches.size() > MAX_BATCHES) {
            log.warn("批次数量过多({})，限制为前{}个", batches.size(), MAX_BATCHES);
            batches = batches.subList(0, MAX_BATCHES);
        }

        // 3. 为每个批次获取增强的成本数据
        List<Map<String, Object>> batchesCostData = batches.stream()
                .map(batch -> {
                    try {
                        Map<String, Object> costData = getEnhancedBatchCostAnalysis(factoryId, batch.getId().toString());
                        // 添加批次基本信息
                        costData.put("batchNumber", batch.getBatchNumber());
                        costData.put("productName", batch.getProductName());
                        costData.put("status", batch.getStatus());
                        costData.put("createdAt", batch.getCreatedAt());
                        return costData;
                    } catch (Exception e) {
                        log.error("获取批次成本数据失败: batchId={}, error={}",
                                batch.getId(), e.getMessage());
                        return null;
                    }
                })
                .filter(data -> data != null)
                .collect(java.util.stream.Collectors.toList());

        log.info("成功获取{}个批次的成本数据", batchesCostData.size());

        return batchesCostData;
    }

    /**
     * 获取多个批次的对比分析数据
     */
    @Override
    public List<Map<String, Object>> getComparativeBatchesCostAnalysis(
            String factoryId,
            List<String> batchIds) {

        log.info("获取批次对比分析数据: factoryId={}, batchIds={}", factoryId, batchIds);

        // 1. 参数校验：确保批次数量在2-5之间
        if (batchIds == null || batchIds.size() < 2) {
            throw new IllegalArgumentException("至少需要2个批次进行对比分析");
        }
        if (batchIds.size() > 5) {
            throw new IllegalArgumentException("最多支持5个批次进行对比分析");
        }

        // 2. 为每个批次获取增强的成本数据
        List<Map<String, Object>> comparativeBatchesData = batchIds.stream()
                .map(batchId -> {
                    try {
                        // 获取批次基本信息
                        Long id = parseBatchId(batchId);
                        ProductionBatch batch = productionBatchRepository.findByIdAndFactoryId(id, factoryId)
                                .orElseThrow(() -> new EntityNotFoundException("ProductionBatch", batchId));

                        // 获取增强的成本数据
                        Map<String, Object> costData = getEnhancedBatchCostAnalysis(factoryId, batchId);

                        // 添加批次基本信息
                        costData.put("batchId", batch.getId());
                        costData.put("batchNumber", batch.getBatchNumber());
                        costData.put("productName", batch.getProductName());
                        costData.put("status", batch.getStatus());
                        costData.put("createdAt", batch.getCreatedAt());

                        log.info("成功获取批次{}的成本数据", batchId);
                        return costData;

                    } catch (Exception e) {
                        log.error("获取批次成本数据失败: batchId={}, error={}", batchId, e.getMessage());
                        return null;
                    }
                })
                .filter(data -> data != null)
                .collect(java.util.stream.Collectors.toList());

        // 3. 校验：确保所有批次数据都获取成功
        if (comparativeBatchesData.size() < 2) {
            throw new RuntimeException("至少需要2个有效批次数据才能进行对比分析");
        }

        log.info("成功获取{}个批次的对比数据", comparativeBatchesData.size());

        return comparativeBatchesData;
    }

    /**
     * 获取指定时间范围内的批次成本摘要数据（用于周报告/月报告）
     * 返回轻量级的批次成本摘要，不包含详细的业务链数据
     */
    @Override
    public List<Map<String, Object>> getWeeklyBatchesCost(
            String factoryId,
            java.time.LocalDateTime startDate,
            java.time.LocalDateTime endDate) {

        log.info("获取周报告批次成本数据: factoryId={}, startDate={}, endDate={}",
                 factoryId, startDate, endDate);

        // 1. 查询时间范围内的所有批次
        List<ProductionBatch> batches = productionBatchRepository
                .findByFactoryIdAndCreatedAtBetween(factoryId, startDate, endDate);

        log.info("查询到{}个批次", batches.size());

        // 2. 为每个批次构建成本摘要（轻量级，不包含详细业务链数据）
        return batches.stream()
                .map(batch -> {
                    Map<String, Object> batchCostSummary = new HashMap<>();

                    // 基本信息
                    batchCostSummary.put("batchId", batch.getId());
                    batchCostSummary.put("batchNumber", batch.getBatchNumber());
                    batchCostSummary.put("productName", batch.getProductName());
                    batchCostSummary.put("status", batch.getStatus());
                    batchCostSummary.put("createdAt", batch.getCreatedAt());

                    // 成本汇总
                    Map<String, Object> costSummary = new HashMap<>();
                    costSummary.put("materialCost", batch.getMaterialCost() != null ? batch.getMaterialCost() : BigDecimal.ZERO);
                    costSummary.put("laborCost", batch.getLaborCost() != null ? batch.getLaborCost() : BigDecimal.ZERO);
                    costSummary.put("equipmentCost", batch.getEquipmentCost() != null ? batch.getEquipmentCost() : BigDecimal.ZERO);
                    costSummary.put("otherCost", batch.getOtherCost() != null ? batch.getOtherCost() : BigDecimal.ZERO);
                    costSummary.put("totalCost", batch.getTotalCost() != null ? batch.getTotalCost() : BigDecimal.ZERO);
                    costSummary.put("unitCost", batch.getUnitCost() != null ? batch.getUnitCost() : BigDecimal.ZERO);
                    batchCostSummary.put("costSummary", costSummary);

                    // 生产指标
                    batchCostSummary.put("actualQuantity", batch.getActualQuantity() != null ? batch.getActualQuantity() : BigDecimal.ZERO);
                    batchCostSummary.put("yieldRate", batch.getYieldRate() != null ? batch.getYieldRate() : BigDecimal.ZERO);
                    batchCostSummary.put("efficiency", batch.getEfficiency() != null ? batch.getEfficiency() : BigDecimal.ZERO);

                    return batchCostSummary;
                })
                .collect(java.util.stream.Collectors.toList());
    }

    /**
     * AI服务健康检查
     */
    @Override
    public Map<String, Object> checkAIServiceHealth() {
        log.info("检查AI服务健康状态");
        return aiAnalysisService.healthCheck();
    }

    // ========== 批次员工分配 ==========

    /**
     * 分配员工到批次
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public List<Map<String, Object>> assignWorkersToBatch(String factoryId, Long batchId,
                                                           List<Long> workerIds, Long assignedBy, String notes) {
        log.info("分配员工到批次: factoryId={}, batchId={}, workerIds={}, assignedBy={}",
                factoryId, batchId, workerIds, assignedBy);

        // 1. 验证批次存在且状态正确
        ProductionBatch batch = productionBatchRepository.findByIdAndFactoryId(batchId, factoryId)
                .orElseThrow(() -> new ResourceNotFoundException("批次不存在: " + batchId));

        // 兼容 PRODUCING 和 IN_PROGRESS，以及 PLANNING 和 PLANNED
        ProductionBatchStatus status = batch.getStatus();
        if (status != ProductionBatchStatus.IN_PROGRESS &&
            status != ProductionBatchStatus.PRODUCING &&
            status != ProductionBatchStatus.PLANNED &&
            status != ProductionBatchStatus.PLANNING) {
            throw new BusinessException(409, "只有计划中或进行中的批次可以分配员工")
                    .withHint("已完成或已取消的批次无法分配员工, 请刷新批次列表查看最新状态");
        }

        // 2. 验证分配人存在
        User assigner = userRepository.findById(assignedBy)
                .orElseThrow(() -> new ResourceNotFoundException("分配人不存在: " + assignedBy));

        List<Map<String, Object>> results = new ArrayList<>();
        LocalDateTime now = LocalDateTime.now();

        // N+1 修复：预先批量查询所有员工，使用 Map 缓存
        Map<Long, User> workerCache = userRepository.findByIdIn(workerIds)
                .stream()
                .collect(Collectors.toMap(User::getId, u -> u));

        for (Long workerId : workerIds) {
            try {
                // 3. N+1 修复：使用预加载的 Map 进行员工查找
                User worker = workerCache.get(workerId);
                if (worker == null) {
                    throw new ResourceNotFoundException("员工不存在: " + workerId);
                }

                if (!factoryId.equals(worker.getFactoryId())) {
                    throw new BusinessException(403, "员工不属于该工厂: " + workerId)
                            .withHint("分配人员中包含其他工厂的员工, 请重新选择").withHintTarget("workerIds");
                }

                // 4. 检查员工是否已分配到该批次
                Optional<BatchWorkSession> existing = batchWorkSessionRepository
                        .findActiveByBatchIdAndEmployeeId(batchId, workerId);

                if (existing.isPresent()) {
                    Map<String, Object> result = new HashMap<>();
                    result.put("workerId", workerId);
                    result.put("workerName", worker.getFullName() != null ? worker.getFullName() : worker.getUsername());
                    result.put("success", false);
                    result.put("message", "员工已分配到该批次");
                    results.add(result);
                    continue;
                }

                // 5. 创建 BatchWorkSession 记录
                BatchWorkSession session = new BatchWorkSession();
                session.setBatchId(batchId);
                session.setEmployeeId(workerId);
                session.setCheckInTime(now);
                session.setAssignedBy(assignedBy);
                session.setStatus(BatchWorkSession.Status.ASSIGNED);
                session.setNotes(notes);
                session.setCreatedAt(now);

                batchWorkSessionRepository.save(session);

                Map<String, Object> result = new HashMap<>();
                result.put("workerId", workerId);
                result.put("workerName", worker.getFullName() != null ? worker.getFullName() : worker.getUsername());
                result.put("sessionId", session.getId());
                result.put("checkInTime", now);
                result.put("success", true);
                result.put("message", "分配成功");
                results.add(result);

                log.info("成功分配员工 {} 到批次 {}", workerId, batchId);

            } catch (Exception e) {
                Map<String, Object> result = new HashMap<>();
                result.put("workerId", workerId);
                result.put("success", false);
                result.put("message", e.getMessage());
                results.add(result);
                log.error("分配员工失败: workerId={}, error={}", workerId, e.getMessage());
            }
        }

        return results;
    }

    /**
     * 员工完成批次工作（签出）
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> workerCheckout(String factoryId, Long batchId, Long workerId,
                                               Integer workMinutes, String notes) {
        log.info("员工签出: factoryId={}, batchId={}, workerId={}", factoryId, batchId, workerId);

        // 1. 查找活跃的工作会话
        BatchWorkSession session = batchWorkSessionRepository
                .findActiveByBatchIdAndEmployeeId(batchId, workerId)
                .orElseThrow(() -> new ResourceNotFoundException("未找到该员工在该批次的活跃工作记录"));

        LocalDateTime now = LocalDateTime.now();

        // 2. 计算工作时长
        int actualWorkMinutes;
        if (workMinutes != null && workMinutes > 0) {
            actualWorkMinutes = workMinutes;
        } else if (session.getCheckInTime() != null) {
            actualWorkMinutes = (int) ChronoUnit.MINUTES.between(session.getCheckInTime(), now);
        } else {
            actualWorkMinutes = 0;
        }

        // 3. 计算人工成本（假设每小时50元）
        BigDecimal hourlyRate = new BigDecimal("50.00");
        BigDecimal laborCost = hourlyRate
                .multiply(BigDecimal.valueOf(actualWorkMinutes))
                .divide(BigDecimal.valueOf(60), 2, RoundingMode.HALF_UP);

        // 4. 更新工作会话
        session.setCheckOutTime(now);
        session.setWorkMinutes(actualWorkMinutes);
        session.setLaborCost(laborCost);
        session.setStatus(BatchWorkSession.Status.COMPLETED);
        if (notes != null && !notes.isEmpty()) {
            session.setNotes(session.getNotes() != null ?
                    session.getNotes() + "\n" + notes : notes);
        }
        session.setUpdatedAt(now);

        batchWorkSessionRepository.save(session);

        // 5. 获取员工信息
        User worker = userRepository.findById(workerId).orElse(null);

        Map<String, Object> result = new HashMap<>();
        result.put("sessionId", session.getId());
        result.put("workerId", workerId);
        result.put("workerName", worker != null ? (worker.getFullName() != null ? worker.getFullName() : worker.getUsername()) : "未知");
        result.put("checkInTime", session.getCheckInTime());
        result.put("checkOutTime", now);
        result.put("workMinutes", actualWorkMinutes);
        result.put("laborCost", laborCost);
        result.put("status", session.getStatus());
        result.put("success", true);
        result.put("message", "签出成功");

        log.info("员工签出成功: workerId={}, workMinutes={}, laborCost={}",
                workerId, actualWorkMinutes, laborCost);

        return result;
    }

    /**
     * 获取批次的员工列表
     */
    @Override
    public List<Map<String, Object>> getBatchWorkers(String factoryId, Long batchId) {
        log.info("获取批次员工列表: factoryId={}, batchId={}", factoryId, batchId);

        // 验证批次存在
        productionBatchRepository.findByIdAndFactoryId(batchId, factoryId)
                .orElseThrow(() -> new ResourceNotFoundException("批次不存在: " + batchId));

        // 获取批次的所有工作会话
        List<BatchWorkSession> sessions = batchWorkSessionRepository.findByBatchIdWithEmployees(batchId);

        return sessions.stream().map(session -> {
            Map<String, Object> workerInfo = new HashMap<>();
            workerInfo.put("sessionId", session.getId());
            workerInfo.put("workerId", session.getEmployeeId());

            User employee = session.getEmployee();
            if (employee != null) {
                workerInfo.put("workerName", employee.getFullName() != null ? employee.getFullName() : employee.getUsername());
                workerInfo.put("employeeNumber", employee.getUsername());
                workerInfo.put("departmentName", employee.getDepartment());
            } else {
                workerInfo.put("workerName", "未知");
            }

            workerInfo.put("checkInTime", session.getCheckInTime());
            workerInfo.put("checkOutTime", session.getCheckOutTime());
            workerInfo.put("workMinutes", session.getWorkMinutes());
            workerInfo.put("laborCost", session.getLaborCost());
            workerInfo.put("status", session.getStatus());

            // 状态显示文字
            String statusText = "未知";
            if (session.getStatus() != null) {
                switch (session.getStatus()) {
                    case BatchWorkSession.Status.ASSIGNED: statusText = "已分配"; break;
                    case BatchWorkSession.Status.WORKING: statusText = "工作中"; break;
                    case BatchWorkSession.Status.COMPLETED: statusText = "已完成"; break;
                    case BatchWorkSession.Status.CANCELLED: statusText = "已取消"; break;
                }
            }
            workerInfo.put("statusText", statusText);

            User assigner = session.getAssigner();
            workerInfo.put("assignedBy", session.getAssignedBy());
            workerInfo.put("assignerName", assigner != null ? (assigner.getFullName() != null ? assigner.getFullName() : assigner.getUsername()) : null);
            workerInfo.put("assignedAt", session.getCreatedAt());
            workerInfo.put("notes", session.getNotes());

            return workerInfo;
        }).collect(Collectors.toList());
    }

    /**
     * 取消员工批次分配
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> cancelWorkerAssignment(String factoryId, Long batchId, Long workerId) {
        log.info("取消员工分配: factoryId={}, batchId={}, workerId={}", factoryId, batchId, workerId);

        // 查找活跃的工作会话
        BatchWorkSession session = batchWorkSessionRepository
                .findActiveByBatchIdAndEmployeeId(batchId, workerId)
                .orElseThrow(() -> new ResourceNotFoundException("未找到该员工在该批次的活跃工作记录"));

        // 更新状态为取消
        session.setStatus(BatchWorkSession.Status.CANCELLED);
        session.setUpdatedAt(LocalDateTime.now());
        batchWorkSessionRepository.save(session);

        Map<String, Object> result = new HashMap<>();
        result.put("sessionId", session.getId());
        result.put("workerId", workerId);
        result.put("success", true);
        result.put("message", "取消分配成功");

        log.info("取消员工分配成功: workerId={}", workerId);

        return result;
    }
}
