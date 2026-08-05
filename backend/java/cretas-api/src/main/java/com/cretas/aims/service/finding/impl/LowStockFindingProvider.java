package com.cretas.aims.service.finding.impl;

import com.cretas.aims.service.MaterialBatchService;
import com.cretas.aims.service.finding.Finding;
import com.cretas.aims.service.finding.FindingProvider;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 低库存发现规则。
 *
 * <p>口径**完全**来自 {@link MaterialBatchService#getLowStockWarnings(String)}
 * （materialType 级，对比 RawMaterialType.minStock）。本类只做形状转换，
 * 不做任何判定 —— 这样它跟 web-admin 的「低库存预警」KPI 卡片永远一致。
 */
@Component
@RequiredArgsConstructor
public class LowStockFindingProvider implements FindingProvider {

    /**
     * 低库存的可行动性。v1 恒定 50：所有低库存的处置动作都是「去补货」，
     * 彼此之间没有可区分的紧迫度差异。等临期（今天不用就废，高）和呆滞
     * （随时可处理，低）两个 provider 进来后，这个常量才有对比意义。
     */
    private static final int ACTIONABILITY = 50;

    private final MaterialBatchService materialBatchService;

    @Override
    public String domain() {
        return "inventory";
    }

    @Override
    public String ruleName() {
        return "低库存";
    }

    @Override
    public List<Finding> detect(String factoryId) {
        List<Map<String, Object>> warnings = materialBatchService.getLowStockWarnings(factoryId);
        List<Finding> findings = new ArrayList<>();
        for (Map<String, Object> w : warnings) {
            Map<String, Object> facts = new LinkedHashMap<>();
            facts.put("currentStock", w.get("currentStock"));
            facts.put("safetyStock", w.get("safetyStock"));
            facts.put("gap", w.get("gap"));
            facts.put("unit", w.get("unit"));
            facts.put("stockRatio", w.get("stockRatio"));
            findings.add(new Finding(
                    "LOW_STOCK",
                    "inventory",
                    toSeverity((String) w.get("warningLevel")),
                    ACTIONABILITY,
                    (String) w.get("materialTypeId"),
                    (String) w.get("materialName"),
                    facts));
        }
        return findings;
    }

    private Finding.Severity toSeverity(String warningLevel) {
        if ("CRITICAL".equals(warningLevel)) {
            return Finding.Severity.CRITICAL;
        }
        if ("WARNING".equals(warningLevel)) {
            return Finding.Severity.WARNING;
        }
        return Finding.Severity.INFO;
    }
}
