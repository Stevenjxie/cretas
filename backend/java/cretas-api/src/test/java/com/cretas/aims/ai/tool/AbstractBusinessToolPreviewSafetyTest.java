package com.cretas.aims.ai.tool;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AbstractBusinessToolPreviewSafetyTest {

    @Test
    void defaultBusinessPreviewFailsClosedWithoutCallingDoExecute() {
        AtomicBoolean executed = new AtomicBoolean(false);
        AbstractBusinessTool tool = new AbstractBusinessTool() {
            @Override
            public String getToolName() {
                return "unsafe_business_preview_default";
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
            protected List<String> getRequiredParameters() {
                return List.of();
            }

            @Override
            protected Map<String, Object> doExecute(
                    String factoryId, Map<String, Object> params, Map<String, Object> context) {
                executed.set(true);
                return Map.of("success", true);
            }
        };

        assertThatThrownBy(() -> tool.doPreview("F006", Map.of(), Map.of()))
                .isInstanceOf(UnsupportedOperationException.class)
                .hasMessageContaining("does not implement a safe business preview");
        assertThat(executed).isFalse();
    }
}
