package com.cretas.aims.repository;

import com.cretas.aims.entity.OpportunityStageHistory;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * 商机阶段变更历史 Repository — Sprint 7 wave 2 T4 (2026-05-20).
 */
@Repository
public interface OpportunityStageHistoryRepository
        extends JpaRepository<OpportunityStageHistory, String> {

    /** 单商机 history 列表 (DESC by changed_at). */
    List<OpportunityStageHistory> findByOpportunityIdAndDeletedAtIsNullOrderByChangedAtDesc(
            String opportunityId);

    /** factory + opportunity scope. */
    List<OpportunityStageHistory> findByFactoryIdAndOpportunityIdAndDeletedAtIsNullOrderByChangedAtDesc(
            String factoryId, String opportunityId);
}
