package com.cretas.aims.ai.tool.impl.workreport;

import com.cretas.aims.ai.tool.AbstractBusinessTool;
import com.cretas.aims.repository.BatchWorkSessionRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;

/**
 * Production Worker Checkin Tool
 *
 * Queries today's worker check-in count using BatchWorkSessionRepository.
 *
 * @author Cretas Team
 * @version 1.0.0
 * @since 2026-03-07
 */
@Slf4j
@Component
public class WorkReportCheckinTool extends AbstractBusinessTool {

    @Autowired
    private BatchWorkSessionRepository batchWorkSessionRepository;

    @Override
    public String getToolName() {
        return "workreport_checkin";
    }

    @Override
    public String getDescription() {
        return "查询生产工人签到情况，返回指定日期的签到人数。" +
                "适用场景：出勤统计、签到人数查询、劳动力到岗情况。";
    }

    @Override
    public Map<String, Object> getParametersSchema() {
        Map<String, Object> schema = new HashMap<>();
        schema.put("type", "object");

        Map<String, Object> properties = new HashMap<>();

        Map<String, Object> date = new HashMap<>();
        date.put("type", "string");
        date.put("description", "查询日期，格式: yyyy-MM-dd，默认今日");
        date.put("format", "date");
        properties.put("date", date);

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
        log.info("执行工人签到查询 - 工厂ID: {}, 参数: {}", factoryId, params);

        String dateStr = getString(params, "date");
        LocalDate queryDate = dateStr != null ? LocalDate.parse(dateStr) : LocalDate.now();

        LocalDateTime start = queryDate.atStartOfDay();
        LocalDateTime end = start.plusDays(1);
        long count = batchWorkSessionRepository.countByFactoryIdAndCheckInTimeBetween(factoryId, start, end);

        Map<String, Object> resultData = new HashMap<>();
        resultData.put("date", queryDate.format(DateTimeFormatter.ISO_DATE));
        resultData.put("checkinCount", count);

        Map<String, Object> result = new HashMap<>();
        result.put("reportType", "PRODUCTION_WORKER_CHECKIN");
        result.put("message", "今日签到人数: " + count);
        result.put("data", resultData);

        log.info("工人签到查询完成 - 工厂ID: {}, 日期: {}, 签到人数: {}", factoryId, queryDate, count);

        return result;
    }

    @Override
    protected String getParameterQuestion(String paramName) {
        Map<String, String> questions = Map.of(
            "date", "请问您要查看哪天的签到情况？（格式：yyyy-MM-dd，默认今日）"
        );
        return questions.get(paramName);
    }

    @Override
    protected String getParameterDisplayName(String paramName) {
        Map<String, String> displayNames = Map.of(
            "date", "查询日期"
        );
        return displayNames.getOrDefault(paramName, paramName);
    }

    /** spec §8.2 只读查询, 无副作用 */
    @Override
    public AccessMode getAccessMode() {
        return AccessMode.READ;
    }
}
