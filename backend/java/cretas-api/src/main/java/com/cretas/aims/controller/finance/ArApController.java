package com.cretas.aims.controller.finance;

import com.cretas.aims.dto.common.ApiResponse;
import com.cretas.aims.dto.common.PageResponse;
import com.cretas.aims.dto.finance.RecordTransactionRequest;
import com.cretas.aims.entity.enums.CounterpartyType;
import com.cretas.aims.entity.enums.PaymentMethod;
import com.cretas.aims.entity.finance.ArApTransaction;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.service.MobileService;
import com.cretas.aims.service.config.FactoryConfigService;
import com.cretas.aims.service.finance.ArApService;
import com.cretas.aims.utils.TokenUtils;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.*;

import com.cretas.aims.annotation.RequirePermission;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import com.cretas.aims.annotation.RequireModule;

@Slf4j
@RestController
@RequestMapping("/api/mobile/{factoryId}/finance")
@RequiredArgsConstructor
@Tag(name = "应收应付管理", description = "AR/AP 交易记录、对账单、账龄分析")
@RequirePermission("finance:read_write")
public class ArApController {

    private final ArApService arApService;
    private final MobileService mobileService;
    /** R23 audit C4: conditional module check for /adjustment endpoint (counterpartyType-dependent). */
    private final FactoryConfigService factoryConfigService;
    private final com.cretas.aims.service.inventory.OpeningInventoryService openingInventoryService;

    /**
     * AUD-5 B-A3 sister sweep batch 3 (edge audit 2026-05-20): explicit length caps mirror PG
     * column widths in {@code ar_ap_transactions} table (see {@link ArApTransaction}
     * {@code @Column(length=...)}). Without these, over-length input lets the request reach PG
     * and surfaces as {@code DataIntegrityViolationException} → generic 409 "数据处理异常".
     * Pre-check at controller boundary delivers a specific 400 with a hintTarget instead.
     *
     * <p>{@link RecordTransactionRequest} DTO has no {@code @Size} annotations, so the input
     * arrives unrestricted from Bean Validation. Controller-side pre-check is the safety net.
     *
     * <p>Mirrors PR #48 / PR #76 / PR #78 length-pre-check pattern.
     */
    private static final int PAYMENT_REFERENCE_MAX_LENGTH = 100;
    private static final int REMARK_MAX_LENGTH = 500;

    // ==================== 应收（AR） ====================

    @RequireModule("finance_ar")
    @PostMapping("/receivable")
    @Operation(summary = "记录应收挂账（销售出货）")
    public ApiResponse<ArApTransaction> recordReceivable(
            @PathVariable @NotBlank String factoryId,
            @RequestHeader("Authorization") String authorization,
            @Valid @RequestBody RecordTransactionRequest request) {
        // AUD-5 B-A3 sister sweep batch 3: length pre-check for paymentReference (100) + remark (500).
        validateRequestLengths(request);
        Long userId = extractUserId(authorization);
        ArApTransaction transaction = arApService.recordReceivable(
                factoryId, request.getCounterpartyId(), request.getOrderId(),
                request.getAmount(), request.getDueDate(), userId, request.getRemark());
        return ApiResponse.success("应收记录创建成功", transaction);
    }

    @RequireModule("finance_ar")
    @PostMapping("/receivable/payment")
    @Operation(summary = "记录客户付款（冲减应收）")
    public ApiResponse<ArApTransaction> recordArPayment(
            @PathVariable @NotBlank String factoryId,
            @RequestHeader("Authorization") String authorization,
            @Valid @RequestBody RecordTransactionRequest request) {
        // AUD-5 B-A3 sister sweep batch 3 (Rule 16: entry-point matrix — AR payment path).
        validateRequestLengths(request);
        Long userId = extractUserId(authorization);
        PaymentMethod method = request.getPaymentMethod() != null
                ? PaymentMethod.valueOf(request.getPaymentMethod()) : PaymentMethod.BANK_TRANSFER;
        // Issue #317 fix: thread orderId (SO id) into service so 快速收款 from SO row
        // persists salesOrderId on the AR_PAYMENT row + updates SO.paidAmount.
        ArApTransaction transaction = arApService.recordArPayment(
                factoryId, request.getCounterpartyId(), request.getOrderId(),
                request.getAmount(), method, request.getPaymentReference(),
                userId, request.getRemark());
        return ApiResponse.success("收款记录创建成功", transaction);
    }

    // ==================== 应付（AP） ====================

    // R23 audit C4: was @RequireModule("finance_ar") — but this endpoint records AP
    // (应付). Factory with only finance_ap enabled couldn't write payables; factory
    // with only finance_ar (no AP) could. Both directions wrong.
    @RequireModule("finance_ap")
    @PostMapping("/payable")
    @Operation(summary = "记录应付挂账（采购入库）")
    public ApiResponse<ArApTransaction> recordPayable(
            @PathVariable @NotBlank String factoryId,
            @RequestHeader("Authorization") String authorization,
            @Valid @RequestBody RecordTransactionRequest request) {
        // AUD-5 B-A3 sister sweep batch 3 (Rule 16: entry-point matrix — AP path).
        validateRequestLengths(request);
        Long userId = extractUserId(authorization);
        ArApTransaction transaction = arApService.recordPayable(
                factoryId, request.getCounterpartyId(), request.getOrderId(),
                request.getAmount(), request.getDueDate(), userId, request.getRemark());
        return ApiResponse.success("应付记录创建成功", transaction);
    }

    // R23 audit C4: was @RequireModule("finance_ar") — wrong, this is AP payment.
    @RequireModule("finance_ap")
    @PostMapping("/payable/payment")
    @Operation(summary = "记录向供应商付款（冲减应付）")
    public ApiResponse<ArApTransaction> recordApPayment(
            @PathVariable @NotBlank String factoryId,
            @RequestHeader("Authorization") String authorization,
            @Valid @RequestBody RecordTransactionRequest request) {
        // AUD-5 B-A3 sister sweep batch 3 (Rule 16: entry-point matrix — AP payment path).
        validateRequestLengths(request);
        Long userId = extractUserId(authorization);
        PaymentMethod method = request.getPaymentMethod() != null
                ? PaymentMethod.valueOf(request.getPaymentMethod()) : PaymentMethod.BANK_TRANSFER;
        ArApTransaction transaction = arApService.recordApPayment(
                factoryId, request.getCounterpartyId(), request.getAmount(),
                method, request.getPaymentReference(), userId, request.getRemark());
        return ApiResponse.success("付款记录创建成功", transaction);
    }

    // ==================== 手工调整 ====================

    // R23 audit C4: was @RequireModule("finance_ar") — but this endpoint dispatches by
    // counterpartyType (CUSTOMER → AR, SUPPLIER → AP). Static class-level annotation
    // can't capture the conditional. Module enforcement moved to service layer below
    // (the service throws "模块未启用" when counterpartyType doesn't match an enabled module).
    @PostMapping("/adjustment")
    @Operation(summary = "手工调整余额（PENDING — 需审批）")
    public ApiResponse<ArApTransaction> recordAdjustment(
            @PathVariable @NotBlank String factoryId,
            @RequestHeader("Authorization") String authorization,
            @RequestParam CounterpartyType counterpartyType,
            @Valid @RequestBody RecordTransactionRequest request) {
        // AUD-5 B-A3 sister sweep batch 3 (Rule 16: entry-point matrix — adjustment path).
        validateRequestLengths(request);
        Long userId = extractUserId(authorization);
        // R23 audit C4 conditional module check: AR adjustment needs finance_ar; AP needs finance_ap.
        String requiredModule = counterpartyType == CounterpartyType.CUSTOMER ? "finance_ar" : "finance_ap";
        if (!factoryConfigService.isModuleEnabled(factoryId, requiredModule)) {
            throw new com.cretas.aims.exception.BusinessException("模块 " + requiredModule + " 未启用");
        }
        ArApTransaction transaction = arApService.recordAdjustment(
                factoryId, counterpartyType, request.getCounterpartyId(),
                request.getAmount(), userId, request.getRemark());
        return ApiResponse.success("调整记录已提交, 等待审批", transaction);
    }

    /**
     * R23 audit C2: approve a PENDING adjustment. Applies amount delta to counterparty
     * balance + flips approval_status to APPROVED. Requires elevated permission
     * 'finance:approve_adjustment' (distinct from 'finance:read_write' that submits).
     * Approver must differ from submitter (4-eyes principle, enforced in service).
     */
    @RequirePermission("finance:approve_adjustment")
    @PostMapping("/adjustment/{transactionId}/approve")
    @Operation(summary = "审批通过手工调整（应用余额变动）")
    public ApiResponse<ArApTransaction> approveAdjustment(
            @PathVariable @NotBlank String factoryId,
            @PathVariable @NotBlank String transactionId,
            @RequestHeader("Authorization") String authorization) {
        Long approverId = extractUserId(authorization);
        ArApTransaction transaction = arApService.approveAdjustment(factoryId, transactionId, approverId);
        return ApiResponse.success("调整已审批通过", transaction);
    }

    /**
     * R23 audit C2: reject a PENDING adjustment. No balance change; remark gets reject reason appended.
     */
    @RequirePermission("finance:approve_adjustment")
    @PostMapping("/adjustment/{transactionId}/reject")
    @Operation(summary = "驳回手工调整")
    public ApiResponse<ArApTransaction> rejectAdjustment(
            @PathVariable @NotBlank String factoryId,
            @PathVariable @NotBlank String transactionId,
            @RequestHeader("Authorization") String authorization,
            @RequestParam(required = false) String reason) {
        Long approverId = extractUserId(authorization);
        ArApTransaction transaction = arApService.rejectAdjustment(factoryId, transactionId, approverId, reason);
        return ApiResponse.success("调整已驳回", transaction);
    }

    // ==================== 查询 ====================

    @GetMapping("/transactions")
    @Operation(summary = "交易记录列表")
    @RequirePermission({"finance:read_write", "finance:read"})
    public ApiResponse<PageResponse<ArApTransaction>> listTransactions(
            @PathVariable @NotBlank String factoryId,
            @RequestParam(required = false) CounterpartyType counterpartyType,
            @RequestParam(required = false) String counterpartyId,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        PageResponse<ArApTransaction> result = arApService.getTransactions(
                factoryId, counterpartyType, counterpartyId, page, size);
        return ApiResponse.success("查询成功", result);
    }

    /**
     * R28 P2 (R23 P5 deferred): list PENDING adjustments awaiting approval.
     * AR_ADJUSTMENT + AP_ADJUSTMENT types where approval_status='PENDING'.
     * Read endpoint: accepts finance:read_write OR finance:read OR finance:approve_adjustment.
     * The approve_adjustment role needs read access to the queue to act on it.
     *
     * R29 P1: optional filters (counterpartyType / amount range / date range).
     * All omitted = full PENDING list (matches R28 behavior).
     */
    @GetMapping("/adjustments/pending")
    @Operation(summary = "待审批调整记录列表 (含筛选)")
    @RequirePermission({"finance:read_write", "finance:read", "finance:approve_adjustment"})
    public ApiResponse<PageResponse<ArApTransaction>> listPendingAdjustments(
            @PathVariable @NotBlank String factoryId,
            @RequestParam(required = false) CounterpartyType counterpartyType,
            @RequestParam(required = false) BigDecimal minAmount,
            @RequestParam(required = false) BigDecimal maxAmount,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate fromDate,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate toDate,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        PageResponse<ArApTransaction> result = arApService.getPendingAdjustments(
                factoryId, counterpartyType, minAmount, maxAmount, fromDate, toDate, page, size);
        return ApiResponse.success("查询成功", result);
    }

    /**
     * R29 P2: batch approve. Body shape: {ids: [String...]}. Returns
     * {requested, approvedCount, failedCount, failures: [{id, reason}...]}.
     * Per-id outcome — single failure (e.g., 4-eye violation) doesn't abort rest.
     */
    @RequirePermission("finance:approve_adjustment")
    @PostMapping("/adjustments/batch-approve")
    @Operation(summary = "批量审批调整 (最多 100 条)")
    public ApiResponse<Map<String, Object>> batchApproveAdjustments(
            @PathVariable @NotBlank String factoryId,
            @RequestHeader("Authorization") String authorization,
            @RequestBody Map<String, Object> body) {
        Long approverId = extractUserId(authorization);
        @SuppressWarnings("unchecked")
        java.util.List<String> ids = (java.util.List<String>) body.get("ids");
        Map<String, Object> result = arApService.batchApproveAdjustments(factoryId, ids, approverId);
        return ApiResponse.success("批量审批完成", result);
    }

    /**
     * R29 P2: batch reject. Body: {ids: [...], reason: "..."}.
     */
    @RequirePermission("finance:approve_adjustment")
    @PostMapping("/adjustments/batch-reject")
    @Operation(summary = "批量驳回调整 (最多 100 条)")
    public ApiResponse<Map<String, Object>> batchRejectAdjustments(
            @PathVariable @NotBlank String factoryId,
            @RequestHeader("Authorization") String authorization,
            @RequestBody Map<String, Object> body) {
        Long approverId = extractUserId(authorization);
        @SuppressWarnings("unchecked")
        java.util.List<String> ids = (java.util.List<String>) body.get("ids");
        String reason = body.get("reason") != null ? body.get("reason").toString() : null;
        Map<String, Object> result = arApService.batchRejectAdjustments(factoryId, ids, approverId, reason);
        return ApiResponse.success("批量驳回完成", result);
    }

    @GetMapping("/statement")
    @Operation(summary = "对账单（指定期间的交易明细+期初期末余额）")
    @RequirePermission({"finance:read_write", "finance:read"})
    public ApiResponse<Map<String, Object>> getStatement(
            @PathVariable @NotBlank String factoryId,
            @RequestParam CounterpartyType counterpartyType,
            @RequestParam String counterpartyId,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate) {
        Map<String, Object> statement = arApService.getStatement(
                factoryId, counterpartyType, counterpartyId, startDate, endDate);
        return ApiResponse.success("查询成功", statement);
    }

    @GetMapping("/aging")
    @Operation(summary = "账龄分析（6桶）")
    @RequirePermission({"finance:read_write", "finance:read"})
    public ApiResponse<List<Map<String, Object>>> getAgingAnalysis(
            @PathVariable @NotBlank String factoryId,
            @RequestParam CounterpartyType counterpartyType) {
        List<Map<String, Object>> aging = arApService.getAgingAnalysis(factoryId, counterpartyType);
        return ApiResponse.success("查询成功", aging);
    }

    @GetMapping("/overview")
    @Operation(summary = "财务概览（应收应付汇总）")
    @RequirePermission({"finance:read_write", "finance:read"})
    public ApiResponse<Map<String, Object>> getFinanceOverview(
            @PathVariable @NotBlank String factoryId) {
        Map<String, Object> overview = arApService.getFinanceOverview(factoryId);
        return ApiResponse.success("查询成功", overview);
    }

    @GetMapping("/credit-check")
    @Operation(summary = "信用额度检查")
    @RequirePermission({"finance:read_write", "finance:read", "sales:read"})
    public ApiResponse<Map<String, Object>> checkCreditLimit(
            @PathVariable @NotBlank String factoryId,
            @RequestParam @NotBlank String customerId,
            @RequestParam BigDecimal amount) {
        boolean withinLimit = arApService.checkCreditLimit(factoryId, customerId, amount);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("customerId", customerId);
        result.put("requestedAmount", amount);
        result.put("withinCreditLimit", withinLimit);
        return ApiResponse.success("查询成功", result);
    }

    // ==================== 内部方法 ====================

    private Long extractUserId(String authorization) {
        String token = TokenUtils.extractToken(authorization);
        return mobileService.getUserFromToken(token).getId();
    }

    // ==================== 期初建账修正 (opening AP correction) ====================

    /**
     * 修正"误走采购入库建账"产生的幽灵应付 —— <b>管理员专用</b>。
     *
     * <p>客户把期初存货当采购入库录入 → 每个物料挂了供应商应付 (¥436k 幽灵应付), 但客户并不欠供应商钱。
     * 本操作红冲指定应付 + 补正确的期初凭证 (借 1403 / 贷 4001), <b>库存数量不动</b>。幂等 + 留痕。
     * 要修正哪几笔由 {@code apTransactionIds} 传入 (不硬编码任何租户/记录), organizer 审阅后对具体记录调用。</p>
     *
     * <p>权限: 覆盖类级 {@code finance:read_write}, 收紧到 {@code finance:approve_adjustment} (审批级, 4-眼)。</p>
     */
    @RequirePermission("finance:approve_adjustment")
    @RequireModule("finance_ap")
    @PostMapping("/opening/correct-misrouted-ap")
    @Operation(summary = "修正误走采购入库建账的幽灵应付 (红冲应付 + 补期初凭证, 库存不动) — 管理员专用")
    public ApiResponse<com.cretas.aims.dto.finance.OpeningApCorrectionResult> correctMisroutedOpeningAp(
            @PathVariable @NotBlank String factoryId,
            @RequestHeader("Authorization") String authorization,
            @Valid @RequestBody com.cretas.aims.dto.finance.OpeningApCorrectionRequest request) {
        Long userId = extractUserId(authorization);
        log.info("期初建账修正(幽灵应付红冲): factoryId={}, apCount={}", factoryId,
                request.getApTransactionIds() != null ? request.getApTransactionIds().size() : 0);
        com.cretas.aims.dto.finance.OpeningApCorrectionResult result =
                openingInventoryService.correctMisroutedOpeningAp(factoryId, request, userId);
        return ApiResponse.success("幽灵应付修正完成", result);
    }

    // ==================== Boundary validators (AUD-5 B-A3 sister sweep batch 3) ====================

    /**
     * AUD-5 B-A3 sister sweep batch 3 (edge audit 2026-05-20): length pre-check for
     * {@code paymentReference} (PG VARCHAR 100) and {@code remark} (PG VARCHAR 500) on
     * all AR/AP write paths (receivable / receivable-payment / payable / payable-payment /
     * adjustment). Rule 16 entry-point matrix — all 5 entry points share this validator.
     *
     * <p>{@link RecordTransactionRequest} DTO has no {@code @Size} annotations, so without
     * this pre-check an over-length input reaches PG and surfaces as
     * {@link org.springframework.dao.DataIntegrityViolationException} → generic 409
     * "数据处理异常". Pre-check delivers a specific 400 with the actual vs allowed length.
     *
     * <p>Mirrors PR #48 / PR #78 pattern.
     */
    private void validateRequestLengths(RecordTransactionRequest request) {
        String paymentReference = request.getPaymentReference();
        if (paymentReference != null && paymentReference.length() > PAYMENT_REFERENCE_MAX_LENGTH) {
            throw new BusinessException(400,
                    "付款凭证号最长 " + PAYMENT_REFERENCE_MAX_LENGTH + " 字符 (当前 " + paymentReference.length() + ")")
                    .withHint("请使用更短的银行流水号/支付凭证号")
                    .withSeverity("warning")
                    .withHintTarget("paymentReference");
        }
        String remark = request.getRemark();
        if (remark != null && remark.length() > REMARK_MAX_LENGTH) {
            throw new BusinessException(400,
                    "备注最长 " + REMARK_MAX_LENGTH + " 字符 (当前 " + remark.length() + ")")
                    .withHint("请使用更短的备注 (上限 500 字符)")
                    .withSeverity("warning")
                    .withHintTarget("remark");
        }
    }
}
