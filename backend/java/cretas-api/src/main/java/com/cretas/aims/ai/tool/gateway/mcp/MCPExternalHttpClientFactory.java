package com.cretas.aims.ai.tool.gateway.mcp;

import org.apache.hc.client5.http.config.RequestConfig;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.core5.util.Timeout;
import org.springframework.http.client.HttpComponentsClientHttpRequestFactory;
import org.springframework.web.client.RestTemplate;

/** Dedicated outbound client with redirects disabled so exact-origin policy cannot be bypassed. */
public final class MCPExternalHttpClientFactory {

    private MCPExternalHttpClientFactory() {
    }

    public static RestTemplate create() {
        RequestConfig requestConfig = RequestConfig.custom()
                .setConnectTimeout(Timeout.ofSeconds(3))
                .setConnectionRequestTimeout(Timeout.ofSeconds(2))
                .setResponseTimeout(Timeout.ofSeconds(10))
                .build();
        CloseableHttpClient client = HttpClients.custom()
                .disableRedirectHandling()
                .setDefaultRequestConfig(requestConfig)
                .build();
        return new RestTemplate(new HttpComponentsClientHttpRequestFactory(client));
    }
}
