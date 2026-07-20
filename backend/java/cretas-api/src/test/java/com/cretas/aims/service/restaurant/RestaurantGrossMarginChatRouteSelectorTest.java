package com.cretas.aims.service.restaurant;

import com.cretas.aims.dto.ai.IntentExecuteResponse;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RestaurantGrossMarginChatRouteSelectorTest {

    private static final Clock CLOCK = Clock.fixed(
            Instant.parse("2026-07-20T04:00:00Z"), ZoneId.of("Asia/Shanghai"));

    @Test
    void selectsOnlyHighPrecisionDeclineAttributionAndReturnsBoundedLaunchMetadata() {
        RestaurantAgentRunService runService = mock(RestaurantAgentRunService.class);
        when(runService.isAvailableTo("REST-1", "restaurant_owner")).thenReturn(true);
        RestaurantGrossMarginChatRouteSelector selector =
                new RestaurantGrossMarginChatRouteSelector(runService, CLOCK);

        IntentExecuteResponse response = selector.select(
                "REST-1", "为什么本月毛利下降？帮我分析原因", "restaurant_owner")
                .orElseThrow();

        assertThat(response.getIntentCode()).isEqualTo("GROSS_MARGIN_DECLINE_ATTRIBUTION");
        assertThat(response.getStatus()).isEqualTo("READY");
        assertThat(response.getResultData()).isNull();
        assertThat(response.getMetadata()).containsOnlyKeys("agentRun");
        assertThat(response.getMetadata().get("agentRun")).isEqualTo(Map.of(
                "schemaVersion", "1.0",
                "routeCode", "GROSS_MARGIN_DECLINE_ATTRIBUTION",
                "startDate", "2026-07-01",
                "endDate", "2026-07-20",
                "startEndpoint", "/api/mobile/REST-1/restaurant-agent/runs",
                "autoStart", true));
        verify(runService).isAvailableTo("REST-1", "restaurant_owner");
    }

    @Test
    void rejectsSimpleValueQuestionsNegationAndNearMissesBeforeAvailabilityLookup() {
        RestaurantAgentRunService runService = mock(RestaurantAgentRunService.class);
        RestaurantGrossMarginChatRouteSelector selector =
                new RestaurantGrossMarginChatRouteSelector(runService, CLOCK);

        assertThat(selector.select("REST-1", "本月毛利是多少", "restaurant_owner")).isEmpty();
        assertThat(selector.select("REST-1", "不要分析本月毛利下降", "restaurant_owner")).isEmpty();
        assertThat(selector.select("REST-1", "为什么本月营收下降", "restaurant_owner")).isEmpty();
        assertThat(selector.select("REST-1", "为什么上月毛利下降", "restaurant_owner")).isEmpty();
        assertThat(selector.select("REST-1", "本月毛利下降了", "restaurant_owner")).isEmpty();
        assertThat(selector.select("", "分析本月毛利下降原因", "restaurant_owner")).isEmpty();

        org.mockito.Mockito.verifyNoInteractions(runService);
    }

    @Test
    void availabilityFailureFallsThroughWithoutExposingLaunchMetadata() {
        RestaurantAgentRunService runService = mock(RestaurantAgentRunService.class);
        when(runService.isAvailableTo("F006", "viewer")).thenReturn(false);
        RestaurantGrossMarginChatRouteSelector selector =
                new RestaurantGrossMarginChatRouteSelector(runService, CLOCK);

        assertThat(selector.select("F006", "分析本月毛利下滑原因", "viewer")).isEmpty();
        verify(runService).isAvailableTo("F006", "viewer");
    }
}
