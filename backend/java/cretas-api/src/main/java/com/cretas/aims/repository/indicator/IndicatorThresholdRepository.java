package com.cretas.aims.repository.indicator;

import com.cretas.aims.entity.indicator.IndicatorThreshold;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * 指标阈值 Repository — GREEN / YELLOW / RED 三色预警配置。
 */
@Repository
public interface IndicatorThresholdRepository extends JpaRepository<IndicatorThreshold, String> {

    /** 指标的全部活跃阈值。 */
    List<IndicatorThreshold> findByIndicatorIdAndIsActiveTrue(String indicatorId);

    /** 工厂内全部阈值 (admin 视角列表)。 */
    List<IndicatorThreshold> findByFactoryIdAndIsActiveTrue(String factoryId);
}
