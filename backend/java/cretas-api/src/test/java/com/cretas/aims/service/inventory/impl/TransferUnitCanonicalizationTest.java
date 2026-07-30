package com.cretas.aims.service.inventory.impl;

import com.cretas.aims.service.unit.UnitContractService;
import com.cretas.aims.service.unit.impl.UnitContractServiceImpl;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

/**
 * 线上事故回归 (LIUSHANMEN 2026-07-30): 「生产仓确实有足够库存, 报工却说可用 0」。
 *
 * <p>根因是<b>调拨与报工两侧单位口径不一致</b>:
 * <ul>
 *   <li>#1976 (2026-07-29) 已确立「等价码只对科学单位成立, 计数/包装单位按字面比较」,
 *       并据此收窄了报工侧 {@code canonicalNativeUnit}。</li>
 *   <li>调拨侧 {@code canonicalTransferUnit} 漏跟这条决定, 仍走 {@code normalize().code()};
 *       契约别名表 {@code alias("pcs","pcs","件","个","只")} 把用户选的「只」写成 {@code pcs}
 *       存进调拨明细, 再写进目标批次。</li>
 * </ul>
 *
 * <p>于是生产仓批次是 {@code pcs}、物料主档是「只」, 报工按字面比较跳过整批 501 只 →
 * 「需要 1只, 可用 0只, 缺少 1只」。库存页因本地化显示成「501 只」, 肉眼看不出异常。
 *
 * <p><b>本测试钉住: 调拨归一口径必须与报工侧一致 —— 只归一质量/体积, 计数/包装保持字面。</b>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("调拨单位归一口径必须与报工侧一致 (#1976 对齐, LIUSHANMEN 2026-07-30 回归)")
class TransferUnitCanonicalizationTest {

    private static final String FACTORY = "LIUSHANMEN";

    private String canonical(String unit) throws Throwable {
        TransferServiceImpl service = new TransferServiceImpl(
                null, null, null, null, null, null, null);
        // 用真实契约实现 (内置单位表 + 别名表就在里面), 只把 4 个仓储 repo mock 掉 ——
        // 这样测的是"真的契约怎么归一", 而不是我编的一份假别名表。
        UnitContractService contract = new UnitContractServiceImpl(
                org.mockito.Mockito.mock(com.cretas.aims.repository.config.UnitOfMeasurementRepository.class),
                org.mockito.Mockito.mock(com.cretas.aims.repository.unit.ProductUnitConversionRepository.class),
                org.mockito.Mockito.mock(com.cretas.aims.repository.MaterialPackagingHierarchyRepository.class),
                org.mockito.Mockito.mock(com.cretas.aims.repository.material.MaterialPackagingSpecRepository.class));
        ReflectionTestUtils.setField(service, "unitContractService", contract);

        Method m = TransferServiceImpl.class.getDeclaredMethod(
                "canonicalTransferUnit", String.class, String.class);
        m.setAccessible(true);
        try {
            return (String) m.invoke(service, FACTORY, unit);
        } catch (InvocationTargetException e) {
            throw e.getCause();
        }
    }

    @Test
    @DisplayName("🔴 计数单位保持字面 —— 「只」不得被改写成 pcs (本次事故的直接成因)")
    void countUnitStaysLiteral() throws Throwable {
        assertEquals("只", canonical("只"),
                "用户选「只」就该存「只」; 改写成 pcs 会让报工按字面比较时看不见这批货");
        assertNotEquals("pcs", canonical("只"));
    }

    @Test
    @DisplayName("计数别名之间不再互相冒充 —— 「只」与「件」是两个单位 (#1976 的核心主张)")
    void countAliasesDoNotCollapse() throws Throwable {
        assertNotEquals(canonical("只"), canonical("件"),
                "一只不等于一件; 给它们编共同等价码等于替工厂断定两个东西相同");
    }

    @Test
    @DisplayName("包装单位同样保持字面")
    void packageUnitStaysLiteral() throws Throwable {
        assertEquals("盒", canonical("盒"));
        assertEquals("箱", canonical("箱"));
        assertEquals("袋", canonical("袋"));
    }

    @Test
    @DisplayName("质量/体积仍照常归一 —— 这类单位有恒定换算, 归一有物理意义")
    void massAndVolumeStillCanonicalize() throws Throwable {
        assertEquals("kg", canonical("公斤"), "公斤与 kg 是同一个单位, 必须归一");
        assertEquals("kg", canonical("KG"), "大小写差异不该被当成两个单位");
        assertEquals("g", canonical("克"));
    }

    @Test
    @DisplayName("契约认不出的单位保持原样(小写去空格), 不因未登记就判非法")
    void unknownUnitFallsBackToLiteral() throws Throwable {
        assertEquals("扇", canonical(" 扇 "));
    }
}
