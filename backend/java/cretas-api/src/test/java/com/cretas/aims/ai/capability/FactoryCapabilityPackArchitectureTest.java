package com.cretas.aims.ai.capability;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class FactoryCapabilityPackArchitectureTest {
    private static final List<String> FORBIDDEN_SOURCE_TOKENS = List.of(
            "ToolRegistry", "ToolExecutor", "SkillExecutor", "DefaultToolExecutionGateway",
            "IntentExecutionOrchestrator", "DynamicToolSelectionService",
            ".execute(", ".preview(", "createPlan(", "replan");

    @Test
    void productionModuleHasNoExecutionRuntimeOrDynamicPlannerDependency() throws IOException {
        Path root = Path.of(System.getProperty("user.dir"));
        List<Path> files = List.of(
                root.resolve("src/main/java/com/cretas/aims/ai/capability"),
                root.resolve("src/main/java/com/cretas/aims/controller/ai/FactoryCapabilityPackController.java"));
        for (Path path : files) {
            if (Files.isDirectory(path)) {
                try (var stream = Files.walk(path)) {
                    for (Path source : stream.filter(item -> item.toString().endsWith(".java")).toList()) {
                        assertStaticOnly(source);
                    }
                }
            } else {
                assertStaticOnly(path);
            }
        }
    }

    @Test
    void packResourcesContainNoDynamicGraphDirective() throws IOException {
        Path resources = Path.of(System.getProperty("user.dir"))
                .resolve("src/main/resources/ai/capability-packs");
        try (var stream = Files.list(resources)) {
            for (Path file : stream.filter(item -> item.toString().endsWith(".yaml")).toList()) {
                String source = Files.readString(file, StandardCharsets.UTF_8).toLowerCase();
                assertThat(source).as(file.toString())
                        .doesNotContain("replan", "dynamic dag", "toolregistry", "skillexecutor");
            }
        }
    }

    private static void assertStaticOnly(Path source) throws IOException {
        String text = Files.readString(source, StandardCharsets.UTF_8);
        assertThat(text).as(source.toString()).doesNotContain(FORBIDDEN_SOURCE_TOKENS.toArray(String[]::new));
    }
}
