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
                        .allowedMethods("GET", "POST", "PUT", "DELETE", "OPTIONS")
                        .allowedHeaders("*")
                        .allowCredentials(true)
                        .maxAge(3600);
            }
        };
    }
}

// ci push-trigger verification 20260730T063027Z
