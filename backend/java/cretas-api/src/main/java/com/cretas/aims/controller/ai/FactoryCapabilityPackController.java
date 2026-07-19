package com.cretas.aims.controller.ai;

import com.cretas.aims.ai.capability.FactoryCapabilityPack;
import com.cretas.aims.ai.capability.FactoryBusinessTypeResolver;
import com.cretas.aims.ai.capability.FactoryCapabilityPackSelector;
import com.cretas.aims.dto.capability.FactoryCapabilityPackMatchRequest;
import com.cretas.aims.dto.capability.FactoryCapabilityPackMatchResponse;
import com.cretas.aims.dto.capability.FactoryCapabilityPackSummary;
import com.cretas.aims.dto.common.ApiResponse;
import com.cretas.aims.entity.enums.FactoryType;
import com.cretas.aims.entity.enums.FactoryUserRole;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import static org.springframework.http.HttpStatus.BAD_REQUEST;
import static org.springframework.http.HttpStatus.FORBIDDEN;
import static org.springframework.http.HttpStatus.UNAUTHORIZED;

/** Read-only API for selecting static factory capability configuration. */
@RestController
@RequestMapping("/api/mobile/{factoryId}/ai/capability-pack")
public final class FactoryCapabilityPackController {
    private final FactoryCapabilityPackSelector selector;
    private final FactoryBusinessTypeResolver businessTypeResolver;

    public FactoryCapabilityPackController(
            FactoryCapabilityPackSelector selector,
            FactoryBusinessTypeResolver businessTypeResolver) {
        this.selector = selector;
        this.businessTypeResolver = businessTypeResolver;
    }

    @GetMapping
    public ResponseEntity<ApiResponse<FactoryCapabilityPackMatchResponse>> current(
            @PathVariable String factoryId,
            HttpServletRequest request) {
        rejectQueryParameters(request);
        FactoryUserRole role = trustedRole(factoryId, request);
        FactoryType businessType = trustedBusinessType(factoryId);
        FactoryCapabilityPack pack = selector.select(role, businessType)
                .orElseThrow(() -> new ResponseStatusException(
                        FORBIDDEN, "no capability pack for trusted principal"));
        return ResponseEntity.ok(ApiResponse.success(
                FactoryCapabilityPackMatchResponse.matched(
                        FactoryCapabilityPackSummary.from(pack))));
    }

    @PostMapping("/match")
    public ResponseEntity<ApiResponse<FactoryCapabilityPackMatchResponse>> match(
            @PathVariable String factoryId,
            @Valid @RequestBody FactoryCapabilityPackMatchRequest body,
            HttpServletRequest request) {
        rejectQueryParameters(request);
        FactoryUserRole role = trustedRole(factoryId, request);
        FactoryType businessType = trustedBusinessType(factoryId);
        if (selector.select(role, businessType).isEmpty()) {
            throw new ResponseStatusException(FORBIDDEN, "no capability pack for trusted principal");
        }
        FactoryCapabilityPackMatchResponse response = selector
                .match(role, businessType, body.getQuery())
                .map(pack -> FactoryCapabilityPackMatchResponse.matched(
                        FactoryCapabilityPackSummary.from(pack)))
                .orElseGet(FactoryCapabilityPackMatchResponse::noMatch);
        return ResponseEntity.ok(ApiResponse.success(response));
    }

    private static FactoryUserRole trustedRole(
            String pathFactoryId, HttpServletRequest request) {
        Object trustedFactoryId = request.getAttribute("factoryId");
        Object trustedUserId = request.getAttribute("userId");
        Object trustedRole = request.getAttribute("role");
        if (!(trustedFactoryId instanceof String factoryId)
                || trustedUserId == null
                || !(trustedRole instanceof String roleValue)) {
            throw new ResponseStatusException(UNAUTHORIZED, "trusted principal is required");
        }
        if (!safeIdentifier(pathFactoryId) || !pathFactoryId.equals(factoryId)) {
            throw new ResponseStatusException(FORBIDDEN, "factory scope mismatch");
        }
        String userId = String.valueOf(trustedUserId);
        if (!safeIdentifier(userId)) {
            throw new ResponseStatusException(UNAUTHORIZED, "trusted principal is invalid");
        }
        FactoryUserRole role;
        try {
            role = "production_manager".equals(roleValue)
                    ? FactoryUserRole.dispatcher
                    : FactoryUserRole.valueOf(roleValue);
        } catch (IllegalArgumentException invalidPrincipal) {
            throw new ResponseStatusException(FORBIDDEN, "trusted role is invalid");
        }
        if (role == FactoryUserRole.unactivated) {
            throw new ResponseStatusException(FORBIDDEN, "trusted role is outside pack scope");
        }
        return role;
    }

    private FactoryType trustedBusinessType(String factoryId) {
        FactoryType type = businessTypeResolver.resolveActive(factoryId)
                .orElseThrow(() -> new ResponseStatusException(
                        FORBIDDEN, "active factory truth is unavailable"));
        if (type != FactoryType.FACTORY && type != FactoryType.CENTRAL_KITCHEN) {
            throw new ResponseStatusException(FORBIDDEN, "factory type is outside pack scope");
        }
        return type;
    }

    private static void rejectQueryParameters(HttpServletRequest request) {
        if (!request.getParameterMap().isEmpty()) {
            throw new ResponseStatusException(BAD_REQUEST, "query parameters are not supported");
        }
    }

    private static boolean safeIdentifier(String value) {
        return value != null && value.matches("^[A-Za-z0-9][A-Za-z0-9._:-]{0,127}$");
    }
}
