package com.cretas.aims.ai.tool.gateway.mcp;

import java.net.URI;
import java.util.Locale;
import java.util.Objects;
import java.util.regex.Pattern;

/** Exact, locally configured destination for one outbound MCP server. */
public record MCPExternalEndpoint(String serverId, URI origin, String basePath) {

    private static final Pattern SERVER_ID = Pattern.compile("[a-z0-9][a-z0-9_-]{0,62}");

    public MCPExternalEndpoint {
        serverId = requireNonBlank(serverId, "serverId").toLowerCase(Locale.ROOT);
        if (!SERVER_ID.matcher(serverId).matches()) {
            throw new IllegalArgumentException("serverId contains unsupported characters");
        }

        URI configuredOrigin = Objects.requireNonNull(origin, "origin");
        if (!configuredOrigin.isAbsolute()
                || !"https".equalsIgnoreCase(configuredOrigin.getScheme())) {
            throw new IllegalArgumentException("MCP origin must use https");
        }
        if (configuredOrigin.getHost() == null || configuredOrigin.getHost().isBlank()) {
            throw new IllegalArgumentException("MCP origin must contain a host");
        }
        if (configuredOrigin.getUserInfo() != null
                || configuredOrigin.getQuery() != null
                || configuredOrigin.getFragment() != null) {
            throw new IllegalArgumentException("MCP origin cannot contain userinfo, query, or fragment");
        }
        String originPath = configuredOrigin.getRawPath();
        if (originPath != null && !originPath.isEmpty() && !"/".equals(originPath)) {
            throw new IllegalArgumentException("MCP origin cannot contain a path; use basePath");
        }
        origin = URI.create(configuredOrigin.getScheme().toLowerCase(Locale.ROOT) + "://"
                + configuredOrigin.getRawAuthority());

        basePath = validateBasePath(basePath);
    }

    public static MCPExternalEndpoint of(String serverId, String origin, String basePath) {
        try {
            return new MCPExternalEndpoint(serverId, URI.create(requireNonBlank(origin, "origin")), basePath);
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("Invalid MCP endpoint for server " + serverId + ": "
                    + exception.getMessage(), exception);
        }
    }

    public URI toolsListUri() {
        return exactUri("tools/list");
    }

    public URI toolsCallUri() {
        return exactUri("tools/call");
    }

    private URI exactUri(String operation) {
        URI result = URI.create(origin.toASCIIString() + basePath + "/" + operation).normalize();
        if (!Objects.equals(result.getScheme(), origin.getScheme())
                || !Objects.equals(result.getRawAuthority(), origin.getRawAuthority())
                || !Objects.equals(result.getRawPath(), basePath + "/" + operation)
                || result.getQuery() != null
                || result.getFragment() != null) {
            throw new IllegalStateException("MCP endpoint resolution changed configured origin/path");
        }
        return result;
    }

    private static String validateBasePath(String value) {
        String path = requireNonBlank(value, "basePath");
        if (!path.startsWith("/") || path.endsWith("/")) {
            throw new IllegalArgumentException("basePath must start with / and must not end with /");
        }
        if (path.contains("\\") || path.contains("//") || path.contains("?") || path.contains("#")
                || path.contains("%")) {
            throw new IllegalArgumentException("basePath contains ambiguous or unsafe characters");
        }
        for (String segment : path.split("/")) {
            if (".".equals(segment) || "..".equals(segment)) {
                throw new IllegalArgumentException("basePath cannot contain dot segments");
            }
        }
        URI parsed = URI.create("https://mcp-policy.invalid" + path);
        if (!Objects.equals(parsed.getRawPath(), path)
                || parsed.getUserInfo() != null
                || parsed.getQuery() != null
                || parsed.getFragment() != null) {
            throw new IllegalArgumentException("basePath is not an exact URI path");
        }
        return path;
    }

    private static String requireNonBlank(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value.trim();
    }
}
