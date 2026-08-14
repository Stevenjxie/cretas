package com.cretas.aims.ai.tool.gateway;

import com.cretas.aims.ai.tool.gateway.descriptor.RuntimeToolDescriptorRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * 打包进 jar 的那三份工具治理配置必须**互相自洽**, 否则应用根本起不来。
 *
 * <h3>为什么有这个文件(2026-08-14 实测事故)</h3>
 *
 * {@link RuntimeToolDescriptorRegistry} 的构造函数会把 {@code tool-descriptors.yaml}(审计清册)
 * 与 {@code runtime-tool-policies.yaml}(运行时授权)逐字段比对, 任一字段不一致就
 * {@code throw new IllegalArgumentException("runtime policy drift for ...")}。
 * 这个 bean 在 Spring 启动时构造 ⇒ **两份 yaml 不一致 = 后端起不来**。
 *
 * PR #2616 只改了清册一侧的 {@code restaurant_dish_delete.supportsPreview}(false→true),
 * 漏了策略一侧, 于是 main 上产出的 jar 无法启动。**当时 CI 全绿**:
 *
 * <ul>
 *   <li>PR 路径 {@code TARGET_TESTS} 为空 → Java 测试一个都不跑</li>
 *   <li>push 路径只跑 {@code *RepositoryQueryValidationTest} / {@code *StartupGuardTest} /
 *       {@code FlywayVersionUniquenessTest} —— 当时没有任何一个覆盖这条</li>
 *   <li>同 PR 新建的清册漂移闸只比对【源码 ↔ 清册】, 管不着【清册 ↔ 策略】</li>
 * </ul>
 *
 * ⚠️ 本类名字刻意以 {@code StartupGuardTest} 结尾 —— push 路径的选择器按这个通配符捞测试,
 * **改名就等于把它从 CI 里摘掉**, 也就等于把这次事故的唯一防线拆了。
 *
 * 刻意不启 Spring 上下文: 这三份配置是纯资源文件, 加载+比对只要几毫秒,
 * 而起上下文要几十秒。守卫要便宜到没人有理由把它从选择器里拿掉。
 */
@DisplayName("工具网关启动守卫")
class ToolGatewayStartupGuardTest {

    @Test
    @DisplayName("🔒 打包进 jar 的清册与运行时策略必须自洽 —— 否则应用起不来")
    void bundledDescriptorAndPolicyManifestsMustAgree() {
        assertThatCode(RuntimeToolDescriptorRegistry::loadDefault)
                .as("tool-descriptors.yaml 与 runtime-tool-policies.yaml 不一致时, "
                        + "RuntimeToolDescriptorRegistry 构造期就抛, Spring 上下文起不来。"
                        + "改了其中一份就必须同步另一份。")
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("守卫不是空跑: 真的装出了 APPROVED 工具, 而不是在空集合上恒真")
    void theGuardActuallyLoadsApprovedTools() {
        RuntimeToolDescriptorRegistry registry = RuntimeToolDescriptorRegistry.loadDefault();

        // APPROVED 集合必须与 runtime-tool-policies.yaml 的条目一一对应(构造函数强制),
        // 这里再断言它非空 —— 否则「不抛异常」在空集合上是恒真的, 守不住任何东西。
        assertThat(registry.approvedToolNames())
                .as("APPROVED 工具集合为空 —— 那么上面那条断言在空集合上恒真, 等于没有守卫")
                .isNotEmpty();
    }
}
