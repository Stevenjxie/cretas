package com.cretas.aims.service.hr.impl;

import com.cretas.aims.entity.config.ApprovalChainConfig.DecisionType;
import com.cretas.aims.entity.hr.OvertimeRequest;
import com.cretas.aims.entity.hr.enums.CompensationType;
import com.cretas.aims.entity.hr.enums.HrRequestStatus;
import com.cretas.aims.entity.hr.enums.LeaveType;
import com.cretas.aims.entity.hr.enums.OvertimeType;
import com.cretas.aims.event.OvertimeApprovedEvent;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.exception.ResourceNotFoundException;
import com.cretas.aims.repository.hr.OvertimeRequestRepository;
import com.cretas.aims.service.ApprovalChainService;
import com.cretas.aims.service.hr.LeaveBalanceService;
import com.cretas.aims.service.hr.OvertimeRequestService;
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
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

@Slf4j
@Service
@RequiredArgsConstructor
public class OvertimeRequestServiceImpl implements OvertimeRequestService {

    private final OvertimeRequestRepository repository;
    private final LeaveBalanceService balanceService;
    private final ApplicationEventPublisher eventPublisher;

    @Autowired
    @Lazy
    private ApprovalChainService approvalChainService;

    @Override
    @Transactional
    public OvertimeRequest create(String factoryId, Long userId, OvertimeType type,
                                  LocalDateTime startTime, LocalDateTime endTime,
                                  BigDecimal hours, CompensationType compensationType, String reason) {
        validate(startTime, endTime, hours, type, compensationType);
        // R4 防呆: 时段重叠拒绝
        var overlapping = repository.findOverlappingActive(factoryId, userId, startTime, endTime);
        if (!overlapping.isEmpty()) {
            var existing = overlapping.get(0);
            throw new BusinessException(409, String.format(
                    "您在 %s ~ %s 已有加班申请 (%s, 状态=%s), 请先撤回",
                    existing.getStartTime(), existing.getEndTime(),
                    existing.getId(), existing.getStatus()));
        }
        return repository.save(OvertimeRequest.builder()
                .factoryId(factoryId)
                .userId(userId)
                .overtimeType(type)
                .startTime(startTime)
                .endTime(endTime)
                .hours(hours)
                .compensationType(compensationType)
                .reason(reason)
                .status(HrRequestStatus.DRAFT)
                .build());
    }

    @Override
    @Transactional
    public OvertimeRequest update(String factoryId, Long userId, String id,
                                  OvertimeType type, LocalDateTime startTime, LocalDateTime endTime,
                                  BigDecimal hours, CompensationType compensationType, String reason) {
        OvertimeRequest req = loadOwn(factoryId, userId, id);
        if (req.getStatus() != HrRequestStatus.DRAFT) {
            throw new BusinessException("仅 DRAFT 可编辑, 当前=" + req.getStatus());
        }
        validate(startTime, endTime, hours, type, compensationType);
        req.setOvertimeType(type);
        req.setStartTime(startTime);
        req.setEndTime(endTime);
        req.setHours(hours);
        req.setCompensationType(compensationType);
        req.setReason(reason);
        return repository.save(req);
    }

    @Override
    @Transactional
    public OvertimeRequest submit(String factoryId, Long userId, String id) {
        OvertimeRequest req = loadOwn(factoryId, userId, id);
        if (req.getStatus() != HrRequestStatus.DRAFT) {
            throw new BusinessException("仅 DRAFT 可提交, 当前=" + req.getStatus());
        }
        Map<String, Object> ctx = new HashMap<>();
        ctx.put("overtimeType", req.getOvertimeType().name());
        ctx.put("compensationType", req.getCompensationType().name());
        ctx.put("hours", req.getHours());
        boolean needsApproval = approvalChainService.requiresApproval(
                factoryId, DecisionType.OVERTIME_APPROVAL, ctx);

        req.setStatus(HrRequestStatus.SUBMITTED);
        req.setSubmittedAt(LocalDateTime.now());
        OvertimeRequest saved = repository.save(req);
        if (!needsApproval) {
            log.info("OvertimeRequest {} auto-approved", id);
            return doApprove(saved, userId);
        }
        return saved;
    }

    @Override
    @Transactional
    public OvertimeRequest approve(String factoryId, Long approverId, String id) {
        OvertimeRequest req = load(factoryId, id);
        if (req.getStatus() != HrRequestStatus.SUBMITTED) {
            throw new BusinessException("仅 SUBMITTED 可审批, 当前=" + req.getStatus());
        }
        return doApprove(req, approverId);
    }

    private OvertimeRequest doApprove(OvertimeRequest req, Long approverId) {
        req.setStatus(HrRequestStatus.APPROVED);
        req.setApprovedAt(LocalDateTime.now());
        req.setApproverIds("[" + approverId + "]");
        OvertimeRequest saved = repository.save(req);

        // COMPTIME -> 累计调休余额 (按 startTime 月份)
        if (req.getCompensationType() == CompensationType.COMPTIME) {
            String yearMonth = YearMonth.from(req.getStartTime().toLocalDate()).toString();
            balanceService.addEarned(req.getFactoryId(), req.getUserId(),
                    LeaveType.COMPTIME, yearMonth, req.getHours());
        }
        // 发布事件 — 给 CompTimeEventListener (ledger 入账) 及未来 Payroll listener 用
        eventPublisher.publishEvent(new OvertimeApprovedEvent(
                this, req.getFactoryId(), req.getId(), req.getUserId(),
                req.getCompensationType(), req.getHours(), req.getStartTime(), approverId));
        log.info("OvertimeRequest {} APPROVED by {} comp={}",
                req.getId(), approverId, req.getCompensationType());
        return saved;
    }

    @Override
    @Transactional
    public OvertimeRequest reject(String factoryId, Long approverId, String id, String reason) {
        OvertimeRequest req = load(factoryId, id);
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
    public OvertimeRequest cancel(String factoryId, Long userId, String id) {
        OvertimeRequest req = loadOwn(factoryId, userId, id);
        if (req.getStatus() != HrRequestStatus.DRAFT && req.getStatus() != HrRequestStatus.SUBMITTED) {
            throw new BusinessException("仅 DRAFT/SUBMITTED 可撤回, 当前=" + req.getStatus());
        }
        req.setStatus(HrRequestStatus.CANCELLED);
        req.setCancelledAt(LocalDateTime.now());
        return repository.save(req);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<OvertimeRequest> getById(String factoryId, String id) {
        return repository.findByIdAndFactoryId(id, factoryId);
    }

    @Override
    @Transactional(readOnly = true)
    public Page<OvertimeRequest> listMine(String factoryId, Long userId,
                                          HrRequestStatus status, Pageable pageable) {
        return status == null
                ? repository.findByFactoryIdAndUserId(factoryId, userId, pageable)
                : repository.findByFactoryIdAndUserIdAndStatus(factoryId, userId, status, pageable);
    }

    @Override
    @Transactional(readOnly = true)
    public Page<OvertimeRequest> listPending(String factoryId, Pageable pageable) {
        return repository.findByFactoryIdAndStatus(factoryId, HrRequestStatus.SUBMITTED, pageable);
    }

    @Override
    @Transactional(readOnly = true)
    public Page<OvertimeRequest> listAll(String factoryId, Pageable pageable) {
        return repository.findByFactoryId(factoryId, pageable);
    }

    @Override
    @Transactional(readOnly = true)
    public Page<OvertimeRequest> listAll(String factoryId, OvertimeType overtimeType,
                                         CompensationType compensationType, HrRequestStatus status, Pageable pageable) {
        return repository.findByFiltersAll(factoryId, overtimeType, compensationType, status, pageable);
    }

    @Override
    @Transactional(readOnly = true)
    public Map<CompensationType, BigDecimal> summarizeMonth(String factoryId, String yearMonth) {
        YearMonth ym = YearMonth.parse(yearMonth);
        LocalDateTime start = ym.atDay(1).atStartOfDay();
        LocalDateTime end = ym.plusMonths(1).atDay(1).atStartOfDay();
        Map<CompensationType, BigDecimal> result = new HashMap<>();
        for (Object[] row : repository.sumApprovedHoursByCompTypeInRange(factoryId, start, end)) {
            result.put((CompensationType) row[0], (BigDecimal) row[1]);
        }
        return result;
    }

    private OvertimeRequest load(String factoryId, String id) {
        return repository.findByIdAndFactoryId(id, factoryId)
                .orElseThrow(() -> new ResourceNotFoundException("OvertimeRequest not found: " + id));
    }

    private OvertimeRequest loadOwn(String factoryId, Long userId, String id) {
        OvertimeRequest req = load(factoryId, id);
        if (!userId.equals(req.getUserId())) {
            throw new BusinessException("无权操作他人申请");
        }
        return req;
    }

    private void validate(LocalDateTime start, LocalDateTime end, BigDecimal hours,
                         OvertimeType type, CompensationType comp) {
        if (start == null || end == null || hours == null || type == null || comp == null) {
            throw new BusinessException("startTime / endTime / hours / overtimeType / compensationType 必填");
        }
        if (!end.isAfter(start)) {
            throw new BusinessException("endTime 必须晚于 startTime");
        }
        if (hours.signum() <= 0) {
            throw new BusinessException("hours 必须 > 0");
        }
        if (type == OvertimeType.HOLIDAY && comp == CompensationType.COMPTIME) {
            throw new BusinessException("法定节假日加班不可转调休");
        }
    }
}
