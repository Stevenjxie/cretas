package com.cretas.aims.service.orchestration;

import com.cretas.aims.dto.orchestration.LineItemMatch;
import com.cretas.aims.dto.orchestration.StockCheckResult;
import com.cretas.aims.entity.factory.WarehouseCodes;
import com.cretas.aims.entity.inventory.FinishedGoodsBatch;
import com.cretas.aims.entity.inventory.SalesOrder;
import com.cretas.aims.entity.inventory.SalesOrderItem;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.repository.ProductTypeRepository;
import com.cretas.aims.repository.inventory.FinishedGoodsBatchRepository;
import com.cretas.aims.repository.inventory.SalesOrderItemRepository;
import com.cretas.aims.repository.inventory.SalesOrderRepository;
import com.cretas.aims.service.factory.WarehouseResolver;
import com.cretas.aims.service.inventory.FgQuantityUnitConverter;
import com.cretas.aims.service.inventory.FgReservationLedgerService;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * 销售订单 → 成品库存 匹配与预留服务
 *
 * <p>职责：
 * <ol>
 *   <li>检查销售订单每个行项目的成品库存可用量（{@link #checkAvailability}）</li>
 *   <li>按 FEFO 策略（先到期先出）对指定产品类型的成品批次执行库存预留（{@link #reserveStock}）</li>
 * </ol>
 *
 * @author Cretas Team
 * @since 2026-02-19
 */
@Service
@RequiredArgsConstructor
public class InventoryMatchingService {

    private static final Logger log = LoggerFactory.getLogger(InventoryMatchingService.class);

    private final SalesOrderRepository salesOrderRepository;
    private final FinishedGoodsBatchRepository finishedGoodsBatchRepository;
    private final WarehouseResolver warehouseResolver;
    private final FgReservationLedgerService reservationLedgerService;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private SalesOrderItemRepository salesOrderItemRepository;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private ProductTypeRepository productTypeRepository;

    /**
     * A5 集团联销 feature flag (PR #309 A5=C, 2026-05-10).
     *
     * <p>默认 {@code false} — 销售订单仅匹配 SO.factoryId 所在工厂的成品批次（单厂语义）。
     *
     * <p>设为 {@code true} 时进入"集团联销"模式：当前实现跳过 factoryId 过滤
     * （允许所有工厂的成品参与匹配 / 预留 — 等价于"集团池"语义）。
     * 未来引入 {@code factory_network} 表后，此处可替换为按销售组织受控的子集。
     *
     * <p>启用方式：在对应环境的 properties 文件中设置
     * {@code cretas.sales.cross-factory.enabled=true}，重启 JVM 即可。
     *
     * <p>详见 {@code docs/architecture/2026-05-10-feature-flag-cross-factory-sales.md}。
     */
    @Value("${cretas.sales.cross-factory.enabled:false}")
    private boolean crossFactoryEnabled;

    /**
     * 检查已确认销售订单的成品库存可用性。
     * 对每个行项目，比较待发货数量与当前可用库存的差值。
     *
     * @param factoryId    工厂 ID
     * @param salesOrderId 销售订单 ID
     * @return 包含每个行项目库存匹配情况及整单是否可满足的检查结果
     * @throws BusinessException 若销售订单不存在
     */
    @Transactional(readOnly = true)
    public StockCheckResult checkAvailability(String factoryId, String salesOrderId) {
        SalesOrder so = salesOrderRepository.findById(salesOrderId)
                .orElseThrow(() -> new BusinessException(404, "销售订单不存在: " + salesOrderId)
                        .withHint("请刷新销售订单列表后重新选择").withHintTarget("salesOrderId"));

        List<LineItemMatch> matches = new ArrayList<>();
        boolean allSatisfied = true;

        for (SalesOrderItem item : so.getItems()) {
            BigDecimal pending = item.getPendingQuantity();
            if (pending.compareTo(BigDecimal.ZERO) <= 0) {
                // 该行项目已全部交货，跳过
                continue;
            }

            String matchingUnit = item.getUnit();
            BigDecimal required = pending;
            if (isPackagingLine(item)) {
                matchingUnit = item.getPackagingBaseUnit();
                required = pending.multiply(item.getPackagingFactor());
            }

            // D1: warehouse strategy per PR #310 §5 — sales from WH-LOG fixed (D5 销售从总仓出货).
            // D5 (2026-05-11 PR #316): cross-factory branch also enforces WH-LOG filter.
            //   - flag=false (default): SO.factoryId + WH-LOG (single-factory + total warehouse).
            //   - flag=true (A5):       all factories + WH-LOG (group pool, still total warehouse only).
            //   WH-WKS (鲜棉仓, 当天清仓) 从不参与销售匹配.
            BigDecimal available;
            if (productTypeRepository == null) {
                // Legacy isolated tests use the original aggregate query path.
                if (crossFactoryEnabled) {
                    available = finishedGoodsBatchRepository
                            .sumAvailableQuantityByProductTypeAllFactoriesAndWarehouseCode(
                                    item.getProductTypeId(), WarehouseCodes.WH_LOG);
                } else {
                    String warehouseId = warehouseResolver.resolveLogisticsId(factoryId);
                    available = finishedGoodsBatchRepository
                            .sumAvailableQuantityByProductTypeAndWarehouse(
                                    factoryId, item.getProductTypeId(), warehouseId);
                }
            } else {
                List<FinishedGoodsBatch> candidateBatches;
                if (crossFactoryEnabled) {
                    candidateBatches = finishedGoodsBatchRepository
                            .findAvailableBatchesAllFactoriesByWarehouseCode(
                                    item.getProductTypeId(), WarehouseCodes.WH_LOG);
                } else {
                    String warehouseId = warehouseResolver.resolveLogisticsId(factoryId);
                    candidateBatches = finishedGoodsBatchRepository
                            .findAvailableBatchesByWarehouse(factoryId, item.getProductTypeId(), warehouseId);
                }
                BigDecimal gramsPerUnit = productTypeRepository.findById(item.getProductTypeId())
                        .map(com.cretas.aims.entity.ProductType::getGramsPerUnit).orElse(null);
                available = BigDecimal.ZERO;
                for (FinishedGoodsBatch batch : candidateBatches) {
                    BigDecimal converted = convertBatchToUnit(
                            batch.getAvailableQuantity(), batch, matchingUnit, gramsPerUnit);
                    if (converted != null) available = available.add(converted);
                }
            }

            // 缺口 = max(待发 - 可用, 0)；若可用充足则缺口为负（富余），isFullySatisfied() 返回 true
            BigDecimal shortfall = required.subtract(available).max(BigDecimal.ZERO);

            LineItemMatch match = new LineItemMatch();
            match.setSalesOrderItemId(item.getId() != null ? String.valueOf(item.getId()) : null);
            match.setProductTypeId(item.getProductTypeId());
            match.setProductTypeName(item.getProductName());
            match.setRequiredQuantity(required);
            match.setAvailableQuantity(available);
            match.setShortfallQuantity(shortfall);
            matches.add(match);

            if (!match.isFullySatisfied()) {
                allSatisfied = false;
            }
        }

        StockCheckResult result = new StockCheckResult();
        result.setSalesOrderId(salesOrderId);
        result.setLineItems(matches);
        result.setAllSatisfied(allSatisfied);

        log.info("库存检查完成: SO={}, 全部满足={}, 行项目={}", salesOrderId, allSatisfied, matches.size());
        return result;
    }

    /**
     * Legacy 3-arg 重载 (test / 无 SO 上下文) —— 不建预留台账, 仅直接累加 batch.reserved。
     *
     * @deprecated 生产路径请用 {@link #reserveStock(String, String, String, String, BigDecimal)}
     *             以建立 per-SO 预留台账 (可精确释放, 防孤儿)。
     */
    @Deprecated
    @Transactional
    public void reserveStock(String factoryId, String productTypeId, BigDecimal quantity) {
        reserveStock(factoryId, null, null, productTypeId, quantity);
    }

    /**
     * 按 FEFO（先到期先出）策略对指定产品类型的成品批次执行库存预留, <b>并建立 per-SO 预留台账</b>。
     *
     * <p>从到期日最早的批次开始依次预留，直至满足所需数量或批次耗尽。
     * 若可用总量不足，记录 WARN 日志但不抛出异常（调用方可根据 {@link #checkAvailability} 结果决策）。
     *
     * <p>{@code salesOrderId != null} 时, 每笔批次预留写一条 ACTIVE 台账行 (via
     * {@link FgReservationLedgerService#reserve}) —— 让 SO 取消 / 发货 / 完成能精确释放, 根治孤儿。
     * {@code salesOrderId == null} (legacy/test) 时退回匿名 reserved 累加, 不建台账。
     *
     * @param factoryId         工厂 ID
     * @param salesOrderId      销售订单 ID (归属主体, null=legacy 匿名)
     * @param salesOrderItemId  销售订单行 ID (可空)
     * @param productTypeId     产品类型 ID
     * @param quantity          需要预留的数量
     */
    @Transactional
    public void reserveStock(String factoryId, String salesOrderId, String salesOrderItemId,
                             String productTypeId, BigDecimal quantity) {
        // D1: warehouse strategy per PR #310 §5 — sales reserve from WH-LOG fixed (D5).
        // D5 (2026-05-11 PR #316): cross-factory FEFO 预留也只取 WH-LOG 批次.
        List<FinishedGoodsBatch> batches;
        if (crossFactoryEnabled) {
            batches = finishedGoodsBatchRepository
                    .findAvailableBatchesAllFactoriesByWarehouseCode(
                            productTypeId, WarehouseCodes.WH_LOG);
        } else {
            String warehouseId = warehouseResolver.resolveLogisticsId(factoryId);
            batches = finishedGoodsBatchRepository
                    .findAvailableBatchesByWarehouse(factoryId, productTypeId, warehouseId);
        }

        SalesOrderItem sourceOrderItem = null;
        if (salesOrderItemRepository != null && salesOrderItemId != null) {
            try {
                sourceOrderItem = salesOrderItemRepository.findById(Long.valueOf(salesOrderItemId)).orElse(null);
            } catch (NumberFormatException ignored) {
                sourceOrderItem = null;
            }
        }
        String reservationUnit = sourceOrderItem == null
                ? null
                : (isPackagingLine(sourceOrderItem)
                        ? sourceOrderItem.getPackagingBaseUnit()
                        : sourceOrderItem.getUnit());
        BigDecimal gramsPerUnit = productTypeRepository == null
                ? null
                : productTypeRepository.findById(productTypeId)
                        .map(com.cretas.aims.entity.ProductType::getGramsPerUnit).orElse(null);

        BigDecimal remaining = quantity;
        for (FinishedGoodsBatch batch : batches) {
            if (remaining.compareTo(BigDecimal.ZERO) <= 0) {
                break;
            }

            BigDecimal availableNative = batch.getAvailableQuantity();
            BigDecimal available = reservationUnit == null
                    ? availableNative
                    : convertBatchToUnit(availableNative, batch, reservationUnit, gramsPerUnit);
            if (available == null) continue;
            BigDecimal reserve = remaining.min(available);
            if (reserve.compareTo(BigDecimal.ZERO) <= 0) {
                continue;
            }
            BigDecimal reserveNative = reservationUnit == null
                    ? reserve
                    : convertUnitToBatch(reserve, reservationUnit, batch, gramsPerUnit);
            if (reserveNative == null || reserveNative.signum() <= 0) continue;
            reserveNative = reserveNative.min(availableNative);

            if (salesOrderId != null) {
                // reserved += reserve 且建 ACTIVE 台账行 (原子一致)。
                reservationLedgerService.reserve(
                        factoryId, salesOrderId, salesOrderItemId, batch, reserveNative);
            } else {
                // legacy 匿名路径 — 仅累加 reserved, 不建台账。
                BigDecimal currentReserved = batch.getReservedQuantity() != null
                        ? batch.getReservedQuantity()
                        : BigDecimal.ZERO;
                batch.setReservedQuantity(currentReserved.add(reserveNative));
                finishedGoodsBatchRepository.save(batch);
            }
            remaining = remaining.subtract(reserve);

            log.debug("预留库存: SO={}, batch={}, reserve={}, remaining={}",
                    salesOrderId, batch.getBatchNumber(), reserve, remaining);
        }

        if (remaining.compareTo(BigDecimal.ZERO) > 0) {
            log.warn("库存预留不完全: SO={}, productType={}, 仍需={}", salesOrderId, productTypeId, remaining);
        }
    }

    private boolean isPackagingLine(SalesOrderItem item) {
        return item != null
                && item.getUnit() != null
                && item.getUnit().equals(item.getPackagingUnit())
                && item.getPackagingBaseUnit() != null
                && item.getPackagingFactor() != null
                && item.getPackagingFactor().signum() > 0;
    }

    private BigDecimal convertBatchToUnit(
            BigDecimal quantity,
            FinishedGoodsBatch batch,
            String targetUnit,
            BigDecimal gramsPerUnit) {
        return FgQuantityUnitConverter.convertWithPackaging(
                quantity,
                batch.getUnit(),
                targetUnit,
                gramsPerUnit,
                batch.getPackagingUnit(),
                batch.getPackagingBaseUnit(),
                batch.getPackagingFactor(),
                null, null, null);
    }

    private BigDecimal convertUnitToBatch(
            BigDecimal quantity,
            String sourceUnit,
            FinishedGoodsBatch batch,
            BigDecimal gramsPerUnit) {
        return FgQuantityUnitConverter.convertWithPackaging(
                quantity,
                sourceUnit,
                batch.getUnit(),
                gramsPerUnit,
                null, null, null,
                batch.getPackagingUnit(),
                batch.getPackagingBaseUnit(),
                batch.getPackagingFactor());
    }
}
