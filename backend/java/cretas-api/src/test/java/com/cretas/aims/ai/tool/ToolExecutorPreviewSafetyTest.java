package com.cretas.aims.ai.tool;

import com.cretas.aims.ai.dto.ToolCall;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ToolExecutorPreviewSafetyTest {

    @Test
    void defaultPreviewFailsClosedWithoutCallingExecute() {
        AtomicBoolean executed = new AtomicBoolean(false);
        ToolExecutor tool = new ToolExecutor() {
            @Override
            public String getToolName() {
                return "unsafe_preview_default";
            }

            @Override
            public String getDescription() {
                return "test";
            }

            @Override
            public Map<String, Object> getParametersSchema() {
                return Map.of();
            }

            @Override
            public String execute(ToolCall toolCall, Map<String, Object> context) {
                executed.set(true);
                return "{\"success\":true}";
            }
        };

        ToolCall call = ToolCall.of("preview-1", tool.getToolName(), "{}");

        assertThatThrownBy(() -> tool.preview(call, Map.of("factoryId", "F006")))
                .isInstanceOf(UnsupportedOperationException.class)
                .hasMessageContaining("does not implement a safe preview");
        assertThat(executed).isFalse();
    }
}
