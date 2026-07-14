package com.cretas.aims.service.hr;

import com.cretas.aims.entity.hr.LeaveRequest;
import com.cretas.aims.entity.hr.enums.HrRequestStatus;
import com.cretas.aims.entity.hr.enums.LeaveType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Map;
import java.util.Optional;

/**
 * 请假申请服务.
 *
 * @author Cretas Team — Sprint 4 W2 Chat E (H-LEAVE-1)
 * @since 2026-05-16
 */
public interface LeaveRequestService {

    LeaveRequest create(String factoryId, Long userId, LeaveType leaveType,
                       LocalDate startDate, LocalDate endDate,
                       BigDecimal durationHours, String reason);

    LeaveRequest update(String factoryId, Long userId, String id,
                       LeaveType leaveType, LocalDate startDate, LocalDate endDate,
                       BigDecimal durationHours, String reason);

    LeaveRequest submit(String factoryId, Long userId, String id);

    LeaveRequest approve(String factoryId, Long approverId, String id);

    LeaveRequest reject(String factoryId, Long approverId, String id, String reason);

    LeaveRequest cancel(String factoryId, Long userId, String id);

    Optional<LeaveRequest> getById(String factoryId, String id);

    /** 员工: 我的申请 (可按状态过滤). */
    Page<LeaveRequest> listMine(String factoryId, Long userId,
                                HrRequestStatus status, Pageable pageable);

    /** 主管: 待审 (status=SUBMITTED). */
    Page<LeaveRequest> listPending(String factoryId, Pageable pageable);

    /** HR: 全部. */
    Page<LeaveRequest> listAll(String factoryId, Pageable pageable);

    /** HR: 全部, 按类型/状态过滤 (2026-07-14 web-admin filter audit, 均可空). */
    Page<LeaveRequest> listAll(String factoryId, LeaveType leaveType, HrRequestStatus status, Pageable pageable);

    /** 工厂月度: leaveType → 总小时数 (APPROVED only). */
    Map<LeaveType, BigDecimal> summarizeMonth(String factoryId, String yearMonth);
}
