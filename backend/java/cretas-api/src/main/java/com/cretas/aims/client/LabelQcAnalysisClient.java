package com.cretas.aims.client;

import com.cretas.aims.entity.enums.LabelQcLabel;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.*;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

import java.util.ArrayList;
import java.util.List;

@Slf4j
@Component
public class LabelQcAnalysisClient {

    private final RestTemplate restTemplate;
    private final String baseUrl;
    private final String internalSecret;
    private final ObjectMapper objectMapper;

    public LabelQcAnalysisClient(
            @Qualifier("labelQcRestTemplate") RestTemplate restTemplate,
            @Qualifier("pythonAiBaseUrl") String baseUrl,
            @Qualifier("pythonAiInternalSecret") String internalSecret,
            ObjectMapper objectMapper) {
        this.restTemplate = restTemplate;
        this.baseUrl = baseUrl;
        this.internalSecret = internalSecret;
        this.objectMapper = objectMapper;
    }

    public AnalysisResult analyze(String signedDownloadUrl, String factoryId, String photoId) {
        if (signedDownloadUrl == null || signedDownloadUrl.isBlank()) {
            throw new LabelQcClientException("图片下载地址为空");
        }
        try {
            byte[] imageBytes = restTemplate.getForObject(signedDownloadUrl, byte[].class);
            if (imageBytes == null || imageBytes.length == 0) {
                throw new LabelQcClientException("图片下载结果为空");
            }

            HttpHeaders imageHeaders = new HttpHeaders();
            imageHeaders.setContentType(MediaType.IMAGE_JPEG);
            ByteArrayResource image = new ByteArrayResource(imageBytes) {
                @Override
                public String getFilename() {
                    return "label-qc-" + photoId + ".jpg";
                }
            };

            MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
            body.add("image", new HttpEntity<>(image, imageHeaders));

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.MULTIPART_FORM_DATA);
            if (internalSecret != null && !internalSecret.isBlank()) {
                headers.add("X-Internal-Secret", internalSecret);
            }
            headers.add("X-Factory-Id", factoryId);

            ResponseEntity<String> response = restTemplate.exchange(
                    baseUrl + "/api/label-qc/analyze",
                    HttpMethod.POST,
                    new HttpEntity<>(body, headers),
                    String.class);
            if (!response.getStatusCode().is2xxSuccessful()
                    || response.getBody() == null
                    || response.getBody().isBlank()) {
                throw new LabelQcClientException("视觉服务返回空结果");
            }
            return parse(response.getBody());
        } catch (LabelQcClientException ex) {
            throw ex;
        } catch (RestClientException ex) {
            throw new LabelQcClientException("视觉服务暂时不可用", ex);
        } catch (Exception ex) {
            throw new LabelQcClientException("视觉结果解析失败", ex);
        }
    }

    private AnalysisResult parse(String responseBody) throws Exception {
        JsonNode root = objectMapper.readTree(responseBody);
        JsonNode data = root.path("data");
        if (!root.path("success").asBoolean(false) || data.isMissingNode()) {
            throw new LabelQcClientException("视觉服务返回失败");
        }
        List<Candidate> candidates = new ArrayList<>();
        for (JsonNode item : data.path("candidates")) {
            JsonNode bbox = item.path("bbox");
            if (!bbox.isArray() || bbox.size() != 4) {
                throw new LabelQcClientException("视觉候选框格式无效");
            }
            candidates.add(new Candidate(
                    item.path("candidateId").asText(),
                    LabelQcLabel.valueOf(item.path("label").asText()),
                    item.path("confidence").asDouble(0),
                    new BoundingBox(
                            bbox.get(0).asDouble(),
                            bbox.get(1).asDouble(),
                            bbox.get(2).asDouble(),
                            bbox.get(3).asDouble()),
                    item.path("evidence").asText("")));
        }
        return new AnalysisResult(
                data.path("verdict").asText(),
                data.path("model").asText("unknown"),
                data.path("promptVersion").asText("unknown"),
                candidates);
    }

    public record AnalysisResult(
            String verdict,
            String model,
            String promptVersion,
            List<Candidate> candidates
    ) {}

    public record Candidate(
            String candidateId,
            LabelQcLabel label,
            Double confidence,
            BoundingBox bbox,
            String evidence
    ) {}

    public record BoundingBox(Double xMin, Double yMin, Double xMax, Double yMax) {}

    public static class LabelQcClientException extends RuntimeException {
        public LabelQcClientException(String message) {
            super(message);
        }

        public LabelQcClientException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
