package com.cretas.aims.repository.foodsafety;

import com.cretas.aims.entity.foodsafety.ColdChainAlert;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Repository — Sprint 9 P2.D {@link ColdChainAlert}.
 *
 * <p>BaseEntity 软删除 via @Where 自动过滤 deleted_at IS NULL.
 */
@Repository
public interface ColdChainAlertRepository extends JpaRepository<ColdChainAlert, Long> {

    /** 工厂当前活跃报警 (OPEN/ACKNOWLEDGED) — 按 alertTime DESC. */
    List<ColdChainAlert> findByFactoryIdAndStatusInOrderByAlertTimeDesc(
            String factoryId, List<String> statuses);

    /** 设备活跃报警 (R4 幂等 dedup — 同设备 OPEN 已存在则不重复创建). */
    List<ColdChainAlert> findByEquipmentIdAndStatusInOrderByAlertTimeDesc(
            Long equipmentId, List<String> statuses);

    /** 报警统计 (dashboard). */
    long countByFactoryIdAndStatusAndAlertTimeAfter(
            String factoryId, String status, LocalDateTime since);

    /** 工厂月报 — 时间窗口内全部报警. */
    List<ColdChainAlert> findByFactoryIdAndAlertTimeBetweenOrderByAlertTimeDesc(
            String factoryId, LocalDateTime start, LocalDateTime end);
}
