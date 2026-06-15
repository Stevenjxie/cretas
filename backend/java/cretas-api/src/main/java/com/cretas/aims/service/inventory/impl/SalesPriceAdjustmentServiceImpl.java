package com.cretas.aims.service.inventory.impl;

import com.cretas.aims.dto.sales.AdjustPriceRequest;
import com.cretas.aims.dto.sales.AdjustPriceResponse;
import com.cretas.aims.dto.sales.SalesPriceAdjustmentRecordDTO;
import com.cretas.aims.entity.User;
import com.cretas.aims.entity.inventory.SalesOrder;
import com.cretas.aims.entity.inventory.SalesOrderItem;
import com.cretas.aims.entity.inventory.SalesPriceAdjustmentRecord;
import com.cretas.aims.entity.inventory.SalesPriceAdjustmentRecord.ReasonType;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.exception.ResourceNotFoundException;
import com.cretas.aims.repository.UserRepository;
import com.cretas.aims.repository.inventory.SalesOrderItemRepository;
import com.cretas.aims.repository.inventory.SalesOrderRepository;
import com.cretas.aims.repository.inventory.SalesPriceAdjustmentRecordRepository;
import com.cretas.aims.service.inventory.SalesPriceAdjustmentService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 销售订单行价格调整服务实现 — warn-not-block 模式
 *
 * <p>阈值规则:
 * <ul>
 *   <li>降价 > 10% → flagged=true + priceWarning 响应 (改价仍立即生效)</li>
 *   <li>涨价 > 20% → flagged=true + priceWarning 响应 (改价仍立即生效)</li>
 * </ul>
 *
 * <p>BigDecimal 规范: 所有金额计算使用 HALF_UP，价格 scale=4，总额 scale=2。
 */
@Slf4j
@Service
public class SalesPriceAdjustmentServiceImpl implements SalesPriceAdjustmentService {

    /** 降价超阈值: 变化率绝对值 > 此值时 flagged=true */
    private static final BigDecimal DECREASE_THRESHOLD_PCT = new BigDecimal("10");
    /** 涨价超阈值: 变化率 > 此值时 flagged=true */
    private static final BigDecimal INCREASE_THRESHOLD_PCT = new BigDecimal("20");
    /** 幂等窗口: 同一行同一目标价在此时间内重复提交 → 返回已有记录 (Rule 4) */
    private static final long IDEMPOTENCY_WINDOW_MINUTES = 5;

    private final SalesOrderRepository salesOrderRepository;
    private final SalesOrderItemRepository salesOrderItemRepository;
    private final SalesPriceAdjustmentRecordRepository adjustmentRecordRepository;
    private final UserRepository userRepository;

    public SalesPriceAdjustmentServiceImpl(
            SalesOrderRepository salesOrderRepository,
            SalesOrderItemRepository salesOrderItemRepository,
            SalesPriceAdjustmentRecordRepository adjustmentRecordRepository,
            UserRepository userRepository) {
        this.salesOrderRepository = salesOrderRepository;
        this.salesOrderItemRepository = salesOrderItemRepository;
        this.adjustmentRecordRepository = adjustmentRecordRepository;
        this.userRepository = userRepository;
    }

    @Override
    @Transactional
    public AdjustPriceResponse adjustLinePrice(String factoryId, String orderId, Long lineId,
                                               AdjustPriceRequest request, Long userId) {
        // 1. Load and security-check the SO line
        SalesOrderItem line = salesOrderItemRepository.findById(lineId)
                .orElseThrow(() -> new ResourceNotFoundException("销售订单行不存在: " + lineId));

        // Line must belong to the given order (403 guard)
        if (!orderId.equals(line.getSalesOrderId())) {
            throw new BusinessException(403, "行 " + lineId + " 不属于订单 " + orderId);
        }
        SalesOrder order = salesOrderRepository.findById(orderId)
                .orElseThrow(() -> new ResourceNotFoundException("销售订单不存在: " + orderId));
        if (!factoryId.equals(order.getFactoryId())) {
            throw new BusinessException(403, "订单 " + orderId + " 不属于工厂 " + factoryId);
        }

        // 2. Status guard: cannot reprice shipped lines (409)
        BigDecimal deliveredQty = line.getDeliveredQuantity() != null ? line.getDeliveredQuantity() : BigDecimal.ZERO;
        if (deliveredQty.compareTo(BigDecimal.ZERO) > 0) {
            throw new BusinessException(409,
                    "行 " + lineId + " 已有发货数量 (" + deliveredQty.stripTrailingZeros().toPlainString()
                            + " " + line.getUnit() + ")，不允许改价")
                    .withHint("已发货行价格已锁定，如需修改请先撤回发货记录")
                    .withHintTarget("deliveredQuantity");
        }

        // 3. Fool-proof Rule 3: OTHER reason requires detail (400)
        if (ReasonType.OTHER == request.reasonType()
                && (request.reasonDetail() == null || request.reasonDetail().isBlank())) {
            throw new BusinessException(400,
                    "改价原因选择「其他」时，原因明细 (reasonDetail) 不能为空")
                    .withHintTarget("reasonDetail");
        }

        BigDecimal oldPrice = line.getUnitPrice() != null ? line.getUnitPrice() : BigDecimal.ZERO;
        BigDecimal newPrice = request.newUnitPrice().setScale(4, RoundingMode.HALF_UP);

        // 4. Idempotency Rule 4: 5-min window + same target price → return existing record
        LocalDateTime since = LocalDateTime.now().minusMinutes(IDEMPOTENCY_WINDOW_MINUTES);
        List<SalesPriceAdjustmentRecord> duplicates = adjustmentRecordRepository
                .findRecentDuplicates(lineId, newPrice, since);
        if (!duplicates.isEmpty()) {
            SalesPriceAdjustmentRecord existing = duplicates.get(0);
            log.info("改价幂等返回: lineId={}, existingRecordId={}", lineId, existing.getId());
            return new AdjustPriceResponse(
                    existing.getId(),
                    orderId,
                    lineId,
                    existing.getOldUnitPrice(),
                    existing.getNewUnitPrice(),
                    true,
                    existing.isFlagged(),
                    existing.isFlagged() ? buildWarningMessage(oldPrice, newPrice) : null
            );
        }

        // 5. Compute threshold flag
        boolean flagged = isOverThreshold(oldPrice, newPrice);
        String warningMessage = flagged ? buildWarningMessage(oldPrice, newPrice) : null;

        // 6. Resolve operator name
        String operatorName = resolveUserName(userId);

        // 7. Apply price change IMMEDIATELY (warn-not-block)
        applyPriceChange(line, newPrice, order);

        // 8. Persist audit record
        SalesPriceAdjustmentRecord record = new SalesPriceAdjustmentRecord();
        record.setSalesOrderLineId(lineId);
        record.setSalesOrderId(orderId);
        record.setFactoryId(factoryId);
        record.setOldUnitPrice(oldPrice);
        record.setNewUnitPrice(newPrice);
        record.setAdjustmentReasonType(request.reasonType());
        record.setAdjustmentReasonDetail(request.reasonDetail());
        record.setAdjustedBy(userId);
        record.setAdjustedByName(operatorName);
        record.setFlagged(flagged);

        SalesPriceAdjustmentRecord saved = adjustmentRecordRepository.save(record);

        log.info("改价立即生效 [flagged={}]: orderId={}, lineId={}, {}→{} ({})",
                flagged, orderId, lineId, oldPrice, newPrice,
                warningMessage != null ? warningMessage : "正常");

        return new AdjustPriceResponse(
                saved.getId(),
                orderId,
                lineId,
                oldPrice,
                newPrice,
                true,
                flagged,
                warningMessage
        );
    }

    @Override
    @Transactional(readOnly = true)
    public List<SalesPriceAdjustmentRecordDTO> getPriceAdjustmentHistory(String factoryId, String orderId) {
        return adjustmentRecordRepository
                .findByFactoryIdAndSalesOrderIdOrderByCreatedAtDesc(factoryId, orderId)
                .stream()
                .map(SalesPriceAdjustmentRecordDTO::from)
                .collect(Collectors.toList());
    }

    // ==================== private helpers ====================

    /**
     * 判断改价是否超过预警阈值 (严格 >，等于阈值不触发).
     *
     * <p>原价为 0 时保守返回 false (无法计算变化率).
     * <p>package-private 改为 public 以支持独立测试类 (同名不同包).
     */
    public boolean isOverThreshold(BigDecimal oldPrice, BigDecimal newPrice) {
        if (oldPrice == null || oldPrice.compareTo(BigDecimal.ZERO) == 0) {
            return false; // conservative: can't compute pct, no warning
        }
        BigDecimal changePct = newPrice.subtract(oldPrice)
                .divide(oldPrice, 4, RoundingMode.HALF_UP)
                .multiply(new BigDecimal("100"))
                .setScale(2, RoundingMode.HALF_UP);

        if (changePct.compareTo(BigDecimal.ZERO) < 0) {
            // Decrease: strictly > DECREASE_THRESHOLD_PCT
            return changePct.abs().compareTo(DECREASE_THRESHOLD_PCT) > 0;
        } else {
            // Increase: strictly > INCREASE_THRESHOLD_PCT
            return changePct.compareTo(INCREASE_THRESHOLD_PCT) > 0;
        }
    }

    /**
     * 构建超阈值预警说明文案.
     *
     * <p>文案包含方向 (降价/涨价) + 变化率 + 阈值，不包含绝对价格数值.
     * <p>public 以支持独立测试类直接调用.
     */
    public String buildWarningMessage(BigDecimal oldPrice, BigDecimal newPrice) {
        if (oldPrice == null || oldPrice.compareTo(BigDecimal.ZERO) == 0) {
            return null;
        }
        BigDecimal changePct = newPrice.subtract(oldPrice)
                .divide(oldPrice, 4, RoundingMode.HALF_UP)
                .multiply(new BigDecimal("100"))
                .abs()
                .setScale(1, RoundingMode.HALF_UP);

        boolean isDecrease = newPrice.compareTo(oldPrice) < 0;
        if (isDecrease) {
            return "降价幅度 " + changePct.toPlainString() + "% 超过 "
                    + DECREASE_THRESHOLD_PCT.toPlainString() + "% 预警阈值，已记录审计标记";
        } else {
            return "涨价幅度 " + changePct.toPlainString() + "% 超过 "
                    + INCREASE_THRESHOLD_PCT.toPlainString() + "% 预警阈值，已记录审计标记";
        }
    }

    /**
     * 应用改价到 SO 行并重算总额.
     */
    private void applyPriceChange(SalesOrderItem line, BigDecimal newPrice, SalesOrder order) {
        line.setUnitPrice(newPrice);
        salesOrderItemRepository.save(line);
        recalculateOrderTotal(order);
    }

    /**
     * 重新计算销售订单总额 (BigDecimal scale=2 HALF_UP).
     */
    private void recalculateOrderTotal(SalesOrder order) {
        List<SalesOrderItem> items = salesOrderItemRepository.findBySalesOrderId(order.getId());
        BigDecimal newTotal = items.stream()
                .map(item -> {
                    if (item.getUnitPrice() == null || item.getQuantity() == null) return BigDecimal.ZERO;
                    BigDecimal lineAmt = item.getQuantity()
                            .multiply(item.getUnitPrice())
                            .setScale(2, RoundingMode.HALF_UP);
                    BigDecimal discount = item.getDiscountRate() != null
                            && item.getDiscountRate().compareTo(BigDecimal.ZERO) > 0
                            ? item.getDiscountRate() : BigDecimal.ZERO;
                    if (discount.compareTo(BigDecimal.ZERO) > 0) {
                        BigDecimal multiplier = BigDecimal.ONE.subtract(
                                discount.divide(new BigDecimal("100"), 6, RoundingMode.HALF_UP));
                        lineAmt = lineAmt.multiply(multiplier).setScale(2, RoundingMode.HALF_UP);
                    }
                    return lineAmt;
                })
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        order.setTotalAmount(newTotal.setScale(2, RoundingMode.HALF_UP));
        salesOrderRepository.save(order);
        log.debug("改价后重算 SO 总额: orderId={}, newTotal={}", order.getId(), newTotal);
    }

    private String resolveUserName(Long userId) {
        if (userId == null) return null;
        try {
            return userRepository.findById(userId)
                    .map(User::getFullName)
                    .orElse(null);
        } catch (Exception e) {
            log.warn("获取用户名失败: userId={}, error={}", userId, e.getMessage());
            return null;
        }
    }
}
