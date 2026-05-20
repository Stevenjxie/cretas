package com.cretas.aims.service.finance.impl;

import com.cretas.aims.dto.finance.InvoiceApplyRequest;
import com.cretas.aims.dto.finance.InvoiceApplyResponse;
import com.cretas.aims.dto.finance.InvoiceStatusResponse;
import com.cretas.aims.service.finance.TaxDirectInvoiceProvider;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 百望云 数电票直连 Provider — F-TAX-DIRECT-1 Sprint 5 spike stub (2026-05-19).
 *
 * <p><b>这是 SKELETON, 全方法 throw {@code UnsupportedOperationException}。</b>
 * Sprint 6 W1 完整实现:
 * <ol>
 *   <li>注入 Baiwang SDK 依赖 (Maven: {@code com.baiwang:baiwang-sdk}) OR 自建 REST WebClient</li>
 *   <li>实装 4 个方法 (apply / queryStatus / downloadInvoicePdf / cancelInvoice)</li>
 *   <li>添加 HMAC-SHA256 签名逻辑 (per 百望 OpenAPI 文档)</li>
 *   <li>添加 retry + backoff (Spring Retry 或 Resilience4j)</li>
 *   <li>切换 {@code @Primary} 从 {@link NoopTaxDirectProvider} 到本类 (Spring Profile / config)</li>
 * </ol>
 *
 * <p>百望 API endpoint reference:
 * <ul>
 *   <li>{@code POST /apis/v1/invoice/apply} — 申请开票</li>
 *   <li>{@code GET  /apis/v1/invoice/status/{id}} — 查询状态</li>
 *   <li>{@code GET  /apis/v1/invoice/pdf/{id}} — 下载 PDF</li>
 *   <li>{@code POST /apis/v1/invoice/cancel/{id}} — 红冲</li>
 * </ul>
 *
 * <p>配置 (Sprint 6 加到 application.yml):
 * <pre>
 * cretas:
 *   tax-direct:
 *     baiwang:
 *       app-key: ${BAIWANG_APP_KEY}
 *       app-secret: ${BAIWANG_APP_SECRET}
 *       endpoint: https://openapi.baiwang.com  # sandbox: https://sandbox-openapi.baiwang.com
 *       timeout-seconds: 30
 * </pre>
 *
 * <p>spec: docs/superpowers/specs/2026-05-19-tax-direct-spike.md §3.1 §4 Phase 2
 */
@Slf4j
@Component
@Qualifier("baiwang")
public class BaiwangTaxDirectProviderStub implements TaxDirectInvoiceProvider {

    private static final String NOT_IMPLEMENTED_MSG =
            "BaiwangTaxDirectProvider 未实装 (Sprint 5 spike stub). "
                    + "Sprint 6 W1 完成实装。当前请使用 NoopTaxDirectProvider。";

    @Value("${cretas.tax-direct.baiwang.app-key:UNSET}")
    private String appKey;

    @Value("${cretas.tax-direct.baiwang.app-secret:UNSET}")
    private String appSecret;

    @Value("${cretas.tax-direct.baiwang.endpoint:https://sandbox-openapi.baiwang.com}")
    private String endpoint;

    @Override
    public String getProviderName() {
        return "BAIWANG";
    }

    @Override
    public InvoiceApplyResponse applyForDirect(InvoiceApplyRequest req) throws TaxDirectProviderException {
        log.info("[TaxDirect.BAIWANG.STUB] applyForDirect (NOT IMPLEMENTED) — factoryId={} invoiceRecordId={} amount={}",
                req.getFactoryId(), req.getInvoiceRecordId(), req.getTotalAmount());
        // TODO Sprint 6 W1:
        //   1. Build HMAC-SHA256 signature: sign = hmacSha256(body, appSecret)
        //   2. POST to {endpoint}/apis/v1/invoice/apply
        //   3. Parse JSON response → InvoiceApplyResponse
        //   4. Persist providerInvoiceId to invoice_records.tax_direct_provider_id
        throw new UnsupportedOperationException(NOT_IMPLEMENTED_MSG);
    }

    @Override
    public InvoiceStatusResponse queryStatus(String providerInvoiceId) throws TaxDirectProviderException {
        log.info("[TaxDirect.BAIWANG.STUB] queryStatus (NOT IMPLEMENTED) — id={}", providerInvoiceId);
        // TODO Sprint 6 W1:
        //   1. GET {endpoint}/apis/v1/invoice/status/{providerInvoiceId}
        //   2. Map Baiwang status → TaxDirectStatus enum
        throw new UnsupportedOperationException(NOT_IMPLEMENTED_MSG);
    }

    @Override
    public byte[] downloadInvoicePdf(String providerInvoiceId) throws TaxDirectProviderException {
        log.info("[TaxDirect.BAIWANG.STUB] downloadInvoicePdf (NOT IMPLEMENTED) — id={}", providerInvoiceId);
        // TODO Sprint 6 W1:
        //   1. GET {endpoint}/apis/v1/invoice/pdf/{providerInvoiceId}
        //   2. Response is Base64-encoded PDF OR redirect to OSS URL
        throw new UnsupportedOperationException(NOT_IMPLEMENTED_MSG);
    }

    @Override
    public void cancelInvoice(String providerInvoiceId, String reason) throws TaxDirectProviderException {
        log.info("[TaxDirect.BAIWANG.STUB] cancelInvoice (NOT IMPLEMENTED) — id={} reason={}",
                providerInvoiceId, reason);
        // TODO Sprint 6 W1:
        //   1. POST {endpoint}/apis/v1/invoice/cancel/{providerInvoiceId} with {"reason": reason}
        //   2. 注意税局红冲时间窗口 (7-30 天, 视省份而定)
        throw new UnsupportedOperationException(NOT_IMPLEMENTED_MSG);
    }
}
