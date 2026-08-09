package com.cretas.aims.service.finding;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.Date;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * 连续提醒天数的行为约束。
 *
 * <p>🔴 Steve 2026-08-08 定的方向：「静默的话为什么要静默呢，只要问就是有问题啊」
 * —— 重复不消除，措辞升级。这个类负责回答「这条提醒连续出现了几天」。
 *
 * <p>⛔ 三条最容易写错的，逐条钉住：
 * <ul>
 *   <li>同一天问十次不能变成「提醒了十天」</li>
 *   <li>中断之后要**重新计数**，否则「连续」是假的</li>
 *   <li>记录失败不能拖垮主提示</li>
 * </ul>
 */
class FindingOccurrenceTrackerTest {

    private static final String FACTORY = "MOCK_REST";

    private JdbcTemplate jdbc;
    private FindingOccurrenceTracker tracker;

    @BeforeEach
    void setUp() {
        jdbc = mock(JdbcTemplate.class);
        tracker = new FindingOccurrenceTracker(jdbc);
    }

    private static Finding finding(String code, String subjectId) {
        return new Finding(code, "restaurant", Finding.Severity.WARNING, 75,
                subjectId, subjectId, Map.of());
    }

    private void existingRow(LocalDate lastSeen, int seenDays) {
        Map<String, Object> row = new HashMap<>();
        row.put("first_seen_on", Date.valueOf(lastSeen.minusDays(seenDays - 1)));
        row.put("last_seen_on", Date.valueOf(lastSeen));
        row.put("seen_days", seenDays);
        List<Map<String, Object>> rows = new ArrayList<>();
        rows.add(row);
        when(jdbc.queryForList(anyString(), any(Object[].class))).thenReturn(rows);
    }

    @Test
    @DisplayName("首次出现 = 1 天, 并插入一行")
    void firstSightingCountsAsOne() {
        when(jdbc.queryForList(anyString(), any(Object[].class))).thenReturn(List.of());

        Map<String, Integer> days = tracker.recordAndCountConsecutiveDays(
                FACTORY, List.of(finding("DISH_PUZZLE", "罗氏虾")));

        assertEquals(1, days.get(FindingOccurrenceTracker.key("DISH_PUZZLE", "罗氏虾")));
        verify(jdbc).update(contains("INSERT INTO finding_occurrence"),
                any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("🔴 同一天问第二次, 天数**不加** —— 问十次不是提醒十天")
    void askingTwiceInOneDayDoesNotBumpTheCount() {
        existingRow(LocalDate.now(), 5);

        Map<String, Integer> days = tracker.recordAndCountConsecutiveDays(
                FACTORY, List.of(finding("DISH_PUZZLE", "罗氏虾")));

        assertEquals(5, days.get(FindingOccurrenceTracker.key("DISH_PUZZLE", "罗氏虾")));
        verify(jdbc, never()).update(contains("UPDATE finding_occurrence"),
                any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("昨天见过 -> 连续, 天数 +1")
    void seenYesterdayContinues() {
        existingRow(LocalDate.now().minusDays(1), 5);

        Map<String, Integer> days = tracker.recordAndCountConsecutiveDays(
                FACTORY, List.of(finding("DISH_PUZZLE", "罗氏虾")));

        assertEquals(6, days.get(FindingOccurrenceTracker.key("DISH_PUZZLE", "罗氏虾")));
    }

    @Test
    @DisplayName("🔴 中断 3 天再出现 -> **重新从 1 开始**, 不是接着数")
    void aGapResetsTheStreak() {
        // ⛔ 不重置就会把「周一问一次、周四问一次」说成「连续 4 天」——
        //    那是假的, 而这个数字会出现在给老板看的措辞里。
        //    宁可少说, 不可虚报。
        existingRow(LocalDate.now().minusDays(3), 9);

        Map<String, Integer> days = tracker.recordAndCountConsecutiveDays(
                FACTORY, List.of(finding("DISH_PUZZLE", "罗氏虾")));

        assertEquals(1, days.get(FindingOccurrenceTracker.key("DISH_PUZZLE", "罗氏虾")),
                "中断过就不算连续, 必须从 1 重来");
    }

    @Test
    @DisplayName("⛔ 记录失败不抛出 —— 附加信息坏了不该拖垮主提示")
    void trackingFailureIsSwallowed() {
        when(jdbc.queryForList(anyString(), any(Object[].class)))
                .thenThrow(new RuntimeException("表不存在"));

        Map<String, Integer> days = assertDoesNotThrow(() ->
                tracker.recordAndCountConsecutiveDays(
                        FACTORY, List.of(finding("DISH_PUZZLE", "罗氏虾"))));

        assertTrue(days.isEmpty(), "出错时返回空表, 调用方当作没有这条信息");
    }

    @Test
    @DisplayName("同一规则指向不同对象, 各自独立计数")
    void differentSubjectsCountSeparately() {
        when(jdbc.queryForList(anyString(), any(Object[].class))).thenReturn(List.of());

        Map<String, Integer> days = tracker.recordAndCountConsecutiveDays(FACTORY,
                List.of(finding("WASTAGE", "变质"), finding("WASTAGE", "过期")));

        assertEquals(2, days.size(), "身份是(规则码, 对象), 不是规则码本身");
    }

    @Test
    @DisplayName("⛔ 第 2 天不提「连续」—— 门槛至少 3 天")
    void twoDaysIsNotWorthSaying() {
        // 第二天就说「已连续提醒 2 天」是噪音, 不是信息。
        assertTrue(FindingOccurrenceTracker.MIN_DAYS_WORTH_SAYING >= 3,
                "门槛低于 3 天会让提示每天都多出一句废话");
    }
}
