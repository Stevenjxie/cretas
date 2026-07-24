package com.cretas.aims.client;

import com.cretas.aims.entity.enums.LabelQcLabel;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;

import java.net.InetSocketAddress;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class LabelQcAnalysisClientTest {

    @Test
    void downloadsPhotoAndMapsPythonCandidateContract() {
        RestTemplate restTemplate = new RestTemplate();
        MockRestServiceServer server = MockRestServiceServer.bindTo(restTemplate).build();
        HttpServer imageServer;
        try {
            imageServer = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        } catch (Exception ex) {
            throw new AssertionError("Could not start image fixture server", ex);
        }
        imageServer.createContext("/photo", exchange -> {
            byte[] body = new byte[]{1, 2, 3};
            exchange.getResponseHeaders().set("Content-Type", MediaType.IMAGE_JPEG_VALUE);
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        imageServer.start();
        LabelQcAnalysisClient client = new LabelQcAnalysisClient(
                restTemplate,
                "http://python.test",
                "internal-secret",
                new ObjectMapper());

        server.expect(requestTo("http://python.test/api/label-qc/analyze"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("X-Internal-Secret", "internal-secret"))
                .andExpect(header("X-Factory-Id", "F006"))
                .andRespond(withSuccess("""
                        {
                          "success": true,
                          "data": {
                            "verdict": "SUSPECTED",
                            "model": "qwen-vl",
                            "promptVersion": "label-presence-high-recall-v1",
                            "candidates": [{
                              "candidateId": "ai-1",
                              "label": "MISSING_WHITE_LABEL",
                              "confidence": 0.82,
                              "bbox": [0.1, 0.2, 0.4, 0.5],
                              "evidence": "盒边没有白色矩形标签"
                            }]
                          }
                        }
                        """, MediaType.APPLICATION_JSON));

        try {
            var result = client.analyze(
                    "http://127.0.0.1:" + imageServer.getAddress().getPort() + "/photo",
                    "F006",
                    "photo-1");

            assertThat(result.model()).isEqualTo("qwen-vl");
            assertThat(result.promptVersion()).isEqualTo("label-presence-high-recall-v1");
            assertThat(result.candidates()).singleElement().satisfies(candidate -> {
                assertThat(candidate.label()).isEqualTo(LabelQcLabel.MISSING_WHITE_LABEL);
                assertThat(candidate.confidence()).isEqualTo(0.82);
                assertThat(candidate.bbox().xMin()).isEqualTo(0.1);
                assertThat(candidate.bbox().yMax()).isEqualTo(0.5);
            });
            server.verify();
        } finally {
            imageServer.stop(0);
        }
    }
}
