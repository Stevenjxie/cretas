package com.cretas.aims.service.hr.impl;

import com.cretas.aims.entity.config.ApprovalChainConfig.DecisionType;
import com.cretas.aims.entity.hr.LeaveRequest;
import com.cretas.aims.entity.hr.enums.HrRequestStatus;
import com.cretas.aims.entity.hr.enums.LeaveType;
import com.cretas.aims.event.LeaveApprovedEvent;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.exception.ResourceNotFoundException;
import com.cretas.aims.repository.hr.LeaveRequestRepository;
import com.cretas.aims.service.ApprovalChainService;
import com.cretas.aims.service.hr.LeaveBalanceService;
import com.cretas.aims.service.hr.LeaveRequestService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Lazy;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

@Slf4j
@Service
@RequiredArgsConstructor
public class LeaveRequestServiceImpl implements LeaveRequestService {

    /** Leave types that contribute to LeaveBalance.usedHours when approved. */
    private static final Set<LeaveType> BALANCE_TRACKED_TYPES =
            EnumSet.of(LeaveType.ANNUAL, LeaveType.SICK, LeaveType.COMPTIME, LeaveType.OTHER);

    private final LeaveRequestRepository repository;
    private final LeaveBalanceService balanceService;
    private final ApplicationEventPublisher eventPublisher;

    /** Lazy to break potential cycles via ApprovalChain ⇄ Tool ⇄ AIIntent (rule). */
    @Autowired
    @Lazy
    private ApprovalChainService approvalChainService;

    @Override
    @Transactional
    public LeaveRequest create(String factoryId, Long userId, LeaveType leaveType,
                               LocalDate startDate, LocalDate endDate,
                               BigDecimal durationHours, String reason) {
        validateDates(startDate, endDate, durationHours);
        // R4 防呆: 同期间已有 DRAFT/SUBMITTED/APPROVED 拒绝
        var overlapping = repository.findOverlappingActive(factoryId, userId, startDate, endDate);
        if (!overlapping.isEmpty()) {
            var existing = overlapping.get(0);
            throw new BusinessException(409, String.format(
                    "您在 %s ~ %s 期间已有请假申请 (%s, 状态=%s), 请先撤回或修改原申请",
                    existing.getStartDate(), existing.getEndDate(),
                    existing.getId(), existing.getStatus()));
        }
        LeaveRequest req = LeaveRequest.builder()
                .factoryId(factoryId)
                .userId(userId)
                .leaveType(leaveType)
                .startDate(startDate)
                .endDate(endDate)
                .durationHours(durationHours)
                .reason(reason)
                .status(HrRequestStatus.DRAFT)
                .build();
        return repository.save(req);
    }

    @Override
    @Transactional
    public LeaveRequest update(String factoryId, Long userId, String id,
                               LeaveType leaveType, LocalDate startDate, LocalDate endDate,
                               BigDecimal durationHours, String reason) {
        LeaveRequest req = loadOwn(factoryId, userId, id);
        if (req.getStatus() != HrRequestStatus.DRAFT) {
            throw new BusinessException("仅 DRAFT 状态可编辑, 当前状态=" + req.getStatus());
        }
        validateDates(startDate, endDate, durationHours);
        req.setLeaveType(leaveType);
        req.setStartDate(startDate);
        req.setEndDate(endDate);
        req.setDurationHours(durationHours);
        req.setReason(reason);
        return repository.save(req);
    }

    @Override
    @Transactional
    public LeaveRequest submit(String factoryId, Long userId, String id) {
        LeaveRequest req = loadOwn(factoryId, userId, id);
        if (req.getStatus() != HrRequestStatus.DRAFT) {
            throw new BusinessException("仅 DRAFT 可提交, 当前=" + req.getStatus());
        }
        Map<String, Object> ctx = new HashMap<>();
        ctx.put("leaveType", req.getLeaveType().name());
        ctx.put("durationHours", req.getDurationHours());
        boolean needsApproval = approvalChainService.requiresApproval(
                factoryId, DecisionType.LEAVE_APPROVAL, ctx);

        req.setStatus(HrRequestStatus.SUBMITTED);
        req.setSubmittedAt(LocalDateTime.now());
        LeaveRequest saved = repository.save(req);

        if (!needsApproval) {
            // 无需审批 -> 自动 APPROVED + 余额扣减
            log.info("LeaveRequest {} auto-approved (no approval config)", id);
            return doApprove(saved, userId);
        }
        return saved;
    }

    @Override
    @Transactional
    public LeaveRequest approve(String factoryId, Long approverId, String id) {
        LeaveRequest req = load(factoryId, id);
        if (req.getStatus() != HrRequestStatus.SUBMITTED) {
            throw new BusinessException("仅 SUBMITTED 可审批, 当前=" + req.getStatus());
        }
        return doApprove(req, approverId);
    }

    private LeaveRequest doApprove(LeaveRequest req, Long approverId) {
        req.setStatus(HrRequestStatus.APPROVED);
        req.setApprovedAt(LocalDateTime.now());
        req.setApproverIds("[" + approverId + "]");
        LeaveRequest saved = repository.save(req);

        // 余额扣减 (仅跟踪型类型)
        if (BALANCE_TRACKED_TYPES.contains(req.getLeaveType())) {
            String yearMonth = YearMonth.from(req.getStartDate()).toString();
            balanceService.addUsed(req.getFactoryId(), req.getUserId(),
                    req.getLeaveType(), yearMonth, req.getDurationHours());
        }
        // 发布事件 — 给 CompTimeEventListener (ledger 出账, 只处理 COMPTIME) 用
        eventPublisher.publishEvent(new LeaveApprovedEvent(
                this, req.getFactoryId(), req.getId(), req.getUserId(),
                req.getLeaveType(), req.getDurationHours(), req.getStartDate(), approverId));
        log.info("LeaveRequest {} APPROVED by {}", req.getId(), approverId);
        return saved;
    }

    @Override
    @Transactional
    public LeaveRequest reject(String factoryId, Long approverId, String id, String reason) {
        LeaveRequest req = load(factoryId, id);
        if (req.getStatus() != HrRequestStatus.SUBMITTED) {
            throw new BusinessException("仅 SUBMITTED 可拒绝, 当前=" + req.getStatus());
        }
        req.setStatus(HrRequestStatus.REJECTED);
        req.setRejectedAt(LocalDateTime.now());
        req.setRejectReason(reason);
        req.setApproverIds("[" + approverId + "]");
        log.info("LeaveRequest {} REJECTED by {}", id, approverId);
        return repository.save(req);
    }

    @Override
    @Transactional
    public LeaveRequest cancel(String factoryId, Long userId, String id) {
        LeaveRequest req = loadOwn(factoryId, userId, id);
        if (req.getStatus() != HrRequestStatus.DRAFT && req.getStatus() != HrRequestStatus.SUBMITTED) {
            throw new BusinessException("仅 DRAFT/SUBMITTED 可撤回, 当前=" + req.getStatus());
        }
        req.setStatus(HrRequestStatus.CANCELLED);
        req.setCancelledAt(LocalDateTime.now());
        return repository.save(req);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<LeaveRequest> getById(String factoryId, String id) {
        return repository.findByIdAndFactoryId(id, factoryId);
    }

    @Override
    @Transactional(readOnly = true)
    public Page<LeaveRequest> listMine(String factoryId, Long userId,
                                       HrRequestStatus status, Pageable pageable) {
        return status == null
                ? repository.findByFactoryIdAndUserId(factoryId, userId, pageable)
                : repository.findByFactoryIdAndUserIdAndStatus(factoryId, userId, status, pageable);
    }

    @Override
    @Transactional(readOnly = true)
    public Page<LeaveRequest> listPending(String factoryId, Pageable pageable) {
        return repository.findByFactoryIdAndStatus(factoryId, HrRequestStatus.SUBMITTED, pageable);
    }

    @Override
    @Transactional(readOnly = true)
    public Page<LeaveRequest> listAll(String factoryId, Pageable pageable) {
        return repository.findByFactoryId(factoryId, pageable);
    }

    @Override
    @Transactional(readOnly = true)
    public Page<LeaveRequest> listAll(String factoryId, LeaveType leaveType, HrRequestStatus status, Pageable pageable) {
        return repository.findByFiltersAll(factoryId, leaveType, status, pageable);
    }

    @Override
    @Transactional(readOnly = true)
    public Map<LeaveType, BigDecimal> summarizeMonth(String factoryId, String yearMonth) {
        YearMonth ym = YearMonth.parse(yearMonth);
        LocalDate start = ym.atDay(1);
        LocalDate end = ym.atEndOfMonth();
        Map<LeaveType, BigDecimal> result = new HashMap<>();
        for (Object[] row : repository.sumApprovedHoursByTypeInRange(factoryId, start, end)) {
            result.put((LeaveType) row[0], (BigDecimal) row[1]);
        }
        return result;
    }

    private LeaveRequest load(String factoryId, String id) {
        return repository.findByIdAndFactoryId(id, factoryId)
                .orElseThrow(() -> new ResourceNotFoundException("LeaveRequest not found: " + id));
    }

    private LeaveRequest loadOwn(String factoryId, Long userId, String id) {
        LeaveRequest req = load(factoryId, id);
        if (!userId.equals(req.getUserId())) {
            throw new BusinessException("无权操作他人申请");
        }
        return req;
    }

    private void validateDates(LocalDate start, LocalDate end, BigDecimal hours) {
        if (start == null || end == null || hours == null) {
            throw new BusinessException("startDate / endDate / durationHours 必填");
        }
        if (end.isBefore(start)) {
            throw new BusinessException("endDate 不能早于 startDate");
        }
        if (hours.signum() <= 0) {
            throw new BusinessException("durationHours 必须 > 0");
        }
    }
}
