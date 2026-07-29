package com.cretas.aims.ai.tool.impl.dictionary;

import com.cretas.aims.ai.dto.ToolCall;
import com.cretas.aims.ai.tool.AbstractTool;
import com.cretas.aims.entity.smartbi.SmartBiDictionary;
import com.cretas.aims.repository.smartbi.SmartBiDictionaryRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.stream.Collectors;

/**
 * 查询字典条目工具
 *
 * AI 可调用此工具查看当前字典中有哪些条目。
 */
@Slf4j
@Component
public class DictionaryListTool extends AbstractTool {

    @Autowired
    private SmartBiDictionaryRepository dictionaryRepository;

    @Override
    public String getToolName() {
        return "dictionary_list";
    }

    @Override
    public String getDescription() {
        return "查询字典中的条目列表。当用户问'有哪些部门'、'支持哪些区域'、" +
               "'能识别哪些指标'等场景时调用。";
    }

    @Override
    public Map<String, Object> getParametersSchema() {
        Map<String, Object> schema = new HashMap<>();
        schema.put("type", "object");

        Map<String, Object> properties = new HashMap<>();

        // dictType: 字典类型（可选，不传则返回所有类型）
        Map<String, Object> dictType = new HashMap<>();
        dictType.put("type", "string");
        dictType.put("description", "字典类型，不传则返回所有类型的统计");
        dictType.put("enum", Arrays.asList("region", "department", "metric", "time", "dimension"));
        properties.put("dictType", dictType);

        // limit: 限制返回数量
        Map<String, Object> limit = new HashMap<>();
        limit.put("type", "integer");
        limit.put("description", "最多返回多少条，默认20");
        limit.put("default", 20);
        properties.put("limit", limit);

        schema.put("properties", properties);
        schema.put("required", Collections.emptyList());

        return schema;
    }

    @Override
    public String execute(ToolCall toolCall, Map<String, Object> context) throws Exception {
        Map<String, Object> args = parseArguments(toolCall);

        String dictType = getOptionalParam(args, "dictType", null);
        int limit = Integer.parseInt(getOptionalParam(args, "limit", "20"));
        String factoryId = context.get("factoryId") != null ?
                context.get("factoryId").toString() : null;

        Map<String, Object> result = new HashMap<>();

        if (dictType != null) {
            // 查询特定类型
            List<SmartBiDictionary> entries = factoryId != null ?
                    dictionaryRepository.findByDictTypeAndFactoryId(dictType, factoryId) :
                    dictionaryRepository.findByDictTypeAndIsActiveTrueOrderByPriorityAsc(dictType);

            List<Map<String, Object>> items = entries.stream()
                    .limit(limit)
                    .map(this::toSimpleMap)
                    .collect(Collectors.toList());

            result.put("dictType", dictType);
            result.put("total", entries.size());
            result.put("items", items);
            result.put("message", dictType + "字典共有" + entries.size() + "个条目");
        } else {
            // 返回所有类型的统计
            List<String> types = dictionaryRepository.findAllDictTypes();
            Map<String, Long> stats = new HashMap<>();
            for (String type : types) {
                stats.put(type, dictionaryRepository.countByDictTypeAndIsActiveTrue(type));
            }
            result.put("statistics", stats);
            result.put("message", "字典统计: " + stats);
        }

        return buildSuccessResult(result);
    }

    private Map<String, Object> toSimpleMap(SmartBiDictionary entry) {
        Map<String, Object> map = new HashMap<>();
        map.put("name", entry.getName());
        map.put("aliases", entry.getAliases());
        map.put("source", entry.getSource());
        return map;
    }

    /** spec §8.2 只读查询, 无副作用 */
    @Override
    public AccessMode getAccessMode() {
        return AccessMode.READ;
    }
}
