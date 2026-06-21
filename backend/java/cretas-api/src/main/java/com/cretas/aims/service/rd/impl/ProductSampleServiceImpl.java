package com.cretas.aims.service.rd.impl;

import com.cretas.aims.entity.rd.ProductSample;
import com.cretas.aims.entity.rd.ProductSampleTrackingRecord;
import com.cretas.aims.entity.rd.QuotationTask;
import com.cretas.aims.entity.rd.RdRequest;
import com.cretas.aims.event.SampleApprovedEvent;
import com.cretas.aims.repository.rd.ProductSampleRepository;
import com.cretas.aims.repository.rd.ProductSampleTrackingRecordRepository;
import com.cretas.aims.repository.rd.QuotationTaskRepository;
import com.cretas.aims.repository.rd.RdRequestRepository;
import com.cretas.aims.service.rd.ProductSampleService;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;

@Slf4j
@Service
@RequiredArgsConstructor
public class ProductSampleServiceImpl implements ProductSampleService {

    private final RdRequestRepository rdRequestRepository;
    private final ProductSampleRepository productSampleRepository;
    private final QuotationTaskRepository quotationTaskRepository;
    private final ApplicationEventPublisher eventPublisher;
    private final ObjectMapper objectMapper;
    private final ProductSampleTrackingRecordRepository trackingRecordRepository;

    // ==================== 研发需求 ====================

    @Override
    @Transactional
    public RdRequest createRequest(String factoryId, String customerName, String customerContact,
                                    String requirements, String urgency, Long submittedBy) {
        RdRequest req = new RdRequest();
        req.setFactoryId(factoryId);
        req.setRequestNumber(genNumber("RD-REQ"));
        req.setCustomerName(customerName);
        req.setCustomerContact(customerContact);
        req.setRequirements(requirements);
        req.setUrgency(urgency != null ? urgency : "MEDIUM");
        req.setStatus("SUBMITTED");
        req.setSubmittedBy(submittedBy);
        req.setSubmittedAt(LocalDateTime.now());
        log.info("研发需求创建: customer={}", customerName);
        return rdRequestRepository.save(req);
    }

    @Override
    @Transactional
    public RdRequest assignRequest(String factoryId, String requestId, Long assignedTo) {
        RdRequest req = rdRequestRepository.findById(requestId)
                .orElseThrow(() -> new IllegalArgumentException("研发需求不存在"));
        // 多租户隔离 (审计 round5): 研发需求必须属于路径 factoryId, 否则 403。
        if (req.getFactoryId() == null || !req.getFactoryId().equals(factoryId)) {
            throw new com.cretas.aims.exception.BusinessException(403, "无权操作该研发需求");
        }
        req.setAssignedTo(assignedTo);
        req.setStatus("ASSIGNED");
        return rdRequestRepository.save(req);
    }

    @Override
    public Page<RdRequest> listRequests(String factoryId, String status, Pageable pageable) {
        if (status != null) return rdRequestRepository.findByFactoryIdAndStatusAndDeletedAtIsNull(factoryId, status, pageable);
        return rdRequestRepository.findByFactoryIdAndDeletedAtIsNull(factoryId, pageable);
    }

    // ==================== 样品管理 ====================

    @Override
    @Transactional
    public ProductSample createSample(String factoryId, String rdRequestId, String name,
                                       String specification, String grade, String mainMaterial, Long assignedTo) {
        ProductSample sample = new ProductSample();
        sample.setFactoryId(factoryId);
        sample.setSampleCode(genNumber("SP"));
        sample.setRdRequestId(rdRequestId);
        sample.setName(name);
        sample.setSpecification(specification);
        sample.setGrade(grade);
        sample.setMainMaterial(mainMaterial);
        sample.setStatus("DRAFT");
        sample.setAssignedTo(assignedTo);
        sample.setProgressNotes("[]");
        sample.setPhotoUrls("[]");

        // 更新研发需求状态
        if (rdRequestId != null) {
            rdRequestRepository.findById(rdRequestId).ifPresent(req -> {
                req.setStatus("IN_PROGRESS");
                rdRequestRepository.save(req);
            });
        }

        log.info("样品创建: name={}, code={}", name, sample.getSampleCode());
        return productSampleRepository.save(sample);
    }

    @Override
    public ProductSample updateSampleFields(ProductSample sample) {
        return productSampleRepository.save(sample);
    }

    @Override
    @Transactional
    public ProductSample updateProgress(String factoryId, String sampleId, String note, String photoUrl) {
        ProductSample sample = getSample(factoryId, sampleId);

        // P1-8 双写: 优先写新独立 table product_sample_tracking_records (支持审计/导出)
        try {
            ProductSampleTrackingRecord record = new ProductSampleTrackingRecord();
            record.setFactoryId(factoryId);
            record.setSampleId(sampleId);
            record.setRecordedAt(LocalDateTime.now());
            record.setContent(note);
            record.setAttachmentUrl(photoUrl);
            trackingRecordRepository.save(record);
        } catch (Exception e) {
            log.warn("[P1-8] tracking record 独立 table 写入失败, 继续写老 progressNotes JSON: {}", e.getMessage());
        }

        try {
            List<Map<String, String>> notes = objectMapper.readValue(
                    sample.getProgressNotes() != null ? sample.getProgressNotes() : "[]", List.class);
            Map<String, String> entry = new LinkedHashMap<>();
            entry.put("time", LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")));
            entry.put("note", note);
            if (photoUrl != null) entry.put("photoUrl", photoUrl);
            notes.add(entry);
            sample.setProgressNotes(objectMapper.writeValueAsString(notes));

            if (photoUrl != null) {
                List<String> photos = objectMapper.readValue(
                        sample.getPhotoUrls() != null ? sample.getPhotoUrls() : "[]", List.class);
                photos.add(photoUrl);
                sample.setPhotoUrls(objectMapper.writeValueAsString(photos));
            }
        } catch (Exception e) {
            log.error("更新进度序列化失败", e);
        }

        if ("DRAFT".equals(sample.getStatus())) sample.setStatus("IN_PROGRESS");
        return productSampleRepository.save(sample);
    }

    @Override
    @Transactional
    public ProductSample submitForApproval(String factoryId, String sampleId, Long submittedBy) {
        ProductSample sample = getSample(factoryId, sampleId);
        // 状态守卫: 防止已提交/已通过的样品被静默回退到 SUBMITTED (approvedBy/At 残留 → 状态矛盾)。
        // 允许 DRAFT / IN_PROGRESS / REJECTED 提交; 拒绝 SUBMITTED (重复) 与 APPROVED (终态回退)。
        String st = sample.getStatus();
        if ("SUBMITTED".equals(st) || "APPROVED".equals(st)) {
            throw new IllegalStateException(
                    String.format("样品 %s 当前状态为 %s, 不可重复提交审核", sample.getName(), st));
        }
        sample.setStatus("SUBMITTED");
        sample.setSubmittedBy(submittedBy);
        return productSampleRepository.save(sample);
    }

    @Override
    @Transactional
    public ProductSample approveSample(String factoryId, String sampleId, Long approvedBy, String notes) {
        ProductSample sample = getSample(factoryId, sampleId);
        if (!"SUBMITTED".equals(sample.getStatus())) {
            throw new IllegalStateException("只能审核已提交的样品");
        }
        sample.setStatus("APPROVED");
        sample.setApprovedBy(approvedBy);
        sample.setApprovedAt(LocalDateTime.now());
        sample.setApprovalNotes(notes);
        ProductSample saved = productSampleRepository.save(sample);

        // 发布审核通过事件 → 自动创建BOM + 报价任务
        eventPublisher.publishEvent(new SampleApprovedEvent(
                this, sample.getFactoryId(), sample.getId(), sample.getName(), approvedBy));

        log.info("样品审核通过: sampleId={}, name={}", sampleId, sample.getName());
        return saved;
    }

    @Override
    @Transactional
    public ProductSample rejectSample(String factoryId, String sampleId, Long approvedBy, String notes) {
        ProductSample sample = getSample(factoryId, sampleId);
        // 状态守卫 (同 approveSample): 只能驳回已提交的样品, 防止 APPROVED 终态被回退到 REJECTED。
        if (!"SUBMITTED".equals(sample.getStatus())) {
            throw new IllegalStateException("只能驳回已提交的样品");
        }
        sample.setStatus("REJECTED");
        sample.setApprovedBy(approvedBy);
        sample.setApprovedAt(LocalDateTime.now());
        sample.setApprovalNotes(notes);
        return productSampleRepository.save(sample);
    }

    @Override
    public Page<ProductSample> listSamples(String factoryId, String status, Pageable pageable) {
        if (status != null) return productSampleRepository.findByFactoryIdAndStatusAndDeletedAtIsNull(factoryId, status, pageable);
        return productSampleRepository.findByFactoryIdAndDeletedAtIsNull(factoryId, pageable);
    }

    @Override
    public ProductSample getSample(String factoryId, String sampleId) {
        return productSampleRepository.findByIdAndFactoryIdAndDeletedAtIsNull(sampleId, factoryId)
                .orElseThrow(() -> new IllegalArgumentException("样品不存在或无权访问: " + sampleId));
    }

    // ==================== 报价任务 ====================

    @Override
    public QuotationTask getQuotationBySample(String sampleId) {
        return quotationTaskRepository.findBySampleIdAndDeletedAtIsNull(sampleId);
    }

    @Override
    @Transactional
    public QuotationTask submitQuotation(String factoryId, String taskId, BigDecimal materialCost, BigDecimal laborCost,
                                          BigDecimal overheadCost, BigDecimal suggestedPrice,
                                          BigDecimal laborPerKg, Long quotedBy) {
        QuotationTask task = quotationTaskRepository.findById(taskId)
                .orElseThrow(() -> new IllegalArgumentException("报价任务不存在"));
        // 跨租户校验: 报价任务须属于当前工厂 (防止越权对别厂报价任务写入价格)
        if (task.getFactoryId() == null || !task.getFactoryId().equals(factoryId)) {
            throw new com.cretas.aims.exception.BusinessException(403, "无权操作该报价任务 / 该报价任务不属于当前工厂");
        }
        task.setMaterialCost(materialCost);
        task.setLaborCost(laborCost);
        task.setOverheadCost(overheadCost);
        task.setTotalCost(materialCost.add(laborCost).add(overheadCost));
        task.setSuggestedPrice(suggestedPrice);
        // feedback_dto_roundtrip_silent_drop fix: 第3处 — service 层 set, laborPerKg 可为 null (可选字段)
        if (laborPerKg != null) {
            task.setLaborPerKg(laborPerKg);
        }
        task.setStatus("QUOTED");
        task.setQuotedBy(quotedBy);
        task.setQuotedAt(LocalDateTime.now());

        if (suggestedPrice.compareTo(BigDecimal.ZERO) > 0 && task.getTotalCost().compareTo(BigDecimal.ZERO) > 0) {
            task.setProfitMargin(suggestedPrice.subtract(task.getTotalCost())
                    .divide(suggestedPrice, 4, BigDecimal.ROUND_HALF_UP)
                    .multiply(new BigDecimal("100")));
        }
        return quotationTaskRepository.save(task);
    }

    @Override
    @Transactional
    public QuotationTask confirmQuotation(String factoryId, String taskId, BigDecimal finalPrice, Long confirmedBy) {
        QuotationTask task = quotationTaskRepository.findById(taskId)
                .orElseThrow(() -> new IllegalArgumentException("报价任务不存在"));
        // 跨租户校验: 报价任务须属于当前工厂 (防止越权确认别厂报价任务)
        if (task.getFactoryId() == null || !task.getFactoryId().equals(factoryId)) {
            throw new com.cretas.aims.exception.BusinessException(403, "无权操作该报价任务 / 该报价任务不属于当前工厂");
        }
        task.setFinalPrice(finalPrice);
        task.setStatus("CONFIRMED");
        task.setConfirmedBy(confirmedBy);
        task.setConfirmedAt(LocalDateTime.now());
        return quotationTaskRepository.save(task);
    }

    @Override
    public Page<QuotationTask> listQuotations(String factoryId, String status, Pageable pageable) {
        if (status != null) return quotationTaskRepository.findByFactoryIdAndStatusAndDeletedAtIsNull(factoryId, status, pageable);
        return quotationTaskRepository.findByFactoryIdAndDeletedAtIsNull(factoryId, pageable);
    }

    @Override
    public List<ProductSampleTrackingRecord> getTrackingRecords(String factoryId, String sampleId) {
        return trackingRecordRepository.findByFactoryIdAndSampleIdAndDeletedAtIsNullOrderByRecordedAtDesc(factoryId, sampleId);
    }

    private String genNumber(String prefix) {
        String dateStr = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd"));
        return String.format("%s-%s-%04d", prefix, dateStr, System.nanoTime() % 10000);
    }
}
