package com.cretas.aims.ai.tool.impl.quality;

import com.cretas.aims.ai.tool.AbstractBusinessTool;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * CCP关键控制点监控数据检测工具
 *
 * 查询CCP关键控制点监控数据状态。
 *
 * Intent Code: CCP_MONITOR_DATA_DETECTION
 *
 * @author Cretas Team
 * @version 1.0.0
 * @since 2026-03-07
 */
@Slf4j
@Component
public class CcpMonitorDetectionTool extends AbstractBusinessTool {

    @Override
    public String getToolName() {
        return "ccp_monitor_detection";
    }

    @Override
    public String getDescription() {
        return "CCP关键控制点监控。查询温度、金属检测、微生物检测等关键控制点的监控数据状态。" +
                "适用场景：查看CCP监控数据、检查关键控制点是否正常。";
    }

    @Override
    public Map<String, Object> getParametersSchema() {
        Map<String, Object> schema = new HashMap<>();
        schema.put("type", "object");
        schema.put("properties", new HashMap<>());
        schema.put("required", Collections.emptyList());
        return schema;
    }

    @Override
    protected List<String> getRequiredParameters() {
        return Collections.emptyList();
    }

    @Override
    protected Map<String, Object> doExecute(String factoryId, Map<String, Object> params, Map<String, Object> context) throws Exception {
        log.info("执行CCP监控数据查询 - 工厂ID: {}", factoryId);

        Map<String, Object> result = new HashMap<>();
        result.put("queryType", "ccp_monitor");
        result.put("factoryId", factoryId);

        return buildSimpleResult(
                "CCP关键控制点监控数据查询已就绪。当前监控点包括:\n" +
                        "- 温度控制点\n- 金属检测点\n- 微生物检测点\n" +
                        "请前往质量管理页面查看详细监控数据。",
                result
        );
    }

    /** spec §8.2 只读查询, 无副作用 */
    @Override
    public AccessMode getAccessMode() {
        return AccessMode.READ;
    }
}
