package com.cretas.aims.controller.hr;

import com.cretas.aims.exception.BusinessException;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Map;

/**
 * HR 请求体 (raw {@code Map<String,Object>}) 的带守卫解析助手。
 *
 * <p>HR 控制器 (请假/报销/加班) 用 raw Map 接收请求体, 之前直接
 * {@code Enum.valueOf((String) body.get(key))} / {@code LocalDate.parse(...)} /
 * {@code new BigDecimal(body.get(key).toString())} —— 缺字段时 {@code body.get(key)}
 * 返回 null → {@code valueOf(null)} / {@code .toString()} NPE → HTTP 500 (不友好且非
 * 字段级提示)。本助手把"缺必填字段 / 格式错误"统一转成 HTTP 400 + 明确字段提示
 * (防呆四位一体: 具体 message + hintTarget 指向字段)。
 */
final class HrBodyParse {

    private HrBodyParse() {}

    static String reqStr(Map<String, Object> body, String key, String label) {
        Object v = body == null ? null : body.get(key);
        if (v == null || v.toString().trim().isEmpty()) {
            throw new BusinessException(400, "请填写" + label).withHintTarget(key);
        }
        return v.toString().trim();
    }

    static <E extends Enum<E>> E reqEnum(Map<String, Object> body, String key, String label, Class<E> type) {
        String s = reqStr(body, key, label);
        try {
            return Enum.valueOf(type, s);
        } catch (IllegalArgumentException e) {
            throw new BusinessException(400, label + "取值无效: " + s).withHintTarget(key);
        }
    }

    static BigDecimal reqDecimal(Map<String, Object> body, String key, String label) {
        String s = reqStr(body, key, label);
        try {
            return new BigDecimal(s);
        } catch (NumberFormatException e) {
            throw new BusinessException(400, label + "必须是数字").withHintTarget(key);
        }
    }

    static LocalDate reqDate(Map<String, Object> body, String key, String label) {
        String s = reqStr(body, key, label);
        try {
            return LocalDate.parse(s);
        } catch (Exception e) {
            throw new BusinessException(400, label + "日期格式应为 yyyy-MM-dd").withHintTarget(key);
        }
    }

    static LocalDateTime reqDateTime(Map<String, Object> body, String key, String label) {
        String s = reqStr(body, key, label);
        try {
            return LocalDateTime.parse(s);
        } catch (Exception e) {
            throw new BusinessException(400, label + "时间格式应为 yyyy-MM-ddTHH:mm:ss").withHintTarget(key);
        }
    }
}
