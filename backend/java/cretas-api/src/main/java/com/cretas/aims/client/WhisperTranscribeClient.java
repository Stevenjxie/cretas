package com.cretas.aims.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

import java.util.HashMap;
import java.util.Map;

/**
 * Sprint 6 W2-C-2 — Java-side proxy for Python's
 * {@code POST /api/efficiency/whisper-transcribe} endpoint.
 *
 * <p>Used by {@link com.cretas.aims.service.CallRecordService#triggerTranscriptionAsync} —
 * given an OSS audio URL, asks Python to transcribe (Whisper or equivalent ASR),
 * synchronously returns the transcript text (or null on failure).
 *
 * <p>Reuses {@code pythonAiRestTemplate} bean from
 * {@link com.cretas.aims.config.PythonAiClientConfig} (35s response timeout +
 * connection pool). Caller invokes via {@code @Async} so HTTP request thread is
 * not blocked.
 *
 * <p>Auth: same X-Internal-Secret header pattern as
 * {@link PythonAiMatcherClient}.
 *
 * <p>Graceful degradation: any HTTP error / Python service down /
 * misconfiguration → returns {@code null} (not throw). Service layer marks
 * {@code transcript_status=FAILED}, frontend renders "转写失败" placeholder.
 *
 * @see PythonAiMatcherClient
 */
@Slf4j
@Component
public class WhisperTranscribeClient {

    private final RestTemplate restTemplate;
    private final String baseUrl;
    private final String internalSecret;
    private final ObjectMapper objectMapper;

    @Autowired
    public WhisperTranscribeClient(
            @Qualifier("pythonAiRestTemplate") RestTemplate restTemplate,
            @Qualifier("pythonAiBaseUrl") String baseUrl,
            @Qualifier("pythonAiInternalSecret") String internalSecret,
            ObjectMapper objectMapper) {
        this.restTemplate = restTemplate;
        this.baseUrl = baseUrl;
        this.internalSecret = internalSecret;
        this.objectMapper = objectMapper;
    }

    /**
     * Submit OSS audio URL to Python for ASR transcription.
     *
     * @param audioOssUrl   OSS URL Python can fetch
     * @param factoryId     tenant scope (audit trail)
     * @param callRecordId  business key (for log correlation)
     * @return transcript text on success, {@code null} on any failure
     */
    public String transcribe(String audioOssUrl, String factoryId, Long callRecordId) {
        if (audioOssUrl == null || audioOssUrl.isBlank()) {
            log.warn("WhisperTranscribeClient: audioOssUrl blank, skip (callRecordId={})", callRecordId);
            return null;
        }
        String url = baseUrl + "/api/efficiency/whisper-transcribe";
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (internalSecret != null && !internalSecret.isBlank()) {
            headers.add("X-Internal-Secret", internalSecret);
        }
        if (factoryId != null) {
            headers.add("X-Factory-Id", factoryId);
        }
        Map<String, Object> body = new HashMap<>();
        body.put("audio_url", audioOssUrl);
        body.put("factory_id", factoryId);
        body.put("call_record_id", callRecordId);
        body.put("language", "zh");

        try {
            long start = System.currentTimeMillis();
            ResponseEntity<String> response = restTemplate.exchange(
                    url, HttpMethod.POST,
                    new HttpEntity<>(body, headers),
                    String.class);
            long elapsedMs = System.currentTimeMillis() - start;

            if (!response.getStatusCode().is2xxSuccessful()) {
                log.warn("WhisperTranscribeClient: HTTP {} from Python (callRecordId={}, elapsedMs={})",
                        response.getStatusCode().value(), callRecordId, elapsedMs);
                return null;
            }
            String responseBody = response.getBody();
            if (responseBody == null || responseBody.isBlank()) {
                log.warn("WhisperTranscribeClient: empty body (callRecordId={})", callRecordId);
                return null;
            }
            JsonNode root = objectMapper.readTree(responseBody);
            // Expected: { "success": true, "data": { "transcript": "..." }, "message": "..." }
            // Or per-route: { "transcript": "..." }
            JsonNode dataNode = root.has("data") ? root.get("data") : root;
            JsonNode transcriptNode = dataNode.has("transcript") ? dataNode.get("transcript") :
                    dataNode.has("text") ? dataNode.get("text") : null;
            if (transcriptNode == null || transcriptNode.isNull()) {
                log.warn("WhisperTranscribeClient: missing transcript field (callRecordId={}, body={})",
                        callRecordId, truncate(responseBody, 200));
                return null;
            }
            String transcript = transcriptNode.asText();
            log.info("WhisperTranscribeClient: success callRecordId={} elapsedMs={} length={}",
                    callRecordId, elapsedMs, transcript.length());
            return transcript;
        } catch (RestClientException ex) {
            log.warn("WhisperTranscribeClient: transport error callRecordId={}: {}",
                    callRecordId, ex.getMessage());
            return null;
        } catch (Exception ex) {
            log.warn("WhisperTranscribeClient: unexpected error callRecordId={}: {}",
                    callRecordId, ex.getMessage(), ex);
            return null;
        }
    }

    private String truncate(String s, int max) {
        if (s == null) return null;
        return s.length() <= max ? s : s.substring(0, max) + "...";
    }
}
