package com.cretas.aims.service.inventory.impl;

import com.cretas.aims.dto.sales.AdjustPriceRequest;
import com.cretas.aims.dto.sales.AdjustPriceResponse;
import com.cretas.aims.dto.sales.SalesPriceAdjustmentRecordDTO;
import com.cretas.aims.entity.User;
import com.cretas.aims.entity.config.ApprovalChainConfig.DecisionType;
import com.cretas.aims.entity.inventory.SalesOrder;
import com.cretas.aims.entity.inventory.SalesOrderItem;
import com.cretas.aims.entity.inventory.SalesPriceAdjustmentRecord;
import com.cretas.aims.entity.inventory.SalesPriceAdjustmentRecord.ApprovalStatus;
import com.cretas.aims.entity.inventory.SalesPriceAdjustmentRecord.ReasonType;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.exception.ResourceNotFoundException;
import com.cretas.aims.repository.UserRepository;
import com.cretas.aims.repository.inventory.SalesOrderItemRepository;
import com.cretas.aims.repository.inventory.SalesOrderRepository;
import com.cretas.aims.repository.inventory.SalesPriceAdjustmentRecordRepository;
import com.cretas.aims.service.ApprovalChainService;
import com.cretas.aims.service.inventory.SalesPriceAdjustmentService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * 销售订单行价格调整服务实现
 *
 * <p>阈值规则:
 * <ul>
 *   <li>降价 > 10% → 触发审批</li>
 *   <li>涨价 > 20% → 触发审批</li>
 * </ul>
 *
 * <p>BigDecimal 规范: 所有金额计算使用 HALF_UP，价格 scale=4，总额 scale=2。
 */
@Slf4j
@Service
public class SalesPriceAdjustmentServiceImpl implements SalesPriceAdjustmentService {

    /** 降价阈值: 超过此比例需审批 */
    private static final BigDecimal DECREASE_THRESHOLD_PCT = new BigDecimal("10");
    /** 涨价阈值: 超过此比例需审批 */
    private static final BigDecimal INCREASE_THRESHOLD_PCT = new BigDecimal("20");

    private final SalesOrderRepository salesOrderRepository;
    private final SalesOrderItemRepository salesOrderItemRepository;
    private final SalesPriceAdjustmentRecordRepository adjustmentRecordRepository;
    private final UserRepository userRepository;

    /** N9 optional — factories without approval chain config skip approval flow */
    @Autowired(required = false)
    private ApprovalChainService approvalChainService;

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

        // Ensure this line belongs to the given order and factory (security guard)
        if (!orderId.equals(line.getSalesOrderId())) {
            throw new BusinessException(403, "行 " + lineId + " 不属于订单 " + orderId);
        }
        SalesOrder order = salesOrderRepository.findById(orderId)
                .orElseThrow(() -> new ResourceNotFoundException("销售订单不存在: " + orderId));
        if (!factoryId.equals(order.getFactoryId())) {
            throw new BusinessException(403, "订单 " + orderId + " 不属于工厂 " + factoryId);
        }

        // 2. Status guard: cannot reprice shipped or invoiced lines
        BigDecimal deliveredQty = line.getDeliveredQuantity() != null ? line.getDeliveredQuantity() : BigDecimal.ZERO;
        if (deliveredQty.compareTo(BigDecimal.ZERO) > 0) {
            throw new BusinessException(409,
                    "行 " + lineId + " 已有发货数量 (" + deliveredQty.stripTrailingZeros().toPlainString()
                            + " " + line.getUnit() + ")，不允许改价")
                    .withHint("已发货行价格已锁定，如需修改请先撤回发货记录")
                    .withHintTarget("deliveredQuantity");
        }

        // 3. Fool-proof Rule 3: validate OTHER reason requires detail
        if (ReasonType.OTHER == request.reasonType()
                && (request.reasonDetail() == null || request.reasonDetail().isBlank())) {
            throw new BusinessException(400,
                    "改价原因选择「其他」时，原因明细 (reasonDetail) 不能为空")
                    .withHintTarget("reasonDetail");
        }

        // 4. Idempotency: if a PENDING approval already exists for this line, return it (Rule 4)
        Optional<SalesPriceAdjustmentRecord> pendingRecord = adjustmentRecordRepository
                .findFirstBySalesOrderLineIdAndApprovalStatusOrderByCreatedAtDesc(lineId, ApprovalStatus.PENDING);
        if (pendingRecord.isPresent()) {
            SalesPriceAdjustmentRecord existing = pendingRecord.get();
            log.info("改价幂等返回: lineId={}, existingRecordId={}", lineId, existing.getId());
            return new AdjustPriceResponse(
                    existing.getId(),
                    orderId,
                    lineId,
                    existing.getOldUnitPrice(),
                    existing.getNewUnitPrice(),
                    existing.isApprovalRequired(),
                    existing.getApprovalStatus().name(),
                    existing.getApprovalChainId(),
                    false
            );
        }

        BigDecimal oldPrice = line.getUnitPrice() != null ? line.getUnitPrice() : BigDecimal.ZERO;
        BigDecimal newPrice = request.newUnitPrice().setScale(4, RoundingMode.HALF_UP);

        // 5. Determine if approval is required based on price change thresholds
        boolean requiresApproval = determineApprovalRequired(factoryId, oldPrice, newPrice, order);

        // 6. Resolve operator name
        String operatorName = resolveUserName(userId);

        // 7. Create adjustment record
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
        record.setApprovalRequired(requiresApproval);
        record.setApprovalStatus(requiresApproval ? ApprovalStatus.PENDING : ApprovalStatus.NOT_REQUIRED);

        String approvalChainId = null;
        boolean effectiveImmediately;

        if (requiresApproval) {
            // 8a. Trigger approval chain
            approvalChainId = triggerApprovalChain(factoryId, orderId, lineId, oldPrice, newPrice, order, userId);
            record.setApprovalChainId(approvalChainId);
            effectiveImmediately = false;
            log.info("改价触发审批: orderId={}, lineId={}, oldPrice={}, newPrice={}, chainId={}",
                    orderId, lineId, oldPrice, newPrice, approvalChainId);
        } else {
            // 8b. Apply price change immediately
            line.setUnitPrice(newPrice);
            salesOrderItemRepository.save(line);
            recalculateOrderTotal(order);
            effectiveImmediately = true;
            log.info("改价立即生效: orderId={}, lineId={}, oldPrice={} → newPrice={}",
                    orderId, lineId, oldPrice, newPrice);
        }

        SalesPriceAdjustmentRecord saved = adjustmentRecordRepository.save(record);

        return new AdjustPriceResponse(
                saved.getId(),
                orderId,
                lineId,
                oldPrice,
                newPrice,
                requiresApproval,
                saved.getApprovalStatus().name(),
                approvalChainId,
                effectiveImmediately
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
     * 判断是否需要审批.
     *
     * <p>阈值: 降价 > 10% 或 涨价 > 20%.
     * 若工厂配置了 SALES_ORDER_APPROVAL 审批链, 还需检查该链的条件.
     */
    private boolean determineApprovalRequired(String factoryId, BigDecimal oldPrice,
                                              BigDecimal newPrice, SalesOrder order) {
        if (oldPrice.compareTo(BigDecimal.ZERO) == 0) {
            // 原价为 0 时无法计算变化百分比, 只要有审批链就走审批
            return approvalChainService != null
                    && !approvalChainService.getConfigsByDecisionType(factoryId, DecisionType.SALES_ORDER_APPROVAL).isEmpty();
        }

        BigDecimal changePct = newPrice.subtract(oldPrice)
                .divide(oldPrice, 4, RoundingMode.HALF_UP)
                .multiply(new BigDecimal("100"))
                .setScale(2, RoundingMode.HALF_UP);

        // Threshold-based rule
        if (changePct.compareTo(BigDecimal.ZERO) < 0) {
            // Decrease
            if (changePct.abs().compareTo(DECREASE_THRESHOLD_PCT) > 0) {
                log.debug("降价 {}% > {}% 阈值, 触发审批: orderId={}",
                        changePct.abs(), DECREASE_THRESHOLD_PCT, order.getId());
                return true;
            }
        } else {
            // Increase
            if (changePct.compareTo(INCREASE_THRESHOLD_PCT) > 0) {
                log.debug("涨价 {}% > {}% 阈值, 触发审批: orderId={}",
                        changePct, INCREASE_THRESHOLD_PCT, order.getId());
                return true;
            }
        }

        // Additionally check if factory has configured approval chain with conditions
        if (approvalChainService != null) {
            Map<String, Object> context = Map.of(
                    "orderId", order.getId(),
                    "orderNumber", order.getOrderNumber() != null ? order.getOrderNumber() : "",
                    "amount", order.getTotalAmount() != null ? order.getTotalAmount() : BigDecimal.ZERO,
                    "priceChangePct", changePct
            );
            return approvalChainService.requiresApproval(factoryId, DecisionType.SALES_ORDER_APPROVAL, context);
        }

        return false;
    }

    /**
     * 触发审批链, 返回审批链 ID (如工厂无审批配置则返回 null).
     */
    private String triggerApprovalChain(String factoryId, String orderId, Long lineId,
                                        BigDecimal oldPrice, BigDecimal newPrice,
                                        SalesOrder order, Long userId) {
        if (approvalChainService == null) {
            log.warn("改价触发审批但 ApprovalChainService 不可用: orderId={}, lineId={}", orderId, lineId);
            return null;
        }
        try {
            Map<String, Object> context = Map.of(
                    "entityType", "SALES_ORDER_PRICE_ADJUSTMENT",
                    "orderId", orderId,
                    "orderNumber", order.getOrderNumber() != null ? order.getOrderNumber() : "",
                    "lineId", lineId,
                    "oldPrice", oldPrice,
                    "newPrice", newPrice,
                    "amount", order.getTotalAmount() != null ? order.getTotalAmount() : BigDecimal.ZERO,
                    "submittedBy", userId
            );
            List<com.cretas.aims.entity.config.ApprovalChainConfig> configs =
                    approvalChainService.getConfigsByDecisionType(factoryId, DecisionType.SALES_ORDER_APPROVAL);
            if (configs.isEmpty()) {
                return null;
            }
            // Use first matching config ID as chain reference
            return configs.get(0).getId();
        } catch (Exception e) {
            log.warn("启动改价审批链失败, 将以 PENDING 状态记录: orderId={}, lineId={}, error={}",
                    orderId, lineId, e.getMessage());
            return null;
        }
    }

    /**
     * 重新计算销售订单总额 (改价后更新 totalAmount).
     *
     * <p>BigDecimal: scale=2 HALF_UP.
     */
    private void recalculateOrderTotal(SalesOrder order) {
        // Force reload items to get updated prices
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
