package com.cretas.aims.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 演示身份与"只读写闸名单"必须一致 —— 配了身份就必须上闸。
 *
 * <p><b>2026-08-10 重开餐饮演示 (owner 拍板「修吧, 但是只需要做餐饮的」)。</b>
 * 本类原名 DemoIdentityDisabledContractTest, 钉的是 08-05「DEMO_REST 随租户收敛停用」
 * 那个决定。今天实测该租户有 <b>523,113 笔交易, 2025-01-01 ~ 2026-08-09(昨天)</b>
 * —— 停用时的理由(收敛后不可演示)已不成立, 于是重开。类名保留是为了让
 * git blame 能顺着找到这段来龙去脉。
 *
 * <p>🔴 <b>重开时发现的真正危险不是"没开", 是"只开一半"</b>: prod 上
 * {@code POST /api/mobile/auth/demo-login?tenant=rest} 返回 404「演示账号不存在」,
 * 而修法看起来只要把 {@code cretas.demo.rest.factory-id} 指向 DEMO_REST 就行。
 * 但只做这一半, 公开扫码演示会拿到 {@code demo_rest}(factory_super_admin) 的
 * <b>完整写权限</b> —— 因为 08-05 那次把 DEMO_REST 从写闸名单里一并移除了。
 *
 * <p>所以本类的承重断言从「DEMO_REST 不在名单里」改成一条<b>不变式</b>:
 * <b>任何被配置成演示身份的租户, 都必须在只读名单内。</b>
 * 它对未来任何一次「加个演示租户」都成立, 不需要有人记得这段历史。
 *
 * <p>⚠️ DEMO_LOGISTICS 是<b>有意</b>不在名单内的(排线调度演示需要真实写操作),
 * 所以不变式只覆盖 rest/factory 两个身份, 并单独断言 logistics 保持豁免 ——
 * 防止有人"顺手补全"把它锁死。
 *
 * <p>⚠️ 配置有三个承载点(application.properties + 两处 {@code @Value} fallback),
 * 08-05 那次三处都改了。本类逐个断言, 不假设它们同步。
 */
class DemoIdentityDisabledContractTest {

    private static final Path PROPS = Path.of("src/main/resources/application.properties");
    private static final Path INTERCEPTOR =
        Path.of("src/main/java/com/cretas/aims/config/DemoReadOnlyInterceptor.java");
    private static final Path ORCHESTRATOR =
        Path.of("src/main/java/com/cretas/aims/service/execution/IntentExecutionOrchestrator.java");

    @Test
    @DisplayName("MOCK_REST 不在演示只读名单里 —— 它必须保持完整写能力")
    void mockRestIsNotReadOnly() throws Exception {
        assertThat(propertyLine("cretas.demo.factory-ids"))
            .as("cretas.demo.factory-ids 的值")
            .doesNotContain("MOCK_REST");
    }

    @Test
    @DisplayName("承重: 配了演示身份的租户, 必须在只读名单内")
    void everyConfiguredDemoIdentityIsReadOnlyLocked() throws Exception {
        String lockList = propertyLine("cretas.demo.factory-ids");
        for (String key : new String[]{"cretas.demo.rest.factory-id",
                                       "cretas.demo.factory.factory-id"}) {
            String tenant = defaultValueOf(key);
            if (tenant.isEmpty()) {
                continue;   // 该身份未配置 = 登录不了 = 无需上闸
            }
            assertThat(lockList)
                .as("%s 配成了 %s, 但它不在只读名单里 —— 公开扫码演示会拿到完整写权限",
                    key, tenant)
                .contains(tenant);
        }
    }

    @Test
    @DisplayName("餐饮演示身份已重开(2026-08-10), 且指向真实存在的租户")
    void restDemoIdentityIsEnabled() throws Exception {
        assertThat(defaultValueOf("cretas.demo.rest.factory-id")).isEqualTo("DEMO_REST");
        assertThat(defaultValueOf("cretas.demo.rest.username")).isNotEmpty();
    }

    @Test
    @DisplayName("DEMO_LOGISTICS 保持豁免 —— 排线调度演示需要真实写操作")
    void logisticsStaysWritable() throws Exception {
        assertThat(propertyLine("cretas.demo.factory-ids"))
            .as("有人把物流演示也锁死了, 排线调度演示会当场不可用")
            .doesNotContain("DEMO_LOGISTICS");
    }

    @Test
    @DisplayName("两处 @Value fallback 与 application.properties 名单一致 —— 三个承载点")
    void codeFallbacksMatchTheProperties() throws Exception {
        String expected = defaultValueOf("cretas.demo.factory-ids");
        for (Path f : new Path[]{INTERCEPTOR, ORCHESTRATOR}) {
            String src = Files.readString(f);
            // fallback 允许是 properties 名单的**子集**(orchestrator 历史上就少一个
            // F_DEMO), 但**不允许漏掉任何一个已配置的演示身份** —— 漏了就是写闸
            // 在那条代码路径上对该租户失效, 而且是静默的。
            for (String key : new String[]{"cretas.demo.rest.factory-id",
                                           "cretas.demo.factory.factory-id"}) {
                String tenant = defaultValueOf(key);
                if (tenant.isEmpty()) {
                    continue;
                }
                assertThat(src)
                    .as("%s 的 @Value fallback 漏了演示租户 %s (properties 名单是 %s)",
                        f.getFileName(), tenant, expected)
                    .contains(tenant);
            }
        }
    }

    private static String propertyLine(String key) throws Exception {
        return Arrays.stream(Files.readString(PROPS).split("\\R"))
            .filter(l -> l.startsWith(key + "="))
            .findFirst().orElseThrow(() -> new AssertionError("找不到配置项: " + key));
    }

    /** 取 `key=${ENV:default}` 里的 default 部分。 */
    private static String defaultValueOf(String key) throws Exception {
        String line = propertyLine(key);
        int colon = line.indexOf(':', line.indexOf("${"));
        return line.substring(colon + 1, line.lastIndexOf('}'));
    }
}
