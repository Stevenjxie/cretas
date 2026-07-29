package com.cretas.aims.ai.tool.impl.scale;

import com.cretas.aims.ai.tool.AbstractBusinessTool;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * 秤校准指导工具
 *
 * 根据校准类型提供校准步骤和注意事项。
 *
 * Intent Code: SCALE_CALIBRATE
 *
 * @author Cretas Team
 * @version 1.0.0
 * @since 2026-03-07
 */
@Slf4j
@Component
public class ScaleCalibrateTool extends AbstractBusinessTool {

    @Override
    public String getToolName() {
        return "scale_calibrate";
    }

    @Override
    public String getDescription() {
        return "秤校准指导。根据校准类型（零点校准、量程校准、角差校准）提供详细校准步骤和注意事项。" +
                "适用场景：需要校准电子秤时获取操作指导。";
    }

    @Override
    public Map<String, Object> getParametersSchema() {
        Map<String, Object> schema = new HashMap<>();
        schema.put("type", "object");

        Map<String, Object> properties = new HashMap<>();

        Map<String, Object> calibrationType = new HashMap<>();
        calibrationType.put("type", "string");
        calibrationType.put("description", "校准类型描述，例如「零点校准」「量程校准」「角差校准」");
        properties.put("calibrationType", calibrationType);

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
        log.info("执行秤校准指导 - 工厂ID: {}, 参数: {}", factoryId, params);

        String input = getString(params, "calibrationType", "").toLowerCase();

        List<Map<String, String>> calibrationSteps = new ArrayList<>();
        String calibrationType;

        if (input.contains("零点") || input.contains("归零")) {
            calibrationType = "零点校准";
            calibrationSteps.add(Map.of("step", "1", "action", "清空秤台", "detail", "确保秤台上没有任何物品"));
            calibrationSteps.add(Map.of("step", "2", "action", "等待稳定", "detail", "等待显示值稳定(通常2-5秒)"));
            calibrationSteps.add(Map.of("step", "3", "action", "执行零点校准", "detail", "按秤上的「置零」或「ZERO」按钮"));
            calibrationSteps.add(Map.of("step", "4", "action", "验证结果", "detail", "确认显示值为0.00"));
        } else if (input.contains("量程") || input.contains("满量程") || input.contains("砝码")) {
            calibrationType = "量程校准";
            calibrationSteps.add(Map.of("step", "1", "action", "准备标准砝码", "detail", "准备与秤量程相符的标准砝码(建议使用满量程的2/3-满量程的砝码)"));
            calibrationSteps.add(Map.of("step", "2", "action", "进入校准模式", "detail", "根据秤型号进入校准模式(通常是长按特定按键组合)"));
            calibrationSteps.add(Map.of("step", "3", "action", "执行零点校准", "detail", "清空秤台，执行零点校准"));
            calibrationSteps.add(Map.of("step", "4", "action", "放置砝码", "detail", "将标准砝码放置在秤台中央"));
            calibrationSteps.add(Map.of("step", "5", "action", "输入砝码重量", "detail", "输入砝码的精确重量值"));
            calibrationSteps.add(Map.of("step", "6", "action", "确认校准", "detail", "确认校准并保存设置"));
            calibrationSteps.add(Map.of("step", "7", "action", "验证结果", "detail", "使用不同重量的砝码验证校准精度"));
        } else if (input.contains("角差") || input.contains("四角")) {
            calibrationType = "角差校准";
            calibrationSteps.add(Map.of("step", "1", "action", "准备测试砝码", "detail", "准备约1/3量程的标准砝码"));
            calibrationSteps.add(Map.of("step", "2", "action", "测试四角", "detail", "将砝码分别放置在秤台四个角落，记录读数"));
            calibrationSteps.add(Map.of("step", "3", "action", "分析误差", "detail", "计算各角与中心读数的差值"));
            calibrationSteps.add(Map.of("step", "4", "action", "调整传感器", "detail", "根据误差调整对应角的传感器灵敏度"));
            calibrationSteps.add(Map.of("step", "5", "action", "重新测试", "detail", "重复测试直到四角误差在允许范围内"));
        } else {
            calibrationType = "常规校准";
            calibrationSteps.add(Map.of("step", "1", "action", "预热", "detail", "开机预热15-30分钟"));
            calibrationSteps.add(Map.of("step", "2", "action", "零点校准", "detail", "清空秤台，执行零点校准"));
            calibrationSteps.add(Map.of("step", "3", "action", "量程校准", "detail", "使用标准砝码进行量程校准"));
            calibrationSteps.add(Map.of("step", "4", "action", "线性校准(可选)", "detail", "使用多个重量点进行线性校准"));
            calibrationSteps.add(Map.of("step", "5", "action", "验证", "detail", "使用已知重量的物品验证校准结果"));
        }

        List<String> cautions = List.of(
                "校准应在稳定的环境条件下进行(温度、湿度稳定)",
                "确保秤台水平放置",
                "使用经过检定的标准砝码",
                "校准后应保存设置并记录校准日期",
                "建议定期(每月或每季度)进行校准检查"
        );

        return buildSimpleResult(
                "校准指导: " + calibrationType,
                Map.of(
                        "calibrationType", calibrationType,
                        "calibrationSteps", calibrationSteps,
                        "cautions", cautions
                )
        );
    }

    @Override
    protected String getParameterQuestion(String paramName) {
        if ("calibrationType".equals(paramName)) {
            return "请问需要哪种校准指导？零点校准、量程校准还是角差校准？";
        }
        return super.getParameterQuestion(paramName);
    }

    @Override
    protected String getParameterDisplayName(String paramName) {
        if ("calibrationType".equals(paramName)) {
            return "校准类型";
        }
        return super.getParameterDisplayName(paramName);
    }

    /** spec §8.2 只读查询, 无副作用 */
    @Override
    public AccessMode getAccessMode() {
        return AccessMode.READ;
    }
}
