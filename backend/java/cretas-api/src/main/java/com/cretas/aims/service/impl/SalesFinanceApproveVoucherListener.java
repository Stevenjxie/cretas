package com.cretas.aims.service.impl;

import com.cretas.aims.entity.enums.VoucherFlag;
import com.cretas.aims.entity.finance.Voucher;
import com.cretas.aims.entity.inventory.SalesOrder;
import com.cretas.aims.event.SalesOrderFinanceApprovedEvent;
import com.cretas.aims.repository.inventory.SalesOrderRepository;
import com.cretas.aims.service.voucher.VoucherService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.util.Optional;

/**
 * SP5 / #754: 财务审核通过 → 自动生成销售凭证 (三行价税分离) + 更新 vflag.
 *
 * <p>监听 {@link SalesOrderFinanceApprovedEvent}:
 * <ol>
 *   <li>调 {@link VoucherService#createFromBusiness} 自动生成销售凭证
 *       (复用 {@code SalesReceiptVoucherGenerator} 三行模板 借应收/贷收入/贷销项税).
 *       {@code createFromBusiness} 本身幂等 — 已有凭证直接返回, 不重复生成.</li>
 *   <li>成功 → vflag = CREATED; 失败 → vflag = FAILED (财审主流程不受影响).</li>
 * </ol>
 *
 * <p>设计选择:
 * <ul>
 *   <li>{@code @TransactionalEventListener(AFTER_COMMIT)}: 财审事务提交后才触发,
 *       SalesOrder 已落库 → createFromBusiness 能 load 到实体; 父事务 rollback 时不生成凭证.</li>
 *   <li>{@code @Async}: 凭证生成不阻塞财审主流程 (response 不等凭证).</li>
 *   <li>幂等 (双层): (a) vflag 已 CREATED/FAILED → skip; (b) createFromBusiness 内部
 *       findBySourceBusiness 幂等命中直接返回 existing.</li>
 *   <li>fail-soft: 凭证生成异常被 catch, 仅标 vflag=FAILED + 日志告警, <b>绝不抛出</b>
 *       (AFTER_COMMIT 监听器抛异常也无法回滚已提交的财审, 反而污染异步线程).</li>
 * </ul>
 *
 * <p>🔒 资金红线: 凭证借贷平衡由 {@code AbstractVoucherGenerator.generate} 末尾的
 * {@code validateBalanced()} 强制保证 (借应收 = 贷收入 + 贷销项税, SalesReceiptVoucherGenerator
 * 用加法构造 receivable = net + tax 无舍入裂缝). 期间结账 gate (assertPeriodOpen) 仍生效.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SalesFinanceApproveVoucherListener {

    private static final String BUSINESS_TYPE = "SALES_ORDER";

    private final SalesOrderRepository salesOrderRepository;
    private final VoucherService voucherService;

    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onFinanceApproved(SalesOrderFinanceApprovedEvent event) {
        String orderId = event.getSalesOrderId();
        String factoryId = event.getFactoryId();
        Optional<SalesOrder> orderOpt = salesOrderRepository.findById(orderId);
        if (orderOpt.isEmpty()) {
            log.warn("SP5 SalesFinanceApproveVoucherListener: order {} not found, skipped", orderId);
            return;
        }

        SalesOrder order = orderOpt.get();

        // 幂等层 (a): 已经是 CREATED / FAILED 状态, 不重复生成凭证
        VoucherFlag currentFlag = order.getVflag();
        if (currentFlag == VoucherFlag.CREATED || currentFlag == VoucherFlag.FAILED) {
            log.debug("SP5 VoucherListener: order {} already in terminal vflag {}, skipped",
                    orderId, currentFlag);
            return;
        }

        // 状态机 (VoucherFlag): UNCREATED → PENDING → CREATED/FAILED. 先标 PENDING (生成中).
        order.setVflag(VoucherFlag.PENDING);
        salesOrderRepository.save(order);

        // 自动生成销售凭证 (三行价税分离). createFromBusiness 幂等 — 已有凭证直接返回.
        // fail-soft: 任何异常仅标 FAILED + 告警, 不抛出 (AFTER_COMMIT 抛异常无法回滚财审,
        // 且会污染 @Async 线程; 财务可后续手动 POST /vouchers/generate 补单, FAILED→PENDING retry).
        try {
            Voucher voucher = voucherService.createFromBusiness(factoryId, BUSINESS_TYPE, orderId);
            order.setVflag(VoucherFlag.CREATED);
            salesOrderRepository.save(order);
            log.info("SP5 VoucherListener: order {} 自动生成销售凭证 {} (vflag=CREATED, factoryId={}, total={})",
                    orderId, voucher.getVoucherNumber(), factoryId, voucher.getTotalDebit());
        } catch (Exception e) {
            order.setVflag(VoucherFlag.FAILED);
            salesOrderRepository.save(order);
            log.error("SP5 VoucherListener: order {} 自动生成销售凭证失败 (vflag=FAILED, 不影响财审; "
                    + "财务可手动 POST /vouchers/generate 补单): {}", orderId, e.getMessage(), e);
        }
    }
}
