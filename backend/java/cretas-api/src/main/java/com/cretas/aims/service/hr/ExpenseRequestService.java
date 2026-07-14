package com.cretas.aims.service.hr;

import com.cretas.aims.entity.hr.ExpenseRequest;
import com.cretas.aims.entity.hr.enums.ExpenseCategory;
import com.cretas.aims.entity.hr.enums.HrRequestStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Map;
import java.util.Optional;

public interface ExpenseRequestService {

    ExpenseRequest create(String factoryId, Long userId, ExpenseCategory category,
                         BigDecimal amount, LocalDate expenseDate, String reason);

    ExpenseRequest update(String factoryId, Long userId, String id,
                         ExpenseCategory category, BigDecimal amount,
                         LocalDate expenseDate, String reason);

    ExpenseRequest submit(String factoryId, Long userId, String id);

    /**
     * 审批通过 (Day 3-5 scope).
     *
     * <p>Day 12 follow-up: PaymentRecord PENDING 创建 + paymentRecordId 反向链接.
     * 当前 paymentRecordId 留 null, 不阻塞主流程.
     */
    ExpenseRequest approve(String factoryId, Long approverId, String id);

    ExpenseRequest reject(String factoryId, Long approverId, String id, String reason);

    ExpenseRequest cancel(String factoryId, Long userId, String id);

    /** 标记已付款 — 通常 PaymentRecord 验证后由 PaymentRecordService 回调. */
    ExpenseRequest markPaid(String factoryId, String id, String paymentRecordId);

    Optional<ExpenseRequest> getById(String factoryId, String id);

    Page<ExpenseRequest> listMine(String factoryId, Long userId,
                                 HrRequestStatus status, Pageable pageable);

    Page<ExpenseRequest> listPending(String factoryId, Pageable pageable);

    Page<ExpenseRequest> listAll(String factoryId, Pageable pageable);

    /** HR: 全部, 按类目/状态过滤 (2026-07-14 web-admin filter audit, 均可空). */
    Page<ExpenseRequest> listAll(String factoryId, ExpenseCategory category, HrRequestStatus status, Pageable pageable);

    /** 工厂月度: category → 总金额 (APPROVED + PAID). */
    Map<ExpenseCategory, BigDecimal> summarizeMonth(String factoryId, String yearMonth);
}
