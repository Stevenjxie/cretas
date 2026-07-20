package com.cretas.aims.service.restaurant;

import com.cretas.aims.dto.ai.IntentExecuteResponse;
import com.cretas.aims.dto.restaurantagent.RestaurantAgentRunStartRequest;
import org.springframework.stereotype.Service;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * High-precision selector for the single bounded restaurant analysis route exposed in Chat.
 *
 * <p>This component only returns a typed launch instruction. It does not start a Python run and
 * never returns financial data. The authenticated {@code /restaurant-agent/runs} facade remains
 * the sole run entry point and repeats authorization, tenant and rollout checks.</p>
 */
@Service
public class RestaurantGrossMarginChatRouteSelector {

    public static final String INTENT_CODE = RestaurantAgentRunStartRequest.ROUTE_CODE;
    private static final String SCHEMA_VERSION = RestaurantAgentRunStartRequest.SCHEMA_VERSION;
    private static final ZoneId BUSINESS_ZONE = ZoneId.of("Asia/Shanghai");

    private static final Pattern CURRENT_MONTH = Pattern.compile("本月|这个月|这月|当月");
    private static final Pattern GROSS_MARGIN = Pattern.compile("毛利(?:率)?");
    private static final Pattern DECLINE = Pattern.compile("下降|下滑|降低|走低|变低|掉了|掉下|减少");
    private static final Pattern ANALYZE = Pattern.compile("为什么|为何|原因|分析|归因|怎么回事|哪里出了问题");
    private static final Pattern NEGATION = Pattern.compile(
            "不要|不用|别|无需|不需要|不想|没有|没下降|未下降|并未|不是");

    private final RestaurantAgentRunService runService;
    private final Clock clock;

    public RestaurantGrossMarginChatRouteSelector(RestaurantAgentRunService runService) {
        this(runService, Clock.system(BUSINESS_ZONE));
    }

    RestaurantGrossMarginChatRouteSelector(RestaurantAgentRunService runService, Clock clock) {
        this.runService = runService;
        this.clock = clock;
    }

    public Optional<IntentExecuteResponse> select(String factoryId, String userInput, String userRole) {
        if (factoryId == null || factoryId.isBlank()
                || !matches(userInput)
                || !runService.isAvailableTo(factoryId, userRole)) {
            return Optional.empty();
        }

        LocalDate endDate = LocalDate.now(clock);
        LocalDate startDate = endDate.withDayOfMonth(1);
        String startEndpoint = "/api/mobile/"
                + URLEncoder.encode(factoryId, StandardCharsets.UTF_8)
                + "/restaurant-agent/runs";
        Map<String, Object> agentRun = Map.of(
                "schemaVersion", SCHEMA_VERSION,
                "routeCode", INTENT_CODE,
                "startDate", startDate.toString(),
                "endDate", endDate.toString(),
                "startEndpoint", startEndpoint,
                "autoStart", true);

        String message = "已为你准备本月毛利下降归因，将在当前消息中启动只读分析。";
        return Optional.of(IntentExecuteResponse.builder()
                .intentRecognized(true)
                .intentCode(INTENT_CODE)
                .intentName("本月毛利下降归因")
                .intentCategory("ANALYSIS")
                .sensitivityLevel("HIGH")
                .confidence(1.0d)
                .matchMethod("DETERMINISTIC")
                .status("READY")
                .message(message)
                .formattedText(message)
                .metadata(Map.of("agentRun", agentRun))
                .executedAt(java.time.LocalDateTime.now(clock))
                .build());
    }

    boolean matches(String userInput) {
        if (userInput == null) {
            return false;
        }
        String normalized = userInput.replaceAll("\\s+", "");
        if (normalized.isEmpty() || NEGATION.matcher(normalized).find()) {
            return false;
        }
        return CURRENT_MONTH.matcher(normalized).find()
                && GROSS_MARGIN.matcher(normalized).find()
                && DECLINE.matcher(normalized).find()
                && ANALYZE.matcher(normalized).find();
    }
}
