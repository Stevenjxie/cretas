package com.cretas.aims.exception;

import com.cretas.aims.dto.common.ApiResponse;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.MethodParameter;
import org.springframework.validation.BeanPropertyBindingResult;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 验证 GlobalExceptionHandler#handleValidationException / #formatFieldError 的用户可见文案
 * 不泄漏原始 Java 字段路径 (fool-proof-design 跨规则铁律 (a): "网络 response.message 必须是
 * 仓管员/财务能看懂的人话")。
 *
 * <p>Bug 来源: 采购入库 (CreateReceiveRecordRequest.items 嵌套数组) + 报损单校验失败时,
 * 之前的 {@code field + ": " + msg} 拼接把 {@code items[0].receivedQuantity} 这类原始 Java
 * 字段路径原样拼进用户可见 toast, 仓管员完全看不懂对应"第几行"或"哪个输入框"。
 *
 * <p>直接实例化 handler + 手工构造 {@link MethodArgumentNotValidException}, 不启动 Spring
 * context, ~1s 跑完 (镜像 {@link GlobalExceptionHandlerBusinessTest} 的写法)。
 *
 * @since 2026-07-03 (warehouse/wastage-reports headed-audit bugfix batch, Bug 3)
 */
class GlobalExceptionHandlerFieldErrorFormatTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    /** 占位方法, 仅用于给 {@link MethodParameter} 提供一个真实的 Method 反射对象。 */
    @SuppressWarnings("unused")
    private void dummyTarget(Object request) { /* no-op */ }

    private MethodArgumentNotValidException buildException(FieldError... fieldErrors) throws NoSuchMethodException {
        MethodParameter methodParameter = new MethodParameter(
                GlobalExceptionHandlerFieldErrorFormatTest.class.getDeclaredMethod("dummyTarget", Object.class), 0);
        BeanPropertyBindingResult bindingResult = new BeanPropertyBindingResult(new Object(), "request");
        for (FieldError fe : fieldErrors) {
            bindingResult.addError(fe);
        }
        return new MethodArgumentNotValidException(methodParameter, bindingResult);
    }

    @Test
    @DisplayName("嵌套数组字段 (items[0].receivedQuantity) → 转成\"第N行\"人话前缀, 不泄漏原始 Java 字段路径")
    void indexedArrayField_formatsAsRowNumber_notRawFieldPath() throws NoSuchMethodException {
        FieldError fieldError = new FieldError(
                "request", "items[0].receivedQuantity", null, false, null, null, "收货数量必须大于0");
        MethodArgumentNotValidException ex = buildException(fieldError);

        ApiResponse<?> body = handler.handleValidationException(ex);

        assertEquals(400, body.getCode());
        assertEquals("第1行：收货数量必须大于0", body.getMessage());
        assertFalse(body.getMessage().contains("items[0]"),
                "message 不应包含原始 Java 字段路径 items[0]");
        assertFalse(body.getMessage().contains("receivedQuantity"),
                "message 不应包含原始 Java camelCase 字段名");
    }

    @Test
    @DisplayName("第二行 (items[1].xxx) → \"第2行\" (index+1, 人类计数从1开始)")
    void indexedArrayField_secondRow_formatsAsRowTwo() throws NoSuchMethodException {
        FieldError fieldError = new FieldError(
                "request", "items[1].unit", null, false, null, null, "单位不能为空");
        MethodArgumentNotValidException ex = buildException(fieldError);

        ApiResponse<?> body = handler.handleValidationException(ex);

        assertEquals("第2行：单位不能为空", body.getMessage());
    }

    @Test
    @DisplayName("非数组顶层字段 + 自定义中文 message → 直接用 msg, 不拼接原始 Java 字段名做噪音")
    void topLevelField_withCustomChineseMessage_usesMessageAsIs() throws NoSuchMethodException {
        FieldError fieldError = new FieldError(
                "request", "supplierId", null, false, null, null, "供应商ID不能为空");
        MethodArgumentNotValidException ex = buildException(fieldError);

        ApiResponse<?> body = handler.handleValidationException(ex);

        assertEquals("供应商ID不能为空", body.getMessage());
        assertFalse(body.getMessage().contains("supplierId"),
                "message 不应包含原始 Java camelCase 字段名 (自定义中文 message 已经是人话)");
    }

    @Test
    @DisplayName("默认英文 Bean Validation message (must not be null) → 仍走既有字段级中文兜底")
    void defaultBeanValidationMessage_stillTranslatedWithFieldName() throws NoSuchMethodException {
        FieldError fieldError = new FieldError(
                "request", "materialTypeId", null, false, null, null, "must not be null");
        MethodArgumentNotValidException ex = buildException(fieldError);

        ApiResponse<?> body = handler.handleValidationException(ex);

        assertEquals("字段 'materialTypeId' 不能为空", body.getMessage());
    }

    @Test
    @DisplayName("多个字段错误 → 逗号拼接, 每个数组字段各自转\"第N行\"")
    void multipleFieldErrors_eachFormattedIndependently() throws NoSuchMethodException {
        FieldError e1 = new FieldError(
                "request", "items[0].receivedQuantity", null, false, null, null, "收货数量必须大于0");
        FieldError e2 = new FieldError(
                "request", "supplierId", null, false, null, null, "供应商ID不能为空");
        MethodArgumentNotValidException ex = buildException(e1, e2);

        ApiResponse<?> body = handler.handleValidationException(ex);

        assertTrue(body.getMessage().contains("第1行：收货数量必须大于0"));
        assertTrue(body.getMessage().contains("供应商ID不能为空"));
        assertFalse(body.getMessage().contains("items[0]"));
    }
}
