package com.cretas.aims;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.data.jpa.repository.config.EnableJpaAuditing;

/**
 * 物流业务线的独立服务入口。
 *
 * <p>刻意放在 {@code com.cretas.aims} 包下, 与单体的 {@code CretasBackendApplication} 同包不同模块 ——
 * 这样组件扫描的基包与单体完全一致, 装配到什么完全由 classpath 决定: 这个服务的 classpath 上
 * 只有 cretas-platform 与 cretas-logistics(及其依赖 model / common), 所以扫到的就只有
 * 运行时基础设施与物流域, 单体里那 3382 个文件一个都不会被加载。
 *
 * <p>与单体主类的差别只有一处: 不开 {@code @EnableScheduling}。动态调度器属于应用侧,
 * 平台层通过 {@code SchedulerReloadPort} 可选注入, 这里没有实现 —— 配置发布后静默跳过
 * reload, 与单体中该实现缺席时的行为一致。
 */
@SpringBootApplication
@EnableJpaAuditing
public class LogisticsServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(LogisticsServiceApplication.class, args);
    }
}
