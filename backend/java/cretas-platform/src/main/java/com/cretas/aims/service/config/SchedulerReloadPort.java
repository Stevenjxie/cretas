package com.cretas.aims.service.config;

/**
 * 工厂配置变更后重新加载定时任务的出口。
 *
 * <p>存在的理由是模块边界: {@code FactoryConfigServiceImpl} 原先内联持有
 * {@code com.cretas.aims.engine.DynamicSchedulerService}(连 import 都没有, 直接写全限定名),
 * 而那个类会牵出 {@code ai.tool.ToolExecutor} / {@code ToolRegistry} / {@code ai.dto.ToolCall} ——
 * 一个执行 AI 工具的调度器不属于「任何独立服务都要自带的运行时层」。
 *
 * <p>依赖方向因此倒置: 平台层只声明它需要什么, 由应用侧提供实现。
 * 注入点仍然是 {@code @Autowired(required = false)} 且调用前判空, 所以没有实现时的行为
 * 与改动前完全一致 —— 静默跳过, 不报错。
 */
public interface SchedulerReloadPort {

    /**
     * 重新加载全部动态定时任务。工厂的调度配置变更后调用。
     */
    void reloadAll();
}
