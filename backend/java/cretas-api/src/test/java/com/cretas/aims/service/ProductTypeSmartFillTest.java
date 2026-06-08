package com.cretas.aims.service;

import com.cretas.aims.dto.producttype.ProductTypeDTO;
import com.cretas.aims.dto.producttype.ProductTypeSuggestionDTO;
import com.cretas.aims.entity.ProductType;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.repository.CustomerRepository;
import com.cretas.aims.repository.ProductTypeRepository;
import com.cretas.aims.service.impl.ProductTypeServiceImpl;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * T149/T150/T151/T152: SKU 智能防呆填充 + 编号去重健壮性.
 *
 * <p>覆盖:
 * <ul>
 *   <li>Part A — 自动生成编号撞唯一约束 → 重新生成重试; 手输重复编号 → 友好 409 不重试;</li>
 *   <li>Part B — suggestDefaults 名称匹配返回历史产品属性 / 无匹配→关键词大类+其余 null / 完全无把握→全 null;
 *       T150 扩展: 匹配时带温区/规格/标准克重/出成率; 历史产品字段为 null 时透传 null (禁假数据).</li>
 *   <li>Part C — T151/T152 LCS 公共子串信号: LCS ≥ 2 中文字即匹配 (T152 简化去占比限制).</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ProductType — T149 智能填充 + 编号去重健壮性")
class ProductTypeSmartFillTest {

    @Mock
    private ProductTypeRepository productTypeRepository;
    @Mock
    private CustomerRepository customerRepository;
    @Mock
    private ObjectMapper objectMapper;

    @InjectMocks
    private ProductTypeServiceImpl service;

    // ==================== Part A: 编号去重健壮性 ====================

    @Test
    @DisplayName("A1: 自动生成编号撞唯一约束 → 重新生成下一序号重试成功")
    void create_generatedCodeCollision_retriesAndSucceeds() {
        ProductTypeDTO dto = baseDto();
        dto.setCode(null); // 自动生成
        dto.setProductCategory("FINISHED_PRODUCT");

        // 第一次 count=12 → CP...0013, 撞约束; 第二次 count=13 → CP...0014, 成功
        when(productTypeRepository.countByFactoryId("F001")).thenReturn(12L, 13L);
        when(productTypeRepository.save(any(ProductType.class)))
                .thenThrow(new DataIntegrityViolationException("duplicate code"))
                .thenAnswer(inv -> inv.getArgument(0));

        ProductTypeDTO result = service.createProductType("F001", dto);

        assertThat(result).isNotNull();
        assertThat(result.getCode()).isEqualTo("CPF0010014"); // 重试后的新序号
        verify(productTypeRepository, times(2)).save(any(ProductType.class));
    }

    @Test
    @DisplayName("A2: 自动生成编号一直撞约束 → 重试上限后抛 409 (不裸 500)")
    void create_generatedCodeAlwaysCollides_throws409AfterRetries() {
        ProductTypeDTO dto = baseDto();
        dto.setCode(null);
        dto.setProductCategory("FINISHED_PRODUCT");

        when(productTypeRepository.countByFactoryId("F001")).thenReturn(0L);
        when(productTypeRepository.save(any(ProductType.class)))
                .thenThrow(new DataIntegrityViolationException("duplicate code"));

        assertThatThrownBy(() -> service.createProductType("F001", dto))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).getCode()).isEqualTo(409));
    }

    @Test
    @DisplayName("A3: 手输重复编号 → 提前检查 → 友好 409, 不调用 save (不重试)")
    void create_manualDuplicateCode_friendly409NoSave() {
        ProductTypeDTO dto = baseDto();
        dto.setCode("CP-MANUAL-001"); // 用户手输

        when(productTypeRepository.existsByFactoryIdAndCode("F001", "CP-MANUAL-001")).thenReturn(true);

        assertThatThrownBy(() -> service.createProductType("F001", dto))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> {
                    assertThat(((BusinessException) e).getCode()).isEqualTo(409);
                    assertThat(e.getMessage()).contains("产品编号已存在");
                });
        verify(productTypeRepository, times(0)).save(any(ProductType.class));
    }

    @Test
    @DisplayName("A4: 手输编号 pre-check 通过但 insert 撞并发约束 → 翻译为友好 409 (不重试)")
    void create_manualCode_raceOnInsert_translatesTo409() {
        ProductTypeDTO dto = baseDto();
        dto.setCode("CP-MANUAL-002");

        when(productTypeRepository.existsByFactoryIdAndCode("F001", "CP-MANUAL-002")).thenReturn(false);
        when(productTypeRepository.save(any(ProductType.class)))
                .thenThrow(new DataIntegrityViolationException("duplicate code"));

        assertThatThrownBy(() -> service.createProductType("F001", dto))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).getCode()).isEqualTo(409));
        // 手输不重试: 只 save 一次
        verify(productTypeRepository, times(1)).save(any(ProductType.class));
    }

    // ==================== Part B: suggestDefaults 历史记忆建议 ====================

    @Test
    @DisplayName("B1: 名称相似匹配历史产品 → 返回其 大类/单位/一级单位/装箱系数 + T150扩展(温区/规格/标准克重/出成率) + matchedFrom")
    void suggest_nameMatch_returnsMatchedProductAttrs() {
        ProductType existing = new ProductType();
        existing.setName("叮咚好食光卤猪蹄 200g");
        existing.setBaseProductName("好食光卤猪蹄");
        existing.setProductCategory("FINISHED_PRODUCT");
        existing.setUnit("盒");
        existing.setLevel1Unit("筐");
        existing.setBoxConversionCoefficient(new BigDecimal("20"));
        // T150: 新增扩展字段
        existing.setTemperatureZone("冷藏");
        existing.setSpecification("200g/盒 20盒/筐");
        existing.setGramsPerUnit(new BigDecimal("200.00"));
        existing.setWipToFgYield(new BigDecimal("0.5500"));

        when(productTypeRepository.findByFactoryId("F001")).thenReturn(List.of(existing));

        ProductTypeSuggestionDTO s = service.suggestDefaults("F001", "好食光卤猪蹄 500g", null);

        assertThat(s.getProductCategory()).isEqualTo("FINISHED_PRODUCT");
        assertThat(s.getUnit()).isEqualTo("盒");
        assertThat(s.getLevel1Unit()).isEqualTo("筐");
        assertThat(s.getBoxConversionCoefficient()).isEqualByComparingTo("20");
        assertThat(s.getMatchedFrom()).isEqualTo("叮咚好食光卤猪蹄 200g");
        // T150: 扩展字段断言
        assertThat(s.getTemperatureZone()).isEqualTo("冷藏");
        assertThat(s.getSpecification()).isEqualTo("200g/盒 20盒/筐");
        assertThat(s.getGramsPerUnit()).isEqualByComparingTo("200.00");
        assertThat(s.getWipToFgYield()).isEqualByComparingTo("0.5500");
        // T153: 基础名称从匹配产品带入
        assertThat(s.getBaseProductName()).isEqualTo("好食光卤猪蹄");
    }

    @Test
    @DisplayName("B6 (T150): 匹配历史产品但扩展字段为 null → 透传 null, 不臆造 (禁假数据)")
    void suggest_nameMatch_extendedFieldsNullWhenNotSetOnMatchedProduct() {
        ProductType existing = new ProductType();
        existing.setName("叮咚椒麻掌中宝 120g");
        existing.setBaseProductName("掌中宝");
        existing.setProductCategory("FINISHED_PRODUCT");
        existing.setUnit("盒");
        existing.setLevel1Unit("框");
        existing.setBoxConversionCoefficient(new BigDecimal("30"));
        // T150: 扩展字段全部 null (历史产品未配置)
        existing.setTemperatureZone(null);
        existing.setSpecification(null);
        existing.setGramsPerUnit(null);
        existing.setWipToFgYield(null);

        when(productTypeRepository.findByFactoryId("F001")).thenReturn(List.of(existing));

        ProductTypeSuggestionDTO s = service.suggestDefaults("F001", "椒麻掌中宝 120g", null);

        assertThat(s.getMatchedFrom()).isEqualTo("叮咚椒麻掌中宝 120g"); // 确实匹配到
        // 扩展字段: 历史产品未配 → 透传 null (禁假数据)
        assertThat(s.getTemperatureZone()).isNull();
        assertThat(s.getSpecification()).isNull();
        assertThat(s.getGramsPerUnit()).isNull();
        assertThat(s.getWipToFgYield()).isNull();
        // T153: 该产品 baseProductName=掌中宝 → 带入
        assertThat(s.getBaseProductName()).isEqualTo("掌中宝");
    }

    @Test
    @DisplayName("B7 (T153): 匹配产品 baseProductName 为空 → suggest 透传 null (禁假数据, 不臆造基础名称)")
    void suggest_nameMatch_baseProductNameNullWhenNotSetOnMatchedProduct() {
        ProductType existing = new ProductType();
        existing.setName("叮咚好食光卤猪蹄 200g");
        existing.setBaseProductName(null);  // 历史产品没填基础名称
        existing.setProductCategory("FINISHED_PRODUCT");
        existing.setUnit("盒");

        when(productTypeRepository.findByFactoryId("F001")).thenReturn(List.of(existing));

        ProductTypeSuggestionDTO s = service.suggestDefaults("F001", "好食光卤猪蹄 500g", null);

        assertThat(s.getMatchedFrom()).isEqualTo("叮咚好食光卤猪蹄 200g"); // 确实匹配到
        assertThat(s.getBaseProductName()).isNull();                    // 透传 null, 不臆造
    }

    @Test
    @DisplayName("B2: 名称无历史匹配 → 仅按关键词推断大类, 单位等留 null (禁假数据)")
    void suggest_noNameMatch_keywordCategoryOnly() {
        // 库里有一个完全不相干的产品 → 不会匹配
        ProductType unrelated = new ProductType();
        unrelated.setName("吸塑包装盒");
        unrelated.setProductCategory("PACKAGING");
        when(productTypeRepository.findByFactoryId("F001")).thenReturn(List.of(unrelated));

        // "卤掌中宝" 关键词 → 成品, 但库里无相似产品名
        ProductTypeSuggestionDTO s = service.suggestDefaults("F001", "卤掌中宝", null);

        assertThat(s.getProductCategory()).isEqualTo("FINISHED_PRODUCT"); // 关键词推断
        assertThat(s.getUnit()).isNull();                                 // 无名称匹配 → null
        assertThat(s.getLevel1Unit()).isNull();
        assertThat(s.getBoxConversionCoefficient()).isNull();
        assertThat(s.getMatchedFrom()).isNull();                          // 非名称匹配 → null
    }

    @Test
    @DisplayName("B3: 名称无匹配且关键词无把握 → 全 null (不臆造)")
    void suggest_noMatchNoKeyword_allNull() {
        when(productTypeRepository.findByFactoryId("F001")).thenReturn(List.of());

        ProductTypeSuggestionDTO s = service.suggestDefaults("F001", "某种不知名的东西XYZ", null);

        assertThat(s.getProductCategory()).isNull();
        assertThat(s.getUnit()).isNull();
        assertThat(s.getLevel1Unit()).isNull();
        assertThat(s.getBoxConversionCoefficient()).isNull();
        assertThat(s.getMatchedFrom()).isNull();
    }

    @Test
    @DisplayName("B4: 空名称 → 全 null, 不查库")
    void suggest_emptyName_allNull() {
        ProductTypeSuggestionDTO s = service.suggestDefaults("F001", "  ", null);

        assertThat(s.getProductCategory()).isNull();
        assertThat(s.getMatchedFrom()).isNull();
        verify(productTypeRepository, times(0)).findByFactoryId(any());
    }

    @Test
    @DisplayName("B5: 关键词包辅材优先于成品 (吸塑包装盒 → PACKAGING 非误判成品)")
    void suggest_packagingKeyword_wins() {
        when(productTypeRepository.findByFactoryId("F001")).thenReturn(List.of());

        ProductTypeSuggestionDTO s = service.suggestDefaults("F001", "吸塑包装盒", null);

        assertThat(s.getProductCategory()).isEqualTo("PACKAGING");
        assertThat(s.getMatchedFrom()).isNull();
    }

    // ==================== Part C: T151 LCS / 公共子串信号 ====================

    @Test
    @DisplayName("C1 (T151): 带噪声前缀 '出纳士大夫地方掌中宝' → LCS「掌中宝」(3字) 匹配「叮咚椒麻掌中宝 120g」")
    void suggest_noisePrefixWithProductKeyword_matchesViaLcs() {
        ProductType existing = new ProductType();
        existing.setName("叮咚椒麻掌中宝 120g");
        existing.setBaseProductName("掌中宝");          // baseProductName 含 3 字 LCS
        existing.setProductCategory("FINISHED_PRODUCT");
        existing.setUnit("盒");
        existing.setLevel1Unit("框");
        existing.setBoxConversionCoefficient(new BigDecimal("30"));
        existing.setTemperatureZone("冷藏");
        existing.setSpecification("120g/盒");
        existing.setGramsPerUnit(new BigDecimal("120.00"));
        existing.setWipToFgYield(new BigDecimal("0.6000"));

        when(productTypeRepository.findByFactoryId("F001")).thenReturn(List.of(existing));

        // Steve 真实 case: 大量噪声前缀 + 产品关键词
        ProductTypeSuggestionDTO s = service.suggestDefaults("F001", "出纳士大夫地方掌中宝", null);

        assertThat(s.getMatchedFrom()).isEqualTo("叮咚椒麻掌中宝 120g");
        assertThat(s.getProductCategory()).isEqualTo("FINISHED_PRODUCT");
        assertThat(s.getUnit()).isEqualTo("盒");
    }

    @Test
    @DisplayName("C2 (T151): '出纳士大夫地方掌中宝' 只有库里有掌中宝产品, baseProductName=null → 靠 name LCS 也能匹配")
    void suggest_noisePrefixWithProductKeyword_matchesViaNameLcsWhenBaseNull() {
        ProductType existing = new ProductType();
        existing.setName("叮咚椒麻掌中宝 120g");
        existing.setBaseProductName(null);              // 没有 baseProductName
        existing.setProductCategory("FINISHED_PRODUCT");
        existing.setUnit("盒");
        existing.setLevel1Unit("框");
        existing.setBoxConversionCoefficient(new BigDecimal("30"));

        when(productTypeRepository.findByFactoryId("F001")).thenReturn(List.of(existing));

        ProductTypeSuggestionDTO s = service.suggestDefaults("F001", "出纳士大夫地方掌中宝", null);

        // name 中「掌中宝」(3字) 仍构成 LCS ≥ 3 → 匹配
        assertThat(s.getMatchedFrom()).isEqualTo("叮咚椒麻掌中宝 120g");
        assertThat(s.getProductCategory()).isEqualTo("FINISHED_PRODUCT");
    }

    @Test
    @DisplayName("C3 (T151): F006 产品「XX猪舌」→ LCS「猪舌」(2字, 占 baseProductName 100%) 匹配猪舌产品")
    void suggest_twoCharProductName_matchesViaLcs() {
        // F006 真实产品: 基础名只有 2 字「猪舌」
        ProductType pZhuShe = new ProductType();
        pZhuShe.setName("叮咚好食光卤猪舌 200g");
        pZhuShe.setBaseProductName("猪舌");
        pZhuShe.setProductCategory("FINISHED_PRODUCT");
        pZhuShe.setUnit("盒");
        pZhuShe.setTemperatureZone("冷藏");
        pZhuShe.setGramsPerUnit(new BigDecimal("200.00"));

        // 另一个无关产品, 不应被选中
        ProductType unrelated = new ProductType();
        unrelated.setName("吸塑包装盒");
        unrelated.setProductCategory("PACKAGING");

        when(productTypeRepository.findByFactoryId("F006")).thenReturn(List.of(pZhuShe, unrelated));

        ProductTypeSuggestionDTO s = service.suggestDefaults("F006", "张老三猪舌头", null);

        assertThat(s.getMatchedFrom()).isEqualTo("叮咚好食光卤猪舌 200g");
        assertThat(s.getProductCategory()).isEqualTo("FINISHED_PRODUCT");
    }

    @Test
    @DisplayName("C4 (T151): F006「牛腱」2字 baseProductName — 带前后缀输入仍匹配")
    void suggest_twoCharNiuJian_matchesWithPrefixSuffix() {
        ProductType pNiuJian = new ProductType();
        pNiuJian.setName("叮咚好食光卤牛腱 250g");
        pNiuJian.setBaseProductName("牛腱");
        pNiuJian.setProductCategory("FINISHED_PRODUCT");
        pNiuJian.setUnit("盒");
        pNiuJian.setGramsPerUnit(new BigDecimal("250.00"));

        when(productTypeRepository.findByFactoryId("F006")).thenReturn(List.of(pNiuJian));

        ProductTypeSuggestionDTO s = service.suggestDefaults("F006", "特制牛腱子肉", null);

        assertThat(s.getMatchedFrom()).isEqualTo("叮咚好食光卤牛腱 250g");
        assertThat(s.getProductCategory()).isEqualTo("FINISHED_PRODUCT");
    }

    @Test
    @DisplayName("C5 (T151): F006「猪蹄」含 LCS ≥ 3 (「猪蹄」仅 2 字但 baseProductName 短占比高) → 匹配")
    void suggest_zhuti_matchesViaLcs() {
        ProductType pZhuTi = new ProductType();
        pZhuTi.setName("叮咚好食光卤猪蹄 200g");
        pZhuTi.setBaseProductName("猪蹄");
        pZhuTi.setProductCategory("FINISHED_PRODUCT");
        pZhuTi.setUnit("盒");
        pZhuTi.setGramsPerUnit(new BigDecimal("200.00"));

        when(productTypeRepository.findByFactoryId("F006")).thenReturn(List.of(pZhuTi));

        ProductTypeSuggestionDTO s = service.suggestDefaults("F006", "六扇门猪蹄半成品", null);

        assertThat(s.getMatchedFrom()).isEqualTo("叮咚好食光卤猪蹄 200g");
        assertThat(s.getProductCategory()).isEqualTo("FINISHED_PRODUCT");
    }

    @Test
    @DisplayName("C6 (T151): 完全无关名称不触发 LCS 误匹配 (LCS 1字 或 2字但占比低)")
    void suggest_unrelatedName_noFalseMatch() {
        // 两个 F006 真实产品
        ProductType pZhuTi = new ProductType();
        pZhuTi.setName("叮咚好食光卤猪蹄 200g");
        pZhuTi.setBaseProductName("猪蹄");
        pZhuTi.setProductCategory("FINISHED_PRODUCT");

        ProductType pZhuShe = new ProductType();
        pZhuShe.setName("叮咚好食光卤猪舌 200g");
        pZhuShe.setBaseProductName("猪舌");
        pZhuShe.setProductCategory("FINISHED_PRODUCT");

        when(productTypeRepository.findByFactoryId("F006")).thenReturn(List.of(pZhuTi, pZhuShe));

        // 完全无关: 没有猪/蹄/舌/腱/掌中宝等字
        ProductTypeSuggestionDTO s = service.suggestDefaults("F006", "不锈钢螺丝XYZ规格", null);

        assertThat(s.getMatchedFrom()).isNull();   // 禁假数据: 无把握不返名称匹配
    }

    @Test
    @DisplayName("C7 (T152): 2字 LCS 「好食」→ T152 简化规则下 NOW 匹配 (Steve 批准的 trade-off: 偶然 2 字重叠可接受)")
    void suggest_incidental2CharLcs_nowMatchesUnderT152() {
        // T152 设计决策: LCS ≥ 2 中文字即匹配, 去掉 T151 的 ≥40% 占比限制.
        // 「好食堂特供餐具盒」与「叮咚好食光卤猪蹄 200g」共享「好食」(2字) → 现在匹配 (可接受的误匹配).
        // 匹配结果 non-clobbering + 可覆盖 + 显示"匹配自 X" 提示 — Steve 明确接受此 trade-off.
        ProductType p1 = new ProductType();
        p1.setName("叮咚好食光卤猪蹄 200g");
        p1.setBaseProductName("猪蹄");   // 2字
        p1.setProductCategory("FINISHED_PRODUCT");
        p1.setUnit("盒");

        when(productTypeRepository.findByFactoryId("F001")).thenReturn(List.of(p1));

        ProductTypeSuggestionDTO s = service.suggestDefaults("F001", "好食堂特供餐具盒", null);

        // T152 简化: 共享「好食」(2字 LCS) → lcsScore=0.8 → 超过阈值 → 匹配
        assertThat(s.getMatchedFrom()).isEqualTo("叮咚好食光卤猪蹄 200g");
    }

    // ==================== Part D: T152 真实 F006 名称 + 全无关名称 ====================

    @Test
    @DisplayName("D1 (T152): Steve 真实 case — '啊iOS的哈佛牛腱' 共享「牛腱」(2字) → 匹配「叮咚好食光纸片牛腱肉 80g」")
    void suggest_realF006_noisyInputNiuJian_matchesViaLcs2() {
        // F006 真实产品 (空 baseProductName, 长品牌名)
        ProductType pNiuJian = new ProductType();
        pNiuJian.setName("叮咚好食光纸片牛腱肉 80g");
        pNiuJian.setBaseProductName(null);          // F006 产品 baseProductName 为空 — 用 name LCS
        pNiuJian.setProductCategory("FINISHED_PRODUCT");
        pNiuJian.setUnit("盒");
        pNiuJian.setGramsPerUnit(new BigDecimal("80.00"));

        ProductType pZhuShe = new ProductType();
        pZhuShe.setName("叮咚好食光轻卤门腔（猪舌）120g");
        pZhuShe.setBaseProductName(null);
        pZhuShe.setProductCategory("FINISHED_PRODUCT");
        pZhuShe.setUnit("盒");

        when(productTypeRepository.findByFactoryId("F006")).thenReturn(List.of(pNiuJian, pZhuShe));

        // T151 会拒绝: 「牛腱」2字占「好食光纸片牛腱肉」10字 = 20% < 40%.
        // T152 接受: LCS ≥ 2 即匹配.
        ProductTypeSuggestionDTO s = service.suggestDefaults("F006", "啊iOS的哈佛牛腱", null);

        assertThat(s.getMatchedFrom()).isEqualTo("叮咚好食光纸片牛腱肉 80g");
        assertThat(s.getProductCategory()).isEqualTo("FINISHED_PRODUCT");
        assertThat(s.getUnit()).isEqualTo("盒");
        assertThat(s.getGramsPerUnit()).isEqualByComparingTo("80.00");
    }

    @Test
    @DisplayName("D2 (T152): 真实 F006 — 'XX猪蹄' baseProductName=null → name LCS「猪蹄」(2字) 匹配")
    void suggest_realF006_noBaseProductName_matchesByNameLcs() {
        ProductType pZhuTi = new ProductType();
        pZhuTi.setName("叮咚好食光卤猪蹄(去大骨) 200g");
        pZhuTi.setBaseProductName(null);
        pZhuTi.setProductCategory("FINISHED_PRODUCT");
        pZhuTi.setUnit("盒");
        pZhuTi.setGramsPerUnit(new BigDecimal("200.00"));

        when(productTypeRepository.findByFactoryId("F006")).thenReturn(List.of(pZhuTi));

        ProductTypeSuggestionDTO s = service.suggestDefaults("F006", "测试猪蹄半成品", null);

        assertThat(s.getMatchedFrom()).isEqualTo("叮咚好食光卤猪蹄(去大骨) 200g");
        assertThat(s.getProductCategory()).isEqualTo("FINISHED_PRODUCT");
    }

    @Test
    @DisplayName("D3 (T152): 多个产品竞争时最长 LCS 获胜 — '特制牛腱掌中宝' → 匹配 LCS=3「掌中宝」非 LCS=2「牛腱」")
    void suggest_multipleProducts_longestLcsWins() {
        ProductType pNiuJian = new ProductType();
        pNiuJian.setName("叮咚好食光卤牛腱 250g");
        pNiuJian.setBaseProductName("牛腱");
        pNiuJian.setProductCategory("FINISHED_PRODUCT");
        pNiuJian.setUnit("盒");

        ProductType pZhangZhongBao = new ProductType();
        pZhangZhongBao.setName("叮咚椒麻掌中宝 120g");
        pZhangZhongBao.setBaseProductName("掌中宝");
        pZhangZhongBao.setProductCategory("FINISHED_PRODUCT");
        pZhangZhongBao.setUnit("袋");

        when(productTypeRepository.findByFactoryId("F006")).thenReturn(List.of(pNiuJian, pZhangZhongBao));

        // 输入含「牛腱」(2字)和「掌中宝」(3字): 掌中宝的 LCS=3 得分更高应获胜
        ProductTypeSuggestionDTO s = service.suggestDefaults("F006", "特制牛腱掌中宝", null);

        assertThat(s.getMatchedFrom()).isEqualTo("叮咚椒麻掌中宝 120g");
        assertThat(s.getUnit()).isEqualTo("袋");
    }

    @Test
    @DisplayName("D4 (T152): 完全无 ≥2 中文字公共子串 → 无名称匹配 (禁假数据)")
    void suggest_noSharedChineseSubstring_noMatch() {
        ProductType pZhuTi = new ProductType();
        pZhuTi.setName("叮咚好食光卤猪蹄 200g");
        pZhuTi.setBaseProductName("猪蹄");
        pZhuTi.setProductCategory("FINISHED_PRODUCT");

        ProductType pZhuShe = new ProductType();
        pZhuShe.setName("叮咚好食光卤猪舌 200g");
        pZhuShe.setBaseProductName("猪舌");
        pZhuShe.setProductCategory("FINISHED_PRODUCT");

        when(productTypeRepository.findByFactoryId("F006")).thenReturn(List.of(pZhuTi, pZhuShe));

        // 输入纯英文+数字: 无中文字符 → LCS=0 → 无名称匹配
        ProductTypeSuggestionDTO s = service.suggestDefaults("F006", "ABC123XYZ", null);

        assertThat(s.getMatchedFrom()).isNull();
    }

    // ==================== helpers ====================

    private ProductTypeDTO baseDto() {
        ProductTypeDTO dto = ProductTypeDTO.builder()
                .name("测试产品")
                .unit("盒")
                .build();
        // lenient: 不是每个 Part A 用例都解析客户名
        lenient().when(customerRepository.findByIdAndFactoryId(any(), any()))
                .thenReturn(java.util.Optional.empty());
        return dto;
    }
}
