package com.cretas.aims.service.hr;

import com.cretas.aims.entity.hr.OvertimeRequest;
import com.cretas.aims.entity.hr.enums.CompensationType;
import com.cretas.aims.entity.hr.enums.HrRequestStatus;
import com.cretas.aims.entity.hr.enums.OvertimeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.Optional;

public interface OvertimeRequestService {

    OvertimeRequest create(String factoryId, Long userId, OvertimeType type,
                          LocalDateTime startTime, LocalDateTime endTime,
                          BigDecimal hours, CompensationType compensationType, String reason);

    OvertimeRequest update(String factoryId, Long userId, String id,
                          OvertimeType type, LocalDateTime startTime, LocalDateTime endTime,
                          BigDecimal hours, CompensationType compensationType, String reason);

    OvertimeRequest submit(String factoryId, Long userId, String id);

    OvertimeRequest approve(String factoryId, Long approverId, String id);

    OvertimeRequest reject(String factoryId, Long approverId, String id, String reason);

    OvertimeRequest cancel(String factoryId, Long userId, String id);

    Optional<OvertimeRequest> getById(String factoryId, String id);

    Page<OvertimeRequest> listMine(String factoryId, Long userId,
                                  HrRequestStatus status, Pageable pageable);

    Page<OvertimeRequest> listPending(String factoryId, Pageable pageable);

    Page<OvertimeRequest> listAll(String factoryId, Pageable pageable);

    /** HR: 全部, 按类型/补偿方式/状态过滤 (2026-07-14 web-admin filter audit, 均可空). */
    Page<OvertimeRequest> listAll(String factoryId, OvertimeType overtimeType,
                                  CompensationType compensationType, HrRequestStatus status, Pageable pageable);

    /** 工厂月度: compensationType → 总小时数 (APPROVED only). */
    Map<CompensationType, BigDecimal> summarizeMonth(String factoryId, String yearMonth);
}
