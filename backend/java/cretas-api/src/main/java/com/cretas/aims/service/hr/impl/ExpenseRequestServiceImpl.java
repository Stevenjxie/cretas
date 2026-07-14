package com.cretas.aims.service.hr.impl;

import com.cretas.aims.entity.config.ApprovalChainConfig.DecisionType;
import com.cretas.aims.entity.hr.ExpenseRequest;
import com.cretas.aims.entity.hr.enums.ExpenseCategory;
import com.cretas.aims.entity.hr.enums.HrRequestStatus;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.exception.ResourceNotFoundException;
import com.cretas.aims.repository.hr.ExpenseRequestRepository;
import com.cretas.aims.service.ApprovalChainService;
import com.cretas.aims.service.finance.PaymentRecordService;
import com.cretas.aims.service.hr.ExpenseRequestService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

@Slf4j
@Service
@RequiredArgsConstructor
public class ExpenseRequestServiceImpl implements ExpenseRequestService {

    private final ExpenseRequestRepository repository;
    private final PaymentRecordService paymentRecordService;

    @Autowired
    @Lazy
    private ApprovalChainService approvalChainService;

    @Override
    @Transactional
    public ExpenseRequest create(String factoryId, Long userId, ExpenseCategory category,
                                 BigDecimal amount, LocalDate expenseDate, String reason) {
        validate(category, amount, expenseDate);
        // R4 防呆: 5min 窗口同 user/category/amount/date 重复 → 409
        var since = LocalDateTime.now().minusMinutes(5);
        var dupes = repository.findRecentDuplicates(factoryId, userId, category, amount, expenseDate, since);
        if (!dupes.isEmpty()) {
            var existing = dupes.get(0);
            throw new BusinessException(409, String.format(
                    "5 分钟内您已创建相同报销申请 (%s, ¥%s, %s), 请勿重复提交",
                    existing.getId(), existing.getAmount(), existing.getCategory()));
        }
        return repository.save(ExpenseRequest.builder()
                .factoryId(factoryId)
                .userId(userId)
                .category(category)
                .amount(amount)
                .expenseDate(expenseDate)
                .reason(reason)
                .status(HrRequestStatus.DRAFT)
                .build());
    }

    @Override
    @Transactional
    public ExpenseRequest update(String factoryId, Long userId, String id,
                                 ExpenseCategory category, BigDecimal amount,
                                 LocalDate expenseDate, String reason) {
        ExpenseRequest req = loadOwn(factoryId, userId, id);
        if (req.getStatus() != HrRequestStatus.DRAFT) {
            throw new BusinessException("仅 DRAFT 可编辑, 当前=" + req.getStatus());
        }
        validate(category, amount, expenseDate);
        req.setCategory(category);
        req.setAmount(amount);
        req.setExpenseDate(expenseDate);
        req.setReason(reason);
        return repository.save(req);
    }

    @Override
    @Transactional
    public ExpenseRequest submit(String factoryId, Long userId, String id) {
        ExpenseRequest req = loadOwn(factoryId, userId, id);
        if (req.getStatus() != HrRequestStatus.DRAFT) {
            throw new BusinessException("仅 DRAFT 可提交, 当前=" + req.getStatus());
        }
        Map<String, Object> ctx = new HashMap<>();
        ctx.put("category", req.getCategory().name());
        ctx.put("amount", req.getAmount());
        boolean needsApproval = approvalChainService.requiresApproval(
                factoryId, DecisionType.EXPENSE_APPROVAL, ctx);

        req.setStatus(HrRequestStatus.SUBMITTED);
        req.setSubmittedAt(LocalDateTime.now());
        ExpenseRequest saved = repository.save(req);
        if (!needsApproval) {
            log.info("ExpenseRequest {} auto-approved", id);
            return doApprove(saved, userId);
        }
        return saved;
    }

    @Override
    @Transactional
    public ExpenseRequest approve(String factoryId, Long approverId, String id) {
        ExpenseRequest req = load(factoryId, id);
        if (req.getStatus() != HrRequestStatus.SUBMITTED) {
            throw new BusinessException("仅 SUBMITTED 可审批, 当前=" + req.getStatus());
        }
        return doApprove(req, approverId);
    }

    private ExpenseRequest doApprove(ExpenseRequest req, Long approverId) {
        req.setStatus(HrRequestStatus.APPROVED);
        req.setApprovedAt(LocalDateTime.now());
        req.setApproverIds("[" + approverId + "]");
        ExpenseRequest saved = repository.save(req);

        // Day 12 PaymentRecord 联动: 创建 PENDING AP record, 反向链接 paymentRecordId.
        // Finance verify 后 ExpenseRequest 手工调 /mark-paid (或通过事件回写 — 见 markPaid).
        try {
            var payment = paymentRecordService.recordExpenseReimbursement(
                    req.getFactoryId(), req.getId(), req.getAmount(),
                    approverId,
                    String.format("[%s] %s", req.getCategory(),
                            req.getReason() == null ? "" : req.getReason()));
            saved.setPaymentRecordId(payment.getId());
            saved = repository.save(saved);
            log.info("ExpenseRequest {} APPROVED + PaymentRecord {} PENDING (AP) amount={}",
                    req.getId(), payment.getId(), req.getAmount());
        } catch (Exception e) {
            // Soft-fail: ExpenseRequest 已 APPROVED, 财务可手工创建 PaymentRecord + /mark-paid
            log.warn("ExpenseRequest {} APPROVED but PaymentRecord creation failed: {}",
                    req.getId(), e.getMessage());
        }
        return saved;
    }

    @Override
    @Transactional
    public ExpenseRequest reject(String factoryId, Long approverId, String id, String reason) {
        ExpenseRequest req = load(factoryId, id);
        if (req.getStatus() != HrRequestStatus.SUBMITTED) {
            throw new BusinessException("仅 SUBMITTED 可拒绝, 当前=" + req.getStatus());
        }
        req.setStatus(HrRequestStatus.REJECTED);
        req.setRejectedAt(LocalDateTime.now());
        req.setRejectReason(reason);
        req.setApproverIds("[" + approverId + "]");
        return repository.save(req);
    }

    @Override
    @Transactional
    public ExpenseRequest cancel(String factoryId, Long userId, String id) {
        ExpenseRequest req = loadOwn(factoryId, userId, id);
        if (req.getStatus() != HrRequestStatus.DRAFT && req.getStatus() != HrRequestStatus.SUBMITTED) {
            throw new BusinessException("仅 DRAFT/SUBMITTED 可撤回, 当前=" + req.getStatus());
        }
        req.setStatus(HrRequestStatus.CANCELLED);
        req.setCancelledAt(LocalDateTime.now());
        return repository.save(req);
    }

    @Override
    @Transactional
    public ExpenseRequest markPaid(String factoryId, String id, String paymentRecordId) {
        ExpenseRequest req = load(factoryId, id);
        if (req.getStatus() != HrRequestStatus.APPROVED) {
            throw new BusinessException("仅 APPROVED 可标记付款, 当前=" + req.getStatus());
        }
        req.setStatus(HrRequestStatus.PAID);
        req.setPaidAt(LocalDateTime.now());
        req.setPaymentRecordId(paymentRecordId);
        log.info("ExpenseRequest {} PAID payment={}", id, paymentRecordId);
        return repository.save(req);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<ExpenseRequest> getById(String factoryId, String id) {
        return repository.findByIdAndFactoryId(id, factoryId);
    }

    @Override
    @Transactional(readOnly = true)
    public Page<ExpenseRequest> listMine(String factoryId, Long userId,
                                         HrRequestStatus status, Pageable pageable) {
        return status == null
                ? repository.findByFactoryIdAndUserId(factoryId, userId, pageable)
                : repository.findByFactoryIdAndUserIdAndStatus(factoryId, userId, status, pageable);
    }

    @Override
    @Transactional(readOnly = true)
    public Page<ExpenseRequest> listPending(String factoryId, Pageable pageable) {
        return repository.findByFactoryIdAndStatus(factoryId, HrRequestStatus.SUBMITTED, pageable);
    }

    @Override
    @Transactional(readOnly = true)
    public Page<ExpenseRequest> listAll(String factoryId, Pageable pageable) {
        return repository.findByFactoryId(factoryId, pageable);
    }

    @Override
    @Transactional(readOnly = true)
    public Page<ExpenseRequest> listAll(String factoryId, ExpenseCategory category, HrRequestStatus status, Pageable pageable) {
        return repository.findByFiltersAll(factoryId, category, status, pageable);
    }

    @Override
    @Transactional(readOnly = true)
    public Map<ExpenseCategory, BigDecimal> summarizeMonth(String factoryId, String yearMonth) {
        YearMonth ym = YearMonth.parse(yearMonth);
        LocalDate start = ym.atDay(1);
        LocalDate end = ym.plusMonths(1).atDay(1);
        Map<ExpenseCategory, BigDecimal> result = new HashMap<>();
        for (Object[] row : repository.sumApprovedAmountByCategoryInRange(factoryId, start, end)) {
            result.put((ExpenseCategory) row[0], (BigDecimal) row[1]);
        }
        return result;
    }

    private ExpenseRequest load(String factoryId, String id) {
        return repository.findByIdAndFactoryId(id, factoryId)
                .orElseThrow(() -> new ResourceNotFoundException("ExpenseRequest not found: " + id));
    }

    private ExpenseRequest loadOwn(String factoryId, Long userId, String id) {
        ExpenseRequest req = load(factoryId, id);
        if (!userId.equals(req.getUserId())) {
            throw new BusinessException("无权操作他人申请");
        }
        return req;
    }

    private void validate(ExpenseCategory category, BigDecimal amount, LocalDate expenseDate) {
        if (category == null || amount == null || expenseDate == null) {
            throw new BusinessException("category / amount / expenseDate 必填");
        }
        if (amount.signum() <= 0) {
            throw new BusinessException("amount 必须 > 0");
        }
    }
}
