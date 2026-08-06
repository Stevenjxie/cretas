package com.cretas.aims.service.finding;

/**
 * 规则**诚实跳过**：数据不足以判断，而不是「查过了没异常」，也不是「查询失败」。
 *
 * <p>三态里的第三态。为什么不复用普通异常（落 {@code failedRules}）：那会把
 * 「历史不够」说成「查询失败」，用户看到的是同一句话，而这两件事的处置完全不同
 * —— 前者要等数据攒够，后者要去查服务。禁止降级处理不只是「别把失败说成正常」，
 * 也包括「别把两种不同的坏消息说成同一种」。
 *
 * <p>⚠️ 必须是 {@link RuntimeException}：{@code FindingProvider#detect} 的既有
 * 签名不声明 {@code throws}，改成受检异常会打断包括
 * {@code LowStockFindingProvider} 在内的全部既有实现。
 */
public class FindingNotApplicableException extends RuntimeException {

    private final String reason;

    public FindingNotApplicableException(String reason) {
        super(reason);
        this.reason = reason;
    }

    /** 给用户看的跳过理由，会原样出现在「暂不判断」那句话里。 */
    public String reason() {
        return reason;
    }
}
