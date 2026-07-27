package com.cretas.aims.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * demo 写闸名单 (cretas.demo.factory-ids) 的配置回归守卫。
 *
 * <p>2026-07-28 F_DEMO 治理 (餐饮 AI 飞轮回接 spec §入口收敛): F_DEMO 是
 * {@code AIPublicDemoController} (/api/public/ai-demo) 的免登录公开演示租户，
 * 此前既不在写闸名单也不是餐饮租户 —— 编排器的 AI 确认执行写闸
 * ({@code IntentExecutionOrchestrator.isDemoFactory}, fail-closed 不随
 * cretas.demo.enabled 关闭) 对它完全不生效，唯一写保护是 controller 内
 * 单点的 ALLOWED_SENSITIVITY_LEVELS={"LOW"} 判断。
 *
 * <p>本测试锁住"三个 demo 租户都在名单内"这一事实，防止后续有人重排/精简
 * 这行配置时把 F_DEMO 悄悄漏掉（写闸失效是静默的，线上不会报错）。
 *
 * <p>注意 DEMO_LOGISTICS 是**有意**不在名单内的（排线调度演示需要真实写操作），
 * 一并断言，防止有人"顺手补全"把它加进去锁死物流演示。
 */
class DemoFactoryGateConfigTest {

    private static final String DEMO_FACTORY_IDS_KEY = "cretas.demo.factory-ids";

    private String demoFactoryIdsValue() throws Exception {
        Properties props = new Properties();
        try (InputStream in = getClass().getClassLoader()
                .getResourceAsStream("application.properties")) {
            assertNotNull(in, "application.properties 必须在 classpath 上");
            props.load(new InputStreamReader(in, StandardCharsets.UTF_8));
        }
        String value = props.getProperty(DEMO_FACTORY_IDS_KEY);
        assertNotNull(value, DEMO_FACTORY_IDS_KEY + " 必须存在");
        return value;
    }

    @Test
    @DisplayName("F_DEMO 在 demo 写闸名单内 — 公开演示租户受编排器写闸保护")
    void publicDemoTenantIsInsideWriteGate() throws Exception {
        assertTrue(demoFactoryIdsValue().contains("F_DEMO"),
                "F_DEMO 必须在 " + DEMO_FACTORY_IDS_KEY
                        + " 内，否则 /api/public/ai-demo 的写意图绕过编排器 demo 写闸");
    }

    @Test
    @DisplayName("既有两个 demo 租户仍在名单内 — 防重排时误删")
    void existingDemoTenantsRemainGated() throws Exception {
        String value = demoFactoryIdsValue();
        assertTrue(value.contains("DEMO_REST"), "DEMO_REST 必须仍在写闸名单内");
        assertTrue(value.contains("DEMO_FACTORY2"), "DEMO_FACTORY2 必须仍在写闸名单内");
    }

    @Test
    @DisplayName("DEMO_LOGISTICS 有意不在名单内 — 排线调度演示需要真实写操作")
    void logisticsDemoStaysWritable() throws Exception {
        assertTrue(!demoFactoryIdsValue().contains("DEMO_LOGISTICS"),
                "DEMO_LOGISTICS 是有意排除的（见 application.properties 注释），"
                        + "加进名单会锁死排线调度演示的创建/调整/确认计划写操作");
    }
}
