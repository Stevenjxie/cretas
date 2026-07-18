package com.cretas.aims.ai.tool.gateway;

import java.util.Set;

/**
 * Authenticated identity supplied by the trusted caller of the gateway.
 *
 * <p>This identity is deliberately not constructed from tool parameters. A tool parameter may
 * identify a business target, but it cannot authenticate the actor executing the command.</p>
 */
public record ExecutionPrincipal(
        String tenantId,
        String businessType,
        String principalId,
        PrincipalType principalType,
        Set<String> roles,
        Set<String> permissions,
        Set<String> scopes) {

    public ExecutionPrincipal {
        tenantId = ContractValidation.requireNonBlank(tenantId, "tenantId");
        businessType = ContractValidation.requireNonBlank(businessType, "businessType");
        principalId = ContractValidation.requireNonBlank(principalId, "principalId");
        principalType = ContractValidation.requireNonNull(principalType, "principalType");
        roles = ContractValidation.immutableNonBlankSet(roles, "roles");
        permissions = ContractValidation.immutableNonBlankSet(permissions, "permissions");
        scopes = ContractValidation.immutableNonBlankSet(scopes, "scopes");
        if (principalType == PrincipalType.USER && roles.isEmpty()) {
            throw new IllegalArgumentException("USER principals require at least one role");
        }
        if (principalType != PrincipalType.USER && scopes.isEmpty()) {
            throw new IllegalArgumentException(
                    principalType + " principals require at least one scope");
        }
    }
}
