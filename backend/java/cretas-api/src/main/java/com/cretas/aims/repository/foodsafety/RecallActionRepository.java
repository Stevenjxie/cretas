package com.cretas.aims.repository.foodsafety;

import com.cretas.aims.entity.foodsafety.RecallAction;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * Repository — Sprint 8 P3 Phase A {@link RecallAction}.
 *
 * <p>主要查询: 按 recall_event_id 找该事件全部行动 (UI dashboard timeline view).
 */
@Repository
public interface RecallActionRepository extends JpaRepository<RecallAction, Long> {

    /** 按 recall_event_id 找全部 actions, 时间正序 (timeline). */
    List<RecallAction> findByRecallEventIdOrderByCreatedAtAsc(Long recallEventId);
}
