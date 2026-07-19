package com.cretas.aims.service.impl;

import com.cretas.aims.dto.WorkProcessTaskDTO;
import com.cretas.aims.dto.common.PageResponse;
import com.cretas.aims.entity.Attachment;
import com.cretas.aims.entity.ProductType;
import com.cretas.aims.entity.ProductionReport;
import com.cretas.aims.entity.WorkProcess;
import com.cretas.aims.entity.workprocess.WorkProcessTask;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.exception.ResourceNotFoundException;
import com.cretas.aims.repository.AttachmentRepository;
import com.cretas.aims.repository.ProductTypeRepository;
import com.cretas.aims.repository.ProductionReportRepository;
import com.cretas.aims.repository.WorkProcessRepository;
import com.cretas.aims.repository.workprocess.WorkProcessTaskRepository;
import com.cretas.aims.service.ProcessWorkReportingService;
import com.cretas.aims.service.wip.WipInventoryService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class ProcessWorkReportingServiceImpl implements ProcessWorkReportingService {

    private final ProductionReportRepository reportRepository;
    private final WorkProcessRepository workProcessRepository;
    private final ProductTypeRepository productTypeRepository;
    private final AttachmentRepository attachmentRepository;
    private final WorkProcessTaskRepository workProcessTaskRepository;
    private final WipInventoryService wipInventoryService;

    @Override
    @Transactional
    public Map<String, Object> approveReport(String factoryId, Long reportId, Long approvedBy) {
        ProductionReport report = loadPendingReport(factoryId, reportId);
        report.setApprovalStatus("APPROVED");
        report.setApprovedBy(approvedBy);
        report.setApprovedAt(LocalDateTime.now());
        reportRepository.save(report);
        postWipForApprovedReport(factoryId, report, approvedBy);
        return Map.of("reportId", reportId, "status", "APPROVED");
    }

    @Override
    @Transactional
    public Map<String, Object> rejectReport(String factoryId, Long reportId, String reason, Long rejectedBy) {
        ProductionReport report = loadPendingReport(factoryId, reportId);
        report.setApprovalStatus("REJECTED");
        report.setRejectedReason(reason);
        report.setApprovedBy(rejectedBy);
        report.setApprovedAt(LocalDateTime.now());
        reportRepository.save(report);
        return Map.of("reportId", reportId, "status", "REJECTED");
    }

    @Override
    @Transactional
    public Map<String, Object> batchApprove(String factoryId, List<Long> reportIds, Long approvedBy) {
        List<Map<String, Object>> approved = new ArrayList<>();
        List<Map<String, Object>> skipped = new ArrayList<>();
        for (Long reportId : reportIds) {
            ProductionReport report = reportRepository.findById(reportId).orElse(null);
            if (report == null) {
                skipped.add(Map.of("reportId", reportId, "reason", "NOT_FOUND"));
                continue;
            }
            if (!factoryId.equals(report.getFactoryId())) {
                skipped.add(Map.of("reportId", reportId, "reason", "WRONG_FACTORY"));
                continue;
            }
            if (!"PENDING".equals(report.getApprovalStatus())) {
                skipped.add(Map.of(
                        "reportId", reportId,
                        "reason", "ALREADY_PROCESSED",
                        "currentStatus", report.getApprovalStatus()));
                continue;
            }
            report.setApprovalStatus("APPROVED");
            report.setApprovedBy(approvedBy);
            report.setApprovedAt(LocalDateTime.now());
            reportRepository.save(report);
            postWipForApprovedReport(factoryId, report, approvedBy);
            approved.add(Map.of("reportId", reportId, "status", "APPROVED"));
        }

        if (approved.isEmpty() && !skipped.isEmpty()) {
            String reasons = skipped.stream()
                    .map(item -> item.get("reportId") + ":" + item.get("reason"))
                    .collect(Collectors.joining(", "));
            throw new BusinessException(409, "全部报工记录均无法批量审批 (" + reasons + ")")
                    .withHint("请刷新报工列表, 仅勾选状态为 PENDING 的记录")
                    .withHintTarget("reportIds");
        }

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("approved", approved.size());
        response.put("skipped", skipped.size());
        response.put("results", approved);
        response.put("skippedIds", skipped);
        return response;
    }

    @Override
    public PageResponse<Map<String, Object>> getPendingApprovals(String factoryId, Pageable pageable) {
        Page<ProductionReport> page = reportRepository
                .findPendingApprovalsForFactory(factoryId, "PENDING", pageable);
        Map<String, List<String>> evidenceUrls = loadProductionReportEvidenceUrls(factoryId, page.getContent());
        List<Map<String, Object>> content = page.getContent().stream()
                .map(report -> reportToMap(factoryId, report, evidenceUrls))
                .toList();
        return PageResponse.of(content, page.getNumber() + 1, page.getSize(), page.getTotalElements());
    }

    @Override
    public List<Map<String, Object>> getReportsByTask(String factoryId, String taskId) {
        Long workProcessTaskId = parseCanonicalTaskId(taskId);
        List<ProductionReport> reports = reportRepository
                .findByFactoryIdAndWorkProcessTaskIdAndDeletedAtIsNull(factoryId, workProcessTaskId);
        Map<String, List<String>> evidenceUrls = loadProductionReportEvidenceUrls(factoryId, reports);
        return reports.stream()
                .map(report -> reportToMap(factoryId, report, evidenceUrls))
                .toList();
    }

    @Override
    public List<WorkProcessTaskDTO.WorkerSummary> getWorkerSummaryByTask(String factoryId, String taskId) {
        Long workProcessTaskId = parseCanonicalTaskId(taskId);
        List<ProductionReport> reports = reportRepository
                .findByFactoryIdAndWorkProcessTaskIdAndDeletedAtIsNull(factoryId, workProcessTaskId);
        Map<WorkerKey, List<ProductionReport>> byWorker = reports.stream()
                .filter(report -> report.getWorkerId() != null)
                .collect(Collectors.groupingBy(
                        report -> new WorkerKey(report.getWorkerId(), report.getReporterName()),
                        LinkedHashMap::new,
                        Collectors.toList()));
        return byWorker.entrySet().stream()
                .map(entry -> {
                    List<ProductionReport> workerReports = entry.getValue();
                    BigDecimal total = sumOutput(workerReports, null);
                    BigDecimal approved = sumOutput(workerReports, "APPROVED");
                    BigDecimal pending = sumOutput(workerReports, "PENDING");
                    return WorkProcessTaskDTO.WorkerSummary.builder()
                            .workerId(entry.getKey().workerId())
                            .workerName(entry.getKey().workerName())
                            .totalQuantity(total)
                            .approvedQuantity(approved)
                            .pendingQuantity(pending)
                            .reportCount(workerReports.size())
                            .build();
                })
                .toList();
    }

    private ProductionReport loadPendingReport(String factoryId, Long reportId) {
        ProductionReport report = reportRepository.findById(reportId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "ProductionReport", "id", String.valueOf(reportId)));
        if (!factoryId.equals(report.getFactoryId())) {
            throw new BusinessException(403, "报工记录不属于当前工厂")
                    .withHint("当前报工记录不属于该工厂, 无法操作");
        }
        if (!"PENDING".equals(report.getApprovalStatus())) {
            throw new BusinessException(409, "报工记录已被处理，当前状态: " + report.getApprovalStatus())
                    .withHint("请刷新报工列表查看最新审批状态")
                    .withHintTarget("报工记录");
        }
        return report;
    }

    private Long parseCanonicalTaskId(String taskId) {
        if (taskId == null || taskId.isBlank()) {
            throw new BusinessException(400, "workProcessTaskId 不能为空")
                    .withHintTarget("taskId");
        }
        String normalized = taskId.startsWith("WPT-") ? taskId.substring(4) : taskId;
        try {
            return Long.valueOf(normalized);
        } catch (NumberFormatException e) {
            throw new BusinessException(400, "无效的 workProcessTaskId: " + taskId)
                    .withHint("请刷新工序任务后重试")
                    .withHintTarget("taskId");
        }
    }

    private BigDecimal sumOutput(List<ProductionReport> reports, String approvalStatus) {
        return reports.stream()
                .filter(report -> approvalStatus == null || approvalStatus.equals(report.getApprovalStatus()))
                .map(ProductionReport::getOutputQuantity)
                .filter(Objects::nonNull)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private String effectiveProductName(String factoryId, ProductionReport report) {
        if (!isBlank(report.getProductName())) {
            return report.getProductName();
        }
        String productTypeId = report.getProductTypeId();
        if (productTypeId == null && report.getWorkProcessTaskId() != null) {
            productTypeId = workProcessTaskRepository
                    .findByFactoryIdAndId(factoryId, report.getWorkProcessTaskId())
                    .map(WorkProcessTask::getProductTypeId)
                    .orElse(null);
        }
        if (productTypeId == null) {
            return null;
        }
        return productTypeRepository.findByIdAndFactoryId(productTypeId, factoryId)
                .map(ProductType::getName)
                .orElse(null);
    }

    private String effectiveProcessCategory(String factoryId, ProductionReport report) {
        if (!isBlank(report.getProcessCategory())) {
            return report.getProcessCategory();
        }
        if (report.getWorkProcessTaskId() == null) {
            return null;
        }
        return workProcessTaskRepository.findByFactoryIdAndId(factoryId, report.getWorkProcessTaskId())
                .map(WorkProcessTask::getWorkProcessId)
                .filter(id -> !isBlank(id))
                .flatMap(id -> workProcessRepository.findByFactoryIdAndId(factoryId, id))
                .map(WorkProcess::getProcessName)
                .orElse(null);
    }

    private void postWipForApprovedReport(String factoryId, ProductionReport report, Long operatorId) {
        if (report.getWorkProcessTaskId() == null) {
            return;
        }
        if (!isApprovalWipPostingReport(report)) {
            log.info("Skip WIP posting for report {}: no approval posting marker", report.getId());
            return;
        }
        WorkProcessTask task = workProcessTaskRepository
                .findByFactoryIdAndId(factoryId, report.getWorkProcessTaskId())
                .orElse(null);
        if (task == null) {
            log.warn("Skip WIP posting for report {}: workProcessTask {} not found",
                    report.getId(), report.getWorkProcessTaskId());
            return;
        }
        wipInventoryService.postApprovedOutput(factoryId, report, task, operatorId);
    }

    private boolean isApprovalWipPostingReport(ProductionReport report) {
        Map<String, Object> fields = report.getCustomFields();
        return fields != null && "APPROVAL".equals(fields.get("wipPostingMode"));
    }

    private Map<String, Object> reportToMap(
            String factoryId,
            ProductionReport report,
            Map<String, List<String>> attachmentUrlsByEntityId) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", report.getId());
        map.put("factoryId", report.getFactoryId());
        map.put("processTaskId", report.getWorkProcessTaskId() == null
                ? null : String.valueOf(report.getWorkProcessTaskId()));
        map.put("batchId", report.getBatchId());
        map.put("workProcessTaskId", report.getWorkProcessTaskId());
        map.put("processOrder", report.getProcessOrder());
        map.put("reportKind", report.getReportKind());
        map.put("productTypeId", report.getProductTypeId());
        map.put("workerId", report.getWorkerId());
        map.put("reporterName", report.getReporterName());
        map.put("reportDate", report.getReportDate());
        map.put("inputQuantity", report.getInputQuantity());
        map.put("inputUnit", report.getInputUnit());
        map.put("warehouseOutQuantity", report.getWarehouseOutQuantity());
        map.put("feedInQuantity", report.getFeedInQuantity());
        map.put("carryoverQuantity", report.getCarryoverQuantity());
        map.put("sourceWipNo", report.getSourceWipNo());
        map.put("outputQuantity", report.getOutputQuantity());
        map.put("outputUnit", report.getOutputUnit());
        map.put("totalWorkers", report.getTotalWorkers());
        map.put("totalWorkMinutes", report.getTotalWorkMinutes());
        map.put("productionStartTime", report.getProductionStartTime());
        map.put("productionEndTime", report.getProductionEndTime());
        map.put("reportMode", report.getReportMode());
        map.put("processCategory", effectiveProcessCategory(factoryId, report));
        map.put("productName", effectiveProductName(factoryId, report));
        map.put("approvalStatus", report.getApprovalStatus());
        map.put("isSupplemental", report.getIsSupplemental());
        map.put("approvedBy", report.getApprovedBy());
        map.put("approvedAt", report.getApprovedAt());
        map.put("rejectedReason", report.getRejectedReason());
        map.put("reversalOfId", report.getReversalOfId());
        map.put("notes", report.getNotes());
        map.put("customFields", report.getCustomFields());
        map.put("laborSegments", report.getLaborSegments());
        map.put("byproducts", report.getByproducts());
        map.put("wasteQuantity", report.getWasteQuantity());
        map.put("sampleRetainQuantity", report.getSampleRetainQuantity());
        map.put("photos", resolveEvidencePhotos(report, attachmentUrlsByEntityId));
        map.put("createdAt", report.getCreatedAt());
        return map;
    }

    private Map<String, List<String>> loadProductionReportEvidenceUrls(
            String factoryId,
            List<ProductionReport> reports) {
        if (reports.isEmpty()) {
            return Map.of();
        }
        List<String> entityIds = reports.stream().map(report -> String.valueOf(report.getId())).toList();
        List<Attachment> attachments = attachmentRepository
                .findByFactoryIdAndEntityTypeAndEntityIdInOrderByUploadedAtAsc(
                        factoryId, Attachment.EntityType.PRODUCTION_REPORT, entityIds);
        Map<String, List<String>> byEntity = new LinkedHashMap<>();
        for (Attachment attachment : attachments) {
            if (isBlank(attachment.getFileUrl())) {
                continue;
            }
            Attachment.FileCategory category = attachment.getFileCategory();
            if (category != Attachment.FileCategory.PHOTO && category != Attachment.FileCategory.VIDEO) {
                continue;
            }
            byEntity.computeIfAbsent(attachment.getEntityId(), ignored -> new ArrayList<>())
                    .add(attachment.getFileUrl());
        }
        return byEntity;
    }

    private List<String> resolveEvidencePhotos(
            ProductionReport report,
            Map<String, List<String>> attachmentUrlsByEntityId) {
        LinkedHashSet<String> merged = new LinkedHashSet<>(
                attachmentUrlsByEntityId.getOrDefault(String.valueOf(report.getId()), List.of()));
        if (report.getPhotos() != null) {
            report.getPhotos().stream()
                    .filter(photo -> !isBlank(photo))
                    .filter(photo -> photo.startsWith("http://") || photo.startsWith("https://"))
                    .forEach(merged::add);
        }
        return merged.isEmpty() ? null : new ArrayList<>(merged);
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private record WorkerKey(Long workerId, String workerName) {
    }
}
