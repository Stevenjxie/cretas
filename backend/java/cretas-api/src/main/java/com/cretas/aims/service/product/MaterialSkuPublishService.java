package com.cretas.aims.service.product;

import com.cretas.aims.dto.producttype.ProductTypeDTO;
import com.cretas.aims.entity.RawMaterialType;
import com.cretas.aims.entity.enums.ProductCategory;
import com.cretas.aims.repository.ProductTypeRepository;
import com.cretas.aims.repository.RawMaterialTypeRepository;
import com.cretas.aims.service.ProductTypeService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

/**
 * 把物料字典里的原料/辅料/包材<b>发布成可售 SKU</b>。
 *
 * <h2>2026-08-12 Steve 拍板(六膳门张权真机反馈)</h2>
 * 「老问题 销售订单 选择不了原料」——「有啥不能卖的 给钱 我都能卖」。
 * 销售订单明细只能指向 {@code product_types}({@code sales_order_items.product_type_id}
 * NOT NULL 且没有任何指向物料的列), 所以要卖物料就得让它在商品目录里有一份。
 *
 * <p>发布出来的 SKU 一律是 {@link ProductCategory#RAW_MATERIAL} ——
 * 这个类别<b>被生产侧的 {@code findVisibleByFactoryIdAndIsActiveTrue} 排除</b>,
 * 所以哪怕把 231 条全发布了, 生产计划/批次/工时/毛利红线那些下拉<b>一条都不会多</b>,
 * 它们只出现在销售侧的 {@code /product-types/sellable} 里。这是「全转是安全的」的依据。
 *
 * <h2>⛔ 本类<b>不能</b>加 {@code @Transactional}</h2>
 * 批量发布要「一条失败不牵连其他」。如果本类开事务, 那么循环里
 * {@code createProductType} 抛出的任何异常都会把<b>整个</b>事务标成 rollback-only ——
 * catch 只吞掉异常, <b>阻止不了回滚</b>, 结果是「日志显示成功 5 条失败 1 条, 实际一条
 * 都没落库」。(2026-08-12 同仓另两处已实测踩过这个形状。)
 *
 * <p>不开事务时, 每次 {@code productTypeService.createProductType(...)} 走 Spring 代理
 * 各自开一个新事务, 失败的那条自己回滚, 已成功的留下。<b>所以必须通过接口注入(代理),
 * 不能把逻辑搬进 ProductTypeServiceImpl 里自调用</b> —— 自调用不经过代理, 事务注解失效。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MaterialSkuPublishService {

    /** 发布出来的商品编号前缀 —— 一眼能看出它来自物料字典, 也是幂等判据。 */
    public static final String PUBLISHED_CODE_PREFIX = "M-";

    /** product_types.code 列长 50。 */
    private static final int CODE_MAX_LENGTH = 50;

    private final RawMaterialTypeRepository rawMaterialTypeRepository;
    private final ProductTypeRepository productTypeRepository;
    private final ProductTypeService productTypeService;

    public record Failure(String materialCode, String materialName, String reason) {}

    public record Result(
            List<String> created,
            List<String> alreadyPublished,
            List<Failure> failed) {

        public int total() {
            return created.size() + alreadyPublished.size() + failed.size();
        }
    }

    /**
     * @param materialTypeIds 要发布的物料 id; 为空时不做任何事(不默认全量 —— 「什么都没传」
     *                        和「要全部」是两件事, 猜错的代价是凭空造出几百个 SKU)
     * @param userId          建档人。⚠️ {@code product_types.created_by} 是 NOT NULL,
     *                        而 {@code createProductType} <b>不会</b>自己填 ——
     *                        正常建档是控制器从 token 取了塞进 DTO 的。漏了这个参数,
     *                        这条路径会在 insert 时炸 NOT NULL 约束。
     */
    public Result publish(String factoryId, Collection<String> materialTypeIds, Long userId) {
        List<String> created = new ArrayList<>();
        List<String> alreadyPublished = new ArrayList<>();
        List<Failure> failed = new ArrayList<>();

        if (materialTypeIds == null || materialTypeIds.isEmpty()) {
            return new Result(created, alreadyPublished, failed);
        }

        // 工厂隔离: 逐条按 (id, factoryId) 取, 不信调用方传来的 id 属于本厂。
        for (String materialTypeId : materialTypeIds) {
            Optional<RawMaterialType> found =
                    rawMaterialTypeRepository.findByIdAndFactoryId(materialTypeId, factoryId);
            if (found.isEmpty()) {
                failed.add(new Failure(materialTypeId, null, "物料不存在或不属于本工厂"));
                continue;
            }
            RawMaterialType material = found.get();
            String code = publishedCode(material.getCode());

            if (productTypeRepository.existsByFactoryIdAndCode(factoryId, code)) {
                alreadyPublished.add(code);
                continue;
            }

            try {
                ProductTypeDTO dto = new ProductTypeDTO();
                dto.setCode(code);
                dto.setName(material.getName());
                // 生产侧据此排除它; 销售侧的 isSellable 放行它。
                dto.setProductCategory(ProductCategory.RAW_MATERIAL);
                // 业务类别原样带过来(原料/辅料/包材), 前端列表直接显示。
                dto.setCategory(material.getCategory());
                dto.setUnit(material.getUnit());
                dto.setUnitPrice(material.getUnitPrice());
                dto.setIsActive(true);
                dto.setCreatedBy(userId);

                productTypeService.createProductType(factoryId, dto);
                created.add(code);
            } catch (RuntimeException e) {
                // 最常见的是重名 409 —— 产品名在厂内唯一, 而物料字典允许同名
                // (F006 实测: 与现有商品 0 条重名, 但物料内部有 3 组同名)。
                // 这里只记不抛: 本方法无事务, 失败的那条自己回滚, 其余照常落库。
                log.warn("物料发布为可售 SKU 失败: factoryId={}, material={}, code={}",
                        factoryId, material.getCode(), code, e);
                failed.add(new Failure(material.getCode(), material.getName(), e.getMessage()));
            }
        }

        log.info("物料发布为可售 SKU 完成: factoryId={}, 新建={}, 已存在={}, 失败={}",
                factoryId, created.size(), alreadyPublished.size(), failed.size());
        return new Result(created, alreadyPublished, failed);
    }

    /** {@code M-<物料编号>}, 超长截断。截断后仍可能撞号, 那种情况会走 alreadyPublished 分支。 */
    public static String publishedCode(String materialCode) {
        String raw = PUBLISHED_CODE_PREFIX + (materialCode == null ? "" : materialCode.trim());
        return raw.length() <= CODE_MAX_LENGTH ? raw : raw.substring(0, CODE_MAX_LENGTH);
    }
}
