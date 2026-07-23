package com.cretas.aims.dto.intent;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.Map;

/**
 * Request body to Python <code>POST /api/ai/intent/match</code> (Phase 2B-α).
 *
 * <p>Mirrors the Python Pydantic <code>IntentMatchRequest</code> declared in
 * <code>backend/python/ai/dto.py</code>. JSON serialization uses Jackson
 * defaults — {@link JsonInclude.Include#NON_NULL} drops absent optional
 * fields so Python's <code>extra='ignore'</code> Pydantic config receives a
 * minimal payload.
 *
 * @see PythonIntentMatchResponse
 * @see <code>backend/python/ai/dto.py</code> for the Pydantic source of truth
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class PythonIntentMatchRequest {

    private String query;
    private String factoryId;
    private String userId;
    private String username;
    private String role;
    private String businessType;
    private List<Map<String, String>> history;
    private Options options;

    /**
     * P1 读写分块: Python matcher 目录过滤参数 (P1-P 消费); null=不过滤。
     * READ=剔除写意图候选 | OPERATE=按 userPermissions 剔除无权限写意图。
     */
    private String mode;

    /**
     * P1 读写分块: Python matcher 目录过滤参数 (P1-P 消费); null=不过滤。
     * 调用者权限码集合 (module:action)。
     */
    private java.util.List<String> userPermissions;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class Options {
        private Boolean enableLlmFallback;
        private Integer timeoutMs;
        private Double minConfidence;
        private Integer intentConfigVersion;
    }
}
