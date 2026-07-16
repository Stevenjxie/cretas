package com.cretas.aims.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;

/**
 * 异步处理配置
 *
 * 配置线程池用于:
 * - 报表异步生成
 * - AI分析异步处理
 *
 * @author Cretas Team
 * @version 1.0.0
 * @since 2026-01-02
 */
@Configuration
@EnableAsync
public class AsyncConfig {

    /**
     * 报表生成线程池
     *
     * 用于异步生成各类报表（生产报表、质量报表、成本报表等）
     *
     * @return 线程池执行器
     */
    @Bean(name = "reportExecutor")
    public Executor reportExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(5);
        executor.setMaxPoolSize(10);
        executor.setQueueCapacity(100);
        executor.setThreadNamePrefix("ReportAsync-");
        executor.initialize();
        return executor;
    }

    /**
     * AI分析线程池
     *
     * 用于异步执行AI分析任务（成本分析、质量分析、预测分析等）
     *
     * @return 线程池执行器
     */
    @Bean(name = "aiAnalysisExecutor")
    public Executor aiAnalysisExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(3);
        executor.setMaxPoolSize(5);
        executor.setQueueCapacity(50);
        executor.setThreadNamePrefix("AIAnalysis-");
        executor.initialize();
        return executor;
    }

    /** Single lane for expensive embedding warmups after core startup. */
    @Bean(name = "startupWarmupExecutor")
    public Executor startupWarmupExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(1);
        executor.setMaxPoolSize(1);
        executor.setQueueCapacity(4);
        executor.setThreadNamePrefix("StartupWarmup-");
        executor.setWaitForTasksToCompleteOnShutdown(false);
        executor.initialize();
        return executor;
    }

    /**
     * SmartBI Sheet 并行处理线程池
     *
     * AUDIT-087: Replaces static ExecutorService in SmartBIUploadFlowServiceImpl.
     * 5 核心线程，避免 DashScope API 限流；8 最大线程应对突发。
     * Spring 管理生命周期，应用关闭时自动 graceful shutdown。
     *
     * @return 线程池执行器
     */
    @Bean(name = "smartbiExecutor")
    public Executor smartbiExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(5);
        executor.setMaxPoolSize(8);
        executor.setQueueCapacity(20);
        executor.setThreadNamePrefix("SmartBI-Sheet-");
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(180);
        executor.initialize();
        return executor;
    }
}
