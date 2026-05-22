package com.cretas.aims.repository.indicator;

import com.cretas.aims.entity.indicator.IndicatorComputation;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * 指标计算策略 Repository — SpEL 公式 / Python 端点 / JPQL 命名查询。
 */
@Repository
public interface IndicatorComputationRepository extends JpaRepository<IndicatorComputation, String> {

    /** 按优先级排序 (priority 数字越小越优先, 用于主备 fallback)。 */
    List<IndicatorComputation> findByIndicatorIdAndIsActiveTrueOrderByPriorityAsc(String indicatorId);

    /** 主策略 (priority=1)。 */
    Optional<IndicatorComputation> findFirstByIndicatorIdAndIsActiveTrueOrderByPriorityAsc(String indicatorId);
}
