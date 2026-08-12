package com.cretas.aims.service.product;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.cretas.aims.entity.ProductType;
import com.cretas.aims.entity.RawMaterialType;
import com.cretas.aims.entity.enums.ProductCategory;
import com.cretas.aims.repository.ProductTypeRepository;
import com.cretas.aims.repository.RawMaterialTypeRepository;

import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.util.Optional;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * 物料字典 → 可售 SKU 的自动镜像。
 *
 * <p>2026-08-12 Steve:「以后录入原料字典就是录入原料 SKU」——不要「发布」这个动作。
 */
@DisplayName("物料字典自动镜像为可售 SKU")
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class MaterialSkuMirrorServiceTest {

    private static final String FACTORY = "LIUSHANMEN";

    @Mock private RawMaterialTypeRepository rawMaterialTypeRepository;
    @Mock private ProductTypeRepository productTypeRepository;

    @InjectMocks private MaterialSkuMirrorService service;

    private RawMaterialType material(String id, String code, String name, String category, boolean active) {
        RawMaterialType m = new RawMaterialType();
        m.setId(id);
        m.setFactoryId(FACTORY);
        m.setCode(code);
        m.setName(name);
        m.setCategory(category);
        m.setUnit("kg");
        m.setUnitPrice(new BigDecimal("12.50"));
        m.setIsActive(active);
        m.setCreatedBy(7L);
        when(rawMaterialTypeRepository.findByIdAndFactoryId(id, FACTORY)).thenReturn(Optional.of(m));
        return m;
    }

    /**
     * ⛔ 本文件最重要的一条。
     *
     * <p>物料的 createMaterialType/updateMaterialType 都是 {@code @Transactional}。
     * 镜像若在<b>同一个事务里</b>做, 镜像失败(最常见是产品名撞唯一约束)会把事务标成
     * rollback-only —— catch 只吞异常、<b>阻止不了回滚</b>, 于是<b>用户的物料存不进去了</b>。
     * 为一个附属品把主操作搞挂, 是本末倒置, 而且现场表现只是「保存没反应」, 不报错不留痕。
     *
     * <p>AFTER_COMMIT 保证物料已落库, REQUIRES_NEW 保证镜像自己开事务自己回滚。缺一不可。
     */
    @Test
    @DisplayName("⛔ 监听必须是 AFTER_COMMIT + REQUIRES_NEW —— 否则镜像失败会把用户的物料一起回滚")
    void listenerMustBeAfterCommitAndRequiresNew() throws NoSuchMethodException {
        Method listener = MaterialSkuMirrorService.class.getMethod(
                "onMaterialSaved", MaterialSkuMirrorService.MaterialSaved.class);

        TransactionalEventListener event = listener.getAnnotation(TransactionalEventListener.class);
        assertThat(event).as("必须是 @TransactionalEventListener").isNotNull();
        assertThat(event.phase())
                .as("必须 AFTER_COMMIT —— 早于提交做镜像会把主事务拖下水")
                .isEqualTo(TransactionPhase.AFTER_COMMIT);

        Transactional tx = listener.getAnnotation(Transactional.class);
        assertThat(tx).as("必须自己开事务").isNotNull();
        assertThat(tx.propagation())
                .as("必须 REQUIRES_NEW —— 否则镜像失败仍会污染调用方事务")
                .isEqualTo(Propagation.REQUIRES_NEW);
    }

    @Test
    @DisplayName("新建: 镜像是 RAW_MATERIAL 类别 + M- 前缀编号, 带上建档人")
    void createsMirror() {
        material("RMT_1", "BC001", "吸塑盒2014-3.5", "包材", true);
        when(productTypeRepository.findByFactoryIdAndCode(FACTORY, "M-BC001")).thenReturn(Optional.empty());

        assertThat(service.mirror(FACTORY, "RMT_1", 99L)).isTrue();

        ArgumentCaptor<ProductType> saved = ArgumentCaptor.forClass(ProductType.class);
        verify(productTypeRepository).save(saved.capture());
        assertThat(saved.getValue().getId()).isEqualTo("PTM_RMT_1");
        assertThat(saved.getValue().getCode()).isEqualTo("M-BC001");
        // 生产侧的 findVisible… 靠这个值把它排除在生产下拉外
        assertThat(saved.getValue().getProductCategory()).isEqualTo(ProductCategory.RAW_MATERIAL);
        assertThat(saved.getValue().getCategory()).isEqualTo("包材");
        assertThat(saved.getValue().getIsActive()).isTrue();
        // product_types.created_by 是 NOT NULL —— 物料自己的建档人优先
        assertThat(saved.getValue().getCreatedBy()).isEqualTo(7L);
    }

    @Test
    @DisplayName("改档: 名称/单位/单价跟着物料走 —— 物料字典是权威")
    void updatesExistingMirror() {
        material("RMT_1", "BC001", "吸塑盒 新名字", "包材", true);
        ProductType existing = new ProductType();
        existing.setId("PTM_RMT_1");
        existing.setFactoryId(FACTORY);
        existing.setCode("M-BC001");
        existing.setName("吸塑盒 旧名字");
        existing.setCreatedBy(3L);
        when(productTypeRepository.findByFactoryIdAndCode(FACTORY, "M-BC001")).thenReturn(Optional.of(existing));

        service.mirror(FACTORY, "RMT_1", 99L);

        ArgumentCaptor<ProductType> saved = ArgumentCaptor.forClass(ProductType.class);
        verify(productTypeRepository).save(saved.capture());
        assertThat(saved.getValue().getName()).isEqualTo("吸塑盒 新名字");
        // 已存在的不改建档人
        assertThat(saved.getValue().getCreatedBy()).isEqualTo(3L);
    }

    @Test
    @DisplayName("物料停用 → 镜像跟着停用, 销售下拉里立刻消失")
    void deactivationPropagates() {
        material("RMT_1", "BC001", "吸塑盒2014-3.5", "包材", false);
        when(productTypeRepository.findByFactoryIdAndCode(FACTORY, "M-BC001")).thenReturn(Optional.empty());

        service.mirror(FACTORY, "RMT_1", 99L);

        ArgumentCaptor<ProductType> saved = ArgumentCaptor.forClass(ProductType.class);
        verify(productTypeRepository).save(saved.capture());
        assertThat(saved.getValue().getIsActive()).isFalse();
    }

    @Test
    @DisplayName("工厂隔离: 不属于本厂的 id 什么都不写")
    void rejectsForeignMaterial() {
        when(rawMaterialTypeRepository.findByIdAndFactoryId("RMT_X", FACTORY)).thenReturn(Optional.empty());

        assertThat(service.mirror(FACTORY, "RMT_X", 99L)).isFalse();
        verify(productTypeRepository, never()).save(any());
    }

    /**
     * 镜像是附属品 —— 它出问题绝不能让主操作(用户的物料建档)看起来失败。
     * 事件入口必须把异常吞掉(它已经在自己的 REQUIRES_NEW 事务里, 吞掉是安全的)。
     */
    @Test
    @DisplayName("镜像抛异常时事件入口不外抛 —— 附属品不能拖垮主操作")
    void listenerSwallowsFailures() {
        material("RMT_1", "BC001", "吸塑盒2014-3.5", "包材", true);
        when(productTypeRepository.findByFactoryIdAndCode(FACTORY, "M-BC001")).thenReturn(Optional.empty());
        when(productTypeRepository.save(any())).thenThrow(new RuntimeException("产品名称已存在"));

        service.onMaterialSaved(new MaterialSkuMirrorService.MaterialSaved(FACTORY, "RMT_1", 99L));
        // 走到这里没抛就是通过
    }

    @Test
    @DisplayName("编号超长截断到 50")
    void truncatesLongCode() {
        assertThat(MaterialSkuMirrorService.mirrorCode("X".repeat(60))).hasSize(50);
        assertThat(MaterialSkuMirrorService.mirrorCode("BC001")).isEqualTo("M-BC001");
    }
}
