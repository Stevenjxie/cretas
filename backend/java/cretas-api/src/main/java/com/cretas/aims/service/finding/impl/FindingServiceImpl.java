package com.cretas.aims.service.finding.impl;

import com.cretas.aims.service.finding.Finding;
import com.cretas.aims.service.finding.FindingProvider;
import com.cretas.aims.service.finding.FindingService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 发现层实现：收集同 domain 的 provider、按 rankScore 排序、截断到 inline 上限。
 *
 * <p>单条 provider 抛异常时**隔离**：不中断其他规则，且把该规则从
 * checkedRules 里剔除。禁止把失败当作「无异常」——那会让 UI 说出
 * 「已检查 X，均正常」的假话（禁止降级处理）。
 */
@Slf4j
@Service
public class FindingServiceImpl implements FindingService {

    private final List<FindingProvider> providers;
    private final int inlineMax;

    public FindingServiceImpl(List<FindingProvider> providers,
                              @Value("${cretas.finding.inline-max:2}") int inlineMax) {
        this.providers = providers;
        this.inlineMax = inlineMax;
    }

    @Override
    public Result detectInline(String factoryId, String domain) {
        List<Finding> all = new ArrayList<>();
        List<String> checked = new ArrayList<>();

        for (FindingProvider provider : providers) {
            if (!provider.domain().equals(domain)) {
                continue;
            }
            try {
                all.addAll(provider.detect(factoryId));
                checked.add(provider.ruleName());
            } catch (Exception e) {
                log.warn("Finding 规则执行失败, 已从 checkedRules 剔除: rule={}, domain={}, factoryId={}",
                        provider.ruleName(), domain, factoryId, e);
            }
        }

        Map<String, Integer> countsByCode = new LinkedHashMap<>();
        for (Finding f : all) {
            countsByCode.merge(f.code(), 1, Integer::sum);
        }

        all.sort(Comparator.comparingInt(Finding::rankScore).reversed());
        int total = all.size();
        List<Finding> top = total > inlineMax ? all.subList(0, inlineMax) : all;

        return new Result(List.copyOf(top), List.copyOf(checked), total, Map.copyOf(countsByCode));
    }
}
