package com.cretas.aims.ai.tool.impl.restaurant.gold;

import com.cretas.aims.client.GoldFinanceClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.lang.reflect.Field;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;

/**
 * P1-A — time-window resolution tests for {@link GoldBackedRestaurantTool}.
 *
 * <p>The Gold restaurant tools were only resolving 本月 / 上月 / absolute month from the NL
 * query. Time-prefixed queries like "本季度…" / "今年…" / "近30天…" fell through to the
 * data-range fallback (ignoring the requested time). This pins the expanded NL coverage:
 * quarter, year (今年/去年/前年/YYYY年), rolling N-days, and the relative month/week — all
 * anchored to the Gold data's latest day (mocked here as 2026-04-30) rather than today, so a
 * "本月" query on data ending 2026-04 resolves to 2026-04 instead of an empty future month.
 *
 * <p>Tested via {@link RestaurantPeakMonthGoldTool} (a concrete subclass) since the methods
 * under test live on the abstract base. {@code resolveWindow} is {@code protected} and
 * {@code parseNlTimeWindow} is package-private — both reachable from this same-package test.
 */
@ExtendWith(MockitoExtension.class)
class GoldBackedRestaurantToolTimeTest {

    private static final String FACTORY_ID = "RES_3101_009";

    /** The Gold data's latest day — anchor for all relative expressions. */
    private static final LocalDate DATA_MIN = LocalDate.of(2025, 1, 1);
    private static final LocalDate DATA_MAX = LocalDate.of(2026, 4, 30);
    private static final LocalDate[] DATA_RANGE = {DATA_MIN, DATA_MAX};

    @Mock
    private GoldFinanceClient goldClient;

    private RestaurantPeakMonthGoldTool tool;

    @BeforeEach
    void setUp() throws Exception {
        tool = new RestaurantPeakMonthGoldTool();
        Field f = GoldBackedRestaurantTool.class.getDeclaredField("gold");
        f.setAccessible(true);
        f.set(tool, goldClient);

        // Gold data spans 2025-01-01 .. 2026-04-30 — used as the "now" anchor.
        Map<String, Object> range = new HashMap<>();
        range.put("min_date", "2025-01-01");
        range.put("max_date", "2026-04-30");
        lenient().when(goldClient.fetchDataRange(anyString())).thenReturn(range);
    }

    private LocalDate[] resolve(String userInput) {
        Map<String, Object> params = new HashMap<>();
        params.put("userInput", userInput);
        return tool.resolveWindow(FACTORY_ID, params);
    }

    private LocalDate[] parse(String userInput) {
        return tool.parseNlTimeWindow(userInput, DATA_MAX, DATA_RANGE);
    }

    private static LocalDate[] win(String start, String end) {
        return new LocalDate[]{LocalDate.parse(start), LocalDate.parse(end)};
    }

    // =========================================================================
    // parseNlTimeWindow — direct, fixed anchor 2026-04-30
    // =========================================================================

    @Test
    @DisplayName("本季度 → current quarter clamped to data (2026-Q2 → 2026-04-01..2026-04-30)")
    void currentQuarter() {
        // anchor 2026-04 is in Q2 (Apr-Jun); clamped to data max 2026-04-30.
        assertThat(parse("本季度哪个菜卖得好")).isEqualTo(win("2026-04-01", "2026-04-30"));
    }

    @Test
    @DisplayName("上季度 → previous quarter (2026-Q1 → 2026-01-01..2026-03-31)")
    void previousQuarter() {
        assertThat(parse("上季度销量最好的菜")).isEqualTo(win("2026-01-01", "2026-03-31"));
    }

    @Test
    @DisplayName("第1季度 → Q1 of anchor year (2026-01-01..2026-03-31)")
    void firstQuarterExplicit() {
        assertThat(parse("第1季度菜品排行")).isEqualTo(win("2026-01-01", "2026-03-31"));
    }

    @Test
    @DisplayName("第二季度 → Q2 of anchor year, clamped to data (2026-04-01..2026-04-30)")
    void secondQuarterChinese() {
        assertThat(parse("第二季度菜品排行")).isEqualTo(win("2026-04-01", "2026-04-30"));
    }

    @Test
    @DisplayName("今年 → whole year clamped to data (2026-01-01..2026-04-30)")
    void thisYear() {
        assertThat(parse("今年哪个菜卖得好")).isEqualTo(win("2026-01-01", "2026-04-30"));
    }

    @Test
    @DisplayName("去年 → prior whole year (2025-01-01..2025-12-31)")
    void lastYear() {
        assertThat(parse("去年销量最好的菜")).isEqualTo(win("2025-01-01", "2025-12-31"));
    }

    @Test
    @DisplayName("前年 → two years back, clamped to data min (2024 fully before data → unchanged whole year)")
    void yearBeforeLast() {
        // 2024 is entirely before data min 2025-01-01 → clamp leaves it disjoint → original kept.
        assertThat(parse("前年菜品排行")).isEqualTo(win("2024-01-01", "2024-12-31"));
    }

    @Test
    @DisplayName("YYYY年 (bare, no month) → whole year (2025-01-01..2025-12-31)")
    void bareYear() {
        assertThat(parse("2025年哪个菜卖得好")).isEqualTo(win("2025-01-01", "2025-12-31"));
    }

    @Test
    @DisplayName("近30天 → trailing 30 days from anchor (2026-04-01..2026-04-30)")
    void last30Days() {
        assertThat(parse("近30天哪个菜卖得好")).isEqualTo(win("2026-04-01", "2026-04-30"));
    }

    @Test
    @DisplayName("最近7天 → trailing 7 days from anchor (2026-04-24..2026-04-30)")
    void last7Days() {
        assertThat(parse("最近7天销量最好的菜")).isEqualTo(win("2026-04-24", "2026-04-30"));
    }

    @Test
    @DisplayName("近3个月 → trailing 3 calendar months from anchor month (2026-02-01..2026-04-30)")
    void last3Months() {
        assertThat(parse("近3个月哪个菜卖得好")).isEqualTo(win("2026-02-01", "2026-04-30"));
    }

    @Test
    @DisplayName("最近6个月 → trailing 6 calendar months from anchor month (2025-11-01..2026-04-30)")
    void last6Months() {
        assertThat(parse("最近6个月销量最好的菜")).isEqualTo(win("2025-11-01", "2026-04-30"));
    }

    @Test
    @DisplayName("近1个月 → just the anchor month (2026-04-01..2026-04-30)")
    void last1Month() {
        assertThat(parse("近1个月销量")).isEqualTo(win("2026-04-01", "2026-04-30"));
    }

    @Test
    @DisplayName("2025年12月 (Chinese) → that month (2025-12-01..2025-12-31)")
    void chineseAbsoluteMonth() {
        assertThat(parse("2025年12月哪个菜卖得好")).isEqualTo(win("2025-12-01", "2025-12-31"));
    }

    @Test
    @DisplayName("2025-12 (ISO) → that month (2025-12-01..2025-12-31), identical to Chinese form")
    void isoAbsoluteMonth() {
        assertThat(parse("2025-12哪个菜卖得好")).isEqualTo(win("2025-12-01", "2025-12-31"));
    }

    @Test
    @DisplayName("本月 → anchored to data latest month, NOT today (2026-04-01..2026-04-30)")
    void thisMonthAnchoredToData() {
        assertThat(parse("本月哪个菜卖得好")).isEqualTo(win("2026-04-01", "2026-04-30"));
    }

    @Test
    @DisplayName("未结束的本月 → upper bound is data anchor, never a future month-end")
    void unfinishedCurrentMonthStopsAtAnchor() {
        LocalDate anchor = LocalDate.of(2026, 4, 18);
        LocalDate[] range = {DATA_MIN, anchor};

        assertThat(tool.parseNlTimeWindow("本月哪个店营业额最高", anchor, range))
                .isEqualTo(win("2026-04-01", "2026-04-18"));
    }

    @Test
    @DisplayName("未结束的本周 → upper bound is data anchor, never a future Sunday")
    void unfinishedCurrentWeekStopsAtAnchor() {
        LocalDate anchor = LocalDate.of(2026, 4, 18);
        LocalDate[] range = {DATA_MIN, anchor};

        assertThat(tool.parseNlTimeWindow("本周营业额", anchor, range))
                .isEqualTo(win("2026-04-13", "2026-04-18"));
    }

    @Test
    @DisplayName("上月 → month before data latest (2026-03-01..2026-03-31)")
    void lastMonthAnchoredToData() {
        assertThat(parse("上月哪个菜卖得好")).isEqualTo(win("2026-03-01", "2026-03-31"));
    }

    @Test
    @DisplayName("no time expression → null (caller falls back to data range)")
    void noTimeExpressionReturnsNull() {
        assertThat(parse("哪个菜卖得好")).isNull();
    }

    // =========================================================================
    // resolveWindow — end-to-end (NL path uses mocked data-range as anchor)
    // =========================================================================

    @Test
    @DisplayName("resolveWindow 本季度 → 2026-04-01..2026-04-30 (anchor from mocked data-range)")
    void resolveWindowQuarter() {
        assertThat(resolve("本季度哪个菜卖得好")).isEqualTo(win("2026-04-01", "2026-04-30"));
    }

    @Test
    @DisplayName("resolveWindow 今年 → 2026-01-01..2026-04-30")
    void resolveWindowThisYear() {
        assertThat(resolve("今年销量最好的菜")).isEqualTo(win("2026-01-01", "2026-04-30"));
    }

    @Test
    @DisplayName("resolveWindow 近30天 → 2026-04-01..2026-04-30")
    void resolveWindowLast30Days() {
        assertThat(resolve("近30天哪个菜卖得好")).isEqualTo(win("2026-04-01", "2026-04-30"));
    }

    @Test
    @DisplayName("resolveWindow 近3个月 → 2026-02-01..2026-04-30 (rolling months, was the gap)")
    void resolveWindowLast3Months() {
        assertThat(resolve("近3个月哪个菜卖得好")).isEqualTo(win("2026-02-01", "2026-04-30"));
    }

    @Test
    @DisplayName("resolveWindow 2025年12月 → 2025-12-01..2025-12-31")
    void resolveWindowChineseMonth() {
        assertThat(resolve("2025年12月哪个菜卖得好")).isEqualTo(win("2025-12-01", "2025-12-31"));
    }

    @Test
    @DisplayName("resolveWindow no time → falls back to full data range 2025-01-01..2026-04-30")
    void resolveWindowNoTimeUsesDataRange() {
        assertThat(resolve("哪个菜卖得好")).isEqualTo(win("2025-01-01", "2026-04-30"));
    }

    @Test
    @DisplayName("resolveWindow explicit ISO startDate/endDate params take precedence over NL")
    void resolveWindowExplicitIsoWins() {
        Map<String, Object> params = new HashMap<>();
        params.put("userInput", "本季度哪个菜卖得好");
        params.put("startDate", "2025-06-01");
        params.put("endDate", "2025-06-30");
        assertThat(tool.resolveWindow(FACTORY_ID, params)).isEqualTo(win("2025-06-01", "2025-06-30"));
    }
}
