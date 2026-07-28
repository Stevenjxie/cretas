package com.cretas.aims.ai.tool.impl.scale;

import com.cretas.aims.ai.tool.AbstractBusinessTool;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * AI生成秤配置工具
 *
 * 根据用户描述的需求自动生成秤通信协议配置。
 *
 * Intent Code: SCALE_CONFIG_GENERATE
 *
 * @author Cretas Team
 * @version 1.0.0
 * @since 2026-03-07
 */
@Slf4j
@Component
public class ScaleConfigGenerateTool extends AbstractBusinessTool {

    @Autowired
    private ObjectMapper objectMapper;

    @Override
    public String getToolName() {
        return "scale_config_generate";
    }

    @Override
    public String getDescription() {
        return "AI生成秤协议配置。根据用户描述的通信需求（编码方式、帧格式、波特率等）自动生成协议配置。" +
                "适用场景：快速生成自定义协议配置、创建新协议模板。";
    }

    @Override
    public Map<String, Object> getParametersSchema() {
        Map<String, Object> schema = new HashMap<>();
        schema.put("type", "object");

        Map<String, Object> properties = new HashMap<>();

        Map<String, Object> description = new HashMap<>();
        description.put("type", "string");
        description.put("description", "协议描述，例如「ASCII编码，STX/ETX帧，包含稳定标志」");
        properties.put("description", description);

        Map<String, Object> encoding = new HashMap<>();
        encoding.put("type", "string");
        encoding.put("description", "编码格式: ASCII 或 BINARY");
        encoding.put("enum", Arrays.asList("ASCII", "BINARY"));
        properties.put("encoding", encoding);

        Map<String, Object> baudRate = new HashMap<>();
        baudRate.put("type", "integer");
        baudRate.put("description", "波特率，例如 9600, 19200, 38400");
        baudRate.put("default", 9600);
        properties.put("baudRate", baudRate);

        Map<String, Object> hasStartEnd = new HashMap<>();
        hasStartEnd.put("type", "boolean");
        hasStartEnd.put("description", "是否有帧头帧尾(STX/ETX)");
        properties.put("hasStartEnd", hasStartEnd);

        Map<String, Object> hasStableFlag = new HashMap<>();
        hasStableFlag.put("type", "boolean");
        hasStableFlag.put("description", "是否包含稳定标志位");
        properties.put("hasStableFlag", hasStableFlag);

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
        log.info("执行AI生成秤配置 - 工厂ID: {}, 参数: {}", factoryId, params);

        String userDesc = getString(params, "description", "");
        String encoding = getString(params, "encoding");
        Integer baudRate = getInteger(params, "baudRate", 9600);
        Boolean hasStartEnd = getBoolean(params, "hasStartEnd");
        Boolean hasStableFlag = getBoolean(params, "hasStableFlag");

        // Infer from description if params not provided
        if (hasStartEnd == null) {
            hasStartEnd = userDesc.contains("起始") || userDesc.contains("结束") ||
                    userDesc.contains("帧头") || userDesc.contains("帧尾") ||
                    userDesc.contains("STX") || userDesc.contains("ETX");
        }
        if (encoding == null) {
            boolean isAscii = userDesc.contains("ASCII") || userDesc.contains("文本") || userDesc.contains("字符");
            boolean isBinary = userDesc.contains("二进制") || userDesc.contains("HEX") || userDesc.contains("16进制");
            encoding = isBinary ? "BINARY" : "ASCII";
        }
        if (hasStableFlag == null) {
            hasStableFlag = userDesc.contains("稳定") || userDesc.contains("stable");
        }

        // Build frame format
        Map<String, Object> frameFormat = new LinkedHashMap<>();
        if (hasStartEnd) {
            frameFormat.put("startByte", "0x02");
            frameFormat.put("endByte", "0x03");
        }

        if ("ASCII".equals(encoding)) {
            frameFormat.put("encoding", "ASCII");
            frameFormat.put("weightStart", 1);
            frameFormat.put("weightLength", 7);
            frameFormat.put("unitStart", 8);
            frameFormat.put("unitLength", 2);
        } else {
            frameFormat.put("encoding", "BINARY");
            frameFormat.put("weightBytes", List.of(1, 2, 3, 4));
            frameFormat.put("weightMultiplier", 0.01);
        }

        if (hasStableFlag) {
            frameFormat.put("stableByteIndex", 0);
            frameFormat.put("stableBitMask", "0x20");
        }

        // Build full config
        Map<String, Object> generatedConfig = new LinkedHashMap<>();
        generatedConfig.put("protocolName", "自定义协议_" + System.currentTimeMillis());
        generatedConfig.put("protocolCode", "CUSTOM_" + UUID.randomUUID().toString().substring(0, 8).toUpperCase());
        generatedConfig.put("frameFormat", frameFormat);
        generatedConfig.put("baudRate", baudRate);
        generatedConfig.put("dataBits", 8);
        generatedConfig.put("stopBits", 1);
        generatedConfig.put("parity", "NONE");

        String configJson;
        try {
            configJson = objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(generatedConfig);
        } catch (Exception e) {
            configJson = generatedConfig.toString();
        }

        return buildSimpleResult(
                "已根据描述生成秤协议配置建议",
                Map.of(
                        "generatedConfig", generatedConfig,
                        "configJson", configJson,
                        "notes", List.of(
                                "这是基于描述生成的初始配置",
                                "请根据实际设备手册调整参数",
                                "建议使用测试数据验证配置正确性"
                        )
                )
        );
    }

    @Override
    protected String getParameterQuestion(String paramName) {
        Map<String, String> questions = Map.of(
                "description", "请描述协议需求，例如「ASCII编码，有STX/ETX帧头帧尾」",
                "encoding", "请问数据编码格式是 ASCII 还是 BINARY？",
                "baudRate", "请问波特率是多少？常见值：9600, 19200, 38400"
        );
        return questions.getOrDefault(paramName, super.getParameterQuestion(paramName));
    }

    @Override
    protected String getParameterDisplayName(String paramName) {
        Map<String, String> displayNames = Map.of(
                "description", "协议描述",
                "encoding", "编码格式",
                "baudRate", "波特率",
                "hasStartEnd", "帧头帧尾",
                "hasStableFlag", "稳定标志"
        );
        return displayNames.getOrDefault(paramName, super.getParameterDisplayName(paramName));
    }

    /** spec §8.2 有副作用, 须走 W0 写确认闸 */
    @Override
    public AccessMode getAccessMode() {
        return AccessMode.WRITE;
    }
}
