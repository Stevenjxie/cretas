package com.cretas.aims.integration;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Locale;
import java.util.regex.Pattern;

/** Prevents the opt-in verifier from ever targeting a shared or remote database. */
final class DisposablePostgresTargetGuard {

    private static final String DATABASE_PREFIX = "cretas_workflow_verify_";
    private static final Pattern SAFE_DATABASE_PATH = Pattern.compile(
            "^/" + DATABASE_PREFIX + "[A-Za-z0-9][A-Za-z0-9_-]*$");

    private DisposablePostgresTargetGuard() {
    }

    static String requireSafeUrl(String jdbcUrl) {
        if (jdbcUrl == null || jdbcUrl.isBlank() || !jdbcUrl.equals(jdbcUrl.trim())) {
            throw unsafe("URL is missing or padded with whitespace");
        }
        if (!jdbcUrl.startsWith("jdbc:")) {
            throw unsafe("URL must use the JDBC PostgreSQL format");
        }

        URI uri;
        try {
            uri = new URI(jdbcUrl.substring("jdbc:".length()));
        } catch (URISyntaxException error) {
            throw unsafe("URL is malformed", error);
        }

        if (!"postgresql".equals(uri.getScheme())) {
            throw unsafe("only the PostgreSQL driver is allowed");
        }
        if (uri.getRawUserInfo() != null || uri.getRawQuery() != null || uri.getRawFragment() != null) {
            throw unsafe("userinfo, query parameters, and fragments are not allowed");
        }

        String host = uri.getHost();
        if (host == null) {
            throw unsafe("an explicit local host is required");
        }
        String normalizedHost = host.toLowerCase(Locale.ROOT);
        if (!"localhost".equals(normalizedHost) && !"127.0.0.1".equals(normalizedHost)) {
            throw unsafe("host must be localhost or 127.0.0.1");
        }
        if (uri.getPort() == 0 || uri.getPort() > 65535) {
            throw unsafe("port is invalid");
        }

        String rawPath = uri.getRawPath();
        if (rawPath == null || !SAFE_DATABASE_PATH.matcher(rawPath).matches()) {
            throw unsafe("database must be one plain path segment beginning with " + DATABASE_PREFIX);
        }
        if (!rawPath.equals(uri.getPath())) {
            throw unsafe("encoded database paths are not allowed");
        }

        return jdbcUrl;
    }

    private static IllegalArgumentException unsafe(String reason) {
        return new IllegalArgumentException("Unsafe PostgreSQL verification target: " + reason);
    }

    private static IllegalArgumentException unsafe(String reason, Exception cause) {
        return new IllegalArgumentException("Unsafe PostgreSQL verification target: " + reason, cause);
    }
}
