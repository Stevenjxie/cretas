package com.cretas.aims.service.finding;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 记录并回答：**这条提醒已经连续出现几天了**。
 *
 * <p>🔴 Steve 2026-08-08 定的方向。我原本提「同一条发现 7 天内静默」，他纠正：
 * 「静默的话为什么要静默呢，只要问就是有问题啊」—— 他是对的，按时间压制会在
 * 老板最想知道的那一刻压制。但第八天还说一模一样的话也是浪费。
 * ⇒ **重复不消除，措辞升级**：重复本身变成信息（你已经被提醒 8 天还没动）。
 *
 * <p>⛔ 语义边界，渲染层必须逐字对齐：这里记的是「**这条提醒连续出现了几天**」，
 * <b>不是</b>「这道菜连续几天卖不动」。后者要按天回算规则、代价太大；而且前者
 * 更有用 —— 它说的是老板的不作为，那才是可行动的。说成后者就是编。
 *
 * <p>⚠️ 只由**同步顺带提示**这一个出口调用。其余 4 个消费者（物料工具 /
 * 损耗工具 / REST 端点 / 行动方案）不计入，否则「提醒了几天」会被后台调用污染。
 *
 * <p>⚠️ 记录失败**绝不能**让顺带提示挂掉：这是附加信息，不是主答案。
 * 任何异常一律吞掉并留日志，返回空的天数表（渲染层就当没有这条信息）。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class FindingOccurrenceTracker {

    /**
     * 连续性的容忍间隔（天）。
     *
     * <p>1 = 昨天见过才算连续。为什么不放宽：老板周末不问问题很正常，放宽到 3 天
     * 会让「周一问一次、周四问一次」被说成连续 4 天 —— 那是假的。
     * ⛔ 宁可少说，不可虚报：这个数字会出现在给老板看的措辞里。
     */
    private static final int CONTINUITY_GAP_DAYS = 1;

    /** 低于这个天数不提「连续」—— 第 2 天就说「已连续 2 天」是噪音。 */
    public static final int MIN_DAYS_WORTH_SAYING = 3;

    private final JdbcTemplate jdbcTemplate;

    /**
     * 记录这批发现今天出现过，并返回每条的连续天数。
     *
     * @return key = {@code code + "|" + subjectId}，value = 连续出现天数（>=1）。
     *         出错时返回空表 —— 调用方据此当作「没有这条信息」，照常出提示。
     */
    public Map<String, Integer> recordAndCountConsecutiveDays(
            String factoryId, List<Finding> findings) {
        Map<String, Integer> days = new HashMap<>();
        if (factoryId == null || findings == null || findings.isEmpty()) {
            return days;
        }
        LocalDate today = LocalDate.now();
        for (Finding f : findings) {
            String subject = f.subjectId() == null ? "" : f.subjectId();
            try {
                days.put(key(f.code(), subject), upsertOne(factoryId, f.code(), subject, today));
            } catch (Exception e) {
                // 附加信息坏了不该拖垮主提示。⛔ 不重试、不抛出。
                log.warn("[FindingOccurrence] 记录失败, 本条不带连续天数: factoryId={}, code={}, subject={}",
                        factoryId, f.code(), subject, e);
            }
        }
        return days;
    }

    /** 拼 key 的唯一处 —— 调用方也用它，避免两处拼法不一致。 */
    public static String key(String code, String subjectId) {
        return code + "|" + (subjectId == null ? "" : subjectId);
    }

    private int upsertOne(String factoryId, String code, String subjectId, LocalDate today) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT first_seen_on, last_seen_on, seen_days FROM finding_occurrence "
                        + "WHERE factory_id = ? AND code = ? AND subject_id = ?",
                factoryId, code, subjectId);

        if (rows.isEmpty()) {
            jdbcTemplate.update(
                    "INSERT INTO finding_occurrence "
                            + "(factory_id, code, subject_id, first_seen_on, last_seen_on, seen_days) "
                            + "VALUES (?, ?, ?, ?, ?, 1) "
                            + "ON CONFLICT (factory_id, code, subject_id) DO NOTHING",
                    factoryId, code, subjectId, today, today);
            return 1;
        }

        Map<String, Object> row = rows.get(0);
        LocalDate lastSeen = ((java.sql.Date) row.get("last_seen_on")).toLocalDate();
        int seenDays = ((Number) row.get("seen_days")).intValue();

        if (lastSeen.equals(today)) {
            // 同一天问第二次 —— 不加天数。⛔ 否则「问了十次」会变成「提醒了十天」。
            return seenDays;
        }
        boolean continuous = lastSeen.plusDays(CONTINUITY_GAP_DAYS).compareTo(today) >= 0;
        int nextDays = continuous ? seenDays + 1 : 1;
        jdbcTemplate.update(
                "UPDATE finding_occurrence SET last_seen_on = ?, seen_days = ?, "
                        + "first_seen_on = CASE WHEN ? THEN first_seen_on ELSE ? END, "
                        + "updated_at = NOW() "
                        + "WHERE factory_id = ? AND code = ? AND subject_id = ?",
                today, nextDays, continuous, today, factoryId, code, subjectId);
        return nextDays;
    }
}
