package com.cretas.aims;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.data.jpa.repository.config.EnableJpaAuditing;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * 白垩纪AI Agent - Spring Boot 主应用类
 *
 * @author Cretas Team
 * @version 1.0.0
 */
@SpringBootApplication
@EnableScheduling
@EnableJpaAuditing
public class CretasBackendApplication {

    public static void main(String[] args) {
        SpringApplication.run(CretasBackendApplication.class, args);
        System.out.println("\n========================================");
        System.out.println("  Cretas Backend System Started!");
        System.out.println("  Server running on port: 10010");
        System.out.println("  TimeClock API: /api/mobile/{factoryId}/timeclock");
        System.out.println("========================================\n");
    }

    /**
     * 全局 CORS 配置.
     *
     * <p>Security HARD requirement: when {@code allowCredentials(true)} is set,
     * {@code allowedOriginPatterns} must NEVER be {@code "*"} or wildcard-too-broad.
     * A wildcard + credentials lets any malicious site read authenticated responses
     * cross-origin (per audit 2026-05-20 AUD-1 P0 finding).
     *
     * <p>Legitimate origins:
     * <ul>
     *   <li>{@code https://admin.cretaceousfuture.com} — prod web-admin (TLS)</li>
     *   <li>{@code https://*.cretaceousfuture.com} — prod sub-domains (centerapi etc.)</li>
     *   <li>{@code http://139.196.165.140:*} — legacy IP-port (test 8097 / prod 8086)</li>
     *   <li>{@code http://localhost:*} — local Vite dev (5173 / 5174 / 3000)</li>
     *   <li>{@code http://127.0.0.1:*} — local Vite dev (IPv4 loopback)</li>
     * </ul>
     */
    @Bean
    public WebMvcConfigurer corsConfigurer() {
        return new WebMvcConfigurer() {
            @Override
            public void addCorsMappings(CorsRegistry registry) {
                registry.addMapping("/api/**")
                        .allowedOriginPatterns(
                                "https://admin.cretaceousfuture.com",
                                "https://*.cretaceousfuture.com",
                                "http://139.196.165.140:*",
                                "http://localhost:*",
                                "http://127.0.0.1:*")
                        // PATCH 是 2026-08-18 补的: 仓里早已有 8+ 个 @PatchMapping 端点
                        // (RowMarkerController / CanvasAlertController / ...), 而这份白名单里
                        // 一直没有 PATCH —— 任何**跨源**浏览器客户端调它们都会在预检就被拒。
                        // web-admin 至今没被咬到, 只是因为它走同源代理 (prod 用相对路径
                        // /api/mobile + nginx, dev 用 vite proxy), 于是 CORS 根本不参与 ——
                        // 「没出事」不等于「配对了」, 换个部署形态就会当场炸。
                        .allowedMethods("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS")
                        .allowedHeaders("*")
                        .allowCredentials(true)
                        .maxAge(3600);
            }
        };
    }
}
