package com.cretas.aims.event;

import lombok.Getter;
import org.springframework.context.ApplicationEvent;

import java.time.LocalDateTime;

/**
 * 质检完成事件 — Phase 2 Canvas-Alerts 引入.
 *
 * <p>触发 {@code AlertQualityAnomalyListener}, 评估 QUALITY_ANOMALY 规则.
 * 一般 SpEL 条件: {@code "#context.result == 'FAIL'"} 或
 * {@code "#context.passRate &lt; 0.95"}.
 *
 * <p><b>Phase 2 follow-up</b>: QualityInspectionServiceImpl 应在质检写入后
 * publish 此事件 (除了已存在的 ProductionAlertEvent — 那个是预定义告警类型,
 * 本事件是新可配规则路径).
 *
 * @since 2026-05-18 (Phase 2 impl)
 */
@Getter
public class QualityInspectionCompletedEvent extends ApplicationEvent {

    private final String factoryId;
    private final Long inspectionId;
    /** 质检结果: e.g. "PASS" / "FAIL" / "CONDITIONAL". */
    private final String result;
    /** 关联业务实体类型: e.g. "MATERIAL_BATCH" / "FINISHED_GOODS". */
    private final String relatedEntityType;
    private final String relatedEntityId;
    /** 合格率 (可选, null 表示按通过/不通过判定). */
    private final Double passRate;
    private final LocalDateTime occurredAt;

    public QualityInspectionCompletedEvent(Object source, String factoryId, Long inspectionId,
                                            String result, String relatedEntityType,
                                            String relatedEntityId, Double passRate) {
        super(source);
        this.factoryId = factoryId;
        this.inspectionId = inspectionId;
        this.result = result;
        this.relatedEntityType = relatedEntityType;
        this.relatedEntityId = relatedEntityId;
        this.passRate = passRate;
        this.occurredAt = LocalDateTime.now();
    }

    @Override
    public String toString() {
        return String.format(
                "QualityInspectionCompletedEvent[factoryId=%s, inspectionId=%s, result=%s, passRate=%s]",
                factoryId, inspectionId, result, passRate);
    }
}
