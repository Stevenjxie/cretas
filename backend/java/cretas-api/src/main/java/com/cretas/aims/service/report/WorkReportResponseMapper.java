package com.cretas.aims.service.report;

import com.cretas.aims.dto.WorkReportResponse;
import com.cretas.aims.entity.ProductionReport;

/**
 * {@link ProductionReport} → {@link WorkReportResponse} 的映射（纯函数）。
 *
 * <p>原本是 {@code WorkReportingServiceImpl} 的私有方法。legacy 报工栈正在退役
 * （设计卡 {@code docs/decisions/2026-08-17-legacy报工栈退役.md}），
 * 而新的 {@code process-work-reporting} 只读端点要用同一套映射 ——
 * ⛔ 不能让新代码依赖一个即将被删掉的类，所以抽到这里。
 *
 * <p>⚠️ 本次是<b>原样搬运</b>（脚本抽取，非手抄）：手抄漏一个字段，
 * 长相就是 2026-08-17 那个「报工人一列空白」——
 * 拼装出来的对象少一个值不会有任何东西变红。
 */
public final class WorkReportResponseMapper {

    private WorkReportResponseMapper() {
    }

    public static WorkReportResponse toResponse(ProductionReport r) {
        return WorkReportResponse.builder()
                .id(r.getId())
                .factoryId(r.getFactoryId())
                .batchId(r.getBatchId())
                .workerId(r.getWorkerId())
                .reportType(r.getReportType())
                .schemaId(r.getSchemaId())
                .reportDate(r.getReportDate())
                .reporterName(r.getReporterName())
                .processCategory(r.getProcessCategory())
                .productName(r.getProductName())
                .outputQuantity(r.getOutputQuantity())
                .goodQuantity(r.getGoodQuantity())
                .defectQuantity(r.getDefectQuantity())
                .totalWorkMinutes(r.getTotalWorkMinutes())
                .totalWorkers(r.getTotalWorkers())
                .operationVolume(r.getOperationVolume())
                .hourEntries(r.getHourEntries())
                .nonProductionEntries(r.getNonProductionEntries())
                .productionStartTime(r.getProductionStartTime())
                .productionEndTime(r.getProductionEndTime())
                .customFields(r.getCustomFields())
                .photos(r.getPhotos())
                .status(r.getStatus())
                .rejectionReason(r.getRejectionReason())
                .syncedToSmartbi(r.getSyncedToSmartbi())
                .createdAt(r.getCreatedAt())
                .updatedAt(r.getUpdatedAt())
                .build();
    }
}
