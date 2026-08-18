package com.cretas.aims.service.factory;

import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.repository.factory.FactoryMaterialRequisitionRepository;
import com.cretas.aims.service.factory.impl.FactoryMaterialRequisitionServiceImpl;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.withSettings;

/**
 * 闸 —— 领料单号按<b>已发到第几号</b>发，不按<b>还剩几张单</b>发。
 *
 * <h2>🔴 为什么有这道闸 (2026-08-18 prod 实测，当场把系统卡死)</h2>
 *
 * 建领料单连续 4 次全部 {@code HTTP 409「数据已存在，请勿重复提交」}，而我一次都没重复点。
 *
 * <p>根因：发号写的是 {@code count(*) + 1}，而实体上有
 * {@code @Where(clause = "deleted_at IS NULL")} —— 它<b>静默作用到那条 JPQL count</b>。
 * F006 当天 6 张单里有 1 张（{@code MR20260818-0005}）被软删，count 只数到 5 →
 * 发号 {@code MR20260818-0006} → 撞上<b>已经存在且没删</b>的 0006 → 唯一约束冲突。
 *
 * <p><b>那天剩下的时间里一张领料单都建不出来</b>，因为每次都发同一个号。
 * 而报错文案把系统自己的编号冲突说成「用户重复提交」，人会以为是自己点了两次。
 *
 * <p>⚠️ 本仓形态 A：那个 count 查的不是我想知道的东西 ——
 * 我想知道「发到第几号」，它答的是「还剩几张」。<b>软删一张就永久错位一个号。</b>
 */
class RequisitionNumberSurvivesSoftDeleteContractTest {

    private static final Path REPO_SRC = Path.of(
            "src/main/java/com/cretas/aims/repository/factory/FactoryMaterialRequisitionRepository.java");

    private FactoryMaterialRequisitionRepository repo;

    private FactoryMaterialRequisitionServiceImpl service(String maxNo) {
        FactoryMaterialRequisitionServiceImpl svc = mock(FactoryMaterialRequisitionServiceImpl.class,
                withSettings().defaultAnswer(CALLS_REAL_METHODS));
        repo = mock(FactoryMaterialRequisitionRepository.class);
        when(repo.findMaxRequisitionNo(anyString(), anyString())).thenReturn(maxNo);
        ReflectionTestUtils.setField(svc, "repository", repo);
        return svc;
    }

    private static int next(FactoryMaterialRequisitionServiceImpl svc, String maxNo) {
        Integer n = (Integer) ReflectionTestUtils.invokeMethod(svc, "nextSequenceAfterMax", maxNo);
        assertTrue(n != null, "拿不到序号");
        return n;
    }

    @Test
    @DisplayName("🔴 prod 那个场景: 已发到 0006(其中 0005 被软删) → 下一个必须是 0007, 不是 0006")
    void softDeletedRowDoesNotRewindTheSequence() {
        FactoryMaterialRequisitionServiceImpl svc = service("MR20260818-0006");
        assertEquals(7, next(svc, "MR20260818-0006"),
                "又发了已经用过的号 —— 唯一约束会炸, 用户看到「请勿重复提交」");
    }

    @Test
    @DisplayName("阳性对照: 当天还没有单时从 1 开始 (否则下面全是恒真)")
    void firstOfTheDayStartsAtOne() {
        FactoryMaterialRequisitionServiceImpl svc = service(null);
        assertEquals(1, next(svc, null));
        assertEquals(1, next(svc, "   "));
    }

    @Test
    @DisplayName("序号连续递增, 不受位数影响")
    void sequenceIncrementsMonotonically() {
        FactoryMaterialRequisitionServiceImpl svc = service("MR20260818-0001");
        assertEquals(2, next(svc, "MR20260818-0001"));
        assertEquals(100, next(svc, "MR20260818-0099"));
        assertEquals(10000, next(svc, "MR20260818-9999"));
    }

    @Test
    @DisplayName("🔴 单号解析不出来时抛错 —— ⛔ 不许回落成 1(那会再次撞号)")
    void unparseableMaxNumberFailsLoudlyInsteadOfRewinding() {
        FactoryMaterialRequisitionServiceImpl svc = service("MR20260818-ABCD");
        BusinessException ex = assertThrows(BusinessException.class,
                () -> next(svc, "MR20260818-ABCD"));
        assertTrue(String.valueOf(ex.getMessage()).contains("单号"), "报错没说清: " + ex.getMessage());
        assertThrows(BusinessException.class, () -> next(svc, "MR20260818"));
    }

    @Test
    @DisplayName("🔴 接线闸: generateRequisitionNo 走的是 max, ⛔ 不再走 count")
    void generateUsesMaxNotCount() {
        FactoryMaterialRequisitionServiceImpl svc = service("MR20260818-0006");
        String no = (String) ReflectionTestUtils.invokeMethod(svc, "generateRequisitionNo", "F006");
        assertTrue(no != null && no.endsWith("-0007"), "发出来的号不对: " + no);
        verify(repo, never()).countByFactoryIdAndRequisitionNoPrefix(anyString(), anyString());
    }

    @Test
    @DisplayName("🔴 那条 max 查询必须是 nativeQuery —— JPQL 会被 @Where 静默过滤掉软删除行")
    void theMaxQueryMustBeNativeOtherwiseSoftDeletedRowsAreInvisible() throws Exception {
        String src = Files.readString(REPO_SRC);
        int at = src.indexOf("findMaxRequisitionNo");
        assertTrue(at > 0, "仓库里没有 findMaxRequisitionNo");
        // 往前找它的 @Query 注解块
        int q = src.lastIndexOf("@Query", at);
        assertTrue(q > 0 && q < at, "找不到它的 @Query");
        String annotation = src.substring(q, at);
        assertTrue(annotation.contains("nativeQuery = true"),
                "这条 max 查询不是 native —— @Where(deleted_at IS NULL) 会把软删除行藏起来, "
                        + "于是又变回「按还活着几张发号」: " + annotation.trim());
        // 阳性对照: 确实截到了注解内容, 不是空串
        assertTrue(annotation.contains("MAX(requisition_no)"),
                "截到的不是那条注解, 上面的断言没有意义: " + annotation.trim());
    }
}
