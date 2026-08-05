package com.cretas.aims.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class DemoIdentityDisabledTest {

    private static final Path PROPS = Path.of("src/main/resources/application.properties");

    @Test
    @DisplayName("MOCK_REST 不在演示只读名单里 —— 它必须保持完整写能力")
    void mockRestIsNotReadOnly() throws Exception {
        String line = propertyLine("cretas.demo.factory-ids");
        assertThat(line)
            .as("cretas.demo.factory-ids 的值")
            .doesNotContain("MOCK_REST");
    }

    @Test
    @DisplayName("演示只读名单不再含已停用的 DEMO_REST")
    void demoRestRemovedFromReadOnlyList() throws Exception {
        assertThat(propertyLine("cretas.demo.factory-ids")).doesNotContain("DEMO_REST");
    }

    @Test
    @DisplayName("演示餐饮身份已停用(默认值为空)")
    void demoRestIdentityDisabled() throws Exception {
        assertThat(defaultValueOf("cretas.demo.rest.factory-id")).isEmpty();
        assertThat(defaultValueOf("cretas.demo.rest.username")).isEmpty();
    }

    @Test
    @DisplayName("代码里的 @Value fallback 默认值也不含 DEMO_REST —— 配置与 fallback 是两个承载点")
    void codeFallbacksAlsoCleaned() throws Exception {
        for (Path f : new Path[]{
                Path.of("src/main/java/com/cretas/aims/config/DemoReadOnlyInterceptor.java"),
                Path.of("src/main/java/com/cretas/aims/service/execution/IntentExecutionOrchestrator.java")}) {
            String src = Files.readString(f);
            assertThat(src)
                .as("%s 的 @Value fallback", f.getFileName())
                .doesNotContain("${cretas.demo.factory-ids:DEMO_REST");
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
