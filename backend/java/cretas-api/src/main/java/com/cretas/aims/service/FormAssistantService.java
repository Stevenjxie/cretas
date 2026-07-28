package com.cretas.aims.service;

import com.cretas.aims.ai.client.DashScopeClient;
import com.cretas.aims.ai.client.DashScopeVisionClient;
import com.cretas.aims.ai.dto.ChatCompletionRequest;
import com.cretas.aims.ai.dto.ChatCompletionResponse;
import com.cretas.aims.config.DashScopeConfig;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * AI表单助手服务
 *
 * 使用 DashScope API 直接调用阿里云通义千问，替代原有 Python 服务
 *
 * 功能：
 * - 表单解析：将自然语言输入解析为表单字段值
 * - OCR解析：从图片中提取并解析表单字段值
 * - Schema生成：根据自然语言描述生成 Formily JSON Schema
 * - 校验反馈：根据校验错误提供修正建议
 *
 * @author Cretas Team
 * @version 2.0.0 (DashScope 直连版)
 * @since 2026-01-04
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class FormAssistantService {

    private final DashScopeClient dashScopeClient;
    private final DashScopeVisionClient visionClient;
    private final DashScopeConfig config;
    private final ObjectMapper objectMapper;
    private final FormPromptRegistry formPromptRegistry;

    // ==================== 表单解析 ====================

    /**
     * 解析用户自然语言输入为表单字段值
     *
     * @param userInput   用户输入
     * @param entityType  实体类型
     * @param formFields  表单字段定义
     * @param factoryId   工厂ID
     * @return 解析结果
     */
    public FormParseResult parseFormInput(String userInput, String entityType,
                                          List<Map<String, Object>> formFields, String factoryId) {
        return parseFormInput(userInput, entityType, formFields, factoryId, false, 20);
    }

    /**
     * 解析用户自然语言输入为表单字段值（支持思考模式）
     *
     * @param userInput       用户输入
     * @param entityType      实体类型
     * @param formFields      表单字段定义
     * @param factoryId       工厂ID
     * @param enableThinking  是否启用思考模式
     * @param thinkingBudget  思考预算 (10-100)
     * @return 解析结果
     */
    public FormParseResult parseFormInput(String userInput, String entityType,
                                          List<Map<String, Object>> formFields, String factoryId,
                                          boolean enableThinking, int thinkingBudget) {
        if (!dashScopeClient.isAvailable()) {
            return FormParseResult.error("AI服务未配置");
        }

        try {
            String systemPrompt = buildFormParseSystemPrompt(entityType, formFields);
            String response;

            if (enableThinking) {
                log.info("表单解析使用思考模式: thinkingBudget={}", thinkingBudget);
                var thinkingResponse = dashScopeClient.chatWithThinking(systemPrompt, userInput, thinkingBudget);
                if (thinkingResponse.hasError()) {
                    return FormParseResult.error("AI思考模式错误: " + thinkingResponse.getErrorMessage());
                }
                response = thinkingResponse.getContent();
            } else {
                response = dashScopeClient.chatLowTemp(systemPrompt, userInput);
            }

            return parseFormParseResponse(response, formFields);
        } catch (Exception e) {
            log.error("表单解析失败", e);
            return FormParseResult.error("解析失败: " + e.getMessage());
        }
    }

    private String buildFormParseSystemPrompt(String entityType, List<Map<String, Object>> formFields) {
        StringBuilder sb = new StringBuilder();

        // 域知识优先取注册表里的专属 prompt (resources/ai/form-prompts/{domain}/{ENTITY}.md)。
        // 那些 prompt 带着通用版给不出的防呆规则 —— 例如生产计划要求
        // "SKU 名必须逐字保留、禁止缩写归一化"，因为页面随后要拿这个名字去跟真实
        // 产品表做唯一匹配，一旦被 AI 缩写成相似品名就会匹配失败或匹配错。
        // 取不到就回退通用 prompt (新实体没登记时不至于整个不可用)。
        // 域先写死 factory: 当前 7 个可填实体全是工厂侧; 将来餐饮侧要填表时
        // 从请求/租户业态推导域再传进来。
        String domainPrompt = formPromptRegistry.promptFor("factory", entityType).orElse(null);
        if (domainPrompt != null) {
            sb.append(domainPrompt).append("\n\n");
        } else {
            sb.append("你是一个食品加工厂的智能表单助手。用户会用自然语言描述要填写的内容，你需要将其解析为表单字段值。\n\n");
            sb.append("实体类型: ").append(entityType).append("\n\n");
        }

        // 当前日期对**所有**实体都必要 —— 多份 prompt 都写着"日期支持自然语言"
        // ("今天"/"明天"/"下周一"), 但此前只有生产计划那份告诉了模型今天几号,
        // 其余几份等于让模型拿训练截止日去猜。统一在这里补上。
        sb.append("工厂当前日期: ").append(formPromptRegistry.today())
          .append(" (时区 Asia/Shanghai)。所有相对日期一律按它换算成 YYYY-MM-DD。\n\n");

        if (formFields != null && !formFields.isEmpty()) {
            sb.append("表单字段定义:\n");
            for (Map<String, Object> field : formFields) {
                sb.append("- ").append(field.get("name"));
                if (field.get("title") != null) {
                    sb.append(" (").append(field.get("title")).append(")");
                }
                sb.append(": 类型=").append(field.get("type"));
                if (Boolean.TRUE.equals(field.get("required"))) {
                    sb.append(" [必填]");
                }
                sb.append("\n");
            }
            sb.append("\n");
        }

        sb.append("""
                请以 JSON 格式返回解析结果：
                {
                    "success": true,
                    "field_values": {
                        "字段名": "解析出的值",
                        ...
                    },
                    "confidence": 0.0-1.0,
                    "unparsed_text": "无法解析的部分",
                    "missing_required_fields": ["缺失的必填字段名"],
                    "follow_up_question": "针对缺失字段的追问问题"
                }

                注意：
                1. 只返回 JSON，不要包含其他文字
                2. 字段值类型要匹配字段定义
                3. 数值类型不要带引号
                4. 日期格式使用 yyyy-MM-dd
                5. 如果有必填字段缺失，生成友好的追问问题
                """);

        return sb.toString();
    }

    private FormParseResult parseFormParseResponse(String response, List<Map<String, Object>> formFields) {
        try {
            JsonNode json = extractJson(response);
            if (json == null) {
                return FormParseResult.error("无法解析AI响应");
            }

            FormParseResult result = new FormParseResult();
            result.setSuccess(json.path("success").asBoolean(true));
            result.setConfidence(json.path("confidence").asDouble(0.8));
            result.setUnparsedText(json.path("unparsed_text").asText(null));
            result.setFollowUpQuestion(json.path("follow_up_question").asText(null));

            // 解析字段值
            JsonNode fieldValues = json.get("field_values");
            if (fieldValues != null && fieldValues.isObject()) {
                Map<String, Object> values = objectMapper.convertValue(fieldValues,
                        new TypeReference<Map<String, Object>>() {});
                result.setFieldValues(values);
            } else {
                result.setFieldValues(new HashMap<>());
            }

            // 解析缺失字段
            JsonNode missingFields = json.get("missing_required_fields");
            if (missingFields != null && missingFields.isArray()) {
                List<String> missing = new ArrayList<>();
                missingFields.forEach(n -> missing.add(n.asText()));
                result.setMissingRequiredFields(missing);
            }

            return result;
        } catch (Exception e) {
            log.error("解析表单响应失败", e);
            return FormParseResult.error("响应解析失败: " + e.getMessage());
        }
    }

    // ==================== OCR解析 ====================

    /**
     * 从图片中提取并解析表单字段值
     *
     * @param imageBase64  Base64编码的图片
     * @param entityType   实体类型
     * @param formFields   表单字段定义
     * @param factoryId    工厂ID
     * @return OCR解析结果
     */
    public OCRParseResult parseFormOCR(String imageBase64, String entityType,
                                       List<Map<String, Object>> formFields, String factoryId) {
        if (!visionClient.isAvailable()) {
            return OCRParseResult.error("视觉AI服务未配置");
        }

        try {
            String prompt = buildOCRPrompt(entityType, formFields);

            // 使用视觉模型
            ChatCompletionRequest request = ChatCompletionRequest.builder()
                    .model(config.getVisionModel())
                    .messages(List.of(
                            com.cretas.aims.ai.dto.ChatMessage.userWithImage(prompt, imageBase64)
                    ))
                    .maxTokens(2000)
                    .temperature(0.3)
                    .build();

            ChatCompletionResponse response = dashScopeClient.chatCompletion(request);

            if (response.hasError()) {
                return OCRParseResult.error("OCR识别失败: " + response.getErrorMessage());
            }

            return parseOCRResponse(response.getContent());
        } catch (Exception e) {
            log.error("OCR解析失败", e);
            return OCRParseResult.error("OCR解析失败: " + e.getMessage());
        }
    }

    private String buildOCRPrompt(String entityType, List<Map<String, Object>> formFields) {
        StringBuilder sb = new StringBuilder();
        sb.append("请识别这张图片中的文字内容，并提取与以下表单相关的信息。\n\n");
        sb.append("实体类型: ").append(entityType).append("\n\n");

        if (formFields != null && !formFields.isEmpty()) {
            sb.append("需要提取的字段:\n");
            for (Map<String, Object> field : formFields) {
                sb.append("- ").append(field.get("name"));
                if (field.get("title") != null) {
                    sb.append(" (").append(field.get("title")).append(")");
                }
                sb.append("\n");
            }
            sb.append("\n");
        }

        sb.append("""
                请以 JSON 格式返回:
                {
                    "success": true,
                    "extracted_text": "图片中识别到的所有文字",
                    "field_values": {
                        "字段名": "提取的值",
                        ...
                    },
                    "confidence": 0.0-1.0
                }

                仅返回 JSON，不要包含其他文字。
                """);

        return sb.toString();
    }

    private OCRParseResult parseOCRResponse(String response) {
        try {
            JsonNode json = extractJson(response);
            if (json == null) {
                return OCRParseResult.error("无法解析OCR响应");
            }

            OCRParseResult result = new OCRParseResult();
            result.setSuccess(json.path("success").asBoolean(true));
            result.setExtractedText(json.path("extracted_text").asText(""));
            result.setConfidence(json.path("confidence").asDouble(0.8));

            JsonNode fieldValues = json.get("field_values");
            if (fieldValues != null && fieldValues.isObject()) {
                Map<String, Object> values = objectMapper.convertValue(fieldValues,
                        new TypeReference<Map<String, Object>>() {});
                result.setFieldValues(values);
            } else {
                result.setFieldValues(new HashMap<>());
            }

            return result;
        } catch (Exception e) {
            log.error("解析OCR响应失败", e);
            return OCRParseResult.error("响应解析失败: " + e.getMessage());
        }
    }

    // ==================== Schema生成 ====================

    /**
     * 根据自然语言描述生成 Formily JSON Schema 字段定义
     *
     * @param userInput      用户描述
     * @param entityType     实体类型
     * @param existingFields 现有字段名列表
     * @param factoryId      工厂ID
     * @return 生成的Schema字段
     */
    public SchemaGenerateResult generateSchema(String userInput, String entityType,
                                               List<String> existingFields, String factoryId) {
        return generateSchemaWithJustification(userInput, entityType, existingFields, null, factoryId);
    }

    /**
     * 根据自然语言描述生成 Formily JSON Schema 字段定义（支持用户解释）
     *
     * 当AI判断字段不相关时，用户可以提供解释说明为什么需要该字段。
     * AI会重新评估，如果解释合理则接受该字段。
     *
     * @param userInput         用户描述
     * @param entityType        实体类型
     * @param existingFields    现有字段名列表
     * @param userJustification 用户解释（为什么需要该字段）
     * @param factoryId         工厂ID
     * @return 生成的Schema字段
     */
    public SchemaGenerateResult generateSchemaWithJustification(String userInput, String entityType,
                                                                 List<String> existingFields,
                                                                 String userJustification,
                                                                 String factoryId) {
        return generateSchemaWithJustification(userInput, entityType, existingFields, userJustification, factoryId, false, 20);
    }

    /**
     * 根据自然语言描述生成 Formily JSON Schema 字段定义（支持用户解释和思考模式）
     *
     * @param userInput         用户描述
     * @param entityType        实体类型
     * @param existingFields    现有字段名列表
     * @param userJustification 用户解释（为什么需要该字段）
     * @param factoryId         工厂ID
     * @param enableThinking    是否启用思考模式
     * @param thinkingBudget    思考预算 (10-100)
     * @return 生成的Schema字段
     */
    public SchemaGenerateResult generateSchemaWithJustification(String userInput, String entityType,
                                                                 List<String> existingFields,
                                                                 String userJustification,
                                                                 String factoryId,
                                                                 boolean enableThinking,
                                                                 int thinkingBudget) {
        if (!dashScopeClient.isAvailable()) {
            return SchemaGenerateResult.error("AI服务未配置");
        }

        try {
            String systemPrompt = buildSchemaGeneratePrompt(entityType, existingFields, userJustification);
            String response;

            if (enableThinking) {
                log.info("Schema生成使用思考模式: thinkingBudget={}", thinkingBudget);
                var thinkingResponse = dashScopeClient.chatWithThinking(systemPrompt, userInput, thinkingBudget);
                if (thinkingResponse.hasError()) {
                    return SchemaGenerateResult.error("AI思考模式错误: " + thinkingResponse.getErrorMessage());
                }
                response = thinkingResponse.getContent();
            } else {
                response = dashScopeClient.chatLowTemp(systemPrompt, userInput);
            }

            return parseSchemaGenerateResponse(response);
        } catch (Exception e) {
            log.error("Schema生成失败", e);
            return SchemaGenerateResult.error("生成失败: " + e.getMessage());
        }
    }

    private String buildSchemaGeneratePrompt(String entityType, List<String> existingFields) {
        return buildSchemaGeneratePrompt(entityType, existingFields, null);
    }

    private String buildSchemaGeneratePrompt(String entityType, List<String> existingFields, String userJustification) {
        StringBuilder sb = new StringBuilder();
        sb.append("你是一个食品加工厂的表单配置专家。用户会描述需要添加的表单字段，你需要:\n");
        sb.append("1. 首先判断该字段是否与当前实体类型相关\n");
        sb.append("2. 如果相关，生成 Formily JSON Schema 格式的字段定义\n");
        sb.append("3. 如果不相关，拒绝并说明原因\n\n");

        sb.append("实体类型: ").append(entityType).append("\n");
        sb.append("实体类型说明:\n");
        sb.append(getEntityTypeDescription(entityType));
        sb.append("\n\n");

        if (existingFields != null && !existingFields.isEmpty()) {
            sb.append("现有字段 (避免重复): ").append(String.join(", ", existingFields)).append("\n\n");
        }

        if (userJustification != null && !userJustification.isBlank()) {
            sb.append("用户解释为什么需要此字段: ").append(userJustification).append("\n");
            sb.append("请重新评估该字段是否合理。如果用户解释合理，可以接受。\n\n");
        }

        sb.append("""
                请以 JSON 格式返回:

                如果字段与实体类型相关:
                {
                    "success": true,
                    "relevant": true,
                    "fields": [
                        {
                            "name": "fieldName",
                            "title": "字段标题",
                            "type": "string|number|boolean|array",
                            "description": "字段描述",
                            "x_component": "Input|NumberPicker|Select|DatePicker|...",
                            "x_component_props": {},
                            "x_decorator": "FormItem",
                            "x_validator": [{"required": true, "message": "xxx"}],
                            "enum": [{"label": "选项1", "value": "1"}],
                            "default": null
                        }
                    ],
                    "suggestions": ["建议1", "建议2"]
                }

                如果字段与实体类型不相关:
                {
                    "success": true,
                    "relevant": false,
                    "rejectionReason": "该字段与质检检查无关，质检检查应关注产品质量指标...",
                    "suggestedEntityType": "PROCESSING_BATCH",
                    "suggestions": ["您可能需要将此字段添加到生产批次表单中"]
                }

                Formily 组件参考:
                - Input: 文本输入
                - NumberPicker: 数字输入
                - Select: 下拉选择
                - DatePicker: 日期选择
                - Switch: 开关
                - Rate: 评分
                - Slider: 滑块

                仅返回 JSON，不要包含其他文字。
                """);

        return sb.toString();
    }

    /**
     * 获取实体类型的业务描述，帮助AI理解该实体应该包含哪些字段
     */
    private String getEntityTypeDescription(String entityType) {
        return switch (entityType.toUpperCase()) {
            case "QUALITY_CHECK" -> """
                - 质检检查表单：用于记录产品质量检验数据
                - 相关字段示例：感官评分、温度检测、微生物指标、检验结果、合格/不合格判定等
                - 不相关字段：生产数量、人员考勤、设备维护、运输信息等
                """;
            case "MATERIAL_BATCH" -> """
                - 原料批次表单：用于记录原材料入库信息
                - 相关字段示例：供应商、原料类型、数量、入库时间、存储位置、保质期等
                - 不相关字段：产品销售、员工考勤、设备状态等
                """;
            case "PROCESSING_BATCH" -> """
                - 生产批次表单：用于记录生产加工信息
                - 相关字段示例：产品类型、生产数量、生产线、操作员、开始/结束时间、原料消耗等
                - 不相关字段：客户信息、销售数据、财务信息等
                """;
            case "SHIPMENT" -> """
                - 出货记录表单：用于记录产品出库和物流信息
                - 相关字段示例：客户、发货数量、运输方式、运输温度、配送地址、签收状态等
                - 不相关字段：生产工艺、设备维护、内部考勤等
                """;
            case "EQUIPMENT" -> """
                - 设备管理表单：用于记录设备信息和维护数据
                - 相关字段示例：设备型号、维护周期、故障记录、运行状态、校准日期等
                - 不相关字段：产品销售、原料采购、人员管理等
                """;
            case "DISPOSAL_RECORD" -> """
                - 废弃物处理表单：用于记录不合格品或废弃物处理信息
                - 相关字段示例：处理原因、处理方式、处理数量、审批人、处理日期等
                - 不相关字段：正常生产数据、销售信息等
                """;
            default -> "- 通用表单：请根据实际业务需求判断字段相关性\n";
        };
    }

    private SchemaGenerateResult parseSchemaGenerateResponse(String response) {
        try {
            JsonNode json = extractJson(response);
            if (json == null) {
                return SchemaGenerateResult.error("无法解析生成结果");
            }

            SchemaGenerateResult result = new SchemaGenerateResult();
            result.setSuccess(json.path("success").asBoolean(true));

            // 解析相关性判断结果
            boolean relevant = json.path("relevant").asBoolean(true); // 默认为true
            result.setRelevant(relevant);

            if (!relevant) {
                // 字段不相关，解析拒绝原因
                result.setRejectionReason(json.path("rejectionReason").asText(null));
                result.setSuggestedEntityType(json.path("suggestedEntityType").asText(null));
                result.setFields(new ArrayList<>());
            } else {
                // 字段相关，解析字段列表
                JsonNode fieldsNode = json.get("fields");
                if (fieldsNode != null && fieldsNode.isArray()) {
                    List<Map<String, Object>> fields = objectMapper.convertValue(fieldsNode,
                            new TypeReference<List<Map<String, Object>>>() {});
                    result.setFields(fields);
                } else {
                    result.setFields(new ArrayList<>());
                }
            }

            // 解析建议
            JsonNode suggestionsNode = json.get("suggestions");
            if (suggestionsNode != null && suggestionsNode.isArray()) {
                List<String> suggestions = new ArrayList<>();
                suggestionsNode.forEach(n -> suggestions.add(n.asText()));
                result.setSuggestions(suggestions);
            }

            return result;
        } catch (Exception e) {
            log.error("解析Schema生成响应失败", e);
            return SchemaGenerateResult.error("响应解析失败: " + e.getMessage());
        }
    }

    // ==================== 校验反馈 ====================

    /**
     * 根据校验错误提供修正建议
     *
     * @param entityType       实体类型
     * @param formFields       表单字段定义
     * @param submittedValues  用户提交的值
     * @param validationErrors 校验错误列表
     * @param userInstruction  用户补充说明
     * @return 修正建议
     */
    public ValidationFeedbackResult submitValidationFeedback(
            String entityType,
            List<Map<String, Object>> formFields,
            Map<String, Object> submittedValues,
            List<Map<String, Object>> validationErrors,
            String userInstruction,
            String factoryId) {
        return submitValidationFeedback(entityType, formFields, submittedValues, validationErrors,
                userInstruction, factoryId, false, 20);
    }

    /**
     * 根据校验错误提供修正建议（支持思考模式）
     *
     * @param entityType       实体类型
     * @param formFields       表单字段定义
     * @param submittedValues  用户提交的值
     * @param validationErrors 校验错误列表
     * @param userInstruction  用户补充说明
     * @param factoryId        工厂ID
     * @param enableThinking   是否启用思考模式
     * @param thinkingBudget   思考预算 (10-100)
     * @return 修正建议
     */
    public ValidationFeedbackResult submitValidationFeedback(
            String entityType,
            List<Map<String, Object>> formFields,
            Map<String, Object> submittedValues,
            List<Map<String, Object>> validationErrors,
            String userInstruction,
            String factoryId,
            boolean enableThinking,
            int thinkingBudget) {

        if (!dashScopeClient.isAvailable()) {
            return ValidationFeedbackResult.error("AI服务未配置");
        }

        try {
            String prompt = buildValidationFeedbackPrompt(entityType, formFields,
                    submittedValues, validationErrors, userInstruction);
            String systemPrompt = "你是一个表单校验助手，帮助用户修正表单填写错误。";
            String response;

            if (enableThinking) {
                log.info("校验反馈使用思考模式: thinkingBudget={}", thinkingBudget);
                var thinkingResponse = dashScopeClient.chatWithThinking(systemPrompt, prompt, thinkingBudget);
                if (thinkingResponse.hasError()) {
                    return ValidationFeedbackResult.error("AI思考模式错误: " + thinkingResponse.getErrorMessage());
                }
                response = thinkingResponse.getContent();
            } else {
                response = dashScopeClient.chatLowTemp(systemPrompt, prompt);
            }

            return parseValidationFeedbackResponse(response);
        } catch (Exception e) {
            log.error("校验反馈处理失败", e);
            return ValidationFeedbackResult.error("处理失败: " + e.getMessage());
        }
    }

    private String buildValidationFeedbackPrompt(
            String entityType,
            List<Map<String, Object>> formFields,
            Map<String, Object> submittedValues,
            List<Map<String, Object>> validationErrors,
            String userInstruction) {

        StringBuilder sb = new StringBuilder();
        sb.append("表单类型: ").append(entityType).append("\n\n");

        sb.append("用户提交的值:\n");
        try {
            sb.append(objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(submittedValues));
        } catch (JsonProcessingException e) {
            sb.append(submittedValues.toString());
        }
        sb.append("\n\n");

        sb.append("校验错误:\n");
        for (Map<String, Object> error : validationErrors) {
            sb.append("- 字段 ").append(error.get("field"))
              .append(": ").append(error.get("message")).append("\n");
        }
        sb.append("\n");

        if (userInstruction != null && !userInstruction.isEmpty()) {
            sb.append("用户说明: ").append(userInstruction).append("\n\n");
        }

        sb.append("""
                请分析校验错误并提供修正建议，以 JSON 格式返回:
                {
                    "success": true,
                    "correction_hints": {
                        "字段名": "修正建议"
                    },
                    "corrected_values": {
                        "字段名": "建议的正确值"
                    },
                    "explanation": "整体解释",
                    "confidence": 0.0-1.0
                }

                仅返回 JSON。
                """);

        return sb.toString();
    }

    private ValidationFeedbackResult parseValidationFeedbackResponse(String response) {
        try {
            JsonNode json = extractJson(response);
            if (json == null) {
                return ValidationFeedbackResult.error("无法解析反馈结果");
            }

            ValidationFeedbackResult result = new ValidationFeedbackResult();
            result.setSuccess(json.path("success").asBoolean(true));
            result.setExplanation(json.path("explanation").asText(""));
            result.setConfidence(json.path("confidence").asDouble(0.8));

            JsonNode hints = json.get("correction_hints");
            if (hints != null && hints.isObject()) {
                Map<String, String> hintsMap = objectMapper.convertValue(hints,
                        new TypeReference<Map<String, String>>() {});
                result.setCorrectionHints(hintsMap);
            } else {
                result.setCorrectionHints(new HashMap<>());
            }

            JsonNode corrected = json.get("corrected_values");
            if (corrected != null && corrected.isObject()) {
                Map<String, Object> correctedMap = objectMapper.convertValue(corrected,
                        new TypeReference<Map<String, Object>>() {});
                result.setCorrectedValues(correctedMap);
            } else {
                result.setCorrectedValues(new HashMap<>());
            }

            return result;
        } catch (Exception e) {
            log.error("解析校验反馈响应失败", e);
            return ValidationFeedbackResult.error("响应解析失败: " + e.getMessage());
        }
    }

    // ==================== 健康检查 ====================

    /**
     * 检查服务是否可用
     */
    public boolean isAvailable() {
        return dashScopeClient.isAvailable();
    }

    /**
     * 获取服务状态信息
     */
    public Map<String, Object> getHealthInfo() {
        Map<String, Object> info = new HashMap<>();
        info.put("available", dashScopeClient.isAvailable());
        info.put("provider", "阿里云 DashScope");
        info.put("model", config.getModel());
        info.put("visionModel", config.getVisionModel());
        info.put("visionAvailable", visionClient.isAvailable());
        return info;
    }

    // ==================== 工具方法 ====================

    private JsonNode extractJson(String text) {
        try {
            // 尝试直接解析
            return objectMapper.readTree(text);
        } catch (JsonProcessingException e) {
            // 尝试提取 JSON 部分
            Pattern pattern = Pattern.compile("\\{[\\s\\S]*\\}");
            Matcher matcher = pattern.matcher(text);
            if (matcher.find()) {
                try {
                    return objectMapper.readTree(matcher.group());
                } catch (JsonProcessingException ex) {
                    log.warn("JSON提取失败: {}", text);
                    return null;
                }
            }
            return null;
        }
    }

    // ==================== 结果类定义 ====================

    @lombok.Data
    @lombok.Builder
    @lombok.NoArgsConstructor
    @lombok.AllArgsConstructor
    public static class FormParseResult {
        private boolean success;
        private Map<String, Object> fieldValues;
        private double confidence;
        private String unparsedText;
        private String message;
        private List<String> missingRequiredFields;
        private String followUpQuestion;

        public static FormParseResult error(String message) {
            return FormParseResult.builder()
                    .success(false)
                    .message(message)
                    .fieldValues(new HashMap<>())
                    .confidence(0.0)
                    .build();
        }
    }

    @lombok.Data
    @lombok.Builder
    @lombok.NoArgsConstructor
    @lombok.AllArgsConstructor
    public static class OCRParseResult {
        private boolean success;
        private String extractedText;
        private Map<String, Object> fieldValues;
        private double confidence;
        private String message;

        public static OCRParseResult error(String message) {
            return OCRParseResult.builder()
                    .success(false)
                    .message(message)
                    .extractedText("")
                    .fieldValues(new HashMap<>())
                    .confidence(0.0)
                    .build();
        }
    }

    @lombok.Data
    @lombok.Builder
    @lombok.NoArgsConstructor
    @lombok.AllArgsConstructor
    public static class SchemaGenerateResult {
        private boolean success;
        private List<Map<String, Object>> fields;
        private List<String> suggestions;
        private String message;

        /** AI判断字段是否与实体类型相关 */
        private boolean relevant;
        /** 如果不相关，拒绝原因 */
        private String rejectionReason;
        /** AI建议的实体类型（如果字段更适合其他表单） */
        private String suggestedEntityType;

        public static SchemaGenerateResult error(String message) {
            return SchemaGenerateResult.builder()
                    .success(false)
                    .message(message)
                    .fields(new ArrayList<>())
                    .relevant(true) // 错误情况默认为true，避免前端误判
                    .build();
        }

        public static SchemaGenerateResult rejected(String rejectionReason, String suggestedEntityType, List<String> suggestions) {
            return SchemaGenerateResult.builder()
                    .success(true)
                    .relevant(false)
                    .rejectionReason(rejectionReason)
                    .suggestedEntityType(suggestedEntityType)
                    .suggestions(suggestions)
                    .fields(new ArrayList<>())
                    .build();
        }
    }

    @lombok.Data
    @lombok.Builder
    @lombok.NoArgsConstructor
    @lombok.AllArgsConstructor
    public static class ValidationFeedbackResult {
        private boolean success;
        private Map<String, String> correctionHints;
        private Map<String, Object> correctedValues;
        private String explanation;
        private double confidence;
        private String message;

        public static ValidationFeedbackResult error(String message) {
            return ValidationFeedbackResult.builder()
                    .success(false)
                    .message(message)
                    .correctionHints(new HashMap<>())
                    .correctedValues(new HashMap<>())
                    .confidence(0.0)
                    .build();
        }
    }
}
