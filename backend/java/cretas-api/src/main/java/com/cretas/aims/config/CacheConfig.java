package com.cretas.aims.config;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.cache.caffeine.CaffeineCache;
import org.springframework.cache.support.SimpleCacheManager;
import com.github.benmanes.caffeine.cache.Caffeine;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.data.redis.cache.RedisCacheConfiguration;
import org.springframework.data.redis.cache.RedisCacheManager;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.serializer.GenericJackson2JsonRedisSerializer;
import org.springframework.data.redis.serializer.RedisSerializationContext;
import org.springframework.data.redis.serializer.StringRedisSerializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

/**
 * 缓存配置 - 支持 Redis 和内存缓存降级
 *
 * 当 Redis 可用时使用 Redis 缓存，否则降级到内存缓存
 *
 * @author Cretas Team
 * @version 1.0.0
 * @since 2026-01-02
 */
@Configuration
@EnableCaching
public class CacheConfig {

    private static final Logger log = LoggerFactory.getLogger(CacheConfig.class);

    @Autowired(required = false)
    private RedisConnectionFactory redisConnectionFactory;

    /**
     * 创建支持 Java 8 时间类型的 Redis JSON 序列化器
     */
    private GenericJackson2JsonRedisSerializer createRedisSerializer() {
        ObjectMapper om = new ObjectMapper();
        om.registerModule(new JavaTimeModule());
        om.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        om.disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
        om.activateDefaultTyping(om.getPolymorphicTypeValidator(),
                ObjectMapper.DefaultTyping.NON_FINAL);
        return new GenericJackson2JsonRedisSerializer(om);
    }

    /**
     * RedisTemplate<String, Object> — CacheService 需要此类型
     */
    @Bean
    public RedisTemplate<String, Object> redisObjectTemplate() {
        if (redisConnectionFactory == null) {
            return null;
        }
        GenericJackson2JsonRedisSerializer serializer = createRedisSerializer();
        RedisTemplate<String, Object> template = new RedisTemplate<>();
        template.setConnectionFactory(redisConnectionFactory);
        template.setKeySerializer(new StringRedisSerializer());
        template.setValueSerializer(serializer);
        template.setHashKeySerializer(new StringRedisSerializer());
        template.setHashValueSerializer(serializer);
        return template;
    }

    /**
     * 缓存管理器 — Redis 可用时用 Redis，否则内存降级
     */
    @Bean
    @Primary
    public CacheManager cacheManager() {
        if (redisConnectionFactory != null) {
            return buildRedisCacheManager(redisConnectionFactory);
        }
        return buildSimpleCacheManager();
    }

    private CacheManager buildRedisCacheManager(RedisConnectionFactory connectionFactory) {
        log.info("使用 Redis 缓存管理器");

        RedisCacheConfiguration defaultConfig = RedisCacheConfiguration.defaultCacheConfig()
                .entryTtl(Duration.ofHours(1))
                .serializeValuesWith(RedisSerializationContext.SerializationPair
                        .fromSerializer(createRedisSerializer()));

        Map<String, RedisCacheConfiguration> cacheConfigurations = new HashMap<>();
        cacheConfigurations.put("aiAnalysisResults", defaultConfig.entryTtl(Duration.ofDays(7)));
        cacheConfigurations.put("aiIntents", defaultConfig.entryTtl(Duration.ofHours(1)));
        cacheConfigurations.put("aiQuota", defaultConfig.entryTtl(Duration.ofHours(1)));
        cacheConfigurations.put("dashboardStats", defaultConfig.entryTtl(Duration.ofMinutes(5)));
        // AI意图服务缓存
        cacheConfigurations.put("allIntents", defaultConfig.entryTtl(Duration.ofHours(1)));
        cacheConfigurations.put("intentsByCategory", defaultConfig.entryTtl(Duration.ofHours(1)));
        cacheConfigurations.put("intentCategories", defaultConfig.entryTtl(Duration.ofHours(1)));
        cacheConfigurations.put("intentsBySensitivity", defaultConfig.entryTtl(Duration.ofHours(1)));
        // Legacy 缓存（向后兼容）
        cacheConfigurations.put("allIntents_legacy", defaultConfig.entryTtl(Duration.ofHours(1)));
        cacheConfigurations.put("intentsByCategory_legacy", defaultConfig.entryTtl(Duration.ofHours(1)));
        cacheConfigurations.put("intentCategories_legacy", defaultConfig.entryTtl(Duration.ofHours(1)));
        // AI Tool 数据查询缓存 - 提升 SSE 流式响应性能
        cacheConfigurations.put("qualityStats", defaultConfig.entryTtl(Duration.ofMinutes(15)));
        cacheConfigurations.put("shipmentStats", defaultConfig.entryTtl(Duration.ofMinutes(15)));
        cacheConfigurations.put("customerStats", defaultConfig.entryTtl(Duration.ofMinutes(30)));
        cacheConfigurations.put("alertStats", defaultConfig.entryTtl(Duration.ofMinutes(5)));
        cacheConfigurations.put("equipmentStats", defaultConfig.entryTtl(Duration.ofMinutes(30)));
        cacheConfigurations.put("productTypes", defaultConfig.entryTtl(Duration.ofHours(4)));
        cacheConfigurations.put("productTypeOptions", defaultConfig.entryTtl(Duration.ofHours(4)));
        cacheConfigurations.put("materialTypes", defaultConfig.entryTtl(Duration.ofHours(6)));
        cacheConfigurations.put("equipmentList", defaultConfig.entryTtl(Duration.ofHours(2)));
        cacheConfigurations.put("customerList", defaultConfig.entryTtl(Duration.ofHours(2)));
        // AI 报告缓存
        cacheConfigurations.put("dailySummary", defaultConfig.entryTtl(Duration.ofHours(24)));
        cacheConfigurations.put("weeklySummary", defaultConfig.entryTtl(Duration.ofDays(7)));
        cacheConfigurations.put("aiToolResult", defaultConfig.entryTtl(Duration.ofMinutes(10)));

        // ========== 报表缓存 (2026-01-14) ==========
        // 实时性要求高的报表
        cacheConfigurations.put("realtimeReport", defaultConfig.entryTtl(Duration.ofMinutes(1)));
        // 中等实时性报表
        cacheConfigurations.put("oeeReport", defaultConfig.entryTtl(Duration.ofMinutes(5)));
        cacheConfigurations.put("kpiMetrics", defaultConfig.entryTtl(Duration.ofMinutes(5)));
        cacheConfigurations.put("costVarianceReport", defaultConfig.entryTtl(Duration.ofMinutes(10)));
        cacheConfigurations.put("capacityUtilization", defaultConfig.entryTtl(Duration.ofMinutes(10)));
        cacheConfigurations.put("onTimeDelivery", defaultConfig.entryTtl(Duration.ofMinutes(15)));
        cacheConfigurations.put("productionByProduct", defaultConfig.entryTtl(Duration.ofMinutes(5)));
        // 稳定数据报表
        cacheConfigurations.put("monthlyReport", defaultConfig.entryTtl(Duration.ofHours(1)));
        cacheConfigurations.put("yearlyReport", defaultConfig.entryTtl(Duration.ofHours(24)));
        cacheConfigurations.put("inventoryReport", defaultConfig.entryTtl(Duration.ofMinutes(30)));
        cacheConfigurations.put("financeReport", defaultConfig.entryTtl(Duration.ofHours(1)));

        // ========== 业务流程图导航 (U-NAV-1, Sprint 2 Track G) ==========
        // 首页/列表页节点 count, 5 分钟可接受 staleness, 无需即时 evict
        cacheConfigurations.put("workflowStats", defaultConfig.entryTtl(Duration.ofMinutes(5)));

        return RedisCacheManager.builder(connectionFactory)
                .cacheDefaults(defaultConfig)
                .withInitialCacheConfigurations(cacheConfigurations)
                .build();
    }

    private CacheManager buildSimpleCacheManager() {
        log.warn("Redis 不可用，使用 Caffeine 内存缓存作为降级方案");

        // Caffeine cache with TTL and size limits (prevents OOM unlike ConcurrentMapCache)
        String[] cacheNames = {
                "aiAnalysisResults", "aiIntents", "aiQuota", "dashboardStats",
                "allIntents", "intentsByCategory", "intentCategories", "intentsBySensitivity",
                "allIntents_legacy", "intentsByCategory_legacy", "intentCategories_legacy",
                "qualityStats", "shipmentStats", "customerStats", "alertStats",
                "equipmentStats", "productTypes", "productTypeOptions", "materialTypes", "equipmentList", "customerList",
                "dailySummary", "weeklySummary", "aiToolResult",
                "realtimeReport", "oeeReport", "kpiMetrics", "costVarianceReport",
                "capacityUtilization", "onTimeDelivery", "productionByProduct",
                "monthlyReport", "yearlyReport", "inventoryReport", "financeReport",
                "workflowStats"
        };

        java.util.List<org.springframework.cache.Cache> caches = new java.util.ArrayList<>();
        for (String name : cacheNames) {
            caches.add(new CaffeineCache(name,
                    Caffeine.newBuilder()
                            .maximumSize(500)
                            .expireAfterWrite(Duration.ofMinutes(10))
                            .build()));
        }

        SimpleCacheManager cacheManager = new SimpleCacheManager();
        cacheManager.setCaches(caches);
        return cacheManager;
    }
}
