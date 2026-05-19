package com.cretas.aims.repository.pricing;

import com.cretas.aims.entity.pricing.PricingApplicationLog;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/**
 * 价格策略应用日志 Repo — Canvas-Pricing Phase 4b.
 *
 * <p>主要用途: finance 月度审计 cost-violation SO 行 (per spec §4 fool-proof rule).
 */
@Repository
public interface PricingApplicationLogRepository extends JpaRepository<PricingApplicationLog, String> {

    Page<PricingApplicationLog> findByFactoryIdAndBusinessEntityIdOrderByAppliedAtDesc(
            String factoryId, String businessEntityId, Pageable pageable
    );

    Page<PricingApplicationLog> findByFactoryIdOrderByAppliedAtDesc(
            String factoryId, Pageable pageable
    );
}
