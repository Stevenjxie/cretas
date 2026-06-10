package com.cretas.aims.util;

import com.cretas.aims.exception.BusinessException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * C-074/C-075/X-10: BackdateWindowValidator — 补录时效锁单元测试。
 *
 * <p>窗口规则 (maxDays=2, 默认):
 * <ul>
 *   <li>T = 今天 → 允许</li>
 *   <li>T-1 = 昨天 → 允许</li>
 *   <li>T-2 = 前天 → 允许 (边界)</li>
 *   <li>T-3 = 大前天 → 拒绝 409 BACKDATE_WINDOW_EXCEEDED</li>
 *   <li>T-30 = 一个月前 → 拒绝</li>
 *   <li>T+1 = 明天 → 允许 (未来, 不拦)</li>
 *   <li>null → 允许 (系统取今天, 不拦)</li>
 * </ul>
 */
@DisplayName("BackdateWindowValidator — 补录时效锁 (C-074/C-075/X-10)")
class BackdateWindowValidatorTest {

    private BackdateWindowValidator validator;
    private static final LocalDate TODAY = LocalDate.of(2026, 6, 10);

    @BeforeEach
    void setUp() {
        validator = new BackdateWindowValidator();
        ReflectionTestUtils.setField(validator, "maxDays", 2);
    }

    // ============================================================
    // 窗口内 — 应放行
    // ============================================================

    @Test
    @DisplayName("T (今天) → 放行")
    void today_allowed() {
        assertThatCode(() -> validator.assertWithinWindow(TODAY, "报工", TODAY))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("T-1 (昨天) → 放行")
    void yesterday_allowed() {
        assertThatCode(() -> validator.assertWithinWindow(TODAY.minusDays(1), "报工", TODAY))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("T-2 (前天) 边界 → 放行")
    void dayBeforeYesterday_boundary_allowed() {
        assertThatCode(() -> validator.assertWithinWindow(TODAY.minusDays(2), "报工", TODAY))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("T+1 (明天 / 未来) → 放行 (未来不拦)")
    void future_allowed() {
        assertThatCode(() -> validator.assertWithinWindow(TODAY.plusDays(1), "原料入库", TODAY))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("T+30 (远未来) → 放行")
    void farFuture_allowed() {
        assertThatCode(() -> validator.assertWithinWindow(TODAY.plusDays(30), "原料入库", TODAY))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("null businessDate → 放行 (系统取今天)")
    void nullDate_allowed() {
        assertThatCode(() -> validator.assertWithinWindow(null, "报工", TODAY))
                .doesNotThrowAnyException();
    }

    // ============================================================
    // 窗口外 — 应拒绝
    // ============================================================

    @Test
    @DisplayName("T-3 (大前天) → 拒绝 409 BACKDATE_WINDOW_EXCEEDED")
    void threeDaysAgo_rejected() {
        LocalDate t3 = TODAY.minusDays(3);
        assertThatThrownBy(() -> validator.assertWithinWindow(t3, "报工", TODAY))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> {
                    BusinessException be = (BusinessException) ex;
                    assertThat(be.getCode()).isEqualTo(409);
                    assertThat(be.getErrorCode()).isEqualTo("BACKDATE_WINDOW_EXCEEDED");
                    assertThat(be.getMessage()).contains("2026-06-07"); // T-3 date
                    assertThat(be.getMessage()).contains("2026-06-08"); // earliest = T-2
                    assertThat(be.getMessage()).contains("报工");
                    assertThat(be.getActionHint()).isNotBlank();
                });
    }

    @Test
    @DisplayName("T-30 (一个月前) → 拒绝")
    void thirtyDaysAgo_rejected() {
        LocalDate t30 = TODAY.minusDays(30);
        assertThatThrownBy(() -> validator.assertWithinWindow(t30, "原料入库", TODAY))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> {
                    BusinessException be = (BusinessException) ex;
                    assertThat(be.getCode()).isEqualTo(409);
                    assertThat(be.getErrorCode()).isEqualTo("BACKDATE_WINDOW_EXCEEDED");
                    assertThat(be.getMessage()).contains("原料入库");
                });
    }

    @Test
    @DisplayName("T-3 拒绝消息含具体日期与最早可补边界")
    void rejectedMessage_containsDateAndBoundary() {
        LocalDate t3 = TODAY.minusDays(3);  // 2026-06-07
        LocalDate earliest = TODAY.minusDays(2); // 2026-06-08

        BusinessException thrown = null;
        try {
            validator.assertWithinWindow(t3, "报工", TODAY);
        } catch (BusinessException e) {
            thrown = e;
        }

        assertThat(thrown).isNotNull();
        assertThat(thrown.getMessage())
                .contains(t3.toString())
                .contains(earliest.toString());
    }

    // ============================================================
    // 边界 — maxDays=1 (自定义配置)
    // ============================================================

    @Test
    @DisplayName("maxDays=1: T-1 → 放行 (边界)")
    void maxDaysOne_yesterdayAllowed() {
        ReflectionTestUtils.setField(validator, "maxDays", 1);
        assertThatCode(() -> validator.assertWithinWindow(TODAY.minusDays(1), "报工", TODAY))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("maxDays=1: T-2 → 拒绝")
    void maxDaysOne_twoDaysAgoRejected() {
        ReflectionTestUtils.setField(validator, "maxDays", 1);
        assertThatThrownBy(() -> validator.assertWithinWindow(TODAY.minusDays(2), "报工", TODAY))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getCode()).isEqualTo(409));
    }

    // ============================================================
    // maxDays=0 (仅今天)
    // ============================================================

    @Test
    @DisplayName("maxDays=0: T → 放行")
    void maxDaysZero_todayAllowed() {
        ReflectionTestUtils.setField(validator, "maxDays", 0);
        assertThatCode(() -> validator.assertWithinWindow(TODAY, "报工", TODAY))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("maxDays=0: T-1 → 拒绝")
    void maxDaysZero_yesterdayRejected() {
        ReflectionTestUtils.setField(validator, "maxDays", 0);
        assertThatThrownBy(() -> validator.assertWithinWindow(TODAY.minusDays(1), "报工", TODAY))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                        .isEqualTo("BACKDATE_WINDOW_EXCEEDED"));
    }

    // ============================================================
    // context string 正确传播
    // ============================================================

    @Test
    @DisplayName("context 字符串正确出现在错误消息中")
    void contextAppearsInMessage() {
        assertThatThrownBy(() -> validator.assertWithinWindow(TODAY.minusDays(3), "原料入库", TODAY))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(ex.getMessage()).contains("原料入库"));
    }

    @Test
    @DisplayName("getMaxDays() 返回配置值")
    void getMaxDays_returnsConfiguredValue() {
        assertThat(validator.getMaxDays()).isEqualTo(2);
        ReflectionTestUtils.setField(validator, "maxDays", 5);
        assertThat(validator.getMaxDays()).isEqualTo(5);
    }
}
