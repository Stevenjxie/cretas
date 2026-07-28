package com.cretas.aims.ai.tool.impl.dictionary;

import com.cretas.aims.ai.dto.ToolCall;
import com.cretas.aims.ai.tool.AbstractTool;
import com.cretas.aims.entity.smartbi.SmartBiDictionary;
import com.cretas.aims.repository.smartbi.SmartBiDictionaryRepository;
import com.cretas.aims.service.smartbi.*;
import com.fasterxml.jackson.core.type.TypeReference;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * 添加字典条目工具
 *
 * AI 可调用此工具动态添加新的识别词条到字典中。
 * 支持添加：区域、部门、指标、时间表达式、维度等。
 */
@Slf4j
@Component
public class DictionaryAddTool extends AbstractTool {

    @Autowired
    private SmartBiDictionaryRepository dictionaryRepository;

    @Autowired
    private RegionEntityRecognizer regionRecognizer;

    @Autowired
    private DepartmentEntityRecognizer departmentRecognizer;

    @Autowired
    private MetricEntityRecognizer metricRecognizer;

    @Autowired
    private TimeEntityRecognizer timeRecognizer;

    @Autowired
    private DimensionEntityRecognizer dimensionRecognizer;

    @Override
    public String getToolName() {
        return "dictionary_add";
    }

    @Override
    public String getDescription() {
        return "添加新的识别词条到字典。当用户说'xxx也是部门'、'把xxx加到区域词典'、" +
               "'以后xxx也要能识别'等场景时调用。支持添加区域、部门、指标、时间表达式、维度等类型。";
    }

    @Override
    public Map<String, Object> getParametersSchema() {
        Map<String, Object> schema = new HashMap<>();
        schema.put("type", "object");

        Map<String, Object> properties = new HashMap<>();

        // dictType: 字典类型
        Map<String, Object> dictType = new HashMap<>();
        dictType.put("type", "string");
        dictType.put("description", "字典类型");
        dictType.put("enum", Arrays.asList("region", "department", "metric", "time", "dimension"));
        properties.put("dictType", dictType);

        // name: 标准名称
        Map<String, Object> name = new HashMap<>();
        name.put("type", "string");
        name.put("description", "标准名称，如：研发部、华东区、销售额");
        properties.put("name", name);

        // aliases: 别名列表
        Map<String, Object> aliases = new HashMap<>();
        aliases.put("type", "array");
        aliases.put("description", "别名列表，如：['研发', 'RD', '技术部']");
        Map<String, Object> aliasItem = new HashMap<>();
        aliasItem.put("type", "string");
        aliases.put("items", aliasItem);
        properties.put("aliases", aliases);

        // parentName: 父级名称（可选）
        Map<String, Object> parentName = new HashMap<>();
        parentName.put("type", "string");
        parentName.put("description", "父级名称，用于层级关系。如城市的父级是省份");
        properties.put("parentName", parentName);

        // metadata: 元数据（可选）
        Map<String, Object> metadata = new HashMap<>();
        metadata.put("type", "object");
        metadata.put("description", "扩展元数据，如指标的单位、聚合方式等");
        properties.put("metadata", metadata);

        schema.put("properties", properties);
        schema.put("required", Arrays.asList("dictType", "name"));

        return schema;
    }

    @Override
    public String execute(ToolCall toolCall, Map<String, Object> context) throws Exception {
        Map<String, Object> args = parseArguments(toolCall);

        String dictType = getRequiredParam(args, "dictType");
        String name = getRequiredParam(args, "name");
        String factoryId = getOptionalParam(args, "factoryId",
                context.get("factoryId") != null ? context.get("factoryId").toString() : null);

        // 检查是否已存在
        if (dictionaryRepository.existsByDictTypeAndNameAndFactoryId(dictType, name, factoryId)) {
            return buildErrorResult("词条已存在: " + dictType + "/" + name);
        }

        // 构建实体
        SmartBiDictionary.SmartBiDictionaryBuilder builder = SmartBiDictionary.builder()
                .dictType(dictType)
                .name(name)
                .factoryId(factoryId)
                .source("AI")
                .isActive(true);

        // 处理别名
        if (args.containsKey("aliases")) {
            @SuppressWarnings("unchecked")
            List<String> aliases = (List<String>) args.get("aliases");
            builder.aliases(objectMapper.writeValueAsString(aliases));
        }

        // 处理父级
        if (args.containsKey("parentName")) {
            builder.parentName(args.get("parentName").toString());
        }

        // 处理元数据
        if (args.containsKey("metadata")) {
            builder.metadata(objectMapper.writeValueAsString(args.get("metadata")));
        }

        SmartBiDictionary saved = dictionaryRepository.save(builder.build());

        log.info("Added dictionary entry: type={}, name={}, id={}", dictType, name, saved.getId());

        // 触发对应识别器的 reload
        reloadRecognizer(dictType);

        Map<String, Object> result = new HashMap<>();
        result.put("id", saved.getId());
        result.put("dictType", dictType);
        result.put("name", name);
        result.put("message", "已成功添加'" + name + "'到" + dictType + "字典，立即生效。");

        return buildSuccessResult(result);
    }

    /**
     * 根据字典类型触发对应识别器的热重载
     */
    private void reloadRecognizer(String dictType) {
        try {
            switch (dictType) {
                case "region":
                    regionRecognizer.reload();
                    log.info("Region recognizer reloaded");
                    break;
                case "department":
                    departmentRecognizer.reload();
                    log.info("Department recognizer reloaded");
                    break;
                case "metric":
                    metricRecognizer.reload();
                    log.info("Metric recognizer reloaded");
                    break;
                case "time":
                    timeRecognizer.reload();
                    log.info("Time recognizer reloaded");
                    break;
                case "dimension":
                    dimensionRecognizer.reload();
                    log.info("Dimension recognizer reloaded");
                    break;
                default:
                    log.warn("Unknown dict type for reload: {}", dictType);
            }
        } catch (Exception e) {
            log.error("Failed to reload recognizer for type {}: {}", dictType, e.getMessage());
        }
    }

    /** spec §8.2 有副作用, 须走 W0 写确认闸 */
    @Override
    public AccessMode getAccessMode() {
        return AccessMode.WRITE;
    }
}
