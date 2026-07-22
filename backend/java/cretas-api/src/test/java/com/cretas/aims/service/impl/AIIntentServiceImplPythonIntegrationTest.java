package com.cretas.aims.service.impl;

import com.cretas.aims.cache.IntentResultCache;
import com.cretas.aims.client.PythonAiMatcherClient;
import com.cretas.aims.dto.intent.IntentMatchResult;
import com.cretas.aims.dto.intent.PythonIntentMatchRequest;
import com.cretas.aims.entity.config.AIIntentConfig;
import com.cretas.aims.service.intent.IntentConfigManagementService;
import com.cretas.aims.service.intent.IntentRecognitionPipelineService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Phase 2B-α (T20) — verifies the Python matcher integration in
 * {@link AIIntentServiceImpl#recognizeIntentWithConfidence(String, String, int, Long, String, String)}.
 *
 * <h3>Three intended behaviors</h3>
 * <ol>
 *   <li><b>Cache hit</b>: when {@link IntentResultCache#get} returns a non-null result,
 *       neither {@link PythonAiMatcherClient#match} nor
 *       {@link IntentRecognitionPipelineService#recognizeIntentWithConfidence}
 *       is called, and the cached result is returned verbatim.</li>
 *   <li><b>Python failure → legacy fallback</b>: when {@code pythonClient.match}
 *       throws (simulating circuit-breaker open / network error), the legacy
 *       {@link IntentRecognitionPipelineService} is called and its result is returned.</li>
 *   <li><b>Feature flag off</b>: when {@code usePythonMatcher=false},
 *       neither cache nor Python is consulted; legacy is the only path.</li>
 * </ol>
 *
 * <p>Constructs the service directly with mocked dependencies (mirrors
 * {@link AIIntentServiceContextTest} pattern) — does NOT use {@code @SpringBootTest}.
 * The {@code pythonClient}, {@code intentResultCache}, and {@code usePythonMatcher}
 * fields are populated via reflection since they are declared with
 * {@code @Autowired(required=false)} / {@code @Value} rather than as final
 * constructor params.
 */
class AIIntentServiceImplPythonIntegrationTest {

    @Mock
    private IntentRecognitionPipelineService pipelineService;
    @Mock
    private IntentConfigManagementService configService;
    @Mock
    private PythonAiMatcherClient pythonClient;
    @Mock
    private IntentResultCache cache;

    private AIIntentServiceImpl service;

    @BeforeEach
    void setup() throws Exception {
        MockitoAnnotations.openMocks(this);
        // The 3 final fields injected by @RequiredArgsConstructor: pipelineService,
        // configService, feedbackService (last is unused in these tests, pass null).
        service = new AIIntentServiceImpl(pipelineService, configService, null);

        // Inject the optional Python-branch fields.
        setField(service, "pythonClient", pythonClient);
        setField(service, "intentResultCache", cache);
        setField(service, "usePythonMatcher", true);

        // Default businessType resolution — return a neutral string so tests
        // that don't care about it still pass.
        when(configService.resolveBusinessDomain(anyString())).thenReturn("FACTORY");
    }

    private static void setField(Object target, String name, Object value) throws Exception {
        var field = AIIntentServiceImpl.class.getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }

    /** Build a minimal IntentMatchResult that {@link IntentMatchResult#hasMatch()} returns true for. */
    private static IntentMatchResult buildRealMatch(String code, String userInput) {
        AIIntentConfig cfg = new AIIntentConfig();
        cfg.setIntentCode(code);
        cfg.setIntentName(code);
        return IntentMatchResult.builder()
                .bestMatch(cfg)
                .confidence(0.95)
                .matchMethod(IntentMatchResult.MatchMethod.SEMANTIC)
                .userInput(userInput)
                .build();
    }

    @Test
    void cacheHit_skipsBothPythonAndLegacy() {
        IntentMatchResult cached = buildRealMatch("CACHED_INTENT", "查询订单");
        when(cache.get(eq("查询订单"), eq("F001"), eq("admin"), eq("FACTORY")))
                .thenReturn(cached);

        IntentMatchResult result = service.recognizeIntentWithConfidence(
                "查询订单", "F001", 3, 22L, "admin", null);

        assertSame(cached, result, "cache hit must be returned verbatim");
        verifyNoInteractions(pythonClient);
        verify(pipelineService, never())
                .recognizeIntentWithConfidence(anyString(), anyString(), anyInt(),
                        any(), anyString(), any());
    }

    @Test
    void pythonRealMatch_isCachedAndReturned_legacyNotCalled() {
        when(cache.get(anyString(), anyString(), anyString(), anyString())).thenReturn(null);
        IntentMatchResult py = buildRealMatch("PY_INTENT", "查询订单");
        when(pythonClient.match(any(PythonIntentMatchRequest.class))).thenReturn(py);

        IntentMatchResult result = service.recognizeIntentWithConfidence(
                "查询订单", "F001", 3, 22L, "admin", null);

        assertSame(py, result, "real Python match must be returned");
        verify(cache, times(1))
                .put(eq("查询订单"), eq("F001"), eq("admin"), eq("FACTORY"), eq(py));
        verify(pipelineService, never())
                .recognizeIntentWithConfidence(anyString(), anyString(), anyInt(),
                        any(), anyString(), any());
    }

    @Test
    void pythonEmpty_fallsBackToLegacy_doesNotCachePut() {
        when(cache.get(anyString(), anyString(), anyString(), anyString())).thenReturn(null);
        // hasMatch() == false because bestMatch == null
        when(pythonClient.match(any(PythonIntentMatchRequest.class)))
                .thenReturn(IntentMatchResult.empty("查询订单"));

        IntentMatchResult legacy = buildRealMatch("LEGACY_INTENT", "查询订单");
        when(pipelineService.recognizeIntentWithConfidence(
                eq("查询订单"), eq("F001"), eq(3), eq(22L), eq("admin"), isNull()))
                .thenReturn(legacy);

        IntentMatchResult result = service.recognizeIntentWithConfidence(
                "查询订单", "F001", 3, 22L, "admin", null);

        assertSame(legacy, result, "legacy result must surface when Python returns empty");
        verify(cache, never()).put(anyString(), anyString(), anyString(), anyString(), any());
        verify(pipelineService, times(1))
                .recognizeIntentWithConfidence(anyString(), anyString(), anyInt(),
                        any(), anyString(), any());
    }

    @Test
    void pythonException_fallsBackToLegacy() {
        when(cache.get(anyString(), anyString(), anyString(), anyString())).thenReturn(null);
        when(pythonClient.match(any(PythonIntentMatchRequest.class)))
                .thenThrow(new RuntimeException("python down"));

        IntentMatchResult legacy = buildRealMatch("LEGACY_INTENT", "q");
        when(pipelineService.recognizeIntentWithConfidence(
                anyString(), anyString(), anyInt(), any(), anyString(), any()))
                .thenReturn(legacy);

        IntentMatchResult result = service.recognizeIntentWithConfidence(
                "q", "F001", 3, 22L, "admin", null);

        assertSame(legacy, result, "Python throw must not propagate; legacy must surface");
        verify(pipelineService, times(1))
                .recognizeIntentWithConfidence(anyString(), anyString(), anyInt(),
                        any(), anyString(), any());
    }

    @Test
    void featureFlagOff_skipsCacheAndPython_legacyOnly() throws Exception {
        setField(service, "usePythonMatcher", false);

        IntentMatchResult legacy = buildRealMatch("LEGACY_INTENT", "q");
        when(pipelineService.recognizeIntentWithConfidence(
                anyString(), anyString(), anyInt(), any(), anyString(), any()))
                .thenReturn(legacy);

        IntentMatchResult result = service.recognizeIntentWithConfidence(
                "q", "F001", 3, 22L, "admin", null);

        assertSame(legacy, result);
        verifyNoInteractions(pythonClient);
        verifyNoInteractions(cache);
    }

    @Test
    void nullPythonClient_skipsBranch_evenWhenFlagOn() throws Exception {
        setField(service, "pythonClient", null);

        IntentMatchResult legacy = buildRealMatch("LEGACY_INTENT", "q");
        when(pipelineService.recognizeIntentWithConfidence(
                anyString(), anyString(), anyInt(), any(), anyString(), any()))
                .thenReturn(legacy);

        IntentMatchResult result = service.recognizeIntentWithConfidence(
                "q", "F001", 3, 22L, "admin", null);

        assertSame(legacy, result);
        verifyNoInteractions(cache);
    }

    @Test
    void restaurantContextualReference_bypassesSessionBlindCacheAndPython() {
        when(configService.resolveBusinessDomain("RES_3101_009")).thenReturn("RESTAURANT");
        IntentMatchResult legacy = buildRealMatch(
                "RESTAURANT_OPS_STORE_MARGIN", "那它的毛利率也是第一吗？");
        when(pipelineService.recognizeIntentWithConfidence(
                eq("那它的毛利率也是第一吗？"), eq("RES_3101_009"), eq(3),
                eq(22L), eq("restaurant_owner"), eq("sid-store-followup")))
                .thenReturn(legacy);

        IntentMatchResult result = service.recognizeIntentWithConfidence(
                "那它的毛利率也是第一吗？", "RES_3101_009", 3,
                22L, "restaurant_owner", "sid-store-followup");

        assertSame(legacy, result);
        verifyNoInteractions(pythonClient);
        verifyNoInteractions(cache);
        verify(pipelineService).recognizeIntentWithConfidence(
                "那它的毛利率也是第一吗？", "RES_3101_009", 3,
                22L, "restaurant_owner", "sid-store-followup");
        assertTrue(AIIntentServiceImpl.requiresContextAwareRestaurantRecognition(
                "那它的毛利率也是第一吗？", "RESTAURANT", "sid"));
        assertTrue(!AIIntentServiceImpl.requiresContextAwareRestaurantRecognition(
                "那它的毛利率也是第一吗？", "FACTORY", "sid"));
        assertTrue(!AIIntentServiceImpl.requiresContextAwareRestaurantRecognition(
                "那它的毛利率也是第一吗？", "RESTAURANT", null));
        assertTrue(!AIIntentServiceImpl.requiresContextAwareRestaurantRecognition(
                "查询本周营收", "RESTAURANT", "sid"));
    }

    @Test
    void nullCache_skipsBranch_evenWhenFlagOn() throws Exception {
        setField(service, "intentResultCache", null);

        IntentMatchResult legacy = buildRealMatch("LEGACY_INTENT", "q");
        when(pipelineService.recognizeIntentWithConfidence(
                anyString(), anyString(), anyInt(), any(), anyString(), any()))
                .thenReturn(legacy);

        IntentMatchResult result = service.recognizeIntentWithConfidence(
                "q", "F001", 3, 22L, "admin", null);

        assertSame(legacy, result);
        verifyNoInteractions(pythonClient);
    }

    /**
     * Phase 2B canary whitelist (#73 §1.4): when {@code ai.python-matcher-factories}
     * lists specific factoryIds, only those factories dispatch to Python; other
     * factories must skip the Python branch entirely (no cache lookup, no
     * pythonClient.match) and fall through to the legacy pipeline.
     */
    @Test
    void canaryWhitelist_excludedFactory_skipsPythonBranch() throws Exception {
        setField(service, "pythonMatcherFactories", "F999");

        IntentMatchResult legacy = buildRealMatch("LEGACY_INTENT", "q");
        when(pipelineService.recognizeIntentWithConfidence(
                anyString(), anyString(), anyInt(), any(), anyString(), any()))
                .thenReturn(legacy);

        IntentMatchResult result = service.recognizeIntentWithConfidence(
                "q", "F001", 3, 22L, "admin", null);

        assertSame(legacy, result, "non-whitelisted factory must surface legacy result");
        verifyNoInteractions(pythonClient);
        verifyNoInteractions(cache);
    }

    /**
     * Phase 2B canary whitelist (#73 §1.4): a factoryId that appears in the
     * whitelist (with surrounding whitespace, to verify the trim() defense)
     * must still flow through the normal cache → Python → legacy pipeline.
     */
    @Test
    void canaryWhitelist_includedFactory_dispatchesToPython() throws Exception {
        setField(service, "pythonMatcherFactories", " F001 , F999 ");
        when(cache.get(anyString(), anyString(), anyString(), anyString())).thenReturn(null);
        IntentMatchResult py = buildRealMatch("PY_INTENT", "q");
        when(pythonClient.match(any(PythonIntentMatchRequest.class))).thenReturn(py);

        IntentMatchResult result = service.recognizeIntentWithConfidence(
                "q", "F001", 3, 22L, "admin", null);

        assertSame(py, result, "whitelisted factory must dispatch to Python");
        verify(pipelineService, never())
                .recognizeIntentWithConfidence(anyString(), anyString(), anyInt(),
                        any(), anyString(), any());
    }

    @Test
    void resolveBusinessDomainException_doesNotPreventPythonCall() {
        when(configService.resolveBusinessDomain(anyString()))
                .thenThrow(new RuntimeException("DB down"));
        when(cache.get(anyString(), anyString(), anyString(), eq("")))
                .thenReturn(null);
        IntentMatchResult py = buildRealMatch("PY_INTENT", "q");
        when(pythonClient.match(any(PythonIntentMatchRequest.class))).thenReturn(py);

        IntentMatchResult result = service.recognizeIntentWithConfidence(
                "q", "F001", 3, 22L, "admin", null);

        assertSame(py, result, "businessType resolution failure must not block Python matcher");
    }
}
