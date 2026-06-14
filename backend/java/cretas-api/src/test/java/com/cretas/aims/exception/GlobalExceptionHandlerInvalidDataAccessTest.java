package com.cretas.aims.exception;

import com.cretas.aims.dto.common.ApiResponse;
import org.hibernate.PropertyValueException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.InvalidDataAccessApiUsageException;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 2026-06-14: handleInvalidDataAccessApiUsageException 之前对枚举/格式转换失败(存储数据值越界)
 * 也返回泛化的 "请求参数不符合要求" 且只 log 顶层 wrapper —— 极具误导性, 真因(如 enum 列存了
 * 中文导致 "No enum constant ...")被掩盖, 排查报损 list 400 时踩过。
 * 现: 记最深层 cause; 枚举/格式转换失败给更准确的提示 + traceId; 必填缺失/泛化场景行为不变。
 */
@DisplayName("handleInvalidDataAccessApiUsageException: 枚举转换 vs 必填缺失 vs 泛化")
class GlobalExceptionHandlerInvalidDataAccessTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    @Test
    @DisplayName("枚举转换失败 (No enum constant) → 更准确的数据层提示 + traceId, 不再泛化")
    void enumConversionFailure_returnsDataLevelHint() {
        InvalidDataAccessApiUsageException ex = new InvalidDataAccessApiUsageException(
                "Could not convert value",
                new IllegalArgumentException("No enum constant com.cretas.aims...WastageReason.加工损耗"));

        ApiResponse<?> resp = handler.handleInvalidDataAccessApiUsageException(ex);

        assertEquals((Integer) 400, resp.getCode());
        assertEquals(Boolean.FALSE, resp.getSuccess());
        assertTrue(resp.getMessage().contains("数据字段值不在允许范围"),
                "枚举转换失败应给数据层提示, 实际: " + resp.getMessage());
        assertFalse(resp.getMessage().equals("请求参数不符合要求"),
                "不应再是泛化消息");
    }

    @Test
    @DisplayName("必填字段缺失 (PropertyValueException) → 行为不变, 含字段名")
    void notNullViolation_unchanged() {
        InvalidDataAccessApiUsageException ex = new InvalidDataAccessApiUsageException(
                "not-null property",
                new PropertyValueException("not-null property references a null value",
                        "FactorySettings", "createdAt"));

        ApiResponse<?> resp = handler.handleInvalidDataAccessApiUsageException(ex);

        assertEquals((Integer) 400, resp.getCode());
        assertTrue(resp.getMessage().contains("必填字段缺失"));
        assertTrue(resp.getMessage().contains("createdAt"),
                "应含缺失字段名, 实际: " + resp.getMessage());
    }

    @Test
    @DisplayName("非枚举/非必填的数据访问异常 → 仍泛化 (行为不变)")
    void genericDataAccess_keepsGenericMessage() {
        InvalidDataAccessApiUsageException ex = new InvalidDataAccessApiUsageException(
                "named parameter not bound",
                new IllegalStateException("named parameter [foo] not bound"));

        ApiResponse<?> resp = handler.handleInvalidDataAccessApiUsageException(ex);

        assertEquals((Integer) 400, resp.getCode());
        assertEquals("请求参数不符合要求", resp.getMessage());
    }
}
