package com.cretas.aims.ai.tool.impl.scale;

import com.cretas.aims.ai.tool.AbstractBusinessTool;
import com.cretas.aims.dto.scale.ProtocolMatchResult;
import com.cretas.aims.dto.scale.ScaleDataParseResult;
import com.cretas.aims.service.ScaleProtocolAdapterService;
import com.cretas.aims.util.ScaleBrandMatcher;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.regex.Matcher;

/**
 * 秤数据解析测试工具
 *
 * 使用指定协议对16进制数据进行试解析，验证协议配置正确性。
 *
 * Intent Code: SCALE_TEST_PARSE
 *
 * @author Cretas Team
 * @version 1.0.0
 * @since 2026-03-07
 */
@Slf4j
@Component
public class ScaleTestParseTool extends AbstractBusinessTool {

    @Autowired
    private ScaleProtocolAdapterService scaleProtocolAdapterService;

    @Override
    public String getToolName() {
        return "scale_test_parse";
    }

    @Override
    public String getDescription() {
        return "测试秤数据解析。使用指定协议对16进制数据进行试解析，验证协议配置是否正确。" +
                "适用场景：验证协议配置、测试数据解析结果、调试通信问题。";
    }

    @Override
    public Map<String, Object> getParametersSchema() {
        Map<String, Object> schema = new HashMap<>();
        schema.put("type", "object");

        Map<String, Object> properties = new HashMap<>();

        Map<String, Object> protocolId = new HashMap<>();
        protocolId.put("type", "string");
        protocolId.put("description", "协议ID或协议代码，不指定则自动识别");
        properties.put("protocolId", protocolId);

        Map<String, Object> testData = new HashMap<>();
        testData.put("type", "string");
        testData.put("description", "16进制测试数据，例如 02 30 30 2E 35 30 6B 67 03");
        properties.put("testData", testData);

        Map<String, Object> hexData = new HashMap<>();
        hexData.put("type", "string");
        hexData.put("description", "16进制数据(无空格格式)");
        properties.put("hexData", hexData);

        schema.put("properties", properties);
        schema.put("required", Collections.emptyList());

        return schema;
    }

    @Override
    protected List<String> getRequiredParameters() {
        return Collections.emptyList();
    }

    @Override
    protected Map<String, Object> doExecute(String factoryId, Map<String, Object> params, Map<String, Object> context) throws Exception {
        log.info("执行测试数据解析 - 工厂ID: {}, 参数: {}", factoryId, params);

        String protocolId = getString(params, "protocolId");
        String testDataHex = getString(params, "testData");
        if (testDataHex == null) {
            testDataHex = getString(params, "hexData");
        }

        // Try extracting hex from userInput
        if (testDataHex == null) {
            String userInput = getString(params, "userInput");
            if (userInput != null) {
                Matcher matcher = ScaleBrandMatcher.HEX_PATTERN.matcher(userInput);
                if (matcher.find()) {
                    testDataHex = matcher.group().replaceAll("\\s+", "");
                }
            }
        }

        if (testDataHex == null || testDataHex.isEmpty()) {
            Map<String, Object> result = new HashMap<>();
            result.put("message", "请提供测试数据。例如：02 30 30 2E 35 30 6B 67 03");
            return result;
        }

        testDataHex = testDataHex.replaceAll("\\s+", "");

        // Auto-detect protocol if not specified
        if (protocolId == null) {
            List<ProtocolMatchResult> matches = scaleProtocolAdapterService.autoDetectProtocolHex(testDataHex);
            if (matches != null && !matches.isEmpty()) {
                protocolId = matches.get(0).getProtocolId();
            } else {
                Map<String, Object> result = new HashMap<>();
                result.put("message", "未能自动识别协议，请指定协议ID");
                return result;
            }
        }

        // Execute parse
        ScaleDataParseResult parseResult = scaleProtocolAdapterService.dryRunParse(protocolId, testDataHex);

        if (parseResult == null) {
            Map<String, Object> result = new HashMap<>();
            result.put("message", "解析返回空结果，请检查协议配置");
            return result;
        }

        String resultMessage;
        if (parseResult.isSuccess()) {
            resultMessage = String.format("解析成功! 重量: %.2f %s%s",
                    parseResult.getWeight(),
                    parseResult.getUnit() != null ? parseResult.getUnit() : "kg",
                    parseResult.isStable() ? " (稳定)" : " (不稳定)");
        } else {
            resultMessage = "解析失败: " + (parseResult.getErrorMessage() != null ?
                    parseResult.getErrorMessage() : "未知错误");
        }

        return buildSimpleResult(
                resultMessage,
                Map.of(
                        "parseResult", parseResult,
                        "inputData", testDataHex,
                        "protocolId", protocolId,
                        "success", parseResult.isSuccess()
                )
        );
    }

    @Override
    protected String getParameterQuestion(String paramName) {
        Map<String, String> questions = Map.of(
                "protocolId", "请提供协议ID或协议代码",
                "testData", "请提供16进制测试数据，例如 02 30 30 2E 35 30 6B 67 03"
        );
        return questions.getOrDefault(paramName, super.getParameterQuestion(paramName));
    }

    @Override
    protected String getParameterDisplayName(String paramName) {
        Map<String, String> displayNames = Map.of(
                "protocolId", "协议ID",
                "testData", "测试数据",
                "hexData", "16进制数据"
        );
        return displayNames.getOrDefault(paramName, super.getParameterDisplayName(paramName));
    }

    /** spec §8.2 只读查询, 无副作用 */
    @Override
    public AccessMode getAccessMode() {
        return AccessMode.READ;
    }
}
