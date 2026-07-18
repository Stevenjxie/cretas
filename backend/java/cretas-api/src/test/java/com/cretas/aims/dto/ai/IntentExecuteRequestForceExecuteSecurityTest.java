package com.cretas.aims.dto.ai;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class IntentExecuteRequestForceExecuteSecurityTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void publicJsonCannotEnableForceExecute() throws Exception {
        IntentExecuteRequest request = objectMapper.readValue(
                "{\"intentCode\":\"MATERIAL_BATCH_QUERY\",\"forceExecute\":true}",
                IntentExecuteRequest.class);

        assertThat(request.getIntentCode()).isEqualTo("MATERIAL_BATCH_QUERY");
        assertThat(request.getForceExecute()).isFalse();
    }

    @Test
    void serverSideSetterAndBuilderCanStillEnableForceExecute() {
        IntentExecuteRequest setterRequest = new IntentExecuteRequest();
        setterRequest.setForceExecute(true);

        IntentExecuteRequest builderRequest = IntentExecuteRequest.builder()
                .forceExecute(true)
                .build();

        assertThat(setterRequest.getForceExecute()).isTrue();
        assertThat(builderRequest.getForceExecute()).isTrue();
    }

    @Test
    void serializationKeepsReadOnlyCompatibility() throws Exception {
        IntentExecuteRequest internalRequest = IntentExecuteRequest.builder()
                .forceExecute(true)
                .build();

        assertThat(objectMapper.readTree(objectMapper.writeValueAsString(internalRequest))
                .path("forceExecute").asBoolean()).isTrue();
    }
}
