package com.cretas.aims.service.orchestration;

import com.cretas.aims.dto.inventory.CreateTransferRequest;
import com.cretas.aims.entity.MaterialBatch;
import com.cretas.aims.entity.RawMaterialType;
import com.cretas.aims.entity.inventory.FinishedGoodsBatch;
import com.cretas.aims.entity.inventory.InternalTransfer;
import com.cretas.aims.event.ProductionCompletedEvent;
import com.cretas.aims.repository.MaterialBatchRepository;
import com.cretas.aims.repository.RawMaterialTypeRepository;
import com.cretas.aims.repository.inventory.FinishedGoodsBatchRepository;
import com.cretas.aims.service.factory.WarehouseResolver;
import com.cretas.aims.service.inventory.TransferService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * D1 反向调拨自动触发服务 (PR #309 A3=A, 2026-05-10 spec).
 *
 * <p>监听 {@link ProductionCompletedEvent} (生产计划整体完成) → 自动创建一张草稿态
 * BRANCH_TO_HQ 内部调拨单, 把车间仓 (WH-WKS) 的余料 + 该 plan 的成品聚合回总仓 (WH-LOG)。
 *
 * <p><b>防呆自动衔接 (R6 #5, 2026-06-22)</b>: 针对<b>同厂内部</b>反向调拨 (车间仓→总仓,
 * {@code sourceFactoryId == targetFactoryId}), 创建 DRAFT 后<b>自动推进到 CONFIRMED</b>
 * (依次 request→approve→ship→receive→confirm), 让成品生产完<b>立即在 WH-LOG 可售</b>,
 * 仓管无需手动走 6 步生命周期。
 * 同厂车间→总仓是无第二方的内部移库, 自动推进安全 (不违反客户"责任到人"——那是跨厂/跨部门场景)。
 * 跨厂 BRANCH_TO_HQ ({@code sourceFactoryId != targetFactoryId}) <b>保留手动多步</b> (留第二方审批)。
 *
 * <p>设计要点:
 * <ul>
 *   <li><b>触发时机</b>: 生产计划状态 PENDING → COMPLETED 时由
 *       {@link SupplyChainOrchestrator#updateProductionPlanProgress} 发布事件。
 *       Plan-level (非 batch-level) 防止重复草稿。</li>
 *   <li><b>聚合策略</b>: 余料按 materialTypeId 合并 (一种原料多个 batch → 一行 item, quantity 累加),
 *       成品按 productTypeId 合并 (同 plan 多个 FG batch → 一行 item)。</li>
 *   <li><b>0 余料场景</b>: 如果 WH-WKS 没余料但有成品 → 仍创建调拨单 (item 列表非空)。
 *       如果 WH-WKS 既无余料也无成品 → 跳过, 记 INFO 日志 (无内容可调)。</li>
 *   <li><b>异常隔离</b>: 调拨创建 / 自动推进失败仅记录 error, 不抛异常, 不回滚生产完成。
 *       自动推进失败时草稿仍在, 用户可手动补完剩余步骤。</li>
 *   <li><b>幂等性</b>: ProductionPlan COMPLETED 状态只设置一次 (incompleteBatches==0 检查), 所以
 *       事件只会发一次。事件处理本身不做去重 (上游不重发)。自动推进沿用状态机, 每步 assertStatus
 *       防止重入。</li>
 * </ul>
 *
 * @see ProductionCompletedEvent
 * @see TransferService#createTransfer
 * @since 2026-05-11 PR #309 A3=A
 */
@Service
public class ReverseTransferService {

    private static final Logger log = LoggerFactory.getLogger(ReverseTransferService.class);

    private final MaterialBatchRepository materialBatchRepository;
    private final FinishedGoodsBatchRepository finishedGoodsBatchRepository;
    private final RawMaterialTypeRepository rawMaterialTypeRepository;
    private final WarehouseResolver warehouseResolver;
    private final TransferService transferService;

    /**
     * Self-injection (proxy) 用于触发 {@code REQUIRES_NEW} 事务边界。
     *
     * <p>{@link #createReverseTransferDraft} 与 {@link #autoAdvanceIntraFactory} 必须各自跑独立事务,
     * 否则 (a) 自动推进失败抛 {@code BusinessException} 会 mark 父事务 rollback-only → 即使 catch 吞掉,
     * 监听器返回时父事务 commit 抛 {@code UnexpectedRollbackException} (本仓库多次复发的 doomed-tx);
     * (b) 草稿创建 + 自动推进合在一个事务里时, 推进失败会连草稿一起回滚, 违反"草稿仍在可手动补完"要求。
     * 拆成两个 {@code REQUIRES_NEW}: 草稿先 commit, 推进在独立 tx, 失败只回滚推进自身。
     */
    @Autowired
    @Lazy
    private ReverseTransferService self;

    /**
     * 反向调拨 system-user ID 占位 — DRAFT 创建 + 同厂自动推进 (request/approve/ship/receive/confirm)
     * 全程由系统执行, 无真实人工操作员。同厂内部移库无第二方审批责任, 用 system user 合理。
     */
    private static final Long SYSTEM_USER_ID = 0L;

    @Autowired
    public ReverseTransferService(MaterialBatchRepository materialBatchRepository,
                                  FinishedGoodsBatchRepository finishedGoodsBatchRepository,
                                  RawMaterialTypeRepository rawMaterialTypeRepository,
                                  WarehouseResolver warehouseResolver,
                                  TransferService transferService) {
        this.materialBatchRepository = materialBatchRepository;
        this.finishedGoodsBatchRepository = finishedGoodsBatchRepository;
        this.rawMaterialTypeRepository = rawMaterialTypeRepository;
        this.warehouseResolver = warehouseResolver;
        this.transferService = transferService;
    }

    /**
     * 监听生产计划完成事件, 创建 DRAFT BRANCH_TO_HQ 调拨单, 同厂场景再自动推进到 CONFIRMED。
     *
     * <p>事务编排 (doomed-tx 防御): 本监听器方法<b>不带 {@code @Transactional}</b>, 仅做编排。
     * 草稿创建 ({@link #createReverseTransferDraft}) 与自动推进 ({@link #autoAdvanceIntraFactory})
     * 各跑独立 {@code REQUIRES_NEW} 事务 (经 {@code self} 代理触发) —— 草稿先 commit, 推进失败只回滚
     * 推进自身, 草稿留存可手动补完。
     *
     * <p>异常隔离: 任何步骤失败 → log.error + 静默吞掉。生产完成主流程不能被反向调拨失败阻塞。
     *
     * <p>单测兼容: 单测直接 new 实例调用本方法 ({@code self} 为 null), 通过 {@link #proxy()} fallback
     * 到 {@code this} 同步执行 (单测 mock TransferService, 不验证事务传播)。
     */
    @EventListener
    public void onProductionCompleted(ProductionCompletedEvent event) {
        String factoryId = event.getFactoryId();
        String planId = event.getPlanId();
        log.info("═══ D1 反向调拨触发 ═══ factoryId={}, planId={}, planNumber={}",
                factoryId, planId, event.getPlanNumber());

        ReverseTransferDraftResult draft;
        try {
            draft = proxy().createReverseTransferDraft(event);
        } catch (Exception e) {
            // 异常隔离: 反向调拨创建失败不影响生产完成主流程
            log.error("D1 反向调拨创建失败 (不影响生产完成): factoryId={}, planId={}, err={}",
                    factoryId, planId, e.getMessage(), e);
            return;
        }

        if (draft == null) {
            return;  // 无 items, 已跳过 (INFO 日志在 createReverseTransferDraft 内打)
        }

        // 同厂车间→总仓只创建来源明确的草稿；后续必须由操作人提交统一 OA。
        // 跨厂同样保留草稿，不在生产完成监听器里执行审批或库存移动。
        if (factoryId.equals(draft.targetFactoryId)) {
            try {
                // 独立 REQUIRES_NEW 事务, 失败只回滚推进自身, 不 doom 草稿 (草稿已 commit)。
                // 注: 推进方法内 catch 后, 若 tx 已被 mark rollback-only, 代理 commit 时会抛
                // UnexpectedRollbackException → 这里二次兜底 (推进事务正常回滚, 草稿不受影响)。
                log.info("D1 同厂反向调拨草稿已创建，等待操作人提交 OA: transferId={}, transferNumber={}",
                        draft.transferId, draft.transferNumber);
            } catch (Exception e) {
                log.error("D1 反向调拨自动推进事务回滚 (草稿仍在, 可手动补完): transferId={}, err={}",
                        draft.transferId, e.getMessage(), e);
            }
        } else {
            log.info("D1 反向调拨跨厂 (source={}, target={}), 保留手动审批流, 不自动推进: transferId={}",
                    factoryId, draft.targetFactoryId, draft.transferId);
        }
    }

    /** Self-proxy fallback: Spring 注入则用代理 (触发 REQUIRES_NEW), 单测下 self==null 退回 this。 */
    private ReverseTransferService proxy() {
        return self != null ? self : this;
    }

    /** 草稿创建结果 (供监听器决策是否 + 如何自动推进)。 */
    static final class ReverseTransferDraftResult {
        final String transferId;
        final String transferNumber;
        final String targetFactoryId;
        ReverseTransferDraftResult(String transferId, String transferNumber, String targetFactoryId) {
            this.transferId = transferId;
            this.transferNumber = transferNumber;
            this.targetFactoryId = targetFactoryId;
        }
    }

    /**
     * 创建反向调拨草稿单的主逻辑 (独立 {@code REQUIRES_NEW} 事务, 草稿先 commit)。
     *
     * <p>步骤:
     * <ol>
     *   <li>解析 WH-WKS / WH-LOG id</li>
     *   <li>查询 WH-WKS 内所有剩余原料 (按 materialTypeId 聚合)</li>
     *   <li>查询该 plan 在 WH-WKS 内的所有成品批次 (按 productTypeId 聚合)</li>
     *   <li>如果 items 非空 → 调用 TransferService.createTransfer 创建 DRAFT 单</li>
     * </ol>
     *
     * @return 草稿结果 (含 targetFactoryId 供同厂判定); 无 items 时返 null (跳过)。
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public ReverseTransferDraftResult createReverseTransferDraft(ProductionCompletedEvent event) {
        String factoryId = event.getFactoryId();
        String planId = event.getPlanId();

        // Step 1: 解析两个仓库 id
        String wksWarehouseId = warehouseResolver.resolveWorkshopId(factoryId);
        String logWarehouseId = warehouseResolver.resolveLogisticsId(factoryId);

        // Step 2: 聚合 WH-WKS 内剩余原料
        List<CreateTransferRequest.TransferItemDTO> items = new ArrayList<>();
        List<MaterialBatch> remainingMaterials = materialBatchRepository
                .findAllAvailableInWarehouse(factoryId, wksWarehouseId);
        Map<String, BigDecimal> materialQtyByType = aggregateMaterialsByType(remainingMaterials);
        for (Map.Entry<String, BigDecimal> entry : materialQtyByType.entrySet()) {
            String materialTypeId = entry.getKey();
            BigDecimal totalQty = entry.getValue();
            if (totalQty.compareTo(BigDecimal.ZERO) <= 0) continue;
            RawMaterialType rawType = rawMaterialTypeRepository.findById(materialTypeId).orElse(null);
            items.add(buildMaterialItem(materialTypeId, totalQty,
                    rawType != null ? rawType.getName() : "未知物料",
                    rawType != null && rawType.getUnit() != null ? rawType.getUnit() : "kg"));
        }

        // Step 3: 聚合该 plan 在 WH-WKS 的成品批次
        List<FinishedGoodsBatch> finishedGoods = finishedGoodsBatchRepository
                .findAvailableByPlanAndWarehouse(factoryId, planId, wksWarehouseId);
        Map<String, FGAggregate> fgByType = aggregateFinishedGoodsByType(finishedGoods);
        for (FGAggregate agg : fgByType.values()) {
            if (agg.totalQty.compareTo(BigDecimal.ZERO) <= 0) continue;
            items.add(buildFinishedGoodsItem(agg));
        }

        // Step 4: 若无 items → 跳过 (无内容可调)
        if (items.isEmpty()) {
            log.info("D1 反向调拨: WH-WKS 无余料且无成品, 跳过创建草稿 — factoryId={}, planId={}",
                    factoryId, planId);
            return null;
        }

        // Step 5: 调用 TransferService 创建 DRAFT 单
        CreateTransferRequest request = new CreateTransferRequest();
        request.setTransferType("BRANCH_TO_HQ");
        // 工厂内跨仓调拨: source / target factory 都是当前工厂, 区别在仓库
        request.setTargetFactoryId(factoryId);
        request.setSourceWarehouseId(wksWarehouseId);
        request.setTargetWarehouseId(logWarehouseId);
        request.setTransferDate(LocalDate.now());
        request.setExpectedArrivalDate(LocalDate.now());
        request.setRemark(String.format("[D1 自动] 生产计划 %s 完成, 自动调回总仓 (余料 %d 种, 成品 %d 种). PR #309 A3=A.",
                event.getPlanNumber() != null ? event.getPlanNumber() : planId,
                materialQtyByType.size(),
                fgByType.size()));
        request.setItems(items);

        InternalTransfer transfer = transferService.createTransfer(factoryId, request, SYSTEM_USER_ID);
        log.info("D1 反向调拨草稿创建成功: transferId={}, transferNumber={}, items={}, planId={}",
                transfer.getId(), transfer.getTransferNumber(), items.size(), planId);

        return new ReverseTransferDraftResult(
                transfer.getId(), transfer.getTransferNumber(), transfer.getTargetFactoryId());
    }

    /**
     * 兼容入口：同厂反向调拨不再自动推进，保留 DRAFT 等待操作人提交 OA。
     *
     *
     * <p>保留该方法仅兼容旧内部调用；方法不改变调拨状态、不写库存，也不创建第二条 OA 实例。
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void autoAdvanceIntraFactory(String factoryId, String transferId, String transferNumber) {
        try {
            // 已停用系统账号自动推进。调拨必须由操作人提交 OA，审批完成后才能确认。
            log.info("D1 反向调拨自动推进已停用，保留草稿等待 OA: transferId={}, transferNumber={}",
                    transferId, transferNumber);
        } catch (Exception e) {
            // 异常隔离: 自动推进失败不影响生产完成, 草稿停在当前状态, 用户可在调拨列表手动补完剩余步骤。
            log.error("D1 反向调拨同厂自动推进失败 (草稿仍在, 可手动补完): transferId={}, transferNumber={}, err={}",
                    transferId, transferNumber, e.getMessage(), e);
        }
    }

    /** 按 materialTypeId 聚合 (合并多 batch 到一行 item)。 */
    private Map<String, BigDecimal> aggregateMaterialsByType(List<MaterialBatch> batches) {
        Map<String, BigDecimal> result = new LinkedHashMap<>();
        for (MaterialBatch b : batches) {
            String typeId = b.getMaterialTypeId();
            if (typeId == null) continue;
            // MaterialBatch: getRemainingQuantity() = receiptQuantity - usedQuantity - reservedQuantity
            BigDecimal avail = b.getRemainingQuantity();
            if (avail == null || avail.compareTo(BigDecimal.ZERO) <= 0) continue;
            result.merge(typeId, avail, BigDecimal::add);
        }
        return result;
    }

    /** 按 productTypeId 聚合 (保留 productName + unit, 数量累加)。 */
    private Map<String, FGAggregate> aggregateFinishedGoodsByType(List<FinishedGoodsBatch> batches) {
        Map<String, FGAggregate> result = new LinkedHashMap<>();
        for (FinishedGoodsBatch b : batches) {
            String typeId = b.getProductTypeId();
            if (typeId == null) continue;
            BigDecimal avail = b.getAvailableQuantity();
            if (avail == null || avail.compareTo(BigDecimal.ZERO) <= 0) continue;
            FGAggregate agg = result.computeIfAbsent(typeId, k -> new FGAggregate());
            agg.productTypeId = typeId;
            // 保留首个非 null name/unit/unitPrice 作为代表 (同 productType 应该相同, 取首个 OK)
            if (agg.productName == null && b.getProductName() != null) {
                agg.productName = b.getProductName();
            }
            if (agg.unit == null && b.getUnit() != null) {
                agg.unit = b.getUnit();
            }
            if (agg.unitPrice == null && b.getUnitPrice() != null) {
                agg.unitPrice = b.getUnitPrice();
            }
            agg.totalQty = agg.totalQty.add(avail);
        }
        return result;
    }

    private CreateTransferRequest.TransferItemDTO buildMaterialItem(String materialTypeId,
                                                                     BigDecimal totalQty,
                                                                     String materialName,
                                                                     String unit) {
        CreateTransferRequest.TransferItemDTO item = new CreateTransferRequest.TransferItemDTO();
        item.setItemType("RAW_MATERIAL");
        item.setMaterialTypeId(materialTypeId);
        item.setItemName(materialName);
        item.setQuantity(totalQty);
        item.setUnit(unit);
        item.setRemark("[D1 自动] 报工后 WH-WKS 余料");
        return item;
    }

    private CreateTransferRequest.TransferItemDTO buildFinishedGoodsItem(FGAggregate agg) {
        CreateTransferRequest.TransferItemDTO item = new CreateTransferRequest.TransferItemDTO();
        item.setItemType("FINISHED_GOODS");
        item.setProductTypeId(agg.productTypeId);
        item.setItemName(agg.productName != null ? agg.productName : "未知产品");
        item.setQuantity(agg.totalQty);
        item.setUnit(agg.unit != null ? agg.unit : "kg");
        item.setUnitPrice(agg.unitPrice);
        item.setRemark("[D1 自动] 生产计划成品");
        return item;
    }

    /** 成品聚合中间结构 (按 productTypeId 累加, 保留 name/unit/unitPrice 元数据)。 */
    private static class FGAggregate {
        String productTypeId;
        String productName;
        String unit;
        BigDecimal unitPrice;
        BigDecimal totalQty = BigDecimal.ZERO;
    }
}
