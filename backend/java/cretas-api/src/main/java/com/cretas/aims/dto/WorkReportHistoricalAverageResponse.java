package com.cretas.aims.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 某工序近 N 天的产量均值 / 标准差（异常检测用；替代 legacy
 * {@code GET /work-reporting/reports/historical-average}）。
 *
 * <p>四个字段是按 RN 消费方逐个对出来的，不是照方法名猜的 ——
 * {@code frontend/CretasFoodTrace/src/hooks/useAnomalyDetection.ts} 的
 * {@code HistoricalStats} 正好读这四个：{@code avgOutput} / {@code stddevOutput} /
 * {@code avgDefect} / {@code sampleCount}（其中 {@code sampleCount < 5} 时它整个不告警）。
 *
 * <p>⚠️ 返回 DTO 而不是 {@code Map} —— 口径见设计卡
 * {@code docs/decisions/2026-08-17-legacy报工栈退役.md}。legacy 那版拼的是
 * {@code HashMap}，少一个键不会有任何东西变红。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class WorkReportHistoricalAverageResponse {

    /** 近 N 天该工序的平均产出。 */
    private double avgOutput;

    /** 近 N 天该工序产出的标准差（PostgreSQL 单行样本时 {@code STDDEV} 为 NULL，SQL 侧已 COALESCE 成 0）。 */
    private double stddevOutput;

    /** 近 N 天该工序的平均不良品数。 */
    private double avgDefect;

    /**
     * 样本条数。
     *
     * <p>⚠️ 它是 {@code COUNT(*)}，<b>空集上是 0 不是 NULL</b> —— 所以
     * 「这个工序近 N 天一条报工都没有」的长相是四个 0，不是 {@code null}。
     * 消费方靠 {@code sampleCount < 5} 判定「样本不够，不告警」。
     */
    private long sampleCount;
}
