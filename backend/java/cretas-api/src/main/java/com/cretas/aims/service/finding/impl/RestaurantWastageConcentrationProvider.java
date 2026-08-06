package com.cretas.aims.service.finding.impl;

import com.cretas.aims.service.finding.Finding;
import com.cretas.aims.service.finding.FindingProvider;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * R2 损耗类型集中度：单一**可行动**损耗类型占比过高。
 *
 * <p>单窗口无基线，任何租户第一天可用。「哪些类型可行动」的配置
 * ({@code ACTIONABLE_WASTAGE_TYPES}) 只存在于 Python 侧那一处 ——
 * 加工损耗是切配常态，店长知道也动不了，报它是噪音。
 */
@Component
@RequiredArgsConstructor
public class RestaurantWastageConcentrationProvider implements FindingProvider {

    private final RestaurantWastageFindingReader reader;

    @Override
    public String domain() {
        return "restaurant";
    }

    @Override
    public String ruleName() {
        return "损耗类型集中度";
    }

    @Override
    public List<Finding> detect(String factoryId) {
        return reader.read(factoryId, "type_concentration");
    }
}
