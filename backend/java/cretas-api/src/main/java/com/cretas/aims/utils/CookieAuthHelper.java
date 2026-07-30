package com.cretas.aims.utils;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Component;

/**
 * Cookie-based authentication helper for web clients.
 * Mobile clients continue using Bearer tokens in the Authorization header.
 * Web admin uses HttpOnly cookies to prevent XSS token theft.
 *
 * @author Cretas Team
 * @since 2026-03-28
 */
@Component
public class CookieAuthHelper {

    public static final String ACCESS_TOKEN_COOKIE = "cretas_access_token";
    public static final String REFRESH_TOKEN_COOKIE = "cretas_refresh_token";
    public static final String CLIENT_TYPE_HEADER = "X-Client-Type";
    public static final String CLIENT_TYPE_WEB = "web";

    private static final String REFRESH_TOKEN_PATH = "/api/mobile/auth/refresh";

    @Value("${cretas.cookie.secure:false}")
    private boolean secureCookie;

    @Value("${cretas.cookie.same-site:Lax}")
    private String sameSite;

    @Value("${cretas.jwt.expiration:86400000}")
    private long accessTokenExpirationMs;

    @Value("${cretas.jwt.refresh-expiration:2592000000}")
    private long refreshTokenExpirationMs;

    /**
     * Check if the request comes from a web client (Vue admin).
     * Detection: X-Client-Type: web header.
     */
    public boolean isWebClient(HttpServletRequest request) {
        String clientType = request.getHeader(CLIENT_TYPE_HEADER);
        return CLIENT_TYPE_WEB.equalsIgnoreCase(clientType);
    }

    /**
     * Set authentication cookies on the response (for web clients).
     */
    public void setAuthCookies(HttpServletResponse response, String accessToken, String refreshToken) {
        long accessMaxAge = accessTokenExpirationMs / 1000; // convert ms to seconds
        long refreshMaxAge = refreshTokenExpirationMs / 1000;

        ResponseCookie accessCookie = ResponseCookie.from(ACCESS_TOKEN_COOKIE, accessToken)
                .httpOnly(true)
                .secure(secureCookie)
                .sameSite(sameSite)
                .path("/")
                .maxAge(accessMaxAge)
                .build();

        ResponseCookie refreshCookie = ResponseCookie.from(REFRESH_TOKEN_COOKIE, refreshToken)
                .httpOnly(true)
                .secure(secureCookie)
                .sameSite(sameSite)
                .path(REFRESH_TOKEN_PATH)
                .maxAge(refreshMaxAge)
                .build();

        // Use addHeader (not setHeader) so both Set-Cookie headers are sent
        response.addHeader("Set-Cookie", accessCookie.toString());
        response.addHeader("Set-Cookie", refreshCookie.toString());
    }

    /**
     * Clear authentication cookies (for logout).
     */
    public void clearAuthCookies(HttpServletResponse response) {
        ResponseCookie accessCookie = ResponseCookie.from(ACCESS_TOKEN_COOKIE, "")
                .httpOnly(true)
                .secure(secureCookie)
                .sameSite(sameSite)
                .path("/")
                .maxAge(0)
                .build();

        ResponseCookie refreshCookie = ResponseCookie.from(REFRESH_TOKEN_COOKIE, "")
                .httpOnly(true)
                .secure(secureCookie)
                .sameSite(sameSite)
                .path(REFRESH_TOKEN_PATH)
                .maxAge(0)
                .build();

        response.addHeader("Set-Cookie", accessCookie.toString());
        response.addHeader("Set-Cookie", refreshCookie.toString());
    }

    /**
     * Extract a cookie value by name from the request.
     * Returns null if the cookie is not present.
     */
    public static String extractCookieValue(HttpServletRequest request, String cookieName) {
        Cookie[] cookies = request.getCookies();
        if (cookies == null) {
            return null;
        }
        for (Cookie cookie : cookies) {
            if (cookieName.equals(cookie.getName())) {
                String value = cookie.getValue();
                if (value != null && !value.isEmpty()) {
                    return value;
                }
            }
        }
        return null;
    }
}
