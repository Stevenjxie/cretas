package com.cretas.aims.service.rd;

import com.cretas.aims.entity.rd.RdRequest;
import com.cretas.aims.entity.rd.ProductSample;
import com.cretas.aims.entity.rd.QuotationTask;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

public interface ProductSampleService {

    // ==================== 研发需求 ====================
    RdRequest createRequest(String factoryId, String customerName, String customerContact,
                             String requirements, String urgency, Long submittedBy);
    RdRequest assignRequest(String factoryId, String requestId, Long assignedTo);
    Page<RdRequest> listRequests(String factoryId, String status, Pageable pageable);

    // ==================== 样品管理 ====================
    ProductSample createSample(String factoryId, String rdRequestId, String name,
                                String specification, String grade, String mainMaterial, Long assignedTo);
    ProductSample updateProgress(String factoryId, String sampleId, String note, String photoUrl);
    ProductSample submitForApproval(String factoryId, String sampleId, Long submittedBy);
    ProductSample approveSample(String factoryId, String sampleId, Long approvedBy, String notes);
    ProductSample rejectSample(String factoryId, String sampleId, Long approvedBy, String notes);
    Page<ProductSample> listSamples(String factoryId, String status, Pageable pageable);
    ProductSample getSample(String factoryId, String sampleId);
    ProductSample updateSampleFields(ProductSample sample);

    // ==================== 追踪记录 ====================
    java.util.List<com.cretas.aims.entity.rd.ProductSampleTrackingRecord> getTrackingRecords(String factoryId, String sampleId);

    // ==================== 报价任务 ====================
    QuotationTask getQuotationBySample(String sampleId);
    /**
     * 提交报价. laborPerKg 为 R&D 经验估算 元/kg, 可为 null (旧数据向后兼容).
     * feedback_dto_roundtrip_silent_drop: 第3处 — service 层接收并持久化 laborPerKg.
     */
    QuotationTask submitQuotation(String taskId, java.math.BigDecimal materialCost,
                                   java.math.BigDecimal laborCost, java.math.BigDecimal overheadCost,
                                   java.math.BigDecimal suggestedPrice, java.math.BigDecimal laborPerKg,
                                   Long quotedBy);
    QuotationTask confirmQuotation(String taskId, java.math.BigDecimal finalPrice, Long confirmedBy);
    Page<QuotationTask> listQuotations(String factoryId, String status, Pageable pageable);
}
