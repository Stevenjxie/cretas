package com.cretas.aims.ai.tool.impl.shipment;

import com.cretas.aims.ai.tool.AbstractBusinessTool;
import com.cretas.aims.dto.traceability.TraceabilityDTO;
import com.cretas.aims.service.TraceabilityService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * 公开溯源工具
 *
 * 提供面向消费者的公开溯源查询，返回脱敏后的追溯信息。
 * 适用于消费者扫描二维码查询产品来源的场景。
 *
 * Intent Code: TRACE_PUBLIC
 *
 * factoryId 隔离豁免说明: 这是**对外公开**的 C 端溯源工具, 消费者扫码只需
 * 批次号即可查询 (无账号/无工厂身份), traceabilityService.getPublicTrace 按设计
 * 跨工厂可查 (脱敏后的公开追溯链). 这是业务需求, 不是漏洞.
 *
 * @author Cretas Team
 * @version 1.0.0
 * @since 2026-01-07
 */
@Slf4j
@Component
public class TracePublicTool extends AbstractBusinessTool {

    @Autowired
    private TraceabilityService traceabilityService;

    @Override
    public String getToolName() {
        return "trace_public";
    }

    @Override
    public String getDescription() {
        return "公开溯源查询，供消费者使用。返回脱敏后的产品追溯信息，包括产地、生产日期、质检结果等。" +
                "适用场景：消费者扫码查询、公开透明度展示。";
    }

    @Override
    public Map<String, Object> getParametersSchema() {
        Map<String, Object> schema = new HashMap<>();
        schema.put("type", "object");

        Map<String, Object> properties = new HashMap<>();

        // batchNumber: 批次号（必需）
        Map<String, Object> batchNumber = new HashMap<>();
        batchNumber.put("type", "string");
        batchNumber.put("description", "产品批次号（通常从二维码扫描获取）");
        properties.put("batchNumber", batchNumber);

        schema.put("properties", properties);
        schema.put("required", Arrays.asList("batchNumber"));

        return schema;
    }

    @Override
    protected List<String> getRequiredParameters() {
        return Arrays.asList("batchNumber");
    }

    @Override
    protected Map<String, Object> doExecute(String factoryId, Map<String, Object> params, Map<String, Object> context) throws Exception {
        log.info("执行公开溯源 - 工厂ID: {}, 参数: {}", factoryId, params);

        // 获取参数
        String batchNumber = getString(params, "batchNumber");

        // 调用公开溯源服务（不需要factoryId，因为是公开接口）
        TraceabilityDTO.PublicTraceResponse traceResponse = traceabilityService.getPublicTrace(batchNumber);

        // 构建返回结果
        Map<String, Object> result = new HashMap<>();
        result.put("success", true);
        result.put("batchNumber", batchNumber);
        result.put("publicTraceInfo", traceResponse);
        result.put("notice", "此信息为公开溯源数据，部分敏感信息已脱敏处理");

        log.info("公开溯源完成 - 批次号: {}", batchNumber);

        return result;
    }

    @Override
    protected String getParameterQuestion(String paramName) {
        Map<String, String> questions = Map.of(
            "batchNumber", "请问要查询哪个产品的溯源信息？请提供批次号或扫描产品二维码。"
        );
        return questions.getOrDefault(paramName, super.getParameterQuestion(paramName));
    }

    @Override
    protected String getParameterDisplayName(String paramName) {
        Map<String, String> displayNames = Map.of(
            "batchNumber", "批次号"
        );
        return displayNames.getOrDefault(paramName, super.getParameterDisplayName(paramName));
    }

    /** spec §8.2 只读查询, 无副作用 */
    @Override
    public AccessMode getAccessMode() {
        return AccessMode.READ;
    }
}
