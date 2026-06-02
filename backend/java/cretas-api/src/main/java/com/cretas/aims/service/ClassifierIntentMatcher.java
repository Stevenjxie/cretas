package com.cretas.aims.service;

import com.cretas.aims.config.PythonClassifierConfig;
import com.cretas.aims.dto.ClassifierResult;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.*;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import jakarta.annotation.PostConstruct;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 分类器意图匹配器
 *
 * 调用 Python 分类器服务进行意图分类，使用 BERT 模型直接输出 softmax 概率。
 *
 * <p>优势:</p>
 * <ul>
 *   <li>直接分类，不是向量相似度，决策边界更清晰</li>
 *   <li>185 个意图类别的 softmax 概率</li>
 *   <li>支持 Top-K 返回</li>
 *   <li>低延迟 (~20ms)</li>
 * </ul>
 *
 * @author Cretas Team
 * @version 1.0.0
 * @since 2026-01-26
 */
@Slf4j
@Service
public class ClassifierIntentMatcher {

    @Autowired
    private PythonClassifierConfig config;

    private RestTemplate restTemplate;
    private final AtomicBoolean serviceAvailable = new AtomicBoolean(false);
    private volatile long lastHealthCheck = 0;
    private final AtomicInteger consecutiveFailures = new AtomicInteger(0);
    private static final int ALERT_THRESHOLD = 3; // 连续3次失败触发ERROR级别告警

    // ==================== Response DTOs ====================

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class PredictionItem {
        private String intent;
        private double confidence;
        private int rank;
    }

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class ClassifyResponse {
        private boolean success;
        private List<PredictionItem> predictions = new ArrayList<>();
        @JsonProperty("top_intent")
        private String topIntent;
        @JsonProperty("top_confidence")
        private double topConfidence;
        private String error;
        // v35.0: OOD 检测指标
        private Double entropy;
        private Double margin;
        @JsonProperty("max_logit")
        private Double maxLogit;
    }

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class HealthResponse {
        private String status;
        @JsonProperty("model_available")
        private boolean modelAvailable;
        private String error;
    }

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class ModelInfoResponse {
        private boolean available;
        @JsonProperty("model_path")
        private String modelPath;
        @JsonProperty("num_labels")
        private int numLabels;
        private String device;
        private List<String> labels = new ArrayList<>();
    }

    // ==================== Request DTOs ====================

    @Data
    public static class ClassifyRequest {
        private String text;
        @JsonProperty("top_k")
        private int topK = 5;
        private double threshold = 0.0;

        public ClassifyRequest(String text, int topK, double threshold) {
            this.text = text;
            this.topK = topK;
            this.threshold = threshold;
        }
    }

    @PostConstruct
    public void init() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(config.getConnectTimeout());
        factory.setReadTimeout(config.getTimeout());
        this.restTemplate = new RestTemplate(factory);

        log.info("ClassifierIntentMatcher 初始化完成:");
        log.info("  - URL: {}", config.getUrl());
        log.info("  - enabled: {}", config.isEnabled());
        log.info("  - weight: {}", config.getWeight());

        // 初始健康检查
        checkHealth();
    }

    /**
     * 定期健康检查
     */
    @Scheduled(fixedRateString = "${python-classifier.health-check-interval:30000}")
    public void scheduledHealthCheck() {
        if (config.isEnabled()) {
            checkHealth();
        }
    }

    /**
     * 检查 Python 分类器服务健康状态
     */
    public boolean checkHealth() {
        if (!config.isAvailable()) {
            serviceAvailable.set(false);
            return false;
        }

        try {
            String url = config.getHealthUrl();
            ResponseEntity<HealthResponse> response = restTemplate.getForEntity(url, HealthResponse.class);

            if (response.getStatusCode() == HttpStatus.OK && response.getBody() != null) {
                boolean available = response.getBody().isModelAvailable();
                serviceAvailable.set(available);
                lastHealthCheck = System.currentTimeMillis();

                if (available) {
                    int prevFailures = consecutiveFailures.getAndSet(0);
                    if (prevFailures >= ALERT_THRESHOLD) {
                        log.info("BERT分类器已恢复 — 此前连续{}次失败", prevFailures);
                    } else {
                        log.debug("分类器服务健康检查通过");
                    }
                } else {
                    int failures = consecutiveFailures.incrementAndGet();
                    if (failures >= ALERT_THRESHOLD) {
                        log.warn("BERT影子分类器模型未加载 — 连续{}次失败 (仅影响 shadow 数据采集，不影响路由)。原因: {}",
                                failures, response.getBody().getError());
                    } else {
                        log.warn("分类器服务可用但模型未加载: {}", response.getBody().getError());
                    }
                }
                return available;
            }
        } catch (Exception e) {
            int failures = consecutiveFailures.incrementAndGet();
            if (failures >= ALERT_THRESHOLD) {
                log.warn("BERT影子分类器服务不可达 — 连续{}次失败 (仅影响 shadow 数据采集，不影响路由)。错误: {}",
                        failures, e.getMessage());
            } else {
                log.warn("分类器服务健康检查失败: {}", e.getMessage());
            }
            serviceAvailable.set(false);
        }
        return false;
    }

    /**
     * 检查服务是否可用
     */
    public boolean isAvailable() {
        if (!config.isEnabled()) {
            return false;
        }

        // 如果超过健康检查间隔，重新检查
        if (System.currentTimeMillis() - lastHealthCheck > config.getHealthCheckInterval()) {
            checkHealth();
        }

        return serviceAvailable.get();
    }

    /**
     * 获取分类器权重
     */
    public double getWeight() {
        return config.getWeight();
    }

    /**
     * 分类用户输入
     *
     * @param userInput 用户输入文本
     * @return 分类结果，如果服务不可用返回 Optional.empty()
     */
    public Optional<ClassifierResult> classify(String userInput) {
        return classify(userInput, config.getTopK(), config.getMinConfidence());
    }

    /**
     * 分类用户输入（带参数）
     *
     * @param userInput 用户输入文本
     * @param topK 返回 Top-K 结果
     * @param minConfidence 最小置信度阈值
     * @return 分类结果
     */
    public Optional<ClassifierResult> classify(String userInput, int topK, double minConfidence) {
        if (!isAvailable()) {
            log.debug("分类器服务不可用，跳过分类");
            return Optional.empty();
        }

        if (userInput == null || userInput.trim().isEmpty()) {
            return Optional.empty();
        }

        try {
            String url = config.getClassifyUrl();
            ClassifyRequest request = new ClassifyRequest(userInput.trim(), topK, minConfidence);

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            HttpEntity<ClassifyRequest> entity = new HttpEntity<>(request, headers);

            long startTime = System.currentTimeMillis();
            ResponseEntity<ClassifyResponse> response = restTemplate.postForEntity(url, entity, ClassifyResponse.class);
            long elapsed = System.currentTimeMillis() - startTime;

            if (response.getStatusCode() == HttpStatus.OK && response.getBody() != null) {
                ClassifyResponse body = response.getBody();

                if (body.isSuccess() && body.getTopIntent() != null) {
                    ClassifierResult result = new ClassifierResult();
                    result.setIntentCode(body.getTopIntent());
                    result.setConfidence(body.getTopConfidence());
                    result.setSource("CLASSIFIER");
                    result.setLatencyMs(elapsed);
                    // v35.0: OOD 指标透传
                    result.setEntropy(body.getEntropy());
                    result.setMargin(body.getMargin());
                    result.setMaxLogit(body.getMaxLogit());

                    // 转换预测列表
                    List<ClassifierResult.PredictionEntry> predictions = new ArrayList<>();
                    for (PredictionItem item : body.getPredictions()) {
                        ClassifierResult.PredictionEntry entry = new ClassifierResult.PredictionEntry();
                        entry.setIntent(item.getIntent());
                        entry.setConfidence(item.getConfidence());
                        entry.setRank(item.getRank());
                        predictions.add(entry);
                    }
                    result.setPredictions(predictions);

                    log.debug("分类器结果: intent={}, confidence={:.4f}, latency={}ms",
                            result.getIntentCode(), result.getConfidence(), elapsed);

                    return Optional.of(result);
                } else {
                    log.warn("分类器返回失败: {}", body.getError());
                }
            }
        } catch (Exception e) {
            log.error("分类器调用异常: {}", e.getMessage());
            // 标记服务可能不可用，触发下次健康检查
            serviceAvailable.set(false);
        }

        return Optional.empty();
    }

    /**
     * 获取特定意图的置信度
     *
     * @param userInput 用户输入
     * @param intentCode 意图代码
     * @return 置信度，如果服务不可用返回 -1
     */
    public double getConfidenceForIntent(String userInput, String intentCode) {
        if (!isAvailable()) {
            return -1;
        }

        try {
            String url = config.getConfidenceUrl();

            Map<String, String> request = new HashMap<>();
            request.put("text", userInput);
            request.put("intent_code", intentCode);

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            HttpEntity<Map<String, String>> entity = new HttpEntity<>(request, headers);

            ResponseEntity<Map> response = restTemplate.postForEntity(url, entity, Map.class);

            if (response.getStatusCode() == HttpStatus.OK && response.getBody() != null) {
                Map<String, Object> body = response.getBody();
                if (Boolean.TRUE.equals(body.get("success"))) {
                    Object confidence = body.get("confidence");
                    if (confidence instanceof Number) {
                        return ((Number) confidence).doubleValue();
                    }
                }
            }
        } catch (Exception e) {
            log.error("获取意图置信度失败: {}", e.getMessage());
        }

        return -1;
    }

    /**
     * 获取模型信息
     */
    public Optional<ModelInfoResponse> getModelInfo() {
        if (!config.isAvailable()) {
            return Optional.empty();
        }

        try {
            String url = config.getInfoUrl();
            ResponseEntity<ModelInfoResponse> response = restTemplate.getForEntity(url, ModelInfoResponse.class);

            if (response.getStatusCode() == HttpStatus.OK) {
                return Optional.ofNullable(response.getBody());
            }
        } catch (Exception e) {
            log.error("获取模型信息失败: {}", e.getMessage());
        }

        return Optional.empty();
    }
}
