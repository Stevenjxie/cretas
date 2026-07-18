package com.cretas.aims.service.execution;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/**
 * Builds execution arguments from untrusted request data while binding the
 * authenticated principal supplied by the server-side call path.
 */
final class TrustedExecutionContext {

    private static final Set<String> RESERVED_PRINCIPAL_KEYS = Set.of(
            "factoryId", "factory_id",
            "tenantId", "tenant_id",
            "userId", "user_id",
            "actorUserId", "actor_user_id",
            "userRole", "user_role",
            "role");

    private TrustedExecutionContext() {
    }

    static Map<String, Object> merge(Map<String, Object> requestContext,
                                     String factoryId,
                                     Long userId,
                                     String userRole) {
        Map<String, Object> trustedContext = new HashMap<>();
        if (requestContext != null) {
            trustedContext.putAll(requestContext);
        }
        enforcePrincipal(trustedContext, factoryId, userId, userRole);
        return trustedContext;
    }

    static void enforcePrincipal(Map<String, Object> context,
                                 String factoryId,
                                 Long userId,
                                 String userRole) {
        // Remove every accepted identity spelling first. This prevents request, LLM or
        // planner output from retaining an alternative alias alongside the trusted one.
        context.keySet().removeIf(RESERVED_PRINCIPAL_KEYS::contains);

        // Keep both legacy aliases bound to the same server-derived tenant.
        context.put("factoryId", factoryId);
        context.put("factory_id", factoryId);
        context.put("tenantId", factoryId);
        context.put("tenant_id", factoryId);

        // Bind both user aliases so tool arguments, RBAC and cache keys agree.
        context.put("userId", userId);
        context.put("user_id", userId);
        context.put("actorUserId", userId);
        context.put("actor_user_id", userId);

        // Some legacy governance tools read role while the main path reads userRole.
        context.put("userRole", userRole);
        context.put("user_role", userRole);
        context.put("role", userRole);
    }
}
