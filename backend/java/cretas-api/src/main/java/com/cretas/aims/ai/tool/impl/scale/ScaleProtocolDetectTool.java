package com.cretas.aims.ai.tool.impl.scale;

import com.cretas.aims.ai.tool.AbstractBusinessTool;
import com.cretas.aims.dto.scale.ProtocolMatchResult;
import com.cretas.aims.service.ScaleProtocolAdapterService;
import com.cretas.aims.util.ScaleBrandMatcher;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.regex.Matcher;

/**
 * 秤协议自动识别工具
 *
 * 根据样本16进制数据自动识别通信协议。
 *
 * Intent Code: SCALE_PROTOCOL_DETECT
 *
 * @author Cretas Team
 * @version 1.0.0
 * @since 2026-03-07
 */
@Slf4j
@Component
public class ScaleProtocolDetectTool extends AbstractBusinessTool {

    @Autowired
    private ScaleProtocolAdapterService scaleProtocolAdapterService;

    @Override
    public String getToolName() {
        return "scale_protocol_detect";
    }

    @Override
    public String getDescription() {
        return "自动识别秤通信协议。根据提供的16进制样本数据，自动检测匹配的通信协议。" +
                "适用场景：不确定秤使用的协议时，提供串口抓取的数据进行自动识别。";
    }

    @Override
    public Map<String, Object> getParametersSchema() {
        Map<String, Object> schema = new HashMap<>();
        schema.put("type", "object");

        Map<String, Object> properties = new HashMap<>();

        Map<String, Object> sampleData = new HashMap<>();
        sampleData.put("type", "string");
        sampleData.put("description", "16进制样本数据，例如 02 30 30 2E 30 30 6B 67 03");
        properties.put("sampleData", sampleData);

        Map<String, Object> hexData = new HashMap<>();
        hexData.put("type", "string");
        hexData.put("description", "16进制数据(无空格)，例如 020030302E30306B6703");
        properties.put("hexData", hexData);

        schema.put("properties", properties);
        schema.put("required", Collections.emptyList());

        return schema;
    }

    @Override
    protected List<String> getRequiredParameters() {
        // sampleData or hexData is needed, validated in doExecute
        return Collections.emptyList();
    }

    @Override
    protected Map<String, Object> doExecute(String factoryId, Map<String, Object> params, Map<String, Object> context) throws Exception {
        log.info("执行协议自动识别 - 工厂ID: {}, 参数: {}", factoryId, params);

        String hexData = getString(params, "sampleData");
        if (hexData == null) {
            hexData = getString(params, "hexData");
        }

        // Try extracting hex from raw input
        if (hexData == null) {
            String userInput = getString(params, "userInput");
            if (userInput != null) {
                Matcher matcher = ScaleBrandMatcher.HEX_PATTERN.matcher(userInput);
                if (matcher.find()) {
                    hexData = matcher.group().replaceAll("\\s+", "");
                }
            }
        }

        if (hexData == null || hexData.isEmpty()) {
            Map<String, Object> result = new HashMap<>();
            result.put("message", "未找到有效的16进制数据。请提供样本数据，例如：02 30 30 2E 30 30 6B 67 03");
            return result;
        }

        // Clean hex data
        hexData = hexData.replaceAll("\\s+", "");

        List<ProtocolMatchResult> matchResults = scaleProtocolAdapterService.autoDetectProtocolHex(hexData);

        if (matchResults == null || matchResults.isEmpty()) {
            return buildSimpleResult(
                    "未能识别到匹配的协议。数据格式可能是自定义协议，建议手动配置。",
                    Map.of(
                            "inputData", hexData,
                            "matchResults", List.of(),
                            "suggestion", "请尝试手动配置协议，或提供更多样本数据"
                    )
            );
        }

        ProtocolMatchResult bestMatch = matchResults.get(0);

        return buildSimpleResult(
                "识别到 " + matchResults.size() + " 个可能的协议，最佳匹配: " + bestMatch.getProtocolName(),
                Map.of(
                        "inputData", hexData,
                        "bestMatch", bestMatch,
                        "allMatches", matchResults,
                        "confidence", bestMatch.getConfidence()
                )
        );
    }

    @Override
    protected String getParameterQuestion(String paramName) {
        Map<String, String> questions = Map.of(
                "sampleData", "请提供16进制样本数据，例如 02 30 30 2E 30 30 6B 67 03",
                "hexData", "请提供16进制数据"
        );
        return questions.getOrDefault(paramName, super.getParameterQuestion(paramName));
    }

    @Override
    protected String getParameterDisplayName(String paramName) {
        Map<String, String> displayNames = Map.of(
                "sampleData", "样本数据",
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
