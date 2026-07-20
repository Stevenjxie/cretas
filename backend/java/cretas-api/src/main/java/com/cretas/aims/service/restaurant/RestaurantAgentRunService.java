package com.cretas.aims.service.restaurant;

import com.cretas.aims.client.RestaurantAgentRuntimeClient;
import com.cretas.aims.client.RestaurantAgentRuntimeClient.TrustedContext;
import com.cretas.aims.client.RestaurantAgentRuntimeClient.UpstreamHttpException;
import com.cretas.aims.client.RestaurantAgentRuntimeClient.UpstreamStream;
import com.cretas.aims.config.RestaurantAgentRuntimeProperties;
import com.cretas.aims.dto.restaurantagent.RestaurantAgentRunReplayResponse;
import com.cretas.aims.dto.restaurantagent.RestaurantAgentRunCancelResponse;
import com.cretas.aims.dto.restaurantagent.RestaurantAgentRunStartRequest;
import com.cretas.aims.service.intent.IntentConfigManagementService;
import lombok.extern.slf4j.Slf4j;
import okio.BufferedSource;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;

/** Safe Java facade over the single bounded restaurant analysis route. */
@Slf4j
@Service
public class RestaurantAgentRunService {

    private static final String RESTAURANT = "RESTAURANT";
    private static final Set<String> PRICE_VIEW_ROLES = Set.of(
            "factory_super_admin", "platform_admin", "procurement_manager",
            "finance_manager", "sales_manager", "dispatcher", "production_manager",
            "restaurant_manager", "restaurant_owner", "restaurant_purchaser",
            "permission_admin", "department_admin");

    private final RestaurantAgentRuntimeClient runtimeClient;
    private final RestaurantAgentRuntimeProperties properties;
    private final IntentConfigManagementService intentConfigManagementService;
    private final Executor executor;

    public RestaurantAgentRunService(
            RestaurantAgentRuntimeClient runtimeClient,
            RestaurantAgentRuntimeProperties properties,
            IntentConfigManagementService intentConfigManagementService,
            @Qualifier("aiAnalysisExecutor") Executor executor) {
        this.runtimeClient = runtimeClient;
        this.properties = properties;
        this.intentConfigManagementService = intentConfigManagementService;
        this.executor = executor;
    }

    public StreamResult start(
            String factoryId,
            String userId,
            String role,
            String correlationId,
            RestaurantAgentRunStartRequest request) {
        TrustedContext context = requireTrustedContext(factoryId, userId, role, correlationId);
        requireAvailable();

        final UpstreamStream upstream;
        try {
            upstream = runtimeClient.openStartStream(request, context);
        } catch (UpstreamHttpException ex) {
            throw translate(ex);
        } catch (IOException ex) {
            throw unavailable("RESTAURANT_AGENT_RUNTIME_UNREACHABLE", ex);
        }

        SseEmitter emitter = new SseEmitter(properties.getEmitterTimeoutMs());
        AtomicBoolean cleaned = new AtomicBoolean(false);
        Runnable cleanup = () -> cleanup(upstream, cleaned);
        emitter.onCompletion(cleanup);
        emitter.onTimeout(cleanup);
        emitter.onError(ignored -> cleanup.run());

        try {
            executor.execute(() -> forwardPersistedEvents(upstream, emitter, cleanup));
        } catch (RuntimeException ex) {
            cleanup.run();
            throw unavailable("RESTAURANT_AGENT_RUNTIME_EXECUTOR_UNAVAILABLE", ex);
        }

        return new StreamResult(emitter, upstream.runId());
    }

    public RestaurantAgentRunReplayResponse replay(
            String factoryId,
            String userId,
            String role,
            String correlationId,
            UUID runId,
            long afterSequence) {
        TrustedContext context = requireTrustedContext(factoryId, userId, role, correlationId);
        requireAvailable();
        try {
            return runtimeClient.replay(runId, afterSequence, context);
        } catch (UpstreamHttpException ex) {
            throw translate(ex);
        } catch (IOException ex) {
            throw unavailable("RESTAURANT_AGENT_RUNTIME_UNREACHABLE", ex);
        }
    }

    public RestaurantAgentRunCancelResponse cancel(
            String factoryId,
            String userId,
            String role,
            String correlationId,
            UUID runId) {
        TrustedContext context = requireTrustedContext(factoryId, userId, role, correlationId);
        requireAvailable();
        try {
            return runtimeClient.cancel(runId, context);
        } catch (UpstreamHttpException ex) {
            throw translate(ex);
        } catch (IOException ex) {
            throw unavailable("RESTAURANT_AGENT_RUNTIME_UNREACHABLE", ex);
        }
    }

    /**
     * Read-only admission check used by the main Chat front door before it advertises the
     * bounded restaurant runtime. The run facade repeats every check when a client follows the
     * returned endpoint; this method never creates a run or contacts Python.
     */
    public boolean isAvailableTo(String factoryId, String role) {
        if (!properties.isActive() || !hasPriceViewRole(role)) {
            return false;
        }
        try {
            return runtimeClient.isConfigured()
                    && RESTAURANT.equals(intentConfigManagementService.resolveBusinessDomain(factoryId));
        } catch (RuntimeException ex) {
            log.warn("Restaurant agent Chat admission failed closed: factoryId={}, reason={}",
                    factoryId, ex.getClass().getSimpleName());
            return false;
        }
    }

    private TrustedContext requireTrustedContext(
            String factoryId,
            String userId,
            String role,
            String correlationId) {
        if (factoryId == null || factoryId.isBlank()
                || userId == null || userId.isBlank()
                || role == null || role.isBlank()
                || correlationId == null || correlationId.isBlank()) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "TRUSTED_JWT_CONTEXT_REQUIRED");
        }
        String normalizedRole = role.trim().toLowerCase(Locale.ROOT);
        if (!hasPriceViewRole(normalizedRole)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "FINANCIAL_ACCESS_REQUIRED");
        }
        final String businessType;
        try {
            businessType = intentConfigManagementService.resolveBusinessDomain(factoryId);
        } catch (RuntimeException ex) {
            throw unavailable("RESTAURANT_BUSINESS_LOOKUP_FAILED", ex);
        }
        if (!RESTAURANT.equals(businessType)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "RESTAURANT_BUSINESS_REQUIRED");
        }
        return new TrustedContext(factoryId, userId, normalizedRole, RESTAURANT, correlationId);
    }

    private boolean hasPriceViewRole(String role) {
        return role != null && PRICE_VIEW_ROLES.contains(role.trim().toLowerCase(Locale.ROOT));
    }

    private void requireAvailable() {
        if (!properties.isActive()) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "RESTAURANT_AGENT_RUNTIME_OFF");
        }
        if (!runtimeClient.isConfigured()) {
            throw new ResponseStatusException(
                    HttpStatus.SERVICE_UNAVAILABLE,
                    "RESTAURANT_AGENT_RUNTIME_SECRET_MISSING");
        }
    }

    private void forwardPersistedEvents(
            UpstreamStream upstream,
            SseEmitter emitter,
            Runnable cleanup) {
        try {
            BufferedSource source = upstream.source();
            String id = null;
            String eventName = null;
            StringBuilder data = new StringBuilder();
            String line;
            while ((line = source.readUtf8Line()) != null) {
                if (line.isEmpty()) {
                    if (data.length() > 0) {
                        sendFrame(upstream.runId(), emitter, id, eventName, data.toString());
                    }
                    id = null;
                    eventName = null;
                    data.setLength(0);
                    continue;
                }
                if (line.startsWith(":")) {
                    continue;
                }
                int separator = line.indexOf(':');
                String field = separator >= 0 ? line.substring(0, separator) : line;
                String value = separator >= 0 ? line.substring(separator + 1) : "";
                if (value.startsWith(" ")) {
                    value = value.substring(1);
                }
                switch (field) {
                    case "id" -> id = value;
                    case "event" -> eventName = value;
                    case "data" -> {
                        if (data.length() > 0) {
                            data.append('\n');
                        }
                        data.append(value);
                    }
                    default -> {
                        // Ignore SSE retry/extension fields; never invent a downstream event.
                    }
                }
            }
            if (data.length() > 0) {
                sendFrame(upstream.runId(), emitter, id, eventName, data.toString());
            }
            emitter.complete();
        } catch (Exception ex) {
            log.warn("Restaurant agent SSE proxy ended before a clean EOF: {}",
                    ex.getClass().getSimpleName());
            emitter.completeWithError(new IOException("Restaurant agent SSE proxy failed"));
        } finally {
            cleanup.run();
        }
    }

    private void sendFrame(
            UUID expectedRunId,
            SseEmitter emitter,
            String id,
            String eventName,
            String rawData) throws IOException {
        if (id == null || !RestaurantAgentRuntimeClient.EVENT_NAME.equals(eventName)) {
            throw new IOException("Invalid restaurant agent SSE frame metadata");
        }
        runtimeClient.validateEventFrame(expectedRunId, id, rawData);
        emitter.send(SseEmitter.event()
                .id(id)
                .name(RestaurantAgentRuntimeClient.EVENT_NAME)
                .data(rawData));
    }

    private void cleanup(UpstreamStream upstream, AtomicBoolean cleaned) {
        if (!cleaned.compareAndSet(false, true)) {
            return;
        }
        upstream.cancel();
        upstream.close();
    }

    private ResponseStatusException translate(UpstreamHttpException ex) {
        return switch (ex.getStatusCode()) {
            case 404 -> new ResponseStatusException(HttpStatus.NOT_FOUND, "RUN_NOT_FOUND");
            case 422 -> new ResponseStatusException(
                    HttpStatus.UNPROCESSABLE_ENTITY, "RESTAURANT_AGENT_RUNTIME_REJECTED");
            case 503 -> unavailable("RESTAURANT_AGENT_RUNTIME_UNAVAILABLE", ex);
            default -> new ResponseStatusException(
                    HttpStatus.BAD_GATEWAY, "RESTAURANT_AGENT_RUNTIME_BAD_UPSTREAM", ex);
        };
    }

    private ResponseStatusException unavailable(String reason, Exception cause) {
        return new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, reason, cause);
    }

    public record StreamResult(SseEmitter emitter, UUID runId) {
    }
}
