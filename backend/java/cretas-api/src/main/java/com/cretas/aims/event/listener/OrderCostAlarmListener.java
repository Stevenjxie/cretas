package com.cretas.aims.event.listener;

import com.cretas.aims.entity.enums.FactoryUserRole;
import com.cretas.aims.entity.enums.NotificationType;
import com.cretas.aims.event.ProductionCostUpdatedEvent;
import com.cretas.aims.repository.ProductTypeRepository;
import com.cretas.aims.service.NotificationService;
import com.cretas.aims.service.bom.CostVarianceService;
import com.cretas.aims.service.bom.StandardCostService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * SP3 P1 #39: 生产成本更新后触发超支百分比预警 + 推送销售主管/工厂总监通知.
 *
 * <p>当 actualUnitCost 超过 BOM 标准成本 × (1 + threshold%) 时:
 * <ol>
 *   <li>记录告警日志 (既有)</li>
 *   <li>推送 App 内通知给 {@code sales_manager} 和 {@code factory_super_admin} (新增)</li>
 * </ol>
 *
 * <p>脱敏规则: 通知 content 使用超支百分比 (相对指标, 不脱敏),
 * 不包含绝对成本金额 (避免 @PriceSensitive 绕过).</p>
 *
 * <p>隔离级别: {@code REQUIRES_NEW} 防止 doomed-tx 传播.</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OrderCostAlarmListener {

    private static final String SOURCE = "SP3_COST_ALARM";

    /**
     * D1: 同口径标准成本 (料 + 研发预估标准人工) — 取代旧的纯料 BomRecipe.totalCost.
     *
     * <p>修复假阳性超支推送: 旧实现拿纯料标准 (BomRecipe.totalCost) 比含人工的实际成本
     * (actualUnitCost = 报工料+人工+制费 rollup) → 系统性虚高 → 误推超支通知给销售主管/工厂总监。
     */
    private final StandardCostService standardCostService;
    private final CostVarianceService costVarianceService;
    private final NotificationService notificationService;
    private final ProductTypeRepository productTypeRepo;

    @Async
    @EventListener
    @Transactional(transactionManager = "primaryTransactionManager",
                   propagation = Propagation.REQUIRES_NEW,
                   readOnly = true)
    public void onProductionCostUpdated(ProductionCostUpdatedEvent event) {
        try {
            String factoryId = event.getFactoryId();
            String productTypeId = event.getProductTypeId();
            BigDecimal actualUnitCost = event.getActualUnitCost();
            if (factoryId == null || productTypeId == null || actualUnitCost == null) return;

            // D1: 同口径标准成本 (料 + 研发预估人工). 口径不全 (totalUnitCost null / 缺人工) → 跳过,
            //   不拿纯料标准比含人工实际 (假阳性), 诚实不报警。
            StandardCostService.StandardUnitCost std =
                    standardCostService.resolveStandardUnitCost(factoryId, productTypeId);
            if (!std.isLaborIncluded() || std.getTotalUnitCost() == null) {
                log.info("[SP3-Alarm] 标准成本口径不全 (缺研发预估人工), 跳过超支报警避免误报: " +
                                "factoryId={}, productTypeId={}, hint={}",
                        factoryId, productTypeId, std.getCaliberHint());
                return;
            }
            BigDecimal standardCost = std.getTotalUnitCost();
            if (standardCost.compareTo(BigDecimal.ZERO) <= 0) return;

            BigDecimal variancePct = costVarianceService.computeVariancePct(actualUnitCost, standardCost);
            if (variancePct == null) return;

            BigDecimal threshold = costVarianceService.resolveThreshold(factoryId, productTypeId);
            boolean exceeded = variancePct.compareTo(threshold) > 0;

            if (exceeded) {
                log.warn("[SP3-Alarm] 成本超支预警: factoryId={}, productTypeId={}, " +
                                "标准成本={}, 实际成本={}, 超支={}%, 阈值={}%",
                        factoryId, productTypeId, standardCost, actualUnitCost,
                        variancePct, threshold);
                pushOverageNotifications(factoryId, productTypeId, variancePct, threshold, event);
            } else {
                log.info("[SP3-Alarm] 成本在控: factoryId={}, productTypeId={}, " +
                                "标准={}, 实际={}, 超支={}%",
                        factoryId, productTypeId, standardCost, actualUnitCost, variancePct);
            }
        } catch (Exception e) {
            // fail-soft: 告警失败不影响主流程
            log.error("[SP3-Alarm] 超支检查失败: {}", e.getMessage(), e);
        }
    }

    /**
     * 超支时推送通知给销售主管和工厂总监.
     *
     * <p>通知内容使用超支百分比 (相对指标), 不含绝对金额 (脱敏安全).</p>
     */
    private void pushOverageNotifications(
            String factoryId,
            String productTypeId,
            BigDecimal variancePct,
            BigDecimal threshold,
            ProductionCostUpdatedEvent event) {

        try {
            // Resolve product name for human-readable notification; fail-safe
            String productName = productTypeRepo.findById(productTypeId)
                    .map(pt -> pt.getName() != null ? pt.getName() : productTypeId)
                    .orElse(productTypeId);

            String pctStr = variancePct.setScale(1, RoundingMode.HALF_UP).toPlainString();
            String threshStr = threshold.setScale(1, RoundingMode.HALF_UP).toPlainString();
            String batchRef = event.getProductionBatchId() != null
                    ? "批次#" + event.getProductionBatchId() : "最新批次";

            String title = "成本超支预警: " + productName;
            String content = String.format(
                    "%s %s 实际生产成本超支 %s%%（阈值 %s%%），请关注人工/物料成本是否异常。",
                    batchRef, productName, pctStr, threshStr);

            String sourceId = event.getProductionBatchId() != null
                    ? event.getProductionBatchId().toString() : null;

            // 推送给销售主管
            notificationService.sendToRole(factoryId, FactoryUserRole.sales_manager,
                    title, content, NotificationType.WARNING, SOURCE, sourceId);

            // 推送给工厂总监
            notificationService.sendToRole(factoryId, FactoryUserRole.factory_super_admin,
                    title, content, NotificationType.WARNING, SOURCE, sourceId);

            log.info("[SP3-Alarm] 超支通知已推送: factoryId={}, productTypeId={}, variance={}%",
                    factoryId, productTypeId, pctStr);
        } catch (Exception e) {
            // fail-soft: 通知失败不影响主流程或告警日志
            log.warn("[SP3-Alarm] 超支通知推送失败: factoryId={}, productTypeId={}: {}",
                    factoryId, productTypeId, e.getMessage());
        }
    }
}
