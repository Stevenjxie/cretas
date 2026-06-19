package com.cretas.aims.controller;

import com.cretas.aims.annotation.RequireModule;
import com.cretas.aims.dto.common.ApiResponse;
import com.cretas.aims.annotation.RequirePermission;
import com.cretas.aims.entity.ProcessCheckinRecord;
import com.cretas.aims.entity.ProductionBatch;
import com.cretas.aims.entity.ProductionReport;
import com.cretas.aims.entity.User;
import com.cretas.aims.repository.ProcessCheckinRecordRepository;
import com.cretas.aims.repository.ProductionBatchRepository;
import com.cretas.aims.repository.ProductionPlanRepository;
import com.cretas.aims.repository.ProductionReportRepository;
import com.cretas.aims.repository.UserRepository;
import com.cretas.aims.exception.ResourceNotFoundException;
import com.cretas.aims.exception.BusinessException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Slf4j
@RestController
@RequestMapping("/api/mobile/{factoryId}/process-checkin")
@RequiredArgsConstructor
@RequireModule("production_report")
public class ProcessCheckinController {

    private final ProcessCheckinRecordRepository checkinRepository;
    private final ProductionPlanRepository planRepository;
    private final ProductionBatchRepository batchRepository;
    private final ProductionReportRepository reportRepository;
    private final UserRepository userRepository;

    @RequirePermission({"production:read_write"})
    @PostMapping
    @PreAuthorize("hasAnyRole('ADMIN', 'MANAGER')")
    public ApiResponse<Map<String, Object>> checkIn(
            @PathVariable String factoryId,
            @RequestBody Map<String, Object> body,
            @RequestAttribute(value = "userId", required = false) Long operatorId) {
        // 缺 employeeId 守卫: raw Map body.get(null).toString() → NPE → 500; 转 400 + 字段提示。
        Object employeeIdRaw = body.get("employeeId");
        if (employeeIdRaw == null || employeeIdRaw.toString().trim().isEmpty()) {
            throw new BusinessException(400, "请提供员工ID (employeeId)").withHintTarget("employeeId");
        }
        Long employeeId;
        try {
            employeeId = Long.valueOf(employeeIdRaw.toString().trim());
        } catch (NumberFormatException e) {
            throw new BusinessException(400, "员工ID必须是数字").withHintTarget("employeeId");
        }

        // P2-7: 重复签到防护 — 同一员工已 CHECKED_IN 时拒绝
        List<ProcessCheckinRecord> existing = checkinRepository
                .findByFactoryIdAndEmployeeIdAndStatus(factoryId, employeeId, "CHECKED_IN");
        if (!existing.isEmpty()) {
            throw new BusinessException(409, "该员工已签到（ID: " + existing.get(0).getId() + "），请先签退后再签到")
                    .withHint("请先签退现有进行中的工序后再签到");
        }

        ProcessCheckinRecord record = new ProcessCheckinRecord();
        record.setFactoryId(factoryId);
        record.setEmployeeId(employeeId);
        record.setProcessName((String) body.get("processName"));
        record.setProcessCategory((String) body.get("processCategory"));
        // P2-8: 关联 processTaskId
        if (body.get("processTaskId") != null) {
            record.setProcessTaskId((String) body.get("processTaskId"));
        }
        if (body.get("batchId") != null) {
            record.setBatchId(Long.valueOf(body.get("batchId").toString()));
        }
        record.setCheckInTime(LocalDateTime.now());
        record.setCheckinMethod((String) body.getOrDefault("checkinMethod", "SCAN"));
        record.setStatus("CHECKED_IN");

        record = checkinRepository.save(record);

        // 查员工姓名
        String employeeName = userRepository.findById(employeeId)
                .map(User::getFullName)
                .orElse("工号" + employeeId);

        Map<String, Object> result = new HashMap<>();
        result.put("id", record.getId());
        result.put("employeeId", employeeId);
        result.put("employeeName", employeeName);
        result.put("processName", record.getProcessName());
        result.put("checkInTime", record.getCheckInTime());
        result.put("status", record.getStatus());
        return ApiResponse.success(result);
    }

    @RequirePermission({"production:read_write"})
    @PostMapping("/checkout/{id}")
    @PreAuthorize("hasAnyRole('ADMIN', 'MANAGER')")
    @Transactional
    public ApiResponse<Map<String, Object>> checkOut(
            @PathVariable String factoryId,
            @PathVariable Long id) {
        ProcessCheckinRecord record = checkinRepository.findByIdAndFactoryId(id, factoryId)
                .orElseThrow(() -> new ResourceNotFoundException("ProcessCheckinRecord", "id", id.toString()));

        if (!"CHECKED_IN".equals(record.getStatus())) {
            throw new BusinessException(409, "当前状态无法签退");
        }

        record.setCheckOutTime(LocalDateTime.now());
        record.setStatus("CHECKED_OUT");

        long minutes = java.time.Duration.between(record.getCheckInTime(), record.getCheckOutTime()).toMinutes();
        record.setWorkMinutes((int) minutes);

        record = checkinRepository.save(record);

        // Fix-4: 签退后自动创建报工草稿（仅当关联了批次时）
        Long draftReportId = null;
        if (record.getBatchId() != null) {
            try {
                draftReportId = createReportDraft(record);
                log.info("签退自动创建报工草稿: checkinId={}, draftReportId={}", id, draftReportId);
            } catch (BusinessException be) {
                throw be;
            } catch (Exception e) {
                log.warn("报工草稿创建失败(不影响签退): checkinId={}, error={}", id, e.getMessage());
            }
        }

        Map<String, Object> result = new HashMap<>();
        result.put("checkinRecord", record);
        result.put("draftReportId", draftReportId);
        result.put("message", draftReportId != null
                ? "签退成功！已自动创建报工草稿，请补充产量信息"
                : "签退成功");
        return ApiResponse.success(result);
    }

    /**
     * Fix-4: 签退→报工草稿联动
     * 自动创建 DRAFT 状态报工记录，预填工时和批次信息
     */
    private Long createReportDraft(ProcessCheckinRecord checkin) {
        // 防重：同一工人+批次+日期已有报工则跳过
        if (reportRepository.existsByFactoryIdAndWorkerIdAndBatchIdAndReportDateAndDeletedAtIsNull(
                checkin.getFactoryId(), checkin.getEmployeeId(), checkin.getBatchId(), LocalDate.now())) {
            log.info("已存在报工记录，跳过草稿创建: employeeId={}, batchId={}", checkin.getEmployeeId(), checkin.getBatchId());
            return null;
        }

        // 从批次获取产品信息
        String productName = null;
        if (checkin.getBatchId() != null) {
            productName = batchRepository.findById(checkin.getBatchId())
                    .map(ProductionBatch::getProductName)
                    .orElse(null);
        }

        ProductionReport draft = ProductionReport.builder()
                .factoryId(checkin.getFactoryId())
                .workerId(checkin.getEmployeeId())
                .batchId(checkin.getBatchId())
                .reportType("PROGRESS")
                .reportDate(LocalDate.now())
                .processCategory(checkin.getProcessCategory())
                .productName(productName)
                .totalWorkMinutes(checkin.getWorkMinutes())
                .totalWorkers(1)
                .status(ProductionReport.Status.DRAFT)
                .build();

        draft = reportRepository.save(draft);
        return draft.getId();
    }

    @GetMapping("/active")
    public ApiResponse<List<ProcessCheckinRecord>> getActiveCheckins(
            @PathVariable String factoryId,
            @RequestParam(required = false) Long employeeId) {
        List<ProcessCheckinRecord> records;
        if (employeeId != null) {
            records = checkinRepository.findByFactoryIdAndEmployeeIdAndStatus(factoryId, employeeId, "CHECKED_IN");
        } else {
            records = checkinRepository.findByFactoryIdAndStatus(factoryId, "CHECKED_IN");
        }
        return ApiResponse.success(records);
    }

    @GetMapping("/today-summary")
    public ApiResponse<List<ProcessCheckinRecord>> getTodaySummary(
            @PathVariable String factoryId,
            @RequestParam(required = false) Long employeeId) {
        LocalDateTime startOfDay = LocalDate.now().atStartOfDay();
        LocalDateTime endOfDay = LocalDate.now().atTime(LocalTime.MAX);

        List<ProcessCheckinRecord> records;
        if (employeeId != null) {
            records = checkinRepository.findTodayByEmployee(factoryId, employeeId, startOfDay, endOfDay);
        } else {
            records = checkinRepository.findTodayRecords(factoryId, startOfDay, endOfDay);
        }
        return ApiResponse.success(records);
    }

    @GetMapping("/available-processes")
    public ApiResponse<List<Map<String, Object>>> getAvailableProcesses(
            @PathVariable String factoryId) {
        // 单元E (F006 防呆 REQ-13): 只返回今日 (plannedDate=today) 的可用工序,
        // 多日计划不污染下拉; plannedDate=null 的计划保留 (向后兼容)。
        LocalDate today = LocalDate.now();
        List<Map<String, Object>> processes = planRepository.findByFactoryId(factoryId).stream()
                .filter(p -> isAvailableProcessPlan(p, today))
                .map(p -> {
                    Map<String, Object> m = new HashMap<>();
                    m.put("processName", p.getProcessName());
                    m.put("productName", p.getProductType() != null ? p.getProductType().getName() : "");
                    m.put("planId", p.getId());
                    m.put("customerName", p.getSourceCustomerName());
                    return m;
                })
                .collect(Collectors.toList());
        return ApiResponse.success(processes);
    }

    /**
     * 单元E (F006 防呆 REQ-13): 判断生产计划是否应出现在 "今日工序" 下拉中。
     * 规则: 工序名非空 + 状态为 PLANNED/PENDING/IN_PROGRESS + (plannedDate=today 或 plannedDate=null)。
     * plannedDate=null 的计划保留以兼容历史无日期数据。
     */
    static boolean isAvailableProcessPlan(com.cretas.aims.entity.ProductionPlan p, LocalDate today) {
        if (p.getProcessName() == null || p.getProcessName().isEmpty()) {
            return false;
        }
        String status = p.getStatus() != null ? p.getStatus().name() : null;
        boolean statusOk = "IN_PROGRESS".equals(status)
                || "PLANNED".equals(status)
                || "PENDING".equals(status);
        if (!statusOk) {
            return false;
        }
        // 保留 null 日期计划 (向后兼容); 有日期则必须等于今天
        return p.getPlannedDate() == null || p.getPlannedDate().isEqual(today);
    }
}
