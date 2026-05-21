package com.cretas.aims.repository.foodsafety;

import com.cretas.aims.entity.foodsafety.RecallEvent;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

/**
 * Repository — Sprint 8 P3 Phase A {@link RecallEvent}.
 *
 * <p>UI dashboard 主要查询: factory 召回事件列表 (倒序按 trigger_time).
 */
@Repository
public interface RecallEventRepository extends JpaRepository<RecallEvent, Long> {

    /** factory 召回事件分页查询, 倒序按触发时间. */
    Page<RecallEvent> findByFactoryIdOrderByTriggerTimeDesc(String factoryId, Pageable pageable);

    /** 按 event_code 全局唯一查找 (e.g. "RECALL-20260801-001"). */
    Optional<RecallEvent> findByEventCode(String eventCode);
}
