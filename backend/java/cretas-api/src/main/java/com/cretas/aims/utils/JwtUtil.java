package com.cretas.aims.utils;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.MalformedJwtException;
import io.jsonwebtoken.SignatureAlgorithm;
import io.jsonwebtoken.UnsupportedJwtException;
import io.jsonwebtoken.security.Keys;
import io.jsonwebtoken.security.SignatureException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * JWT工具类
 *
 * @author Cretas Team
 * @version 1.0.0
 * @since 2025-01-09
 */
@Slf4j
@Component
public class JwtUtil {

    @Value("${cretas.jwt.secret:your-jwt-secret-key-here-please-change-in-production}")
    private String secret;

    @Value("${cretas.jwt.expiration:86400000}")
    private Long expiration; // 默认24小时

    @Value("${cretas.jwt.refresh-expiration:2592000000}")
    private Long refreshExpiration; // 默认30天

    /**
     * 生成密钥
     */
    private SecretKey getSigningKey() {
        byte[] keyBytes = secret.getBytes(StandardCharsets.UTF_8);
        // 确保密钥长度至少为256位（32字节）
        if (keyBytes.length < 32) {
            byte[] newKeyBytes = new byte[32];
            System.arraycopy(keyBytes, 0, newKeyBytes, 0, keyBytes.length);
            keyBytes = newKeyBytes;
        }
        return Keys.hmacShaKeyFor(keyBytes);
    }

    /**
     * 生成Token（包含角色信息）
     */
    public String generateToken(Long userId, String factoryId, String username, String role) {
        Map<String, Object> claims = new HashMap<>();
        claims.put("userId", userId);
        claims.put("factoryId", factoryId);
        claims.put("username", username);
        claims.put("role", role);
        return createToken(claims, username);
    }

    /**
     * 生成Token（兼容旧版本，不包含角色）
     */
    public String generateToken(Long userId, String factoryId, String username) {
        Map<String, Object> claims = new HashMap<>();
        claims.put("userId", userId);
        claims.put("factoryId", factoryId);
        claims.put("username", username);
        return createToken(claims, username);
    }

    /**
     * 创建Token
     */
    private String createToken(Map<String, Object> claims, String subject) {
        Date now = new Date();
        Date expiryDate = new Date(now.getTime() + expiration);
        return Jwts.builder()
                .setClaims(claims)
                .setId(UUID.randomUUID().toString())
                .setSubject(subject)
                .setIssuedAt(now)
                .setExpiration(expiryDate)
                .signWith(getSigningKey(), SignatureAlgorithm.HS256)
                .compact();
    }

    /**
     * 验证Token
     */
    public boolean validateToken(String token) {
        // Short-circuit null/empty before jjwt throws IllegalArgumentException.
        // Hitting an unauthenticated endpoint with no Authorization header is
        // routine — logging this as ERROR ("JWT String argument cannot be null
        // or empty") was just noise.
        if (token == null || token.isEmpty()) {
            log.debug("Token 为空 (预期, 客户端未携带 Authorization)");
            return false;
        }
        try {
            Jwts.parserBuilder()
                    .setSigningKey(getSigningKey())
                    .build()
                    .parseClaimsJws(token);
            return true;
        } catch (ExpiredJwtException e) {
            // Expected — access tokens expire after 24h, refresh after 7d.
            // Client should refresh or re-login. Not a server bug.
            log.debug("Token 已过期 (预期): {}", e.getMessage());
            return false;
        } catch (MalformedJwtException | UnsupportedJwtException | SignatureException e) {
            // Client sent a token that's not JWT / wrong format / forged signature.
            // Still "client problem", not server — WARN so it's searchable if
            // someone suspects abuse, but not ERROR-level pager noise.
            log.warn("Token 格式/签名无效: {}", e.getMessage());
            return false;
        } catch (IllegalArgumentException e) {
            // jjwt throws this for null/empty token after we've already
            // short-circuited, OR for oddly-formed strings that aren't caught
            // by MalformedJwtException. Still a client-side problem.
            log.debug("Token 参数非法: {}", e.getMessage());
            return false;
        } catch (Exception e) {
            // Anything else is unexpected — keep ERROR so real bugs surface.
            log.error("Token验证失败 (未预期异常): {}", e.getMessage());
            return false;
        }
    }

    /**
     * 从Token中获取Claims
     */
    private Claims getClaimsFromToken(String token) {
        if (token == null || token.isEmpty()) {
            log.debug("Token 为空, 无 Claims 可解析");
            return null;
        }
        try {
            return Jwts.parserBuilder()
                    .setSigningKey(getSigningKey())
                    .build()
                    .parseClaimsJws(token)
                    .getBody();
        } catch (ExpiredJwtException e) {
            log.debug("Token 已过期, 无法解析 Claims (预期): {}", e.getMessage());
            return null;
        } catch (MalformedJwtException | UnsupportedJwtException | SignatureException e) {
            log.warn("Token 格式/签名无效, 无法解析 Claims: {}", e.getMessage());
            return null;
        } catch (IllegalArgumentException e) {
            log.debug("Token 参数非法, 无法解析 Claims: {}", e.getMessage());
            return null;
        } catch (Exception e) {
            log.error("解析Token失败 (未预期异常): {}", e.getMessage());
            return null;
        }
    }

    /**
     * 从Token中获取用户ID
     */
    public Long getUserIdFromToken(String token) {
        Claims claims = getClaimsFromToken(token);
        if (claims != null) {
            Object userId = claims.get("userId");
            if (userId != null) {
                // 兼容字符串和整数两种格式
                if (userId instanceof Long) {
                    return (Long) userId;
                } else if (userId instanceof Integer) {
                    return ((Integer) userId).longValue();
                } else if (userId instanceof String) {
                    // 移除 "platform_" 前缀（如果存在）
                    String userIdStr = (String) userId;
                    if (userIdStr.startsWith("platform_")) {
                        userIdStr = userIdStr.substring(9); // 移除 "platform_" 前缀
                    }
                    try {
                        return Long.parseLong(userIdStr);
                    } catch (NumberFormatException e) {
                        log.error("无法将userId字符串转换为整数: {}", userIdStr);
                        return null;
                    }
                } else if (userId instanceof Number) {
                    return ((Number) userId).longValue();
                }
            }
        }
        return null;
    }

    /**
     * 从Token中获取工厂ID
     */
    public String getFactoryIdFromToken(String token) {
        Claims claims = getClaimsFromToken(token);
        if (claims != null) {
            return claims.get("factoryId", String.class);
        }
        return null;
    }

    /**
     * 从Token中获取用户名
     */
    public String getUsernameFromToken(String token) {
        Claims claims = getClaimsFromToken(token);
        if (claims != null) {
            return claims.getSubject();
        }
        return null;
    }

    /**
     * 从Token中获取过期时间
     *
     * @return 过期时间，解析失败返回null
     */
    public Date getExpirationDateFromToken(String token) {
        Claims claims = getClaimsFromToken(token);
        if (claims != null) {
            return claims.getExpiration();
        }
        return null;
    }

    /**
     * 获取令牌的唯一会话标识。
     */
    public String getTokenId(String token) {
        Claims claims = getClaimsFromToken(token);
        return claims != null ? claims.getId() : null;
    }

    /**
     * 检查Token是否过期
     */
    public boolean isTokenExpired(String token) {
        try {
            Claims claims = getClaimsFromToken(token);
            if (claims != null) {
                Date expiration = claims.getExpiration();
                return expiration.before(new Date());
            }
            return true;
        } catch (Exception e) {
            return true;
        }
    }

    /**
     * 生成简单Token（只包含用户ID和角色）
     * 用于移动端等简化场景
     */
    public String generateToken(String userId, String role) {
        Map<String, Object> claims = new HashMap<>();
        claims.put("userId", userId);
        claims.put("role", role);
        return createToken(claims, userId);
    }

    /**
     * 生成简单Token（只包含用户ID，兼容旧版本）
     * 用于移动端等简化场景
     */
    public String generateToken(String userId) {
        Map<String, Object> claims = new HashMap<>();
        claims.put("userId", userId);
        return createToken(claims, userId);
    }

    /**
     * 生成刷新Token
     */
    public String generateRefreshToken(String userId) {
        Map<String, Object> claims = new HashMap<>();
        claims.put("userId", userId);
        claims.put("type", "refresh");
        Date now = new Date();
        Date expiryDate = new Date(now.getTime() + refreshExpiration);
        return Jwts.builder()
                .setClaims(claims)
                .setId(UUID.randomUUID().toString())
                .setSubject(userId)
                .setIssuedAt(now)
                .setExpiration(expiryDate)
                .signWith(getSigningKey(), SignatureAlgorithm.HS256)
                .compact();
    }

    /**
     * 从Token中获取用户ID（字符串格式）
     * 兼容方法，用于移动端
     */
    public String getUserIdFromTokenAsString(String token) {
        Claims claims = getClaimsFromToken(token);
        if (claims != null) {
            Object userId = claims.get("userId");
            return userId != null ? userId.toString() : null;
        }
        return null;
    }

    /**
     * 从Token中获取角色
     */
    public String getRoleFromToken(String token) {
        Claims claims = getClaimsFromToken(token);
        if (claims != null) {
            return claims.get("role", String.class);
        }
        return null;
    }
}
