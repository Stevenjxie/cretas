package com.cretas.aims.ai.tool.impl.scale;

import com.cretas.aims.ai.tool.AbstractBusinessTool;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * 秤故障排查工具
 *
 * 根据用户描述的故障现象提供排查步骤和建议。
 *
 * Intent Code: SCALE_TROUBLESHOOT
 *
 * @author Cretas Team
 * @version 1.0.0
 * @since 2026-03-07
 */
@Slf4j
@Component
public class ScaleTroubleshootTool extends AbstractBusinessTool {

    @Override
    public String getToolName() {
        return "scale_troubleshoot";
    }

    @Override
    public String getDescription() {
        return "秤故障排查。根据故障现象（无数据、乱码、不稳定、超时、精度问题、零点漂移等）提供排查步骤。" +
                "适用场景：电子秤出现问题时获取排查指导。";
    }

    @Override
    public Map<String, Object> getParametersSchema() {
        Map<String, Object> schema = new HashMap<>();
        schema.put("type", "object");

        Map<String, Object> properties = new HashMap<>();

        Map<String, Object> symptom = new HashMap<>();
        symptom.put("type", "string");
        symptom.put("description", "故障现象描述，例如「收不到数据」「显示乱码」「称重不稳定」「精度不准」「零点漂移」");
        properties.put("symptom", symptom);

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
        log.info("执行秤故障排查 - 工厂ID: {}, 参数: {}", factoryId, params);

        String symptom = getString(params, "symptom", "");
        String input = symptom.toLowerCase();

        List<Map<String, String>> troubleshootSteps = new ArrayList<>();
        String diagnosis;

        if (input.contains("无数据") || input.contains("没有数据") || input.contains("收不到")) {
            diagnosis = "数据通信异常";
            troubleshootSteps.add(Map.of("step", "1", "action", "检查物理连接", "detail", "确认串口线连接牢固，检查接口是否松动或损坏"));
            troubleshootSteps.add(Map.of("step", "2", "action", "验证串口设置", "detail", "确认波特率、数据位、停止位、校验位设置与秤一致"));
            troubleshootSteps.add(Map.of("step", "3", "action", "检查秤设置", "detail", "确认秤已开启串口输出功能，检查输出模式是否正确"));
            troubleshootSteps.add(Map.of("step", "4", "action", "使用串口调试工具", "detail", "使用 SSCOM 等工具直接读取串口数据，排除软件问题"));
        } else if (input.contains("乱码") || input.contains("数据错误") || input.contains("解析失败")) {
            diagnosis = "数据解析异常";
            troubleshootSteps.add(Map.of("step", "1", "action", "检查协议配置", "detail", "确认选择的协议与秤实际使用的协议一致"));
            troubleshootSteps.add(Map.of("step", "2", "action", "验证波特率", "detail", "波特率不匹配是乱码最常见的原因，尝试 9600/19200/38400"));
            troubleshootSteps.add(Map.of("step", "3", "action", "检查编码格式", "detail", "确认数据是 ASCII 还是二进制格式"));
            troubleshootSteps.add(Map.of("step", "4", "action", "使用协议识别", "detail", "使用 AI 协议识别功能自动检测正确的协议"));
        } else if (input.contains("不稳定") || input.contains("跳动") || input.contains("波动")) {
            diagnosis = "称重数据不稳定";
            troubleshootSteps.add(Map.of("step", "1", "action", "检查秤台", "detail", "确保秤台放置水平，无震动干扰"));
            troubleshootSteps.add(Map.of("step", "2", "action", "检查传感器", "detail", "检查传感器是否损坏或老化"));
            troubleshootSteps.add(Map.of("step", "3", "action", "调整滤波参数", "detail", "在秤仪表上调整数字滤波强度"));
            troubleshootSteps.add(Map.of("step", "4", "action", "软件过滤", "detail", "在应用层增加稳定性判断，只采集稳定标志为真的数据"));
        } else if (input.contains("超时") || input.contains("断连") || input.contains("连接中断")) {
            diagnosis = "连接超时";
            troubleshootSteps.add(Map.of("step", "1", "action", "检查供电", "detail", "确认秤和转换器供电稳定"));
            troubleshootSteps.add(Map.of("step", "2", "action", "检查线缆长度", "detail", "RS232线缆过长会导致信号衰减，建议不超过15米"));
            troubleshootSteps.add(Map.of("step", "3", "action", "检查转换器", "detail", "如使用 USB-串口转换器，检查驱动和硬件是否正常"));
            troubleshootSteps.add(Map.of("step", "4", "action", "增加重试机制", "detail", "在软件层面增加自动重连和错误恢复机制"));
        } else if (input.contains("精度") || input.contains("不准") || input.contains("误差")) {
            diagnosis = "称重精度问题";
            troubleshootSteps.add(Map.of("step", "1", "action", "检查校准状态", "detail", "确认秤是否已进行标准砝码校准"));
            troubleshootSteps.add(Map.of("step", "2", "action", "检查环境因素", "detail", "温度、湿度、气流等环境因素可能影响精度"));
            troubleshootSteps.add(Map.of("step", "3", "action", "检查秤台水平", "detail", "使用水平仪检查秤台是否水平放置"));
            troubleshootSteps.add(Map.of("step", "4", "action", "执行角差调整", "detail", "对于台秤，可能需要进行四角误差调整"));
        } else if (input.contains("零点") || input.contains("漂移") || input.contains("归零")) {
            diagnosis = "零点漂移问题";
            troubleshootSteps.add(Map.of("step", "1", "action", "执行零点校准", "detail", "清空秤台后执行零点校准操作"));
            troubleshootSteps.add(Map.of("step", "2", "action", "检查预热时间", "detail", "电子秤需要充分预热(通常15-30分钟)才能稳定"));
            troubleshootSteps.add(Map.of("step", "3", "action", "检查传感器", "detail", "传感器蠕变或老化可能导致零点漂移"));
            troubleshootSteps.add(Map.of("step", "4", "action", "启用自动零点跟踪", "detail", "部分秤支持自动零点跟踪功能，可在设置中开启"));
        } else {
            diagnosis = "通用故障排查";
            troubleshootSteps.add(Map.of("step", "1", "action", "检查硬件连接", "detail", "确认所有物理连接正常"));
            troubleshootSteps.add(Map.of("step", "2", "action", "验证配置参数", "detail", "检查协议、波特率等配置是否正确"));
            troubleshootSteps.add(Map.of("step", "3", "action", "查看错误日志", "detail", "检查系统日志获取更详细的错误信息"));
            troubleshootSteps.add(Map.of("step", "4", "action", "联系技术支持", "detail", "如问题持续，请联系设备厂商或技术支持"));
        }

        return buildSimpleResult(
                "故障诊断: " + diagnosis,
                Map.of(
                        "diagnosis", diagnosis,
                        "troubleshootSteps", troubleshootSteps,
                        "userSymptom", symptom
                )
        );
    }

    @Override
    protected String getParameterQuestion(String paramName) {
        if ("symptom".equals(paramName)) {
            return "请描述故障现象，例如「收不到数据」「显示乱码」「称重不稳定」";
        }
        return super.getParameterQuestion(paramName);
    }

    @Override
    protected String getParameterDisplayName(String paramName) {
        if ("symptom".equals(paramName)) {
            return "故障现象";
        }
        return super.getParameterDisplayName(paramName);
    }

    /** spec §8.2 只读查询, 无副作用 */
    @Override
    public AccessMode getAccessMode() {
        return AccessMode.READ;
    }
}
