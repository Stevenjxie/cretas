package com.cretas.aims.repository.alerts;

import com.cretas.aims.entity.alerts.AlertEvent;
import com.cretas.aims.entity.alerts.AlertEventStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/**
 * AlertEvent 数据访问 — Phase 2 Canvas-Alerts.
 *
 * @since 2026-05-18 (Phase 2 impl)
 */
@Repository
public interface AlertEventRepository extends JpaRepository<AlertEvent, UUID> {

    /** 按 status 列出事件 — dashboard / event-history 主查询. */
    Page<AlertEvent> findByFactoryIdAndStatusOrderByCreatedAtDesc(
            String factoryId, AlertEventStatus status, Pageable pageable);

    /** 列工厂全部事件 (无 status 过滤), 用于历史归档查询. */
    Page<AlertEvent> findByFactoryIdOrderByCreatedAtDesc(String factoryId, Pageable pageable);

    /** 单规则在指定 status 下的 count — 仪表板 KPI. */
    long countByRuleIdAndStatus(UUID ruleId, AlertEventStatus status);

    /** 单工厂在指定 status 下的 count — dashboard 总数. */
    long countByFactoryIdAndStatus(String factoryId, AlertEventStatus status);

    /**
     * 幂等 dedup query — 找同 (rule, business entity) 在 since 时间后的非 RESOLVED 事件.
     *
     * <p>用于 {@code triggerAlert} 防重复创建: 若同一规则在 1 小时窗口内已经为
     * 同一业务实体创建过 OPEN/ACKNOWLEDGED 事件, 跳过新建.
     */
    List<AlertEvent> findByRuleIdAndBusinessEntityIdAndStatusInAndCreatedAtAfter(
            UUID ruleId, String businessEntityId, List<AlertEventStatus> statuses,
            LocalDateTime since);
}
