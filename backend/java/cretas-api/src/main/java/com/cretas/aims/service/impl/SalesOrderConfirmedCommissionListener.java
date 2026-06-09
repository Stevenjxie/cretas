package com.cretas.aims.service.impl;

import com.cretas.aims.dto.pricing.CommissionPreviewDTO;
import com.cretas.aims.entity.Customer;
import com.cretas.aims.entity.inventory.SalesOrder;
import com.cretas.aims.event.SalesOrderConfirmedEvent;
import com.cretas.aims.repository.CustomerRepository;
import com.cretas.aims.repository.inventory.SalesOrderRepository;
import com.cretas.aims.service.CommissionService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.time.LocalDate;
import java.util.Optional;

/**
 * SP5: 销售订单确认 → 预估提成，写入 SalesOrder.commissionPreview / commissionRatePct.
 *
 * <p>监听 {@link SalesOrderConfirmedEvent}，调用 {@link CommissionService#previewByGrossMargin}
 * 计算预估提成金额和费率，并回写到对应 SalesOrder 字段。
 *
 * <p>设计选择:
 * <ul>
 *   <li>{@code @TransactionalEventListener(AFTER_COMMIT)}: SO 确认事务提交后才触发，
 *       避免在父事务 rollback 时写入无效提成预估值。</li>
 *   <li>{@code @Async}: 提成预估不阻塞订单确认主流程。</li>
 *   <li>幂等: 已有 commissionPreview（非 null）时跳过，避免重复触发覆盖。</li>
 *   <li>业务降级: 找不到客户 customerType 时以 null 传入 CommissionService，
 *       服务层返回 hasRule=false 的预览（不中断主流程）。</li>
 * </ul>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SalesOrderConfirmedCommissionListener {

    private final SalesOrderRepository salesOrderRepository;
    private final CustomerRepository customerRepository;
    private final CommissionService commissionService;

    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onSalesOrderConfirmed(SalesOrderConfirmedEvent event) {
        String orderId = event.getSalesOrderId();
        String factoryId = event.getFactoryId();

        Optional<SalesOrder> orderOpt = salesOrderRepository.findById(orderId);
        if (orderOpt.isEmpty()) {
            log.warn("SP5 CommissionListener: order {} not found, skipped", orderId);
            return;
        }

        SalesOrder order = orderOpt.get();

        // 幂等: 已有提成预估值，不重复计算
        if (order.getCommissionPreview() != null) {
            log.debug("SP5 CommissionListener: order {} already has commissionPreview {}, skipped",
                    orderId, order.getCommissionPreview());
            return;
        }

        // 解析 customerType（可为 null — CommissionService 兜底 hasRule=false）
        String customerType = null;
        if (order.getCustomerId() != null) {
            Optional<Customer> customerOpt = customerRepository.findById(order.getCustomerId());
            if (customerOpt.isPresent()) {
                customerType = customerOpt.get().getCustomerType();
            } else {
                log.debug("SP5 CommissionListener: customer {} not found for order {}, customerType=null",
                        order.getCustomerId(), orderId);
            }
        }

        // 确认日期：优先 order.confirmedAt，兜底今天
        LocalDate confirmedDate = order.getConfirmedAt() != null
                ? order.getConfirmedAt().toLocalDate()
                : LocalDate.now();

        try {
            CommissionPreviewDTO preview = commissionService.previewByGrossMargin(
                    factoryId,
                    order.getTotalAmount(),
                    order.getSalespersonId(),
                    customerType,
                    confirmedDate
            );

            if (!preview.isHasRule()) {
                log.debug("SP5 CommissionListener: no applicable commission rule for order {} "
                        + "(salespersonId={}, customerType={}), skipped write-back",
                        orderId, order.getSalespersonId(), customerType);
                return;
            }

            order.setCommissionPreview(preview.getCommissionAmount());
            order.setCommissionRatePct(preview.getCommissionRate());
            salesOrderRepository.save(order);

            log.info("SP5 CommissionListener: order {} commissionPreview={} ratePct={} written "
                    + "(factoryId={}, salespersonId={}, customerType={})",
                    orderId, preview.getCommissionAmount(), preview.getCommissionRate(),
                    factoryId, order.getSalespersonId(), customerType);

        } catch (Exception e) {
            // 隔离失败 — 不让提成预估问题影响已提交的 SO 确认
            log.error("SP5 CommissionListener: failed to compute commission preview for order {} — skipped",
                    orderId, e);
        }
    }
}
