package com.cretas.aims.ai.tool.impl.dictionary;

import com.cretas.aims.ai.dto.ToolCall;
import com.cretas.aims.ai.tool.AbstractTool;
import com.cretas.aims.entity.smartbi.SmartBiDictionary;
import com.cretas.aims.repository.smartbi.SmartBiDictionaryRepository;
import com.cretas.aims.service.smartbi.*;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * 批量导入字典条目工具
 *
 * 支持从数据列表中批量导入新词条到字典。
 * 常用于 Excel 上传后自动学习新字段。
 */
@Slf4j
@Component
public class DictionaryBatchImportTool extends AbstractTool {

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
        return "dictionary_batch_import";
    }

    @Override
    public String getDescription() {
        return "批量导入词条到字典。当上传 Excel 后发现新的部门、区域等字段时调用。" +
               "会自动去重，只导入不存在的新词条。";
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

        // values: 要导入的值列表
        Map<String, Object> values = new HashMap<>();
        values.put("type", "array");
        values.put("description", "要导入的值列表，如：['研发部', '产品部', '运维部']");
        Map<String, Object> valueItem = new HashMap<>();
        valueItem.put("type", "string");
        values.put("items", valueItem);
        properties.put("values", values);

        schema.put("properties", properties);
        schema.put("required", Arrays.asList("dictType", "values"));

        return schema;
    }

    @Override
    public String execute(ToolCall toolCall, Map<String, Object> context) throws Exception {
        Map<String, Object> args = parseArguments(toolCall);

        String dictType = getRequiredParam(args, "dictType");
        @SuppressWarnings("unchecked")
        List<String> values = (List<String>) args.get("values");
        String factoryId = context.get("factoryId") != null ?
                context.get("factoryId").toString() : null;

        if (values == null || values.isEmpty()) {
            return buildErrorResult("values 不能为空");
        }

        int imported = 0;
        int skipped = 0;
        List<String> newEntries = new ArrayList<>();

        for (String value : values) {
            if (value == null || value.trim().isEmpty()) {
                continue;
            }

            String name = value.trim();

            // 检查是否已存在
            boolean exists = dictionaryRepository.existsByDictTypeAndNameAndFactoryId(
                    dictType, name, factoryId);

            if (exists) {
                skipped++;
                continue;
            }

            // 创建新条目
            SmartBiDictionary entry = SmartBiDictionary.builder()
                    .dictType(dictType)
                    .name(name)
                    .factoryId(factoryId)
                    .source("AI")
                    .isActive(true)
                    .priority(100)
                    .build();

            dictionaryRepository.save(entry);
            imported++;
            newEntries.add(name);
        }

        log.info("✅ 批量导入字典: type={}, imported={}, skipped={}",
                dictType, imported, skipped);

        // 如果有新条目导入，触发识别器 reload
        if (imported > 0) {
            reloadRecognizer(dictType);
        }

        Map<String, Object> result = new HashMap<>();
        result.put("dictType", dictType);
        result.put("imported", imported);
        result.put("skipped", skipped);
        result.put("newEntries", newEntries);
        result.put("message", String.format(
                "已导入 %d 个新词条到 %s 字典，跳过 %d 个已存在的条目。",
                imported, dictType, skipped));

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
                    log.info("Region recognizer reloaded after batch import");
                    break;
                case "department":
                    departmentRecognizer.reload();
                    log.info("Department recognizer reloaded after batch import");
                    break;
                case "metric":
                    metricRecognizer.reload();
                    log.info("Metric recognizer reloaded after batch import");
                    break;
                case "time":
                    timeRecognizer.reload();
                    log.info("Time recognizer reloaded after batch import");
                    break;
                case "dimension":
                    dimensionRecognizer.reload();
                    log.info("Dimension recognizer reloaded after batch import");
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
