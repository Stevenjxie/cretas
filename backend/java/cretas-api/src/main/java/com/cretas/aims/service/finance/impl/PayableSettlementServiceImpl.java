package com.cretas.aims.service.finance.impl;

import com.cretas.aims.dto.finance.PayableSettlementRequest;
import com.cretas.aims.dto.finance.PayableSettlementResult;
import com.cretas.aims.dto.finance.UnallocatedApPaymentDTO;
import com.cretas.aims.entity.Supplier;
import com.cretas.aims.entity.enums.ArApApprovalStatus;
import com.cretas.aims.entity.enums.ArApTransactionType;
import com.cretas.aims.entity.enums.CounterpartyType;
import com.cretas.aims.entity.enums.PayablePaymentStatus;
import com.cretas.aims.entity.enums.VoucherType;
import com.cretas.aims.entity.finance.ArApPaymentAllocation;
import com.cretas.aims.entity.finance.ArApTransaction;
import com.cretas.aims.event.CashMovementRecordedEvent;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.repository.SupplierRepository;
import com.cretas.aims.repository.finance.ArApPaymentAllocationRepository;
import com.cretas.aims.repository.finance.ArApTransactionRepository;
import com.cretas.aims.service.finance.PayableSettlementService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class PayableSettlementServiceImpl implements PayableSettlementService {

    static final String SETTLEMENT_SOURCE_TYPE = "PAYABLE_SETTLEMENT";
    static final String LEGACY_UNALLOCATED_CODE = "AP_LEGACY_UNALLOCATED_PAYMENT";

    private final ArApTransactionRepository transactionRepository;
    private final ArApPaymentAllocationRepository allocationRepository;
    private final SupplierRepository supplierRepository;
    private final ApplicationEventPublisher eventPublisher;

    @Override
    @Transactional
    public PayableSettlementResult settle(
            String factoryId,
            String payableTransactionId,
            PayableSettlementRequest request,
            Long operatedBy) {
        requireText(factoryId, "factoryId");
        requireText(payableTransactionId, "payableTransactionId");
        if (request == null) {
            throw badRequest("付款请求不能为空", "request");
        }
        requireText(request.getSupplierId(), "supplierId");
        requireText(request.getIdempotencyKey(), "idempotencyKey");
        requireText(request.getPaymentReference(), "paymentReference");
        if (operatedBy == null) {
            throw new BusinessException(401, "无法识别付款操作人")
                    .withCode("AP_PAYMENT_OPERATOR_REQUIRED");
        }

        BigDecimal amount = normalizeAmount(request.getAmount());
        String currency = normalizeCurrency(request.getCurrencyCode());
        if (request.getPaymentMethod() == null) {
            throw badRequest("付款方式不能为空", "paymentMethod");
        }

        ArApTransaction payable = transactionRepository
                .findPayableForSettlement(factoryId, payableTransactionId)
                .orElseThrow(() -> new BusinessException(404, "应付记录不存在或不属于当前工厂")
                        .withCode("AP_PAYABLE_NOT_FOUND")
                        .withHint("请刷新应付列表后重试")
                        .withHintTarget("payableTransactionId"));

        validatePayableIdentity(payable, request.getSupplierId(), currency);

        Optional<ArApTransaction> replay = transactionRepository
                .findFirstByFactoryIdAndSourceTypeAndSourceIdAndTransactionTypeAndDeletedAtIsNull(
                        factoryId,
                        SETTLEMENT_SOURCE_TYPE,
                        request.getIdempotencyKey().trim(),
                        ArApTransactionType.AP_PAYMENT);
        if (replay.isPresent()) {
            return replayResult(factoryId, payable, replay.get(), request, amount, currency);
        }

        rejectUnreconciledLegacyPayable(payable);
        rejectLegacyUnallocatedPayment(payable);

        BigDecimal outstanding = requireMoney(payable.getOutstandingAmount(), "应付未付金额缺失");
        BigDecimal settled = requireMoney(payable.getSettledAmount(), "应付已付金额缺失");
        if (outstanding.compareTo(BigDecimal.ZERO) <= 0 || payable.getPaymentStatus() == PayablePaymentStatus.PAID) {
            throw new BusinessException(409, "该应付已结清，不能重复付款")
                    .withCode("AP_PAYABLE_ALREADY_PAID")
                    .withHint("请查看已有付款及核销记录");
        }
        if (amount.compareTo(outstanding) > 0) {
            throw new BusinessException(409,
                    "付款金额超过未付金额：未付 " + outstanding + " " + currency)
                    .withCode("AP_PAYMENT_EXCEEDS_OUTSTANDING")
                    .withHint("请将付款金额调整为不超过当前未付金额")
                    .withHintTarget("amount");
        }
        if (transactionRepository.existsByFactoryIdAndPaymentReference(
                factoryId, request.getPaymentReference().trim())) {
            throw new BusinessException(409, "付款凭证号已存在")
                    .withCode("AP_PAYMENT_REFERENCE_DUPLICATE")
                    .withHint("请核对是否已完成付款，或填写正确的银行流水号")
                    .withHintTarget("paymentReference");
        }

        Supplier supplier = supplierRepository
                .findByIdAndFactoryId(request.getSupplierId(), factoryId)
                .orElseThrow(() -> new BusinessException(409, "应付供应商不存在或不属于当前工厂")
                        .withCode("AP_SUPPLIER_IDENTITY_MISMATCH"));
        BigDecimal supplierBalance = supplier.getCurrentBalance();
        if (supplierBalance == null || supplierBalance.compareTo(amount) < 0) {
            throw new BusinessException(409, "供应商应付总账与开放应付不一致，禁止继续付款")
                    .withCode("AP_SUPPLIER_BALANCE_MISMATCH")
                    .withHint("请由财务核对历史付款和未核销记录");
        }

        BigDecimal newSettled = settled.add(amount).setScale(2, RoundingMode.UNNECESSARY);
        BigDecimal newOutstanding = outstanding.subtract(amount).setScale(2, RoundingMode.UNNECESSARY);
        payable.setSettledAmount(newSettled);
        payable.setOutstandingAmount(newOutstanding);
        payable.setPaymentStatus(newOutstanding.signum() == 0
                ? PayablePaymentStatus.PAID
                : PayablePaymentStatus.PARTIALLY_PAID);

        BigDecimal newSupplierBalance = supplierBalance.subtract(amount).setScale(2, RoundingMode.UNNECESSARY);
        supplier.setCurrentBalance(newSupplierBalance);

        ArApTransaction payment = new ArApTransaction();
        payment.setId(UUID.randomUUID().toString());
        payment.setFactoryId(factoryId);
        payment.setTransactionNumber("AP-PAY-" + LocalDate.now().toString().replace("-", "")
                + "-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase(Locale.ROOT));
        payment.setTransactionType(ArApTransactionType.AP_PAYMENT);
        payment.setCounterpartyType(CounterpartyType.SUPPLIER);
        payment.setCounterpartyId(supplier.getId());
        payment.setCounterpartyName(supplier.getName());
        payment.setPurchaseOrderId(payable.getPurchaseOrderId());
        payment.setSourceType(SETTLEMENT_SOURCE_TYPE);
        payment.setSourceId(request.getIdempotencyKey().trim());
        payment.setAmount(amount.negate());
        payment.setBalanceAfter(newSupplierBalance);
        payment.setCurrencyCode(currency);
        payment.setPaymentMethod(request.getPaymentMethod());
        payment.setPaymentReference(request.getPaymentReference().trim());
        payment.setTransactionDate(LocalDate.now());
        payment.setOperatedBy(operatedBy);
        payment.setRemark(trimToNull(request.getRemark()));
        payment.setApprovalStatus(ArApApprovalStatus.APPROVED);

        ArApPaymentAllocation allocation = new ArApPaymentAllocation();
        allocation.setId(UUID.randomUUID().toString());
        allocation.setFactoryId(factoryId);
        allocation.setPaymentTransactionId(payment.getId());
        allocation.setPayableTransactionId(payable.getId());
        allocation.setAllocatedAmount(amount);
        allocation.setCurrencyCode(currency);
        allocation.setOperatedBy(operatedBy);

        transactionRepository.save(payable);
        transactionRepository.save(payment);
        transactionRepository.flush();
        allocationRepository.save(allocation);
        supplierRepository.save(supplier);

        eventPublisher.publishEvent(new CashMovementRecordedEvent(
                this,
                factoryId,
                payment.getId(),
                VoucherType.CASH_PAYMENT,
                supplier.getId(),
                supplier.getName(),
                amount,
                request.getPaymentMethod(),
                payment.getTransactionDate(),
                operatedBy));

        log.info("AP settlement posted: factoryId={}, payableId={}, paymentId={}, amount={}, outstanding={}",
                factoryId, payable.getId(), payment.getId(), amount, newOutstanding);
        return result(payable, payment, allocation, amount, false);
    }

    @Override
    @Transactional(readOnly = true)
    public List<UnallocatedApPaymentDTO> listUnallocatedPayments(String factoryId) {
        requireText(factoryId, "factoryId");
        return transactionRepository.findUnallocatedApPayments(factoryId).stream()
                .map(payment -> UnallocatedApPaymentDTO.builder()
                        .paymentTransactionId(payment.getId())
                        .paymentTransactionNumber(payment.getTransactionNumber())
                        .supplierId(payment.getCounterpartyId())
                        .supplierName(payment.getCounterpartyName())
                        .purchaseOrderId(payment.getPurchaseOrderId())
                        .amount(payment.getAmount() != null ? payment.getAmount().abs() : null)
                        .currencyCode(payment.getCurrencyCode())
                        .paymentReference(payment.getPaymentReference())
                        .transactionDate(payment.getTransactionDate())
                        .anomalyCode(LEGACY_UNALLOCATED_CODE)
                        .build())
                .toList();
    }

    private PayableSettlementResult replayResult(
            String factoryId,
            ArApTransaction payable,
            ArApTransaction payment,
            PayableSettlementRequest request,
            BigDecimal amount,
            String currency) {
        ArApPaymentAllocation allocation = allocationRepository
                .findByFactoryIdAndPaymentTransactionIdAndPayableTransactionId(
                        factoryId, payment.getId(), payable.getId())
                .orElseThrow(() -> new BusinessException(409,
                        "幂等键已被历史未核销付款占用，禁止自动匹配")
                        .withCode("AP_IDEMPOTENCY_KEY_CONFLICT")
                        .withHint("请由财务核对历史付款记录"));
        boolean sameRequest = payment.getCounterpartyId().equals(request.getSupplierId())
                && allocation.getAllocatedAmount().compareTo(amount) == 0
                && currency.equalsIgnoreCase(allocation.getCurrencyCode())
                && payment.getPaymentMethod() == request.getPaymentMethod()
                && Objects.equals(payment.getPaymentReference(), request.getPaymentReference().trim());
        if (!sameRequest) {
            throw new BusinessException(409, "幂等键已用于不同的付款请求")
                    .withCode("AP_IDEMPOTENCY_KEY_CONFLICT")
                    .withHint("请勿修改重放请求；新付款请使用新的幂等键");
        }
        return result(payable, payment, allocation, amount, true);
    }

    private void rejectLegacyUnallocatedPayment(ArApTransaction payable) {
        if (payable.getPurchaseOrderId() == null || payable.getPurchaseOrderId().isBlank()) {
            return;
        }
        List<ArApTransaction> payments = transactionRepository
                .findByFactoryIdAndPurchaseOrderIdAndCounterpartyIdAndTransactionTypeAndDeletedAtIsNull(
                        payable.getFactoryId(), payable.getPurchaseOrderId(), payable.getCounterpartyId(),
                        ArApTransactionType.AP_PAYMENT);
        boolean hasUnallocated = payments.stream().anyMatch(payment ->
                !allocationRepository.existsByFactoryIdAndPaymentTransactionId(
                        payable.getFactoryId(), payment.getId()));
        if (hasUnallocated) {
            throw new BusinessException(409, "该采购单存在历史未核销付款，禁止再次付款")
                    .withCode(LEGACY_UNALLOCATED_CODE)
                    .withHint("请先由财务确认历史付款应核销到哪一笔应付；系统不会自动匹配");
        }
    }

    private void rejectUnreconciledLegacyPayable(ArApTransaction payable) {
        if (payable.getPaymentStatus() != PayablePaymentStatus.NEEDS_RECONCILIATION) {
            return;
        }
        throw new BusinessException(409,
                "该历史应付尚未核对旧付款与核销关系，禁止直接付款")
                .withCode("AP_LEGACY_PAYABLE_NEEDS_RECONCILIATION")
                .withHint("请由财务在异常待匹配中核对历史付款；系统不会自动匹配或重开应付");
    }

    private void validatePayableIdentity(ArApTransaction payable, String supplierId, String currency) {
        if (payable.getTransactionType() != ArApTransactionType.AP_INVOICE
                || payable.getCounterpartyType() != CounterpartyType.SUPPLIER
                || !supplierId.equals(payable.getCounterpartyId())) {
            throw new BusinessException(409, "付款供应商与应付记录不一致")
                    .withCode("AP_SUPPLIER_IDENTITY_MISMATCH");
        }
        if (payable.getCurrencyCode() == null || !currency.equalsIgnoreCase(payable.getCurrencyCode())) {
            throw new BusinessException(409, "付款币种与应付币种不一致")
                    .withCode("AP_CURRENCY_MISMATCH")
                    .withHint("请使用应付记录的原币种付款");
        }
    }

    private PayableSettlementResult result(
            ArApTransaction payable,
            ArApTransaction payment,
            ArApPaymentAllocation allocation,
            BigDecimal amount,
            boolean replayed) {
        return PayableSettlementResult.builder()
                .payableTransactionId(payable.getId())
                .paymentTransactionId(payment.getId())
                .paymentTransactionNumber(payment.getTransactionNumber())
                .allocationId(allocation.getId())
                .supplierId(payable.getCounterpartyId())
                .allocatedAmount(amount)
                .settledAmount(payable.getSettledAmount())
                .outstandingAmount(payable.getOutstandingAmount())
                .currencyCode(payable.getCurrencyCode())
                .paymentStatus(payable.getPaymentStatus())
                .replayed(replayed)
                .build();
    }

    private static BigDecimal normalizeAmount(BigDecimal amount) {
        if (amount == null || amount.signum() <= 0) {
            throw badRequest("付款金额必须大于0", "amount");
        }
        if (amount.stripTrailingZeros().scale() > 2) {
            throw badRequest("付款金额最多保留2位小数", "amount");
        }
        return amount.setScale(2, RoundingMode.UNNECESSARY);
    }

    private static BigDecimal requireMoney(BigDecimal value, String message) {
        if (value == null || value.signum() < 0) {
            throw new BusinessException(409, message)
                    .withCode("AP_PAYABLE_OPEN_ITEM_INCOMPLETE")
                    .withHint("请由财务检查应付开放项初始化状态");
        }
        return value.setScale(2, RoundingMode.UNNECESSARY);
    }

    private static String normalizeCurrency(String currencyCode) {
        requireText(currencyCode, "currencyCode");
        String normalized = currencyCode.trim().toUpperCase(Locale.ROOT);
        if (!normalized.matches("[A-Z]{3}")) {
            throw badRequest("币种必须是3位ISO代码", "currencyCode");
        }
        return normalized;
    }

    private static void requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw badRequest(field + "不能为空", field);
        }
    }

    private static String trimToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static BusinessException badRequest(String message, String field) {
        return new BusinessException(400, message)
                .withCode("AP_PAYMENT_REQUEST_INVALID")
                .withHintTarget(field);
    }
}
