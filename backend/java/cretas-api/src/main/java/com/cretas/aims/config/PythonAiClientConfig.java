package com.cretas.aims.config;

import org.apache.hc.client5.http.config.RequestConfig;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManager;
import org.apache.hc.core5.util.Timeout;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.HttpComponentsClientHttpRequestFactory;
import org.springframework.web.client.RestTemplate;

/**
 * HTTP client wiring for {@link com.cretas.aims.client.PythonAiMatcherClient}
 * (Phase 2B-α task T17).
 *
 * <p>Provides three named beans:
 * <ul>
 *   <li>{@code pythonAiRestTemplate} — RestTemplate backed by HttpClient5 with
 *       a connection pool (50 total / 20 per-route) and explicit
 *       connect/response/connection-request timeouts so a slow Python
 *       endpoint cannot stall a calling thread indefinitely.</li>
 *   <li>{@code pythonAiBaseUrl} — base URL string from
 *       {@code cretas.python.base-url} (defaults to {@code http://localhost:8083}).</li>
 *   <li>{@code pythonAiInternalSecret} — shared X-Internal-Secret value from
 *       {@code cretas.python.internal-secret} (mandatory in prod; T18 client
 *       fails fast with {@code IllegalStateException} when blank).</li>
 * </ul>
 *
 * <p>The response timeout (35s) intentionally exceeds the Python LLM
 * fallback budget (30s per AI_LLM_TIMEOUT_S) by 5s so transport-layer
 * errors surface as Resilience4j circuit-breaker fallbacks rather than
 * raw socket timeouts.
 */
@Configuration
public class PythonAiClientConfig {

    @Bean(name = "pythonAiRestTemplate")
    @Qualifier("pythonAiRestTemplate")
    public RestTemplate pythonAiRestTemplate() {
        return buildRestTemplate(35);
    }

    /**
     * Label-QC analyzes several bounded high-resolution tiles. It runs off the
     * request thread and therefore uses a longer, dedicated response timeout
     * without weakening the latency budget of existing Python AI callers.
     */
    @Bean(name = "labelQcRestTemplate")
    @Qualifier("labelQcRestTemplate")
    public RestTemplate labelQcRestTemplate() {
        return buildRestTemplate(210);
    }

    private RestTemplate buildRestTemplate(int responseTimeoutSeconds) {
        PoolingHttpClientConnectionManager pool = new PoolingHttpClientConnectionManager();
        pool.setMaxTotal(50);
        pool.setDefaultMaxPerRoute(20);

        RequestConfig requestConfig = RequestConfig.custom()
                .setConnectTimeout(Timeout.ofSeconds(3))
                .setResponseTimeout(Timeout.ofSeconds(responseTimeoutSeconds))
                .setConnectionRequestTimeout(Timeout.ofSeconds(2))
                .build();

        CloseableHttpClient httpClient = HttpClients.custom()
                .setConnectionManager(pool)
                .setDefaultRequestConfig(requestConfig)
                .evictExpiredConnections()
                .evictIdleConnections(Timeout.ofSeconds(30))
                .build();

        return new RestTemplate(new HttpComponentsClientHttpRequestFactory(httpClient));
    }

    @Bean(name = "pythonAiBaseUrl")
    public String pythonAiBaseUrl(@Value("${cretas.python.base-url:http://localhost:8083}") String url) {
        return url;
    }

    @Bean(name = "pythonAiInternalSecret")
    public String pythonAiInternalSecret(@Value("${cretas.python.internal-secret:}") String secret) {
        return secret;
    }
}
