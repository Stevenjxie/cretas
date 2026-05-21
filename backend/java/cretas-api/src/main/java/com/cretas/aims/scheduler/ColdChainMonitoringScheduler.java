package com.cretas.aims.scheduler;

import com.cretas.aims.entity.foodsafety.ColdChainAlert;
import com.cretas.aims.entity.foodsafety.ColdChainEquipment;
import com.cretas.aims.entity.foodsafety.ColdChainTempReading;
import com.cretas.aims.repository.foodsafety.ColdChainAlertRepository;
import com.cretas.aims.repository.foodsafety.ColdChainEquipmentRepository;
import com.cretas.aims.repository.foodsafety.ColdChainTempReadingRepository;
import com.cretas.aims.service.notification.NotificationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;

/**
 * Sprint 9 P2.D — 冷链温控偏离 Scheduler (polling fallback).
 *
 * <p>每 5 min cron 扫描全部启用冷链设备:
 * <ol>
 *   <li>取最近 {@code toleranceMinutes + 5 min} 区间的 readings (覆盖容差窗口)</li>
 *   <li>判断是否持续偏离 (所有 reading 都 outside [tempMin, tempMax])</li>
 *   <li>持续超 toleranceMinutes 且无活跃 alert → 创 ColdChainAlert + notifyRole("QUALITY_MANAGER")</li>
 *   <li>mark readings.alertSent = true 避免重复</li>
 * </ol>
 *
 * <p>实际 prod IoT 设备应主动 push reading + 自身决定偏离, 本 scheduler 是
 * polling fallback (设备无 push, 或确保未漏报).
 *
 * <p>幂等性: 同设备 OPEN 报警已存在则不再创建. ShedLock 防 blue-green 双 JVM
 * 同步 fire 时只一个跑.
 *
 * <p>Severity rule:
 * <ul>
 *   <li>WARNING: 偏离持续 < 30 min</li>
 *   <li>CRITICAL: 偏离持续 ≥ 30 min</li>
 * </ul>
 *
 * @since 2026-05-21
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ColdChainMonitoringScheduler {

    private static final String NOTIFY_ROLE = "QUALITY_MANAGER";
    private static final int CRITICAL_THRESHOLD_MINUTES = 30;

    private final ColdChainEquipmentRepository equipmentRepository;
    private final ColdChainTempReadingRepository readingRepository;
    private final ColdChainAlertRepository alertRepository;
    private final NotificationService notificationService;

    @Value("${coldchain.monitor-scheduler.enabled:true}")
    private boolean enabled;

    /**
     * 每 5 min 执行. cron 顺序: 秒 分 时 日 月 周.
     * {@code 0 *\/5 * * * ?} = 0 秒, 每 5 min, 任意时日月周.
     */
    @Scheduled(cron = "${coldchain.monitor-scheduler.cron:0 */5 * * * ?}")
    @SchedulerLock(
            name = "ColdChainMonitoringScheduler.deviationCheck",
            lockAtMostFor = "PT5M",
            lockAtLeastFor = "PT30S")
    public void deviationCheck() {
        if (!enabled) {
            log.debug("[ColdChainMonitoringScheduler] 已禁用 (enabled=false)");
            return;
        }

        log.info("[ColdChainMonitoringScheduler] 开始冷链偏离扫描");
        long startMs = System.currentTimeMillis();

        int equipmentChecked = 0;
        int alertsCreated = 0;
        int alertsSkippedDedup = 0;

        try {
            List<ColdChainEquipment> equipments = equipmentRepository.findByActiveTrue();
            for (ColdChainEquipment eq : equipments) {
                equipmentChecked++;
                AlertResult r = checkSingleEquipment(eq);
                if (r == AlertResult.CREATED) alertsCreated++;
                else if (r == AlertResult.SKIPPED_DEDUP) alertsSkippedDedup++;
            }
        } catch (Exception e) {
            log.error("[ColdChainMonitoringScheduler] 扫描异常: {}", e.getMessage(), e);
        }

        long duration = System.currentTimeMillis() - startMs;
        log.info("[ColdChainMonitoringScheduler] 扫描完成, 耗时 {}ms, " +
                        "检查设备 {} 台, 创建报警 {} 个, dedup 跳过 {} 个",
                duration, equipmentChecked, alertsCreated, alertsSkippedDedup);
    }

    private enum AlertResult { CREATED, SKIPPED_DEDUP, NO_DEVIATION, NO_DATA }

    private AlertResult checkSingleEquipment(ColdChainEquipment eq) {
        int toleranceMinutes = eq.getToleranceMinutes() != null
                ? eq.getToleranceMinutes() : 15;
        // 查容差窗口 + 5 min buffer, 覆盖足够数据点
        LocalDateTime since = LocalDateTime.now().minusMinutes(toleranceMinutes + 5L);
        List<ColdChainTempReading> readings = readingRepository
                .findByEquipmentIdAndRecordedAtAfterOrderByRecordedAtAsc(eq.getId(), since);

        if (readings.isEmpty()) {
            return AlertResult.NO_DATA;
        }

        // 判断是否持续偏离 (全部 reading 都 outside 区间)
        BigDecimal tempMin = eq.getTargetTempMin();
        BigDecimal tempMax = eq.getTargetTempMax();
        BigDecimal observedMin = null;
        BigDecimal observedMax = null;
        boolean allDeviating = true;
        LocalDateTime firstDeviationAt = null;
        LocalDateTime lastDeviationAt = null;

        for (ColdChainTempReading r : readings) {
            BigDecimal t = r.getTemperature();
            boolean isOutside = t.compareTo(tempMin) < 0 || t.compareTo(tempMax) > 0;
            if (!isOutside) {
                allDeviating = false;
                // 不连续偏离, scheduler 不报警 (短期波动)
            } else {
                if (firstDeviationAt == null) firstDeviationAt = r.getRecordedAt();
                lastDeviationAt = r.getRecordedAt();
                if (observedMin == null || t.compareTo(observedMin) < 0) observedMin = t;
                if (observedMax == null || t.compareTo(observedMax) > 0) observedMax = t;
                // Mark reading 偏离 (实际 prod IoT 应已 mark, polling 兜底)
                if (!r.isDeviation()) {
                    r.setDeviation(true);
                    readingRepository.save(r);
                }
            }
        }

        if (!allDeviating || firstDeviationAt == null) {
            return AlertResult.NO_DEVIATION;
        }

        long durationMinutes = Duration.between(firstDeviationAt, lastDeviationAt).toMinutes();
        if (durationMinutes < toleranceMinutes) {
            return AlertResult.NO_DEVIATION;  // 持续未超阈值
        }

        // 检查活跃报警 dedup — 同设备 OPEN/ACKNOWLEDGED 已存在则不重复创建
        List<ColdChainAlert> activeAlerts = alertRepository
                .findByEquipmentIdAndStatusInOrderByAlertTimeDesc(
                        eq.getId(), List.of("OPEN", "ACKNOWLEDGED"));
        if (!activeAlerts.isEmpty()) {
            return AlertResult.SKIPPED_DEDUP;
        }

        // 创建新 alert
        String severity = durationMinutes >= CRITICAL_THRESHOLD_MINUTES
                ? "CRITICAL" : "WARNING";

        ColdChainAlert alert = ColdChainAlert.builder()
                .factoryId(eq.getFactoryId())
                .equipmentId(eq.getId())
                .alertTime(LocalDateTime.now())
                .severity(severity)
                .minTemp(observedMin)
                .maxTemp(observedMax)
                .durationMinutes((int) durationMinutes)
                .status("OPEN")
                .build();
        alert = alertRepository.save(alert);

        // mark readings alertSent = true 避免重复
        for (ColdChainTempReading r : readings) {
            BigDecimal t = r.getTemperature();
            boolean isOutside = t.compareTo(tempMin) < 0 || t.compareTo(tempMax) > 0;
            if (isOutside && !r.isAlertSent()) {
                r.setAlertSent(true);
                readingRepository.save(r);
            }
        }

        notifyAlert(eq, alert);
        return AlertResult.CREATED;
    }

    private void notifyAlert(ColdChainEquipment eq, ColdChainAlert alert) {
        String emoji = "CRITICAL".equals(alert.getSeverity()) ? "⛔" : "🚨";
        String title = String.format("%s 冷链温控偏离 — %s (%s)",
                emoji, eq.getEquipmentCode(), eq.getEquipmentName());
        String body = String.format(
                "冷链温度偏离报警 (factory=%s)\n" +
                        "设备: %s (%s, %s)\n位置: %s\n" +
                        "目标温度: %s ~ %s%s\n" +
                        "偏离极值: %s ~ %s%s\n持续: %d min\n" +
                        "严重等级: %s\n报警 id: %d\n\n" +
                        "%s 请立即检查设备并确认报警原因",
                eq.getFactoryId(),
                eq.getEquipmentCode(), eq.getEquipmentName(), eq.getType(),
                eq.getLocation() != null ? eq.getLocation() : "-",
                eq.getTargetTempMin(), eq.getTargetTempMax(), eq.getUnit(),
                alert.getMinTemp(), alert.getMaxTemp(), eq.getUnit(),
                alert.getDurationMinutes(), alert.getSeverity(), alert.getId(),
                emoji);
        try {
            notificationService.notifyRole(eq.getFactoryId(), NOTIFY_ROLE, title, body);
        } catch (Exception e) {
            log.warn("[ColdChainMonitoringScheduler] 通知失败 (id={}): {}",
                    alert.getId(), e.getMessage());
        }
    }
}
