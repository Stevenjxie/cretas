package com.cretas.aims.repository.foodsafety;

import com.cretas.aims.entity.foodsafety.ColdChainTempReading;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Repository — Sprint 9 P2.D {@link ColdChainTempReading}.
 *
 * <p>高频时序数据. BaseEntity 软删除沿用通用规范.
 */
@Repository
public interface ColdChainTempReadingRepository extends JpaRepository<ColdChainTempReading, Long> {

    /** 设备 24h 曲线数据 — 按时间 ASC. */
    List<ColdChainTempReading> findByEquipmentIdAndRecordedAtAfterOrderByRecordedAtAsc(
            Long equipmentId, LocalDateTime since);

    /** 设备时间窗口内读数 (区间查询). */
    List<ColdChainTempReading> findByEquipmentIdAndRecordedAtBetweenOrderByRecordedAtAsc(
            Long equipmentId, LocalDateTime start, LocalDateTime end);

    /** 设备最新 N 个读数 (scheduler 偏离检测用 — 取最近 toleranceMinutes 区间). */
    List<ColdChainTempReading> findByEquipmentIdAndRecordedAtAfterOrderByRecordedAtDesc(
            Long equipmentId, LocalDateTime since);

    /** 工厂全部偏离未发报的 reading (定时巡检). */
    List<ColdChainTempReading> findByFactoryIdAndIsDeviationTrueAndAlertSentFalse(
            String factoryId);
}
