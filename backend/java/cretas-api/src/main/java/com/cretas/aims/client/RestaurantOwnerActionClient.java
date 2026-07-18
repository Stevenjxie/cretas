package com.cretas.aims.client;

import com.cretas.aims.ai.dto.ToolCall;
import com.cretas.aims.ai.tool.gateway.ToolEgressPermit;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import okhttp3.HttpUrl;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;
import okio.BufferedSource;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Fail-closed exact-path client for restaurant owner decision analysis. */
@Component
public class RestaurantOwnerActionClient {

    public static final String TOOL_NAME = "restaurant_owner_action_advisor";
    public static final String TOOL_VERSION = "2.0.0";
    public static final String DESTINATION_ID = "python-smartbi.owner-action-chat.v1";
    public static final String OWNER_ACTION_PATH =
            "/api/smartbi/restaurant/sections/owner-action-chat";

    private static final long MAX_RESPONSE_BYTES = 1_048_576L;
    private static final int MAX_MESSAGE_LENGTH = 20_000;
    private static final int MAX_LABEL_LENGTH = 512;
    private static final MediaType JSON = Objects.requireNonNull(
            MediaType.parse("application/json; charset=utf-8"));
    private static final Set<String> ALLOWED_RESPONSE_FIELDS = Set.of(
            "sessionId",
            "scenario",
            "answer",
            "responseText",
            "followUpSuggestions",
            "charts",
            "chartGuide",
            "roleActionPlan",
            "decisionFocus",
            "ownerDecisionPage",
            "dataReadiness",
            "demoActionScenarios");
    private static final Set<String> TEXT_RESPONSE_FIELDS = Set.of(
            "sessionId", "scenario", "answer", "responseText", "chartGuide");
    private static final Set<String> ARRAY_RESPONSE_FIELDS = Set.of(
            "followUpSuggestions", "charts", "roleActionPlan", "demoActionScenarios");
    private static final Set<String> OBJECT_RESPONSE_FIELDS = Set.of(
            "decisionFocus", "ownerDecisionPage", "dataReadiness");

    private final OkHttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final HttpUrl endpoint;
    private final String internalSecret;

    public RestaurantOwnerActionClient(
            @Qualifier("aiServiceHttpClient") OkHttpClient sharedClient,
            @Qualifier("pythonAiBaseUrl") String pythonBaseUrl,
            @Qualifier("pythonAiInternalSecret") String internalSecret,
            ObjectMapper objectMapper) {
        this.httpClient = Objects.requireNonNull(sharedClient, "sharedClient")
                .newBuilder()
                .followRedirects(false)
                .followSslRedirects(false)
                .retryOnConnectionFailure(false)
                .build();
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
        this.endpoint = exactEndpoint(pythonBaseUrl);
        this.internalSecret = internalSecret == null ? "" : internalSecret.trim();
    }

    /**
     * Performs exactly one governed POST. All permit checks and configuration checks happen before
     * {@code newCall}, so rejected calls have zero network side effects.
     */
    public Map<String, Object> advise(
            ToolCall actualToolCall,
            ToolEgressPermit permit,
            TrustedContext context,
            OwnerActionRequest body) throws IOException {
        requireActualToolCall(actualToolCall);
        if (permit == null) {
            throw new SecurityException("Tool egress destination is not permitted");
        }
        permit.requireExact(
                TOOL_NAME,
                TOOL_VERSION,
                actualToolCall.getId(),
                DESTINATION_ID);
        Objects.requireNonNull(context, "context").validate();
        Objects.requireNonNull(body, "body").validate();
        if (internalSecret.isBlank()) {
            throw new OwnerActionUnavailableException();
        }

        Map<String, Object> outbound = new LinkedHashMap<>();
        outbound.put("factory_id", context.factoryId());
        outbound.put("message", body.message());
        putIfPresent(outbound, "session_id", body.sessionId());
        putIfPresent(outbound, "demo_scenario", body.demoScenario());
        putIfPresent(outbound, "store_name", body.storeName());
        putIfPresent(outbound, "sub_sector", body.subSector());
        putIfPresent(outbound, "period", body.period());

        Request request;
        try {
            request = new Request.Builder()
                    .url(endpoint)
                    .header("X-Internal-Secret", internalSecret)
                    .header("X-Factory-Id", context.factoryId())
                    .header("X-User-Id", context.userId())
                    .header("X-User-Role", context.role())
                    .header("X-Business-Type", context.businessType())
                    .header("X-Correlation-ID", context.correlationId())
                    .post(RequestBody.create(objectMapper.writeValueAsBytes(outbound), JSON))
                    .build();
        } catch (RuntimeException | IOException invalidRequest) {
            throw new OwnerActionUnavailableException();
        }

        try (Response response = httpClient.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                throw new OwnerActionUnavailableException();
            }
            MediaType responseType = MediaType.parse(response.header("Content-Type", ""));
            if (responseType == null
                    || !"application".equalsIgnoreCase(responseType.type())
                    || !"json".equalsIgnoreCase(responseType.subtype())) {
                throw new OwnerActionUnavailableException();
            }
            JsonNode root = readBoundedJson(response.body());
            JsonNode data = root == null ? null : root.get("data");
            if (root == null
                    || !root.path("success").isBoolean()
                    || !root.path("success").booleanValue()
                    || data == null
                    || !data.isObject()) {
                throw new OwnerActionUnavailableException();
            }
            validateKnownResponseTypes(data);

            ObjectNode allowed = objectMapper.createObjectNode();
            ALLOWED_RESPONSE_FIELDS.forEach(field -> {
                JsonNode value = data.get(field);
                if (value != null && !value.isNull()) {
                    allowed.set(field, value.deepCopy());
                }
            });
            return objectMapper.convertValue(
                    allowed, new TypeReference<LinkedHashMap<String, Object>>() { });
        } catch (OwnerActionUnavailableException unavailable) {
            throw unavailable;
        } catch (RuntimeException | IOException upstreamFailure) {
            throw new OwnerActionUnavailableException();
        }
    }

    private void validateKnownResponseTypes(JsonNode data) throws IOException {
        for (String field : TEXT_RESPONSE_FIELDS) {
            JsonNode value = data.get(field);
            if (value != null && !value.isNull() && !value.isTextual()) {
                throw new OwnerActionUnavailableException();
            }
        }
        for (String field : ARRAY_RESPONSE_FIELDS) {
            JsonNode value = data.get(field);
            if (value != null && !value.isNull() && !value.isArray()) {
                throw new OwnerActionUnavailableException();
            }
        }
        for (String field : OBJECT_RESPONSE_FIELDS) {
            JsonNode value = data.get(field);
            if (value != null && !value.isNull() && !value.isObject()) {
                throw new OwnerActionUnavailableException();
            }
        }
        String answer = data.path("answer").isTextual()
                ? data.path("answer").textValue()
                : null;
        String responseText = data.path("responseText").isTextual()
                ? data.path("responseText").textValue()
                : null;
        if ((answer == null || answer.isBlank())
                && (responseText == null || responseText.isBlank())) {
            throw new OwnerActionUnavailableException();
        }
    }

    private JsonNode readBoundedJson(ResponseBody responseBody) throws IOException {
        if (responseBody == null
                || responseBody.contentLength() > MAX_RESPONSE_BYTES) {
            throw new OwnerActionUnavailableException();
        }
        BufferedSource source = responseBody.source();
        source.request(MAX_RESPONSE_BYTES + 1L);
        if (source.buffer().size() > MAX_RESPONSE_BYTES) {
            throw new OwnerActionUnavailableException();
        }
        try {
            return objectMapper.readTree(source.readByteArray());
        } catch (IOException | RuntimeException malformed) {
            throw new OwnerActionUnavailableException();
        }
    }

    private static void requireActualToolCall(ToolCall toolCall) {
        if (toolCall == null
                || toolCall.getId() == null
                || toolCall.getId().isBlank()
                || toolCall.getFunction() == null
                || !TOOL_NAME.equals(toolCall.getFunction().getName())) {
            throw new SecurityException("Tool egress destination is not permitted");
        }
    }

    private static HttpUrl exactEndpoint(String pythonBaseUrl) {
        HttpUrl base;
        try {
            base = HttpUrl.get(pythonBaseUrl);
        } catch (RuntimeException invalidUrl) {
            throw new IllegalArgumentException("Invalid Python base URL", invalidUrl);
        }
        if (!Set.of("http", "https").contains(base.scheme())
                || !base.username().isEmpty()
                || !base.password().isEmpty()
                || !"/".equals(base.encodedPath())
                || base.query() != null
                || base.fragment() != null) {
            throw new IllegalArgumentException(
                    "Python base URL must contain only scheme, host and port");
        }
        return base.newBuilder().encodedPath(OWNER_ACTION_PATH).build();
    }

    private static void putIfPresent(Map<String, Object> target, String field, String value) {
        if (value != null && !value.isBlank()) {
            target.put(field, value);
        }
    }

    private static String requireText(String value, String field, int maxLength) {
        if (value == null || value.isBlank() || value.length() > maxLength) {
            throw new IllegalArgumentException(field + " is invalid");
        }
        return value;
    }

    private static String optionalText(String value, String field) {
        if (value != null && value.length() > MAX_LABEL_LENGTH) {
            throw new IllegalArgumentException(field + " is invalid");
        }
        return value;
    }

    public record TrustedContext(
            String factoryId,
            String userId,
            String role,
            String businessType,
            String correlationId) {

        void validate() {
            requireText(factoryId, "factoryId", MAX_LABEL_LENGTH);
            requireText(userId, "userId", MAX_LABEL_LENGTH);
            requireText(role, "role", MAX_LABEL_LENGTH);
            requireText(businessType, "businessType", MAX_LABEL_LENGTH);
            requireText(correlationId, "correlationId", MAX_LABEL_LENGTH);
        }
    }

    public record OwnerActionRequest(
            String message,
            String sessionId,
            String demoScenario,
            String storeName,
            String subSector,
            String period) {

        void validate() {
            requireText(message, "message", MAX_MESSAGE_LENGTH);
            optionalText(sessionId, "sessionId");
            optionalText(demoScenario, "demoScenario");
            optionalText(storeName, "storeName");
            optionalText(subSector, "subSector");
            optionalText(period, "period");
        }
    }

    /** Fixed, sanitized exception; never includes an upstream status, body, URL or payload. */
    public static final class OwnerActionUnavailableException extends IOException {
        public OwnerActionUnavailableException() {
            super("Restaurant owner action service is unavailable");
        }
    }
}
