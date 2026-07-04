package com.cretas.aims.event;

import com.cretas.aims.entity.enums.PaymentMethod;
import com.cretas.aims.entity.enums.VoucherType;
import lombok.Getter;
import org.springframework.context.ApplicationEvent;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * 资金段现金流水事件 (finance audit Bug 5).
 *
 * <p>{@code ArApServiceImpl.recordArPayment} (收款) / {@code recordApPayment} (付款) 在写完
 * AR/AP 子账后发布本事件。{@code CashMovementVoucherListener} 以
 * {@code @TransactionalEventListener(AFTER_COMMIT)} 监听, 在收付款事务提交后 fail-soft 生成
 * 现金流水 GL 凭证 (收款 借 1002/贷 1122; 付款 借 2202/贷 1002), 使 1002/1122/2202 随真实
 * 资金流动 —— 而非像修复前那样只动子账、GL 死账。
 *
 * <p>用 AFTER_COMMIT (而非同事务同步生成) 避免凭证 insert 异常 doom 收付款事务 (#1225 同范式)。
 * 事件自包含所有建凭证所需字段, 监听器无需重新 load 实体 (避免 @Async 线程里的 LazyInit)。
 */
@Getter
public class CashMovementRecordedEvent extends ApplicationEvent {

    private final String factoryId;
    /** AR/AP 交易记录 id — 作为凭证幂等键 (sourceBusinessId)。 */
    private final String arApTransactionId;
    /** CASH_RECEIPT (收款) 或 CASH_PAYMENT (付款)。 */
    private final VoucherType voucherType;
    /** 客户 (收款) / 供应商 (付款) id — 辅助核算实体。 */
    private final String counterpartyId;
    private final String counterpartyName;
    /** 收/付款金额, 正数 (子账里存的是负数, 此处已取绝对值)。 */
    private final BigDecimal amount;
    /** 付款方式 — 决定现金科目 (CASH→1001 库存现金, 其余→1002 银行存款)。可空。 */
    private final PaymentMethod paymentMethod;
    /** 凭证日期 (交易发生日)。 */
    private final LocalDate voucherDate;
    /** 操作人 (userId), 作为凭证 createdBy。可空。 */
    private final Long operatedBy;

    public CashMovementRecordedEvent(Object source, String factoryId, String arApTransactionId,
                                     VoucherType voucherType, String counterpartyId, String counterpartyName,
                                     BigDecimal amount, PaymentMethod paymentMethod,
                                     LocalDate voucherDate, Long operatedBy) {
        super(source);
        this.factoryId = factoryId;
        this.arApTransactionId = arApTransactionId;
        this.voucherType = voucherType;
        this.counterpartyId = counterpartyId;
        this.counterpartyName = counterpartyName;
        this.amount = amount;
        this.paymentMethod = paymentMethod;
        this.voucherDate = voucherDate;
        this.operatedBy = operatedBy;
    }
}
