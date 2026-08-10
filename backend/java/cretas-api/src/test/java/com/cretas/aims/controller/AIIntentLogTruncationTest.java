package com.cretas.aims.controller;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 日志语句不该有能力让请求失败。
 *
 * <p>🔴 2026-08-10 prod: {@code POST /api/mobile/{factoryId}/ai-intents/execute}
 * 请求体缺 {@code userInput} 时返回 <b>500「系统处理异常」</b>。追踪码查到根因:
 *
 * <pre>
 * java.lang.NullPointerException: Cannot invoke "String.length()" because the
 *   return value of "IntentExecuteRequest.getUserInput()" is null
 *   at AIIntentConfigController.executeIntent(AIIntentConfigController.java:275)
 * </pre>
 *
 * 而第 275 行是一句 <b>log.info</b>。下游全是 null 安全的
 * ({@code isRestaurantDerivedDishSelectionWrite} /
 * {@code extractExplicitRestaurantDishDeleteTarget} 都以
 * {@code userInput == null || isBlank()} 开头) —— 这个 500 完全来自一行日志。
 *
 * <p>🔑 判据: <b>日志在旁路上。它崩了不但没记成日志, 还把一个本来能正常处理的
 * 请求变成 500</b> —— 而 500 会污染错误监控、掩盖真正的故障。
 *
 * <p>⚠️ 这个缺陷是我自己的探针踩出来的(字段名写成了 question), 不是用户报的。
 * 判据: <b>探针报异常时先怀疑探针, 但修完探针要回头看被探的东西是不是也有问题。</b>
 */
class AIIntentLogTruncationTest {

    @Test
    @DisplayName("null 输入不抛异常 —— 这正是 500 的来源")
    void nullDoesNotThrow() {
        assertThat(AIIntentConfigController.truncateForLog(null)).isEqualTo("<null>");
    }

    @Test
    @DisplayName("短输入原样返回")
    void shortInputIsUnchanged() {
        assertThat(AIIntentConfigController.truncateForLog("本月营收多少"))
            .isEqualTo("本月营收多少");
    }

    @Test
    @DisplayName("长输入截断并加省略号 —— 原行为不能改坏")
    void longInputIsTruncated() {
        String long40 = "本月全部门店营收多少这是一句很长的问句用来触发截断逻辑一二三四五";
        String got = AIIntentConfigController.truncateForLog(long40);
        assertThat(got).endsWith("...");
        assertThat(got).hasSize(33);   // 30 字符 + "..."
        assertThat(long40).startsWith(got.substring(0, 30));
    }

    @Test
    @DisplayName("恰好 30 字不截断 —— 边界不能顺手改")
    void exactlyThirtyIsNotTruncated() {
        String exact30 = "一二三四五六七八九十".repeat(3);
        assertThat(exact30).hasSize(30);
        assertThat(AIIntentConfigController.truncateForLog(exact30)).isEqualTo(exact30);
    }

    @Test
    @DisplayName("空串不当成 null —— 两者在日志里要分得开")
    void emptyIsNotNull() {
        assertThat(AIIntentConfigController.truncateForLog("")).isEmpty();
    }
}
