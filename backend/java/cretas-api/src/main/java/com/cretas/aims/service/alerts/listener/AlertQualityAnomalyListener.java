package com.cretas.aims.service.alerts.listener;

import com.cretas.aims.entity.alerts.AlertType;
import com.cretas.aims.event.QualityInspectionCompletedEvent;
import com.cretas.aims.service.alerts.AlertEngineService;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashMap;
import java.util.Map;

/**
 * QUALITY_ANOMALY 事件路由 — Phase 2 Canvas-Alerts.
 *
 * <p>监听 {@link QualityInspectionCompletedEvent}, 派发到
 * {@link AlertEngineService#triggerAlert}.
 *
 * <p>SpEL 默认 context 变量:
 * <ul>
 *   <li>{@code #context.result} — "PASS" / "FAIL" / "CONDITIONAL"</li>
 *   <li>{@code #context.passRate} — 合格率 (Double, 可能为 null)</li>
 *   <li>{@code #context.relatedEntityType} / {@code #context.relatedEntityId}</li>
 * </ul>
 *
 * <p>典型 SpEL: {@code "#context.result == 'FAIL'"} 或
 * {@code "#context.passRate != null && #context.passRate < 0.95"}.
 *
 * <p>Phase 2 follow-up: QualityInspectionServiceImpl 需 publish 此事件.
 *
 * @since 2026-05-18 (Phase 2 impl)
 */
@Slf4j
@Component
public class AlertQualityAnomalyListener {

    @Autowired
    private AlertEngineService alertEngineService;

    @PostConstruct
    void init() {
        log.warn("AlertQualityAnomalyListener registered — "
                + "Phase 2 follow-up: QualityInspectionServiceImpl should publish "
                + "QualityInspectionCompletedEvent on inspection write.");
    }

    @EventListener
    @Async
    @Transactional
    public void onQualityInspectionCompleted(QualityInspectionCompletedEvent event) {
        try {
            log.debug("Received {}", event);
            Map<String, Object> context = new HashMap<>();
            context.put("result", event.getResult());
            context.put("passRate", event.getPassRate());
            context.put("relatedEntityType", event.getRelatedEntityType());
            context.put("relatedEntityId", event.getRelatedEntityId());
            context.put("inspectionId", event.getInspectionId());

            String relatedDisplay = event.getRelatedEntityType() != null
                    ? event.getRelatedEntityType() + " " + event.getRelatedEntityId()
                    : "未知实体";
            context.put("message", String.format(
                    "质检异常: %s 结果=%s%s",
                    relatedDisplay,
                    event.getResult(),
                    event.getPassRate() != null
                            ? ", 合格率=" + event.getPassRate()
                            : ""));

            alertEngineService.triggerAlert(
                    event.getFactoryId(),
                    AlertType.QUALITY_ANOMALY,
                    "QUALITY_INSPECTION",
                    event.getInspectionId() != null ? String.valueOf(event.getInspectionId()) : null,
                    context);
        } catch (Exception e) {
            log.error("AlertQualityAnomalyListener: failed to process event {}", event, e);
        }
    }
}
