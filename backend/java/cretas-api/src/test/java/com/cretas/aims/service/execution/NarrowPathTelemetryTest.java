package com.cretas.aims.service.execution;

import com.cretas.aims.config.IntentKnowledgeBase;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * [NarrowPath] 埋点断言 —— 跑在**产品真实入口** {@link IntentKnowledgeBase#matchPhrase}
 * 上(真实例、真 601 条表、无 mock), 不是直接调我自己写的 log 语句。
 *
 * <p>🔴 这个埋点不是可有可无的日志: 它是「601 条兜底短语到底有没有人走」的唯一读数
 * 来源。上一轮实测生产 Java 日志里 {@code factoryPack / ReadVeto / previewOnly /
 * matchPhrase} 四个词全是 0 次(阳性对照 {@code INFO}=9593) —— 不是没人走, 是**根本
 * 没有仪器**。观察窗没跑满之前不许删那张表。
 *
 * <p>⚠️ 埋点最容易长成的坏形状是「两侧都打」或「打了个恒真的东西」。这里钉死两条:
 * ① 命中才打(taken=true 一侧); ② 只打餐饮那张表, 工厂表命中不打 —— 否则读数会被
 * 工厂流量淹掉, 而我们要判的是**餐饮 601 条**的去留。
 */
class NarrowPathTelemetryTest {

    private static final String NARROW_PATH = "[NarrowPath]";

    private IntentKnowledgeBase kb;
    private Logger logger;
    private ListAppender<ILoggingEvent> appender;

    @BeforeEach
    void setUp() {
        kb = new IntentKnowledgeBase();
        kb.initDefaults();
        logger = (Logger) LoggerFactory.getLogger(IntentKnowledgeBase.class);
        appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        logger.setLevel(Level.INFO);
    }

    @AfterEach
    void tearDown() {
        logger.detachAppender(appender);
    }

    private List<String> narrowPathLines() {
        return appender.list.stream()
                .map(ILoggingEvent::getFormattedMessage)
                .filter(m -> m.contains(NARROW_PATH))
                .toList();
    }

    @Test
    @DisplayName("UT-NP-01: 餐饮短语命中 601 表 -> 打出 gate=restaurantPhrase taken=true 带 intentCode 和问句")
    void restaurantPhraseHitIsInstrumented() {
        Optional<String> hit = kb.matchPhrase("菜品列表", "RESTAURANT");

        // 先证明仪器测的是真事: 这条短语确实命中了餐饮表。
        // ⛔ 没有这一步, 「日志里有那行」也可能只是我把 log 写在了分支外面。
        assertThat(hit).as("样本短语必须真的命中 601 表, 否则这条断言测的不是埋点")
                .contains("RESTAURANT_DISH_LIST");

        assertThat(narrowPathLines())
                .as("命中餐饮短语表却没有埋点 —— 601 条的去留就没有读数来源")
                .hasSize(1);
        assertThat(narrowPathLines().get(0))
                .contains("gate=restaurantPhrase")
                .contains("taken=true")
                .contains("intentCode=RESTAURANT_DISH_LIST")
                .contains("q=菜品列表");
    }

    @Test
    @DisplayName("UT-NP-02: 工厂短语命中不打 —— 埋点只覆盖餐饮那张表")
    void factoryPhraseHitIsNotInstrumented() {
        Optional<String> hit = kb.matchPhrase("本月业绩如何", "FACTORY");

        assertThat(hit).as("阳性对照: 工厂样本必须真的命中, 否则「没打日志」是因为它压根没走到")
                .contains("MONTHLY_FINANCIAL_CLOSE");

        assertThat(narrowPathLines())
                .as("工厂流量也打进来会淹掉餐饮读数 —— 要判的是餐饮 601 条的去留: %s",
                        narrowPathLines())
                .isEmpty();
    }

    @Test
    @DisplayName("UT-NP-03: 没命中不打 —— 只有 taken=true 一侧有量")
    void missIsNotInstrumented() {
        Optional<String> hit = kb.matchPhrase("阿巴阿巴不存在的问句xyz", "RESTAURANT");

        assertThat(hit).as("阳性对照: 这句必须真的没命中").isEmpty();
        assertThat(narrowPathLines())
                .as("没命中也打 = 每个请求一行, 取数时分不出走没走窄路径")
                .isEmpty();
    }

    @Test
    @DisplayName("UT-NP-04: 问句截断到 40 字, null 打成 -")
    void truncationKeepsTheLogBounded() {
        String q = "这是一句很长的问题".repeat(20);
        kb.matchPhrase(q, "RESTAURANT");
        assertThat(q.length()).isGreaterThan(40);

        assertThat(IntentExecutionOrchestrator.truncateForTelemetry(q))
                .hasSize(40);
        assertThat(IntentExecutionOrchestrator.truncateForTelemetry(null))
                .as("null 打成 \"null\" 会在取数时被当成一个真的问句")
                .isEqualTo("-");
        assertThat(IntentExecutionOrchestrator.truncateForTelemetry("换行\n会把一行日志劈成两行"))
                .doesNotContain("\n");
    }
}
