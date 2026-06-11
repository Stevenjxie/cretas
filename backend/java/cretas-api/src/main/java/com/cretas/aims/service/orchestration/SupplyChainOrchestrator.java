package com.cretas.aims.service.orchestration;

import com.cretas.aims.dto.orchestration.*;
import com.cretas.aims.entity.*;
import com.cretas.aims.entity.config.FactoryTriggerChain;
import com.cretas.aims.entity.enums.*;
import com.cretas.aims.entity.inventory.FinishedGoodsBatch;
import com.cretas.aims.event.*;
import com.cretas.aims.repository.ProductTypeRepository;
import com.cretas.aims.repository.ProductionBatchRepository;
import com.cretas.aims.repository.ProductionPlanRepository;
import com.cretas.aims.repository.config.FactoryTriggerChainRepository;
import com.cretas.aims.repository.inventory.FinishedGoodsBatchRepository;
import com.cretas.aims.service.BatchConsumptionService;
import com.cretas.aims.service.LinkArrayService;
import com.cretas.aims.service.QualityInspectionService;
import com.cretas.aims.service.restock.RestockUnitConverter;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.UUID;

/**
 * 供应链联动编排服务
 *
 * <p>监听 Spring ApplicationEvent，驱动跨模块的供应链自动化流程：
 * <ul>
 *   <li>正向链：SO确认 → 库存检查 → 生产计划 → BOM展开 → 采购建议</li>
 *   <li>反向链：报工完成 → 成品入库 → 生产计划更新 → SO通知</li>
 *   <li>物料链：原辅料入库 → 重新检查待匹配PP的物料充足性</li>
 * </ul>
 *
 * <p>所有监听方法均包含异常隔离：联动失败不影响触发方的主流程。
 *
 * @author Cretas Team
 * @since 2026-02-19
 */
@Service
@RequiredArgsConstructor
public class SupplyChainOrchestrator {

    private static final Logger log = LoggerFactory.getLogger(SupplyChainOrchestrator.class);

    private final InventoryMatchingService inventoryMatchingService;
    private final BomExpansionService bomExpansionService;
    private final ProcurementSuggestionService procurementSuggestionService;
    private final ProductionPlanRepository productionPlanRepository;
    private final ProductionBatchRepository productionBatchRepository;
    private final FinishedGoodsBatchRepository finishedGoodsBatchRepository;
    private final ApplicationEventPublisher applicationEventPublisher;
    private final QualityInspectionService qualityInspectionService;
    private final BatchConsumptionService batchConsumptionService;
    private final ProductTypeRepository productTypeRepository;

    /** D1 双仓流转 (2026-05-10 spec, PR #309 A1=A) — production output 默认 WH-WKS. */
    @org.springframework.beans.factory.annotation.Autowired
    private com.cretas.aims.service.factory.WarehouseResolver warehouseResolver;

    @Autowired(required = false)
    private FactoryTriggerChainRepository triggerChainRepository;

    /** Sprint 3 Track-F (C-LINKARRAY-1): unified link service for auto-cascade path. */
    @Autowired(required = false)
    private LinkArrayService linkArrayService;

    /**
     * @deprecated Bug #296 design fix (2026-04-18): the "skip hardcoded if chain
     * exists" semantics was fundamentally wrong — a configured chain adding one
     * side-effect (e.g. auto-QC task) would silently disable all other core
     * invariants (auto-FG, auto-BOM 扣料, 库存预留, PP 创建 etc). Chains are now
     * treated as purely ADDITIVE via {@code TriggerChainExecutor}. Hardcoded
     * handlers always run. Do NOT re-introduce early-return-on-configured-chain
     * without a per-handler opt-out flag on the chain itself. See commit log.
     */
    @Deprecated
    @SuppressWarnings("unused")
    private boolean hasConfiguredChain(String factoryId, String eventType) {
        if (triggerChainRepository == null) return false;
        List<FactoryTriggerChain> chains = triggerChainRepository
                .findByFactoryIdAndEventTypeAndEnabledTrue(factoryId, eventType);
        if (!chains.isEmpty()) return true;
        chains = triggerChainRepository.findGlobalByEventType(eventType);
        return !chains.isEmpty();
    }

    // ═══════════ 正向链：SO → 财务审核 → 库存 → 生产 → 采购 ═══════════

    /**
     * 节点①b: SO确认 → 仅记录日志（供应链联动已移至财务审核通过后）
     *
     * <p>保留此监听器用于日志/通知，但不再触发库存检查和生产计划创建。
     * 供应链联动由 {@link #onSalesOrderFinanceApproved} 驱动。
     */
    @EventListener
    @Transactional
    public void onSalesOrderConfirmed(SalesOrderConfirmedEvent event) {
        // Bug #296 design fix: trigger chains (via TriggerChainExecutor) run additively
        // alongside hardcoded handlers — not as replacements. Hardcoded logic is the
        // business baseline (auto-FG, auto-BOM 扣料, auto-QC 任务, 库存预留, PP 创建
        // 等 core invariants) and must always run. Chains can EXTEND behavior, never
        // SKIP it. See commit 9259cc5ee root cause analysis.
        log.info("═══ 供应链通知: SO确认(等待财务审核) ═══ factoryId={}, SO={}",
                event.getFactoryId(), event.getSalesOrderId());
        // 供应链联动已移至 onSalesOrderFinanceApproved，此处仅做记录
    }

    /**
     * 节点②: 财务审核通过后 → 检查成品库存 → 满足则预留，不足则创建PP
     *
     * <p>收到 {@link SalesOrderFinanceApprovedEvent} 后：
     * <ol>
     *   <li>逐行检查订单商品在成品库中的可用量</li>
     *   <li>库存充足 → {@code reserveStock} 预留</li>
     *   <li>库存不足 → {@code createProductionPlanFromSO} 创建PENDING状态的PP草稿，并触发BOM展开</li>
     * </ol>
     *
     * <p>业务规则变更（2026-03-26）：生产计划只有在财务审核通过后才可创建，
     * 确保每笔订单的预估成本和利润已经过财务确认。
     *
     * <p>B1 性能修复 (2026-06-04): 加 {@link org.springframework.scheduling.annotation.Async}
     * 让供应链联动在 Spring 公共线程池执行，不阻塞财务审核 HTTP 响应。
     * 原来 publishEvent 后 Spring 同步调用本方法（同 HTTP 线程），库存检查 + PP 创建
     * 全部串行完成才返回给前端，造成"通过点击后卡很久"。
     * REQUIRES_NEW 保证本方法独立事务，不影响 finance-approve 的事务语义。
     */
    @org.springframework.scheduling.annotation.Async
    @EventListener
    @Transactional(propagation = org.springframework.transaction.annotation.Propagation.REQUIRES_NEW)
    public void onSalesOrderFinanceApproved(SalesOrderFinanceApprovedEvent event) {
        // Bug #296 design fix: chains are additive, not replacements. See onSalesOrderConfirmed.
        log.info("═══ 供应链联动: 财务审核通过 ═══ factoryId={}, SO={}, approvedBy={}",
                event.getFactoryId(), event.getSalesOrderId(), event.getApprovedBy());

        try {
            StockCheckResult result = inventoryMatchingService.checkAvailability(
                    event.getFactoryId(), event.getSalesOrderId());

            if (result == null || result.getLineItems() == null) {
                log.warn("库存检查返回空结果, 跳过联动: SO={}", event.getSalesOrderId());
                return;
            }

            for (LineItemMatch match : result.getLineItems()) {
                if (match.isFullySatisfied()) {
                    // ③a 库存满足 → 预留
                    inventoryMatchingService.reserveStock(
                            event.getFactoryId(), match.getProductTypeId(), match.getRequiredQuantity());
                    log.info("库存满足并预留: product={}, qty={}", match.getProductTypeId(), match.getRequiredQuantity());
                } else {
                    // ③b 库存不足 → 创建生产计划草稿
                    createProductionPlanFromSO(
                            event.getFactoryId(), event.getSalesOrderId(),
                            match.getProductTypeId(), match.getProductTypeName(), match.getShortfallQuantity());
                }
            }
        } catch (Exception e) {
            // Log but never re-throw — approval must succeed even if orchestration fails
            log.error("财务审核通过联动失败(不影响审批状态): SO={}", event.getSalesOrderId(), e);
        }
    }

    /**
     * 节点⑤d: 原辅料入库 → 检查待匹配PP的原料是否到齐
     *
     * <p>收到 {@link MaterialReceivedEvent} 后，遍历所有 PENDING 且未完全匹配的PP，
     * 重新做BOM物料可用性校验；如果全部到位则将 {@code isFullyMatched} 置为 true，
     * 通知调度员可以开始排产。
     */
    /**
     * doomed-tx 修复 (2026-06-12, 第4次复发): 改 AFTER_COMMIT + REQUIRES_NEW.
     *
     * <p>旧 {@code @EventListener @Transactional} 在 confirmReceive 的同一事务内同步执行,
     * 内层 {@code recheckAvailability} (@Transactional readOnly) 抛 BOM 单位换算 BusinessException
     * 时, Spring tx 拦截器 mark 共享事务 rollback-only —— 即使本方法 catch 吞掉异常, confirmReceive
     * commit 时仍抛 UnexpectedRollbackException 500, 收货整体回滚 (实测吸塑盒2014-3.5 BOM单位 g vs
     * 库存 件 触发). 供应链联动是收货的"下游通知"副作用, 绝不应回滚物理收货.
     *
     * <p>AFTER_COMMIT: 等 confirmReceive 真正提交后才跑, 看到已落库的批次. REQUIRES_NEW: 本联动
     * 在独立事务执行, 它的任何失败 (BOM/乐观锁) 只回滚自身, 与收货物理隔离. 与 PurchaseOrderVoucherListener
     * 既有 AFTER_COMMIT 模式一致.
     */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void onMaterialReceived(MaterialReceivedEvent event) {
        // Bug #296 design fix: chains are additive, not replacements. See onSalesOrderConfirmed.
        log.info("═══ 供应链联动: 物料入库 ═══ factoryId={}, material={}, qty={}",
                event.getFactoryId(), event.getMaterialTypeId(), event.getReceivedQuantity());

        // 查找所有未完全匹配的 PENDING 生产计划
        List<ProductionPlan> waitingPlans = productionPlanRepository
                .findByFactoryIdAndStatus(event.getFactoryId(), ProductionPlanStatus.PENDING);

        for (ProductionPlan plan : waitingPlans) {
            if (Boolean.TRUE.equals(plan.getIsFullyMatched())) continue;
            // 注: recheckAvailability 现在对单位不可换算的 BOM 返回 false (软探针, 不抛),
            // 故单个坏 BOM 的计划不会再 doom 本独立事务; 仍保留 per-plan catch 作纵深防御.
            try {
                boolean allReady = bomExpansionService.recheckAvailability(plan);
                if (allReady) {
                    plan.setIsFullyMatched(true);
                    productionPlanRepository.save(plan);
                    log.info("原料到齐: PP={}, 可以开始生产", plan.getPlanNumber());
                }
            } catch (Exception e) {
                log.error("物料入库联动单计划失败(跳过, 不影响收货): PP={}, material={}",
                        plan.getId(), event.getMaterialTypeId(), e);
            }
        }
    }

    // ═══════════ 反向链：报工 → 成品 → SO → 出库 ═══════════

    /**
     * 节点⑦: 生产批次完成 → 自动创建成品批次 + 更新PP进度 + 通知SO
     *
     * <p>收到 {@link BatchCompletedEvent} 后：
     * <ol>
     *   <li>良品数量 &gt; 0 → 创建 {@link FinishedGoodsBatch} 入库</li>
     *   <li>关联PP存在 → 累加 actualQuantity，检查PP下所有PB是否完成</li>
     *   <li>PP来自SO → 发布 {@link FinishedGoodsCreatedEvent} 通知出库模块</li>
     * </ol>
     */
    // doomed-tx 修复 (2026-06-12, 同 onMaterialReceived): 改 AFTER_COMMIT + REQUIRES_NEW.
    // 旧 @EventListener @Transactional 同步加入报工(批次完成)事务. autoConsume 虽已 REQUIRES_NEW 隔离(#396),
    // 但 createFinishedGoodsFromBatch / updateProductionPlanProgress / createQualityInspectionFromBatch 仍在
    // 父事务内 —— 任一经嵌套 @Transactional 抛异常即 mark 报工事务 rollback-only, catch 吞掉但已 doom →
    // 报工 commit 抛 UnexpectedRollbackException. FG入库/计划更新/质检建单是完工的下游副作用, 绝不应回滚物理报工.
    // AFTER_COMMIT: 报工真提交后才跑(看到已落库批次); REQUIRES_NEW: 独立事务, 任何失败只回滚自身.
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void onBatchCompleted(BatchCompletedEvent event) {
        ProductionBatch batch = event.getBatch();
        // Bug #296 design fix: chains are additive, not replacements. See onSalesOrderConfirmed.
        log.info("═══ 供应链联动: 批次完成 ═══ factoryId={}, batchId={}, goodQty={}",
                batch.getFactoryId(), batch.getId(), batch.getGoodQuantity());

        try {
            // ⑦-AUTO: BOM自动扣料
            try {
                batchConsumptionService.autoConsumeForBatch(batch);
                log.info("自动扣料成功: batchId={}", batch.getId());
            } catch (Exception e) {
                log.error("自动扣料失败(不影响后续流程): batchId={}", batch.getId(), e);
            }

            // ⑦a 自动创建成品批次
            if (batch.getGoodQuantity() != null && batch.getGoodQuantity().compareTo(BigDecimal.ZERO) > 0) {
                FinishedGoodsBatch fg = createFinishedGoodsFromBatch(batch);
                if (fg != null) {
                    log.info("自动创建成品: batchNumber={}, qty={}", fg.getBatchNumber(), fg.getProducedQuantity());
                }
            }

            // ⑦b 更新关联的 ProductionPlan
            if (batch.getProductionPlanId() != null) {
                updateProductionPlanProgress(batch);
            }

            // ⑦c Fix-6: 自动创建质检任务（PENDING状态）
            createQualityInspectionFromBatch(batch);
        } catch (Exception e) {
            log.error("批次完成联动失败(不影响报工): batchId={}", batch.getId(), e);
        }
    }

    /**
     * 节点⑩: 收到回款 → 检查是否有等待发货的SO
     *
     * <p>Phase C 占位：回款门控出库逻辑将在此处实现。
     */
    @EventListener
    @Transactional
    public void onPaymentReceived(PaymentReceivedEvent event) {
        // Bug #296 design fix: chains are additive, not replacements. See onSalesOrderConfirmed.
        log.info("═══ 供应链联动: 回款 ═══ factoryId={}, counterparty={}, amount={}",
                event.getFactoryId(), event.getCounterpartyId(), event.getAmount());
        // TODO: Phase C — 回款门控出库
        // salesService.checkPaymentGateForPendingDeliveries(event.getFactoryId(), event.getCounterpartyId());
    }

    // ═══════════ 内部方法 ═══════════

    /**
     * 根据SO缺口创建 PENDING 状态的生产计划草稿，并触发 BOM 展开与采购建议生成。
     *
     * @param factoryId         工厂ID
     * @param salesOrderId      销售订单ID
     * @param productTypeId     产品类型ID
     * @param productTypeName   产品类型名称（用于冗余记录）
     * @param shortfallQuantity 库存缺口数量
     */
    private void createProductionPlanFromSO(String factoryId, String salesOrderId,
                                            String productTypeId, String productTypeName,
                                            BigDecimal shortfallQuantity) {
        // 创建PP草稿（PENDING状态，需调度员确认后排产）
        ProductionPlan plan = new ProductionPlan();
        plan.setId(UUID.randomUUID().toString());
        plan.setFactoryId(factoryId);
        plan.setPlanNumber(generatePlanNumber(factoryId));
        plan.setProductTypeId(productTypeId);
        plan.setPlannedQuantity(shortfallQuantity);
        plan.setSourceType(PlanSourceType.CUSTOMER_ORDER);
        plan.setSourceOrderId(salesOrderId);
        plan.setStatus(ProductionPlanStatus.PENDING);
        plan.setIsFullyMatched(false);
        // ProductionPlan 无 productName 字段；productTypeName 冗余记录在 sourceCustomerName 旁
        // 如业务需要，可将 productTypeName 写入 notes 字段
        plan.setNotes("自动创建：来自销售订单 " + salesOrderId + "，产品 " + productTypeName);
        // createdBy: 使用 factory_admin1 的 user ID (1L)，避免外键违约
        plan.setCreatedBy(1L);

        ProductionPlan saved = productionPlanRepository.save(plan);
        log.info("自动创建生产计划: PP={}, product={}, qty={}, sourceOrder={}",
                saved.getPlanNumber(), productTypeId, shortfallQuantity, salesOrderId);

        // Sprint 3 Track-F (C-LINKARRAY-1): unified BusinessLink for auto-cascade path.
        // This path bypasses ProductionPlanService.createProductionPlan, so the link()
        // call in that service does NOT fire here — must replicate.
        if (linkArrayService != null && salesOrderId != null && !salesOrderId.isBlank()) {
            try {
                linkArrayService.link(factoryId,
                        "PRODUCTION_PLAN", saved.getId(),
                        "sale",
                        "SALES_ORDER", salesOrderId,
                        "财务审批自动触发",
                        "1");
            } catch (Exception e) {
                log.warn("BusinessLink double-write failed for auto-created PP {}: {}",
                        saved.getId(), e.getMessage());
            }
        }

        // ④ BOM展开 + ⑤ 原辅料检查
        try {
            List<MaterialRequirement> requirements = bomExpansionService.expandBOM(
                    factoryId, productTypeId, shortfallQuantity);
            MaterialCheckResult materialResult = bomExpansionService.checkMaterialAvailability(
                    factoryId, requirements);

            if (materialResult.isAllSatisfied()) {
                saved.setIsFullyMatched(true);
                productionPlanRepository.save(saved);
                log.info("原辅料充足: PP={}", saved.getPlanNumber());
            } else {
                // ⑤b 生成采购建议
                procurementSuggestionService.generateSuggestions(
                        factoryId, saved.getId(), materialResult.getShortfalls());
                log.info("原辅料不足，已生成采购建议: PP={}, 缺口种类={}",
                        saved.getPlanNumber(), materialResult.getShortfalls().size());
            }
        } catch (Exception e) {
            log.error("BOM展开/采购建议生成失败(不影响PP创建): PP={}", saved.getPlanNumber(), e);
        }
    }

    /**
     * 根据完成的生产批次创建成品批次入库记录。
     *
     * <p>P4 kg↔盒单位标准化 (2026-06-03):
     * 若报工单位=kg 且产品标准单位=盒 且 gramsPerUnit 已配置, 则自动换算为盒入库.
     * 防双重换算: 若批次产出单位已是产品单位, 直接原值入库.
     * 严格条件: 只在产品 unit=盒 且 批次 unit=kg 且 gramsPerUnit!=null 时换算, 不误伤其他产品.
     *
     * @param batch 已完成的生产批次
     * @return 持久化后的 {@link FinishedGoodsBatch}
     */
    private FinishedGoodsBatch createFinishedGoodsFromBatch(ProductionBatch batch) {
        // 幂等性：同一 batch 不重复创建成品
        String fgBatchNumber = generateFGBatchNumber(batch);
        if (finishedGoodsBatchRepository.findByFactoryIdAndBatchNumber(batch.getFactoryId(), fgBatchNumber).isPresent()) {
            log.warn("成品批次已存在，跳过: batchNumber={}", fgBatchNumber);
            return null;
        }

        // P4: 解析产品标准单位 + gramsPerUnit, 判断是否需要 kg→盒换算
        String batchUnit = batch.getUnit() != null ? batch.getUnit() : "kg";
        BigDecimal producedQty = batch.getGoodQuantity();
        String fgUnit = batchUnit;
        String conversionRemark = null;

        ProductType productType = batch.getProductTypeId() != null
                ? productTypeRepository.findById(batch.getProductTypeId()).orElse(null)
                : null;

        if (productType != null) {
            String productUnit = productType.getUnit();
            BigDecimal gramsPerUnit = productType.getGramsPerUnit();

            boolean needsConversion = "kg".equals(batchUnit)
                    && "盒".equals(productUnit)
                    && gramsPerUnit != null
                    && gramsPerUnit.compareTo(BigDecimal.ZERO) > 0;

            if (needsConversion) {
                // kg → 盒 换算
                BigDecimal kgQty = producedQty;
                BigDecimal boxQty = RestockUnitConverter.kgToBox(kgQty, gramsPerUnit);
                if (boxQty != null) {
                    conversionRemark = "[P4]原kg=" + kgQty.toPlainString()
                            + ", gramsPerUnit=" + gramsPerUnit.toPlainString()
                            + ", 换算=" + boxQty.toPlainString() + "盒";
                    producedQty = boxQty;
                    fgUnit = "盒";
                    log.info("P4 kg→盒换算: batchId={}, {}kg -> {}盒 (gramsPerUnit={})",
                            batch.getId(), kgQty, boxQty, gramsPerUnit);
                } else {
                    log.warn("P4 换算失败(kgToBox返null), 保持kg入库: batchId={}", batch.getId());
                }
            } else if ("盒".equals(batchUnit) && "盒".equals(productUnit)) {
                // 防双重换算: 产出已是盒, 直接原值入库
                log.debug("P4: 批次单位已是盒, 不重复换算: batchId={}", batch.getId());
            }
        }

        FinishedGoodsBatch fg = new FinishedGoodsBatch();
        fg.setFactoryId(batch.getFactoryId());
        fg.setProductTypeId(batch.getProductTypeId());
        fg.setProductName(batch.getProductName());
        fg.setProducedQuantity(producedQty);
        fg.setBatchNumber(fgBatchNumber);
        fg.setProductionDate(LocalDate.now());
        fg.setExpireDate(LocalDate.now().plusDays(180));
        fg.setStorageLocation("默认仓位");
        fg.setCreatedBy(0L);
        fg.setStatus("AVAILABLE");
        fg.setUnit(fgUnit);
        if (conversionRemark != null) {
            fg.setRemark(conversionRemark);
        }
        if (batch.getProductionPlanId() != null) {
            fg.setProductionPlanId(batch.getProductionPlanId());
        }
        // SP10 §RD-1: 试制批次 (is_trial=true) 产出进研发/中试库 (WH-RD), 不进可售车间仓 (WH-WKS).
        // 研发库成品不参与备货看板 / 销售出货，仅供研发查阅。
        // D1: 常规批次产出默认 WH-WKS (车间仓). per PR #310 spec — finished goods born on workshop floor;
        // 反向调拨 (BRANCH_TO_HQ) 后才到 WH-LOG.
        boolean isTrial = Boolean.TRUE.equals(batch.getIsTrial());
        if (isTrial) {
            fg.setWarehouseId(warehouseResolver.resolveRdId(batch.getFactoryId()));
            String trialRemark = "[RD-1] 试制批次 — 研发/中试库，不计入可售库存";
            fg.setRemark(fg.getRemark() != null ? fg.getRemark() + "; " + trialRemark : trialRemark);
            log.info("试制批次产出进研发库(WH-RD): batchId={}, qty={}", batch.getId(), producedQty);
        } else {
            fg.setWarehouseId(warehouseResolver.resolveWorkshopId(batch.getFactoryId()));
        }
        return finishedGoodsBatchRepository.save(fg);
    }

    /**
     * 累加生产批次的良品产量到关联PP，并在PP下所有PB都完成后将PP状态更新为COMPLETED。
     * 如果PP来自客户订单，则发布 {@link FinishedGoodsCreatedEvent}。
     *
     * @param batch 已完成的生产批次
     */
    private void updateProductionPlanProgress(ProductionBatch batch) {
        String planId = batch.getProductionPlanId();
        ProductionPlan plan = productionPlanRepository.findById(planId).orElse(null);
        if (plan == null) {
            log.warn("批次关联的PP不存在: planId={}, batchId={}", planId, batch.getId());
            return;
        }

        // 累加实际产量
        BigDecimal totalActual = plan.getActualQuantity() != null ? plan.getActualQuantity() : BigDecimal.ZERO;
        plan.setActualQuantity(totalActual.add(
                batch.getGoodQuantity() != null ? batch.getGoodQuantity() : BigDecimal.ZERO));

        // 检查该PP下是否还有未完成的PB
        long incompleteBatches = productionBatchRepository
                .countByProductionPlanIdAndStatusNot(batch.getProductionPlanId(), ProductionBatchStatus.COMPLETED);

        boolean planJustCompleted = false;
        if (incompleteBatches == 0) {
            plan.setStatus(ProductionPlanStatus.COMPLETED);
            plan.setEndTime(java.time.LocalDateTime.now());
            planJustCompleted = true;
            log.info("生产计划全部完成: PP={}, actualQty={}", plan.getPlanNumber(), plan.getActualQuantity());
        }

        productionPlanRepository.save(plan);

        // 如果PP来自SO → 发布 FinishedGoodsCreatedEvent 通知出库/发货模块
        if (plan.getSourceOrderId() != null && PlanSourceType.CUSTOMER_ORDER == plan.getSourceType()) {
            applicationEventPublisher.publishEvent(new FinishedGoodsCreatedEvent(
                    this,
                    batch.getFactoryId(),
                    plan.getSourceOrderId(),
                    batch.getProductTypeId(),
                    batch.getGoodQuantity(),
                    null  // batchId: 由下游模块通过sourceOrderId查询
            ));
        }

        // D1 反向调拨自动触发 (PR #309 A3=A, 2026-05-10 spec):
        // 生产计划整体完成 → 发布 ProductionCompletedEvent → ReverseTransferService
        // 创建 DRAFT BRANCH_TO_HQ 调拨单 (WH-WKS → WH-LOG, items = WH-WKS 余料 + plan 在 WH-WKS 的成品批次)
        if (planJustCompleted) {
            applicationEventPublisher.publishEvent(new ProductionCompletedEvent(
                    this,
                    plan.getFactoryId(),
                    plan.getId(),
                    plan.getPlanNumber(),
                    plan.getProductTypeId(),
                    plan.getActualQuantity()
            ));
        }
    }

    /**
     * 生成PP编号，格式: {@code PP-AUTO-yyyyMMdd-XXXX}
     *
     * @param factoryId 工厂ID
     * @return 唯一的PP编号
     */
    private String generatePlanNumber(String factoryId) {
        String dateStr = LocalDate.now().format(DateTimeFormatter.ofPattern("yyyyMMdd"));
        long count = productionPlanRepository.countByFactoryId(factoryId);
        return String.format("PP-AUTO-%s-%04d", dateStr, (count % 10000) + 1);
    }

    /**
     * 生成成品批次编号，格式: {@code FG-AUTO-yyyyMMdd-{batchId}}
     *
     * @param batch 来源生产批次
     * @return 唯一的成品批次编号
     */
    private String generateFGBatchNumber(ProductionBatch batch) {
        String dateStr = LocalDate.now().format(DateTimeFormatter.ofPattern("yyyyMMdd"));
        return String.format("FG-AUTO-%s-%s", dateStr, batch.getId());
    }

    /**
     * Fix-6: 批次完成 → 自动创建 PENDING 状态质检任务
     *
     * <p>所有完成的批次都应进行质检，确保出厂品质。质检记录为 PENDING 状态，
     * 等待质检员登录后查看并完成实际检验。
     *
     * @param batch 已完成的生产批次
     */
    private void createQualityInspectionFromBatch(ProductionBatch batch) {
        try {
            BigDecimal totalQty = batch.getActualQuantity() != null ? batch.getActualQuantity() : BigDecimal.ZERO;
            if (totalQty.compareTo(BigDecimal.ZERO) <= 0) {
                return; // 无产出，不需要质检
            }

            // Bug #296 fix: inspector_id=0L violates FK to users.id (0 isn't a valid user),
            // causing transaction rollback on commit — which also dropped the FG we just created.
            // Prefer batch supervisor (always a valid user); skip auto-QC creation if no supervisor.
            Long inspectorId = batch.getSupervisorId() != null ? batch.getSupervisorId().longValue() : null;
            if (inspectorId == null) {
                log.info("跳过自动创建质检任务: batchId={} 无 supervisorId (无候选质检员)", batch.getId());
                return;
            }

            QualityInspection inspection = QualityInspection.builder()
                    .factoryId(batch.getFactoryId())
                    .productionBatchId(batch.getId())
                    .inspectorId(inspectorId)
                    .inspectionDate(LocalDate.now())
                    .sampleSize(totalQty)
                    .passCount(BigDecimal.ZERO)
                    .failCount(BigDecimal.ZERO)
                    .result("PENDING")
                    .notes("批次完成自动创建质检任务，产品: " +
                            (batch.getProductName() != null ? batch.getProductName() : "未知") +
                            "，产量: " + totalQty)
                    .build();

            qualityInspectionService.createInspection(batch.getFactoryId(), inspection);
            log.info("自动创建质检任务: batchId={}, qty={}, inspectorId={}", batch.getId(), totalQty, inspectorId);
        } catch (Exception e) {
            log.warn("自动创建质检任务失败(不影响批次完成): batchId={}, error={}", batch.getId(), e.getMessage());
        }
    }
}
