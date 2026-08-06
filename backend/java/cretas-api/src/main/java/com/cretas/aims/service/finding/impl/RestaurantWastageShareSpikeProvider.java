package com.cretas.aims.service.finding.impl;

import com.cretas.aims.service.finding.Finding;
import com.cretas.aims.service.finding.FindingProvider;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * R1 食材损耗离群：某食材的损耗份额相对自身基线放大。
 *
 * <p>口径完全在 Python 侧。分母用全店总损耗，所以「全店损耗一起跳 24 倍」
 * （2026-07-30 的数据回填）不会被误读成一堆食材异常；食材名单变了则由
 * Jaccard 闸挡下并诚实跳过。
 */
@Component
@RequiredArgsConstructor
public class RestaurantWastageShareSpikeProvider implements FindingProvider {

    private final RestaurantWastageFindingReader reader;

    @Override
    public String domain() {
        return "restaurant";
    }

    @Override
    public String ruleName() {
        return "食材损耗离群";
    }

    @Override
    public List<Finding> detect(String factoryId) {
        return reader.read(factoryId, "share_spike");
    }
}
