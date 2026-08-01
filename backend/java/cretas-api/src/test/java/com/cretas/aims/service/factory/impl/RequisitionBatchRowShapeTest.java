package com.cretas.aims.service.factory.impl;

import com.cretas.aims.entity.factory.FactoryMaterialRequisitionItem;
import com.cretas.aims.exception.BusinessException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

/**
 * 领料确认里 {@code batchNumbers} 的形状必须<b>当场</b>校验。
 *
 * <h2>事故形态</h2>
 *
 * <p>原实现是一次<b>未检查的强转</b>:
 *
 * <pre>
 * if (batches instanceof List) {
 *     item.setBatchNumbers((List&lt;Map&lt;String, Object&gt;&gt;) batches);
 * }
 * </pre>
 *
 * <p>泛型擦除让 {@code ["LSM-OPEN-YADUI-001"]} 这种<b>字符串列表也能存进去</b> ——
 * {@code instanceof List} 拦不住元素类型。字段是 jsonb 且声明为
 * {@code List<Map<String,Object>>}, 于是直到<b>响应序列化那一刻</b>才抛
 * {@code HttpMessageNotWritableException: Class java.lang.String not subtype of Map},
 * 用户拿到通用 500「系统处理异常，请稍后重试(追踪码 XXX)」。
 *
 * <p>🔴 <b>最坏的一点: 事务已经提交</b> —— picked_qty 全部写入, 只有响应炸了。
 * 用户以为没成功会重试, 而实际状态已经变了。2026-08-01 prod 实测
 * (六膳门 MR20260801-0001): 四行 picked_qty 全部落库, batch_numbers 存成裸字符串,
 * 之后任何读取该单据的请求都会 500。
 *
 * <p>「拦在写入前」而不是「炸在序列化时」——这是本用例守的东西。
 */
@DisplayName("领料确认 batchNumbers 形状校验")
class RequisitionBatchRowShapeTest {

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> coerce(Object raw) throws Exception {
        Constructor<?> ctor = FactoryMaterialRequisitionServiceImpl.class
                .getDeclaredConstructors()[0];
        ctor.setAccessible(true);
        Object service = ctor.newInstance(new Object[ctor.getParameterCount()]);

        Method m = FactoryMaterialRequisitionServiceImpl.class.getDeclaredMethod(
                "coerceBatchRows", Object.class, FactoryMaterialRequisitionItem.class);
        m.setAccessible(true);

        FactoryMaterialRequisitionItem item = new FactoryMaterialRequisitionItem();
        item.setMaterialName("YL-DL-冷冻鸭腿");
        try {
            return (List<Map<String, Object>>) m.invoke(service, raw, item);
        } catch (InvocationTargetException e) {
            throw (Exception) e.getCause();
        }
    }

    @Test
    @DisplayName("🔴 字符串列表必须当场 400, 不能存进去等序列化炸")
    void rejectsBareStringList() {
        Throwable thrown = catchThrowable(() -> coerce(List.of("LSM-OPEN-YADUI-001")));

        assertThat(thrown)
                .as("存进去 = 事务提交后响应 500, 而数据已经改了")
                .isInstanceOf(BusinessException.class);
        BusinessException be = (BusinessException) thrown;
        assertThat(be.getMessage())
                .as("要点名是哪个物料、收到的是什么类型")
                .contains("YL-DL-冷冻鸭腿")
                .contains("String");
        assertThat(be.getActionHint())
                .as("必须告诉调用方正确形状 —— 只说「不对」没用")
                .contains("batchNo");
    }

    @Test
    @DisplayName("正确形状 [{batchNo, qty}] 放行")
    void acceptsProperRows() throws Exception {
        List<Map<String, Object>> rows = coerce(List.of(
                Map.of("batchNo", "LSM-OPEN-YADUI-001", "qty", 162.5)));

        assertThat(rows).hasSize(1);
        assertThat(rows.get(0)).containsEntry("batchNo", "LSM-OPEN-YADUI-001");
    }

    @Test
    @DisplayName("空数组 = 显式清空; 不传(null) = 保留原值交给 FEFO 自动分配")
    void distinguishesEmptyFromAbsent() throws Exception {
        assertThat(coerce(List.of())).isEmpty();
        // null 时返回 item 原有值(这里未设置 → null), 由 transferToFactory 走 FEFO。
        assertThat(coerce(null)).isNull();
    }

    @Test
    @DisplayName("整体不是数组时也要 400, 而不是被 instanceof 悄悄跳过")
    void rejectsNonList() {
        Throwable thrown = catchThrowable(() -> coerce("LSM-OPEN-YADUI-001"));
        assertThat(thrown).isInstanceOf(BusinessException.class);
        assertThat(((BusinessException) thrown).getMessage()).contains("必须是数组");
    }
}
