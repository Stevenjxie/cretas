package com.cretas.aims.exception;

/**
 * Sprint 7 T2 F-PERIOD: 期间已结账, 试图写 voucher 时抛出.
 *
 * <p>错误码 400 — message 含 {year}-{month} + actionHint 含反结账 URL.
 *
 * <p>UI 防呆 (Rule 2 + Rule 5): 前端 catch 此 exception 后弹 ElMessageBox.confirm
 * "{year}-{month} 期间已结账, 凭证不可修改. 是否反结账?" + router.push(actionHint).
 *
 * @since 2026-05-20
 */
public class PeriodClosedException extends BusinessException {

    private final String factoryId;
    private final Integer year;
    private final Integer month;

    public PeriodClosedException(String factoryId, Integer year, Integer month) {
        super(400, buildMessage(year, month));
        this.factoryId = factoryId;
        this.year = year;
        this.month = month;
        // 防呆 R5 dead-end: 给前端跳转到反结账页面的提示
        withHint(String.format("/finance/accounting-period?year=%d&month=%d", year, month));
        withSeverity("BLOCKING");
    }

    private static String buildMessage(Integer year, Integer month) {
        return String.format("%d-%02d 期间已结账, 凭证不可修改. 是否反结账?", year, month);
    }

    public String getFactoryId() { return factoryId; }
    public Integer getYear() { return year; }
    public Integer getMonth() { return month; }
}
