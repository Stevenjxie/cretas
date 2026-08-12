package com.cretas.aims.service.finding.impl;

import com.cretas.aims.repository.MaterialBatchRepository;
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
    private final MaterialBatchRepository materialBatchRepository;

    @Override
    public String domain() {
        return "inventory";
    }

    @Override
    public String ruleName() {
        return "低库存";
    }

    /**
     * 只保留**进过货**的物料。
     *
     * <p>🔴 2026-08-12 prod 实测（cretas_prod_db，库名取自活 jar 进程 environ）：
     * MOCK_REST 的 25 个物料里 24 个有进货历史，只有「罗氏虾」一条批次都没有，
     * 却挂着安全线 2288.42 —— 于是每条回答末尾都在报
     * 「罗氏虾 剩 0kg，低于安全线 2288.42kg（缺 2288.42kg）」，缺口恰等于安全线全额。
     * 那是**种子数据残留**，不是缺货。上一轮 LLM-judge 量出的「同一条发现重复 19 次、
     * 命中率 100%」，来源就是它；而在所有样本上都响的东西不区分好坏。
     *
     * <p>⛔ **判据是「有没有进货历史」，不是「当前余额是不是 0」**：真缺货也是 0，
     * 拿余额消音会把真信号一起干掉。这里用的是
     * {@link MaterialBatchRepository#findMaterialTypeIdsEverStocked}（不看状态、
     * 不看余量），所以「买过、用光了」照旧报。
     *
     * <p>⛔ 修在 provider 侧不修 {@code getLowStockWarnings}：那个方法有 5 个消费者，
     * 其中 4 个是工厂端 Tool（本轮红线：工厂端不碰）。
     */
    @Override
    public List<Finding> detect(String factoryId) {
        List<Map<String, Object>> warnings = materialBatchService.getLowStockWarnings(factoryId);
        java.util.Set<String> everStocked = new java.util.HashSet<>(
                materialBatchRepository.findMaterialTypeIdsEverStocked(factoryId));
        List<Finding> findings = new ArrayList<>();
        for (Map<String, Object> w : warnings) {
            if (!everStocked.contains((String) w.get("materialTypeId"))) {
                continue;
            }
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
