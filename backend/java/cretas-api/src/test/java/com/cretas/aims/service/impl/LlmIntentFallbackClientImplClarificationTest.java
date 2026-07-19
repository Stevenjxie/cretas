package com.cretas.aims.service.impl;

import com.cretas.aims.client.PythonSmartBIClient;
import com.cretas.aims.dto.intent.IntentMatchResult.CandidateIntent;
import com.cretas.aims.dto.python.PythonGeneralAnalysisResponse;
import com.cretas.aims.entity.config.AIIntentConfig;
import com.cretas.aims.service.LlmIntentFallbackClient.RerankingResult;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * 测试 LlmIntentFallbackClientImpl 的澄清问题生成功能
 */
class LlmIntentFallbackClientImplClarificationTest {

    @InjectMocks
    private LlmIntentFallbackClientImpl client;

    @Mock
    private PythonSmartBIClient pythonSmartBIClient;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setAttribute("userId", 55L);
        request.setAttribute("role", "factory_super_admin");
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
    }

    @AfterEach
    void tearDown() {
        RequestContextHolder.resetRequestAttributes();
    }

    @Test
    void missingParameterClarificationUsesTypedTenantBoundRequest() throws Exception {
        when(pythonSmartBIClient.analyzeGeneral(
                anyString(), nullable(String.class), nullable(String.class),
                any(PythonSmartBIClient.GeneralAnalysisCall.class)))
                .thenReturn(PythonGeneralAnalysisResponse.builder()
                        .success(true)
                        .answer("请提供批次编号？")
                        .build());
        AIIntentConfig intent = AIIntentConfig.builder()
                .intentCode("UPDATE_BATCH")
                .intentName("批次更新")
                .build();

        List<String> questions = client.generateClarificationQuestionsForMissingParams(
                "更新批次", intent, List.of("batchId"), "FACTORY-9");

        assertEquals(List.of("请提供批次编号？"), questions);
        var requestCaptor = org.mockito.ArgumentCaptor.forClass(
                PythonSmartBIClient.GeneralAnalysisCall.class);
        verify(pythonSmartBIClient).analyzeGeneral(
                eq("FACTORY-9"), eq("55"), eq("factory_super_admin"),
                requestCaptor.capture());
        PythonSmartBIClient.GeneralAnalysisCall request = requestCaptor.getValue();
        assertEquals("intent_clarification", request.tableType());
        assertFalse(request.allowTenantDataFallback());
        assertTrue(request.query().contains("更新批次"));
        assertEquals("UPDATE_BATCH", request.data().get(0).get("intent_code"));
        assertEquals(List.of("batchId"), request.data().get(0).get("missing_parameters"));
    }

    @Test
    void rerankingUsesTypedCandidateDataInsteadOfWrongSchemaBody() throws Exception {
        when(pythonSmartBIClient.analyzeGeneral(
                anyString(), nullable(String.class), nullable(String.class),
                any(PythonSmartBIClient.GeneralAnalysisCall.class)))
                .thenReturn(PythonGeneralAnalysisResponse.builder()
                        .success(true)
                        .answer("{\"selected_intent\":\"MATERIAL_QUERY\","
                                + "\"confidence\":0.91,\"reasoning\":\"matches\","
                                + "\"agrees_with_ranking\":true}")
                        .build());
        CandidateIntent candidate = CandidateIntent.builder()
                .intentCode("MATERIAL_QUERY")
                .intentName("材料查询")
                .description("查询材料")
                .confidence(0.8)
                .build();

        RerankingResult result = client.rerankCandidates(
                "查询材料", List.of(candidate), "FACTORY-10");

        assertTrue(result.isSuccess());
        assertEquals("MATERIAL_QUERY", result.getSelectedIntentCode());
        var requestCaptor = org.mockito.ArgumentCaptor.forClass(
                PythonSmartBIClient.GeneralAnalysisCall.class);
        verify(pythonSmartBIClient).analyzeGeneral(
                eq("FACTORY-10"), eq("55"), eq("factory_super_admin"),
                requestCaptor.capture());
        PythonSmartBIClient.GeneralAnalysisCall request = requestCaptor.getValue();
        assertEquals("intent_reranking", request.tableType());
        assertFalse(request.allowTenantDataFallback());
        assertEquals("MATERIAL_QUERY", request.data().get(0).get("intent_code"));
        assertTrue(request.query().contains("查询材料"));
    }

    /**
     * 测试参数名到友好名称的映射
     */
    @Test
    void testGetParameterFriendlyName() throws Exception {
        // 使用反射访问私有方法
        Method method = LlmIntentFallbackClientImpl.class.getDeclaredMethod(
                "getParameterFriendlyName", String.class);
        method.setAccessible(true);

        // 测试已知参数
        assertEquals("批次编号", method.invoke(client, "batchId"));
        assertEquals("数量", method.invoke(client, "quantity"));
        assertEquals("材料类型", method.invoke(client, "materialTypeId"));
        assertEquals("供应商", method.invoke(client, "supplierId"));

        // 测试未知参数（应返回原值）
        assertEquals("unknownParam", method.invoke(client, "unknownParam"));

        // 测试 null —— 显式 (Object) null 转型避免被解析为 varargs Object[]{}
        assertEquals("信息", method.invoke(client, new Object[]{ null }));
    }

    /**
     * 测试模板生成澄清问题 - 单个参数
     */
    @Test
    void testGenerateTemplateClarificationQuestions_SingleParam() throws Exception {
        Method method = LlmIntentFallbackClientImpl.class.getDeclaredMethod(
                "generateTemplateClarificationQuestions",
                AIIntentConfig.class,
                List.class);
        method.setAccessible(true);

        AIIntentConfig intent = AIIntentConfig.builder()
                .intentCode("UPDATE_BATCH")
                .intentName("批次更新")
                .build();

        List<String> missingParams = Arrays.asList("batchId");

        @SuppressWarnings("unchecked")
        List<String> questions = (List<String>) method.invoke(client, intent, missingParams);

        assertNotNull(questions);
        assertEquals(1, questions.size());
        assertTrue(questions.get(0).contains("批次编号"));
    }

    /**
     * 测试模板生成澄清问题 - 多个参数
     */
    @Test
    void testGenerateTemplateClarificationQuestions_MultipleParams() throws Exception {
        Method method = LlmIntentFallbackClientImpl.class.getDeclaredMethod(
                "generateTemplateClarificationQuestions",
                AIIntentConfig.class,
                List.class);
        method.setAccessible(true);

        AIIntentConfig intent = AIIntentConfig.builder()
                .intentCode("UPDATE_BATCH")
                .intentName("批次更新")
                .build();

        List<String> missingParams = Arrays.asList("batchId", "quantity", "supplierId");

        @SuppressWarnings("unchecked")
        List<String> questions = (List<String>) method.invoke(client, intent, missingParams);

        assertNotNull(questions);
        assertTrue(questions.size() <= 3, "Should return at most 3 questions");
        assertTrue(questions.size() >= 1, "Should return at least 1 question");

        // 第一个问题应该是汇总问题
        String firstQuestion = questions.get(0);
        assertTrue(firstQuestion.contains("批次编号"));
        assertTrue(firstQuestion.contains("数量"));
        assertTrue(firstQuestion.contains("供应商"));
    }

    /**
     * 测试解析澄清问题 - 标准格式
     */
    @Test
    void testParseClarificationQuestions_Standard() throws Exception {
        Method method = LlmIntentFallbackClientImpl.class.getDeclaredMethod(
                "parseClarificationQuestions", String.class);
        method.setAccessible(true);

        String llmResponse = "请问是哪个批次的材料？\n需要更新多少数量？\n供应商是哪一家？";

        @SuppressWarnings("unchecked")
        List<String> questions = (List<String>) method.invoke(client, llmResponse);

        assertNotNull(questions);
        assertEquals(3, questions.size());
        assertEquals("请问是哪个批次的材料？", questions.get(0));
        assertEquals("需要更新多少数量？", questions.get(1));
        assertEquals("供应商是哪一家？", questions.get(2));
    }

    /**
     * 测试解析澄清问题 - 带编号格式
     */
    @Test
    void testParseClarificationQuestions_WithNumbers() throws Exception {
        Method method = LlmIntentFallbackClientImpl.class.getDeclaredMethod(
                "parseClarificationQuestions", String.class);
        method.setAccessible(true);

        String llmResponse = "1. 请问是哪个批次的材料？\n2. 需要更新多少数量？\n3. 供应商是哪一家？";

        @SuppressWarnings("unchecked")
        List<String> questions = (List<String>) method.invoke(client, llmResponse);

        assertNotNull(questions);
        assertEquals(3, questions.size());
        // 编号应该被移除
        assertFalse(questions.get(0).startsWith("1."));
        assertFalse(questions.get(1).startsWith("2."));
    }

    /**
     * 测试解析澄清问题 - 空输入
     */
    @Test
    void testParseClarificationQuestions_Empty() throws Exception {
        Method method = LlmIntentFallbackClientImpl.class.getDeclaredMethod(
                "parseClarificationQuestions", String.class);
        method.setAccessible(true);

        @SuppressWarnings("unchecked")
        List<String> questions1 = (List<String>) method.invoke(client, "");
        assertTrue(questions1.isEmpty());

        @SuppressWarnings("unchecked")
        List<String> questions2 = (List<String>) method.invoke(client, (String) null);
        assertTrue(questions2.isEmpty());
    }

    /**
     * 测试解析澄清问题 - 超过3个问题（应只返回前3个）
     */
    @Test
    void testParseClarificationQuestions_MoreThanThree() throws Exception {
        Method method = LlmIntentFallbackClientImpl.class.getDeclaredMethod(
                "parseClarificationQuestions", String.class);
        method.setAccessible(true);

        String llmResponse = "问题1？\n问题2？\n问题3？\n问题4？\n问题5？";

        @SuppressWarnings("unchecked")
        List<String> questions = (List<String>) method.invoke(client, llmResponse);

        assertNotNull(questions);
        assertEquals(3, questions.size(), "Should return at most 3 questions");
    }

    /**
     * 测试构建澄清问题提示词
     */
    @Test
    void testBuildClarificationPrompt() throws Exception {
        Method method = LlmIntentFallbackClientImpl.class.getDeclaredMethod(
                "buildClarificationPrompt",
                String.class,
                AIIntentConfig.class,
                List.class);
        method.setAccessible(true);

        AIIntentConfig intent = AIIntentConfig.builder()
                .intentCode("UPDATE_BATCH")
                .intentName("批次更新")
                .build();

        List<String> missingParams = Arrays.asList("batchId", "quantity");

        String prompt = (String) method.invoke(
                client,
                "更新批次数量",
                intent,
                missingParams);

        assertNotNull(prompt);
        assertTrue(prompt.contains("批次更新"), "Should contain intent name");
        assertTrue(prompt.contains("更新批次数量"), "Should contain user input");
        assertTrue(prompt.contains("批次编号"), "Should contain friendly parameter name");
        assertTrue(prompt.contains("数量"), "Should contain friendly parameter name");
        assertTrue(prompt.contains("1-3"), "Should mention question count limit");
    }
}
