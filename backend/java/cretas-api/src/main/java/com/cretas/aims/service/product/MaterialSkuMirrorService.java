package com.cretas.aims.service.product;

import com.cretas.aims.entity.ProductType;
import com.cretas.aims.entity.RawMaterialType;
import com.cretas.aims.entity.enums.ProductCategory;
import com.cretas.aims.repository.ProductTypeRepository;
import com.cretas.aims.repository.RawMaterialTypeRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.util.Optional;

/**
 * 物料字典 → 可售 SKU 的自动镜像。
 *
 * <h2>2026-08-12 Steve 拍板</h2>
 * 六膳门张权:「老问题 销售订单 选择不了原料」「有啥不能卖的 给钱 我都能卖」。
 * Steve:「以后录入原料字典就是录入原料 SKU」——<b>不要「发布」这个动作</b>。
 *
 * <p>所以物料字典是唯一录入口, 镜像 SKU 是它的副产品, 用户全程看不见。
 *
 * <h2>为什么必须建镜像而不是直接卖物料</h2>
 * {@code sales_order_items.product_type_id} 是 NOT NULL, 且整张表<b>没有</b>指向物料的列
 * (实测该表只有 sales_order_id 一条外键)。要在销售订单里选到物料, 它就得在商品目录里有一份。
 *
 * <p>镜像一律是 {@link ProductCategory#RAW_MATERIAL} —— 这个类别被生产侧的
 * {@code findVisibleByFactoryIdAndIsActiveTrue} 排除, 所以镜像再多,
 * <b>生产计划/批次/工时/毛利红线那些下拉一条都不会多</b>, 只出现在销售侧的
 * {@code /product-types/sellable} 里。
 *
 * <h2>⛔ 监听必须是 AFTER_COMMIT + REQUIRES_NEW</h2>
 * 物料的 {@code createMaterialType}/{@code updateMaterialType} 都是 {@code @Transactional}。
 * 如果镜像在<b>同一个事务里</b>做, 镜像失败(最常见是产品名撞唯一约束)会把事务标成
 * rollback-only —— <b>用户的物料就存不进去了</b>。为了一个附属品把主操作搞挂, 是本末倒置。
 *
 * <p>AFTER_COMMIT 保证物料已经落库; REQUIRES_NEW 保证镜像自己开事务、自己失败自己回滚。
 * 这两个缺一不可, 已用反射钉进 {@code MaterialSkuMirrorServiceTest}。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MaterialSkuMirrorService {

    /** 镜像 SKU 的编号前缀 —— 一眼看出它来自物料字典, 也是幂等判据。 */
    public static final String MIRROR_CODE_PREFIX = "M-";

    /** product_types.code 列长 50。 */
    private static final int CODE_MAX_LENGTH = 50;

    private final RawMaterialTypeRepository rawMaterialTypeRepository;
    private final ProductTypeRepository productTypeRepository;

    /** 物料落库后发的事件。{@code userId} 可为 null(批量状态变更等场景取不到)。 */
    public record MaterialSaved(String factoryId, String materialTypeId, Long userId) {}

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void onMaterialSaved(MaterialSaved event) {
        try {
            mirror(event.factoryId(), event.materialTypeId(), event.userId());
        } catch (RuntimeException e) {
            // 镜像是附属品, 出问题只记不抛 —— 主操作(物料建档)已经提交, 不该被牵连。
            // 最常见的是产品名撞唯一约束(物料字典允许同名, 商品目录不允许)。
            log.warn("物料镜像为可售 SKU 失败(不影响物料本身): factoryId={}, materialTypeId={}",
                    event.factoryId(), event.materialTypeId(), e);
        }
    }

    /**
     * upsert 一条镜像。物料字典是权威 —— 名称/单位/单价/类别/启用状态都以它为准。
     *
     * @return 是否真的写了(false = 物料不存在或不属于本厂)
     */
    public boolean mirror(String factoryId, String materialTypeId, Long userId) {
        Optional<RawMaterialType> found =
                rawMaterialTypeRepository.findByIdAndFactoryId(materialTypeId, factoryId);
        if (found.isEmpty()) {
            log.warn("镜像跳过: 物料不存在或不属于本工厂, factoryId={}, id={}", factoryId, materialTypeId);
            return false;
        }
        RawMaterialType material = found.get();
        String code = mirrorCode(material.getCode());

        ProductType sku = productTypeRepository.findByFactoryIdAndCode(factoryId, code)
                .orElseGet(ProductType::new);
        boolean isNew = sku.getId() == null;
        if (isNew) {
            sku.setId("PTM_" + material.getId());
            sku.setFactoryId(factoryId);
            sku.setCode(code);
            // created_by 是 NOT NULL。物料自己的建档人优先, 取不到就用触发方。
            sku.setCreatedBy(material.getCreatedBy() != null ? material.getCreatedBy() : userId);
        }
        sku.setName(material.getName());
        sku.setProductCategory(ProductCategory.RAW_MATERIAL);
        sku.setCategory(material.getCategory());
        sku.setUnit(material.getUnit());
        sku.setUnitPrice(material.getUnitPrice());
        // 物料停用 → 镜像跟着停用, 销售下拉里立刻消失。
        sku.setIsActive(Boolean.TRUE.equals(material.getIsActive()));

        productTypeRepository.save(sku);
        log.info("物料镜像{}: factoryId={}, code={}, name={}",
                isNew ? "已创建" : "已更新", factoryId, code, material.getName());
        return true;
    }

    /** {@code M-<物料编号>}, 超长截断。 */
    public static String mirrorCode(String materialCode) {
        String raw = MIRROR_CODE_PREFIX + (materialCode == null ? "" : materialCode.trim());
        return raw.length() <= CODE_MAX_LENGTH ? raw : raw.substring(0, CODE_MAX_LENGTH);
    }
}
