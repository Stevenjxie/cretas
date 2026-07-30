package com.cretas.aims.service.config;

import java.util.List;

/**
 * 平台按工厂配置禁用 AI 工具时, 需要知道「现在一共有哪些工具名」。
 *
 * <p>与 {@link SchedulerReloadPort} 同一个理由: {@code FactoryConfigServiceImpl} 原先内联持有
 * {@code com.cretas.aims.ai.tool.ToolRegistry}(全限定名, 无 import)。整个 AI 工具注册表
 * 不属于「任何独立服务都要自带的运行时层」—— 一个物流服务不需要 AI 工具, 却仍然需要
 * 按工厂开关模块。
 *
 * <p>注入点仍是 {@code @Autowired(required = false)} 且调用前判空: 没有 AI 的服务里
 * 这个口就是空的, 禁用模式不生效, 与改动前行为一致。
 */
public interface ToolCatalogPort {

    /**
     * 当前注册的全部工具名。
     */
    List<String> getAllToolNames();
}
