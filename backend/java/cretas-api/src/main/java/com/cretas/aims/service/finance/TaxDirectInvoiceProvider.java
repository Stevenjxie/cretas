package com.cretas.aims.service.finance;

import com.cretas.aims.dto.finance.InvoiceApplyRequest;
import com.cretas.aims.dto.finance.InvoiceApplyResponse;
import com.cretas.aims.dto.finance.InvoiceStatusResponse;

/**
 * 数电票税局直连 Provider 抽象 — F-TAX-DIRECT-1 Sprint 5 spike (2026-05-19).
 *
 * <p>抽象层 design 目的: 支持 multi-provider 切换 (百望 / 诺诺 / 瑞宏),
 * 上层 {@link InvoiceService} 不感知具体 Provider 的 API quirks。
 *
 * <p><b>Async pattern</b>:
 * <ol>
 *   <li>{@link #applyForDirect(InvoiceApplyRequest)} 立刻返回 (税局异步出票, 5-30 分钟)</li>
 *   <li>Scheduler 轮询 {@link #queryStatus(String)} 直到 SUCCESS / FAILED</li>
 *   <li>SUCCESS 时 {@link #downloadInvoicePdf(String)} 下载 PDF, 调现有 {@link InvoiceService#issueInvoice} 完成本地流程</li>
 *   <li>异常时 {@link #cancelInvoice(String, String)} 红冲 (需要先 SUCCESS)</li>
 * </ol>
 *
 * <p><b>实现要求</b>:
 * <ul>
 *   <li>Spring {@code @Component} 注解, 通过 {@code @Qualifier("baiwang")} 等区分</li>
 *   <li>线程安全 (provider 跨 request 单例)</li>
 *   <li>异常用 {@link TaxDirectProviderException} 包装, 上层 retry / log</li>
 *   <li>幂等性: 同 invoiceRecordId 重复调用 apply, 返回相同 providerInvoiceId (Provider 端 dedup)</li>
 * </ul>
 *
 * <p>spec: docs/superpowers/specs/2026-05-19-tax-direct-spike.md §3.2
 *
 * @see com.cretas.aims.service.finance.impl.NoopTaxDirectProvider 默认实现 (生产 default)
 * @see com.cretas.aims.service.finance.impl.BaiwangTaxDirectProviderStub 百望 skeleton
 * @since Sprint 5 (2026-05-19)
 */
public interface TaxDirectInvoiceProvider {

    /**
     * Provider 标识 (用于多 Provider 切换 / 日志 / 监控).
     *
     * @return 唯一名称, 全大写, 如 "BAIWANG" / "NUONUO" / "NOOP"
     */
    String getProviderName();

    /**
     * 提交开票申请到税局。异步返回, 需轮询 {@link #queryStatus(String)}。
     *
     * <p>Provider 应在收到 request 后:
     * <ol>
     *   <li>校验税号 / 金额 / 明细行 (合法性 + 业务规则)</li>
     *   <li>调税局 API 提交开票 (税局返回 transactionId)</li>
     *   <li>返回 {@link InvoiceApplyResponse} 带 status=REQUESTED + estimatedCompletionAt</li>
     * </ol>
     *
     * @param req 开票请求 (含 sellerTaxId / buyerTaxId / items / totalAmount)
     * @return 含 providerInvoiceId 的响应; status 通常是 REQUESTED (异步)
     * @throws TaxDirectProviderException 网络 / Provider 内部 / 税局 reject 等异常
     */
    InvoiceApplyResponse applyForDirect(InvoiceApplyRequest req) throws TaxDirectProviderException;

    /**
     * 查询开票状态 (Provider 内部 ID).
     *
     * <p>Scheduler 轮询调用:
     * <ul>
     *   <li>status=REQUESTED → 继续等待</li>
     *   <li>status=SUCCESS → 调 {@link #downloadInvoicePdf(String)} 拉 PDF</li>
     *   <li>status=FAILED → 写 error_message, 不重试 (用户手动 retry)</li>
     * </ul>
     *
     * @param providerInvoiceId apply 返回的 ID
     * @return 当前状态 + PDF URL (如 SUCCESS) + error (如 FAILED)
     * @throws TaxDirectProviderException 网络 / Provider 内部异常
     */
    InvoiceStatusResponse queryStatus(String providerInvoiceId) throws TaxDirectProviderException;

    /**
     * 下载发票 PDF (return raw byte[], 上层负责存 OSS).
     *
     * <p>仅当 {@link #queryStatus(String)} 返回 SUCCESS 后调用。
     *
     * @param providerInvoiceId apply 返回的 ID
     * @return PDF 文件 raw bytes (通常 50-200 KB)
     * @throws TaxDirectProviderException 网络 / Provider 内部异常
     */
    byte[] downloadInvoicePdf(String providerInvoiceId) throws TaxDirectProviderException;

    /**
     * 红冲 (撤销已开发票).
     *
     * <p>仅当状态 SUCCESS 后可调用 (REQUESTED / FAILED 不能红冲)。
     * 税局对红冲有时间窗口限制 (通常开票 7 天内, 部分省份 30 天)。
     *
     * @param providerInvoiceId apply 返回的 ID
     * @param reason 红冲原因 (税局要求填)
     * @throws TaxDirectProviderException 网络 / Provider 内部 / 税局 reject (超时 / 重复红冲) 异常
     */
    void cancelInvoice(String providerInvoiceId, String reason) throws TaxDirectProviderException;

    /**
     * Provider exception — 包装所有 Provider 错误 (网络 / 4xx / 5xx / 税局 reject).
     */
    class TaxDirectProviderException extends Exception {
        private final String providerErrorCode;

        public TaxDirectProviderException(String message) {
            super(message);
            this.providerErrorCode = null;
        }

        public TaxDirectProviderException(String message, String providerErrorCode, Throwable cause) {
            super(message, cause);
            this.providerErrorCode = providerErrorCode;
        }

        public String getProviderErrorCode() {
            return providerErrorCode;
        }
    }
}
