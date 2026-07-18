package com.cretas.aims.ai.tool.gateway;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class ApprovedToolSensitiveLoggingTest {

    @Test
    void approvedRestrictedToolsDoNotLogRawParametersOrUserProvidedNamesAndReasons() throws Exception {
        String userDisable = logLines(Path.of(
                "src/main/java/com/cretas/aims/ai/tool/impl/user/UserDisableTool.java"));
        String dishDelete = logLines(Path.of(
                "src/main/java/com/cretas/aims/ai/tool/impl/restaurant/RestaurantDishDeleteTool.java"));

        assertThat(userDisable)
                .doesNotContain("参数: {}")
                .doesNotContain("username={}, userId={}")
                .doesNotContain("原因: {}")
                .doesNotContain("targetUserId, reason");
        assertThat(dishDelete)
                .doesNotContain("参数: {}")
                .doesNotContain("name={}")
                .doesNotContain("dish.getName()");
    }

    private static String logLines(Path source) throws Exception {
        return String.join("\n", Files.readAllLines(source).stream()
                .filter(line -> line.contains("log."))
                .toList());
    }
}
