package com.cretas.aims.ai.tool.impl.foodsafety;

import com.cretas.aims.ai.tool.AbstractBusinessTool;
import com.cretas.aims.entity.foodsafety.AdditiveLimit;
import com.cretas.aims.repository.foodsafety.AdditiveLimitRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * GB 2760-2014 添加剂智能匹配 Tool — Sprint 9 P2.F V2 (2026-05-21).
 *
 * <p>用户输入添加剂名称 (俗名 / 英文 / 化学名 / INS code 不全) → 自动找 GB 2760 entry.
 *
 * <p>匹配优先级:
 * <ol>
 *   <li>精确 additive_code 匹配 (e.g. "INS 250")</li>
 *   <li>精确 additive_name 匹配 (e.g. "亚硝酸钠")</li>
 *   <li>aliases JSONB 字段精确 / 模糊匹配 (e.g. "Sodium Nitrite" / "硝酸钠")</li>
 *   <li>additive_name LIKE 模糊匹配 (e.g. "亚硝")</li>
 *   <li>Levenshtein distance top 3 (拼写错误兜底)</li>
 * </ol>
 *
 * <p>跨食品类目 — 一个 additive_code 可能在多个类目下有不同 max_limit, 返回时聚合 foodCategories 列表.
 *
 * <p>read-only.
 *
 * <p><b>factoryId 隔离豁免说明</b>: 本 Tool 查询的是 GB 2760-2014 国家标准添加剂数据库
 * ({@code additive_limits} 表)。该表是全局参考数据，无 {@code factory_id} 列，天然不存在
 * 租户归属。所有工厂共享同一份国标数据，无需按工厂过滤。
 * {@code @FactoryIsolationExempt}
 *
 * <p>LLM 触发场景:
 * <ul>
 *   <li>"亚硝酸钠是什么 INS code"</li>
 *   <li>"Sodium Benzoate 在系统里有没有"</li>
 *   <li>"查一下 山梨酸钾 适用哪些食品类目"</li>
 *   <li>"我配方写的 'VC' 系统能识别吗"</li>
 * </ul>
 *
 * <p>Intent Code: {@code ADDITIVE_SMART_MATCH}
 *
 * @author Cretas Team / Sprint 9 P2.F
 * @since 2026-05-21
 */
@Slf4j
@Component
public class AdditiveSmartMatchTool extends AbstractBusinessTool {

    /** Levenshtein 距离阈值 (相对长度的比例). 距离 / max(len1, len2) ≤ 此值才视为匹配. */
    private static final double LEVENSHTEIN_RATIO_THRESHOLD = 0.5;

    /** Levenshtein top-N 结果上限. */
    private static final int LEVENSHTEIN_TOP_N = 3;

    @Autowired
    private AdditiveLimitRepository additiveLimitRepository;

    @Override
    public String getToolName() {
        return "additive_smart_match";
    }

    @Override
    public String getDescription() {
        return "GB 2760-2014 添加剂智能匹配 — 用户输入俗名/英文/化学名/INS code 不全, "
                + "Tool 自动找 GB 2760 entry (按优先级 code → name → aliases → fuzzy LIKE → Levenshtein). "
                + "返聚合 matches 列表 (含 foodCategories 多类目). "
                + "LLM 触发: '亚硝酸钠是什么 INS code' / 'Sodium Benzoate 在系统里有没有' / "
                + "'查 山梨酸钾 适用哪些类目' / 'VC 系统能识别吗'. read-only.";
    }

    @Override
    public Map<String, Object> getParametersSchema() {
        Map<String, Object> schema = new HashMap<>();
        schema.put("type", "object");
        Map<String, Object> properties = new HashMap<>();

        Map<String, Object> additiveQuery = new HashMap<>();
        additiveQuery.put("type", "string");
        additiveQuery.put("description",
                "用户原文输入的添加剂名称 (任意形式 — 中文 / 英文 / INS code / 俗名), e.g. " +
                "'亚硝酸钠' / 'Sodium Benzoate' / 'INS 250' / 'VC' / 'MSG'");
        properties.put("additiveQuery", additiveQuery);

        schema.put("properties", properties);
        schema.put("required", List.of("additiveQuery"));
        return schema;
    }

    @Override
    protected List<String> getRequiredParameters() {
        return List.of("additiveQuery");
    }

    @Override
    protected Map<String, Object> doExecute(String factoryId,
            Map<String, Object> params, Map<String, Object> context) throws Exception {
        String query = getString(params, "additiveQuery");
        if (query == null || query.trim().isEmpty()) {
            return buildSimpleResult(
                    "⚠️ 请提供添加剂名称 (中文 / 英文 / INS code 都可)",
                    Map.of("matches", List.of(), "matchStrategy", "EMPTY_QUERY"));
        }
        String q = query.trim();

        log.info("additive_smart_match — factory={} query={}", factoryId, q);

        // 用 LinkedHashSet 保插入顺序 + dedup (按 additiveCode + foodCategory 组合)
        Map<String, AggregatedMatch> aggregated = new LinkedHashMap<>();
        String strategyHit = null;

        // Strategy 1: 精确 additive_code (e.g. "INS 250")
        List<AdditiveLimit> byCode = additiveLimitRepository.findByAdditiveCodeAndActiveTrue(q);
        if (!byCode.isEmpty()) {
            for (AdditiveLimit l : byCode) {
                addOrMerge(aggregated, l);
            }
            strategyHit = "EXACT_CODE";
        }

        // Strategy 2: 精确 additive_name (e.g. "亚硝酸钠")
        if (aggregated.isEmpty()) {
            List<AdditiveLimit> byName = additiveLimitRepository.findByAdditiveNameAndActiveTrue(q);
            for (AdditiveLimit l : byName) {
                addOrMerge(aggregated, l);
            }
            if (!aggregated.isEmpty()) {
                strategyHit = "EXACT_NAME";
            }
        }

        // Strategy 3: aliases JSONB 精确/模糊匹配 (e.g. "Sodium Nitrite" / "MSG" / "VC")
        if (aggregated.isEmpty()) {
            try {
                List<AdditiveLimit> byAlias = additiveLimitRepository.findByAliasContaining(q);
                for (AdditiveLimit l : byAlias) {
                    addOrMerge(aggregated, l);
                }
                if (!aggregated.isEmpty()) {
                    strategyHit = "ALIAS_MATCH";
                }
            } catch (Exception e) {
                // PG JSONB ?? operator 个别 driver 兼容性差; 失败回退到下一策略
                log.warn("aliases JSONB query failed (fallback to name LIKE): {}", e.getMessage());
            }
        }

        // Strategy 4: additive_name LIKE 模糊匹配 (e.g. "亚硝" / "酱油")
        if (aggregated.isEmpty()) {
            List<AdditiveLimit> byNameLike = additiveLimitRepository
                    .findByAdditiveNameContainingAndActiveTrue(q);
            for (AdditiveLimit l : byNameLike) {
                addOrMerge(aggregated, l);
            }
            if (!aggregated.isEmpty()) {
                strategyHit = "NAME_FUZZY";
            }
        }

        // Strategy 5: Levenshtein top-3 (拼写错误 / typo 兜底)
        if (aggregated.isEmpty()) {
            List<AdditiveLimit> all = additiveLimitRepository.findAllActiveForFuzzy();
            List<RankedMatch> ranked = new ArrayList<>();
            for (AdditiveLimit l : all) {
                int d1 = levenshteinDistance(q.toLowerCase(), l.getAdditiveName().toLowerCase());
                int d2 = l.getAliases() != null ? l.getAliases().stream()
                        .mapToInt(a -> levenshteinDistance(q.toLowerCase(), a.toLowerCase()))
                        .min().orElse(Integer.MAX_VALUE)
                        : Integer.MAX_VALUE;
                int dist = Math.min(d1, d2);
                int maxLen = Math.max(q.length(),
                        Math.max(l.getAdditiveName().length(),
                                l.getAliases() != null ? l.getAliases().stream()
                                        .mapToInt(String::length).max().orElse(0) : 0));
                double ratio = maxLen == 0 ? 1.0 : (double) dist / maxLen;
                if (ratio <= LEVENSHTEIN_RATIO_THRESHOLD) {
                    ranked.add(new RankedMatch(l, dist, ratio));
                }
            }
            ranked.sort(Comparator.comparingInt(r -> r.distance));
            for (int i = 0; i < Math.min(LEVENSHTEIN_TOP_N, ranked.size()); i++) {
                addOrMerge(aggregated, ranked.get(i).limit);
            }
            if (!aggregated.isEmpty()) {
                strategyHit = "LEVENSHTEIN_FUZZY";
            }
        }

        // 构建响应
        List<Map<String, Object>> matches = new ArrayList<>();
        for (AggregatedMatch am : aggregated.values()) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("additiveCode", am.additiveCode);
            row.put("additiveName", am.additiveName);
            row.put("aliases", am.aliases);
            row.put("foodCategories", am.foodCategories);  // 跨类目聚合
            row.put("limitDetails", am.limitDetails);  // 每类目的 max_limit + unit + regulation
            matches.add(row);
        }

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("query", q);
        data.put("matchStrategy", strategyHit != null ? strategyHit : "NO_MATCH");
        data.put("matchCount", matches.size());
        data.put("matches", matches);
        data.put("actionHint", "/quality/additive-compliance?query=" + q);

        String message;
        if (matches.isEmpty()) {
            message = String.format(
                    "❓ 未在 GB 2760-2014 找到 '%s'. 请确认拼写, 或人工核对 (可能国标未收录)",
                    q);
        } else if (matches.size() == 1) {
            AggregatedMatch only = aggregated.values().iterator().next();
            message = String.format(
                    "✅ '%s' 匹配到: %s (%s) — 适用 %d 个食品类目. 详见 matches 字段 [策略: %s]",
                    q, only.additiveName, only.additiveCode, only.foodCategories.size(), strategyHit);
        } else {
            message = String.format(
                    "🔍 '%s' 找到 %d 个候选添加剂 (跨类目聚合). 请确认具体哪一项 [策略: %s]. 详见 matches 字段",
                    q, matches.size(), strategyHit);
        }
        return buildSimpleResult(message, data);
    }

    /** 把 AdditiveLimit 添加到聚合 map (按 additive_code 聚合, 跨食品类目). */
    private void addOrMerge(Map<String, AggregatedMatch> aggregated, AdditiveLimit limit) {
        AggregatedMatch am = aggregated.computeIfAbsent(limit.getAdditiveCode(),
                k -> new AggregatedMatch(limit.getAdditiveCode(), limit.getAdditiveName(),
                        limit.getAliases() != null ? new ArrayList<>(limit.getAliases()) : new ArrayList<>()));
        // 合并 foodCategory + limit detail
        if (!am.foodCategories.contains(limit.getFoodCategory())) {
            am.foodCategories.add(limit.getFoodCategory());
        }
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("foodCategory", limit.getFoodCategory());
        detail.put("maxLimit", limit.getMaxLimit());
        detail.put("unit", limit.getUnit());
        detail.put("regulationRef", limit.getRegulationRef());
        am.limitDetails.add(detail);
    }

    /**
     * 简单 Levenshtein 距离 (避免引入 commons-text 依赖).
     * 时间 O(m*n), 空间 O(min(m,n)). 适合 ≤200 字符短输入.
     */
    private int levenshteinDistance(String a, String b) {
        if (a == null) a = "";
        if (b == null) b = "";
        if (a.equals(b)) return 0;
        if (a.isEmpty()) return b.length();
        if (b.isEmpty()) return a.length();

        int[] prev = new int[b.length() + 1];
        int[] curr = new int[b.length() + 1];
        for (int j = 0; j <= b.length(); j++) prev[j] = j;

        for (int i = 1; i <= a.length(); i++) {
            curr[0] = i;
            for (int j = 1; j <= b.length(); j++) {
                int cost = a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1;
                curr[j] = Math.min(
                        Math.min(curr[j - 1] + 1, prev[j] + 1),
                        prev[j - 1] + cost);
            }
            System.arraycopy(curr, 0, prev, 0, b.length() + 1);
        }
        return prev[b.length()];
    }

    /** Aggregated by additive_code (跨食品类目). */
    private static class AggregatedMatch {
        final String additiveCode;
        final String additiveName;
        final List<String> aliases;
        final List<String> foodCategories = new ArrayList<>();
        final List<Map<String, Object>> limitDetails = new ArrayList<>();

        AggregatedMatch(String code, String name, List<String> aliases) {
            this.additiveCode = code;
            this.additiveName = name;
            this.aliases = aliases;
        }
    }

    /** Levenshtein ranked candidate. */
    private static class RankedMatch {
        final AdditiveLimit limit;
        final int distance;
        final double ratio;

        RankedMatch(AdditiveLimit limit, int distance, double ratio) {
            this.limit = limit;
            this.distance = distance;
            this.ratio = ratio;
        }
    }

    /** spec §8.2 只读查询, 无副作用 */
    @Override
    public AccessMode getAccessMode() {
        return AccessMode.READ;
    }
}
