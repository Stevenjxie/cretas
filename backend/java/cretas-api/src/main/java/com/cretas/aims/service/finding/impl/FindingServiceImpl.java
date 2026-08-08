package com.cretas.aims.service.finding.impl;

import com.cretas.aims.service.finding.Finding;
import com.cretas.aims.service.finding.FindingNotApplicableException;
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
 * 「已检查 X，均正常」的假话（禁止降级处理）。规则失败会被记录进
 * {@link FindingService.Result#failedRules()}，供消费方用
 * {@link FindingService.Result#complete()} 判断本次结果是否完整——
 * 不完整时不得把 countsByCode / findings 当作「已确认无异常」来展示。
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
        return detectInline(factoryId, java.util.List.of(domain));
    }

    @Override
    public Result detectInline(String factoryId, java.util.Collection<String> domains) {
        List<Finding> all = new ArrayList<>();
        List<String> checked = new ArrayList<>();
        List<String> failed = new ArrayList<>();
        List<SkippedRule> skipped = new ArrayList<>();

        for (FindingProvider provider : providers) {
            if (!domains.contains(provider.domain())) {
                continue;
            }
            try {
                all.addAll(provider.detect(factoryId));
                checked.add(provider.ruleName());
            } catch (FindingNotApplicableException notApplicable) {
                // 数据不足以判断 —— 诚实跳过, 不是故障。必须在 catch(Exception)
                // 之前, 否则会被下面那条当成失败吞掉, 用户看到的就成了「服务坏了」。
                log.info("Finding 规则数据不足, 诚实跳过: rule={}, domain={}, factoryId={}, reason={}",
                        provider.ruleName(), provider.domain(), factoryId, notApplicable.reason());
                skipped.add(new SkippedRule(provider.ruleName(), notApplicable.reason()));
            } catch (Exception e) {
                log.warn("Finding 规则执行失败, 已从 checkedRules 剔除: rule={}, domain={}, factoryId={}",
                        provider.ruleName(), provider.domain(), factoryId, e);
                failed.add(provider.ruleName());
            }
        }

        Map<String, Integer> countsByCode = new LinkedHashMap<>();
        for (Finding f : all) {
            countsByCode.merge(f.code(), 1, Integer::sum);
        }

        all.sort(Comparator.comparingInt(Finding::rankScore).reversed());
        int total = all.size();
        List<Finding> top = total > inlineMax ? all.subList(0, inlineMax) : all;

        return new Result(List.copyOf(top), List.copyOf(checked), total,
                Map.copyOf(countsByCode), List.copyOf(failed), List.copyOf(skipped));
    }
}
