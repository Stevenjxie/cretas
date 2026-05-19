package com.cretas.aims.service.finance.impl;

import com.cretas.aims.dto.finance.InvoiceApplyRequest;
import com.cretas.aims.dto.finance.InvoiceApplyResponse;
import com.cretas.aims.dto.finance.InvoiceStatusResponse;
import com.cretas.aims.service.finance.TaxDirectInvoiceProvider;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

/**
 * Noop tax-direct provider — F-TAX-DIRECT-1 Sprint 5 spike default (2026-05-19).
 *
 * <p><b>生产默认 active</b> ({@link Primary}). 所有调用都明确 throw
 * {@code UnsupportedOperationException} — 防止误调用 直连税局 流程在没有 Provider
 * 配置时静默失败。
 *
 * <p>Sprint 6 W1 完成 {@code BaiwangTaxDirectProviderImpl} 后, 通过 Spring Profile
 * 或显式 {@code @Qualifier("baiwang")} 切换。
 *
 * <p>spec: docs/superpowers/specs/2026-05-19-tax-direct-spike.md §4 Phase 1
 */
@Slf4j
@Component
@Primary
@Qualifier("noop")
public class NoopTaxDirectProvider implements TaxDirectInvoiceProvider {

    private static final String UNSUPPORTED_MSG =
            "数电票直连税局功能未启用。请联系管理员配置 Provider (Sprint 6 W1 完成)。";

    @Override
    public String getProviderName() {
        return "NOOP";
    }

    @Override
    public InvoiceApplyResponse applyForDirect(InvoiceApplyRequest req) {
        log.warn("[TaxDirect.NOOP] applyForDirect called but no provider configured. factoryId={} invoiceRecordId={}",
                req.getFactoryId(), req.getInvoiceRecordId());
        throw new UnsupportedOperationException(UNSUPPORTED_MSG);
    }

    @Override
    public InvoiceStatusResponse queryStatus(String providerInvoiceId) {
        log.warn("[TaxDirect.NOOP] queryStatus called but no provider configured. id={}", providerInvoiceId);
        throw new UnsupportedOperationException(UNSUPPORTED_MSG);
    }

    @Override
    public byte[] downloadInvoicePdf(String providerInvoiceId) {
        log.warn("[TaxDirect.NOOP] downloadInvoicePdf called but no provider configured. id={}", providerInvoiceId);
        throw new UnsupportedOperationException(UNSUPPORTED_MSG);
    }

    @Override
    public void cancelInvoice(String providerInvoiceId, String reason) {
        log.warn("[TaxDirect.NOOP] cancelInvoice called but no provider configured. id={} reason={}",
                providerInvoiceId, reason);
        throw new UnsupportedOperationException(UNSUPPORTED_MSG);
    }
}
