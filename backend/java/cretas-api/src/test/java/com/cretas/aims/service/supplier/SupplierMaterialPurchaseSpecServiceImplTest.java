package com.cretas.aims.service.supplier;

import com.cretas.aims.dto.supplier.SupplierMaterialPurchaseSpecRequest;
import com.cretas.aims.entity.RawMaterialType;
import com.cretas.aims.entity.SupplierMaterial;
import com.cretas.aims.entity.SupplierMaterialPurchaseSpec;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.repository.RawMaterialTypeRepository;
import com.cretas.aims.repository.SupplierMaterialPurchaseSpecRepository;
import com.cretas.aims.repository.SupplierMaterialRepository;
import com.cretas.aims.service.unit.UnitContractService;
import com.cretas.aims.service.unit.UnitNormalizationResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.cretas.aims.dto.supplier.SupplierMaterialPurchaseSpecDTO;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class SupplierMaterialPurchaseSpecServiceImplTest {
    @Mock SupplierMaterialPurchaseSpecRepository repository;
    @Mock SupplierMaterialRepository relationRepository;
    @Mock RawMaterialTypeRepository materialRepository;
    @Mock UnitContractService unitContractService;
    SupplierMaterialPurchaseSpecServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new SupplierMaterialPurchaseSpecServiceImpl(repository, relationRepository,
                materialRepository, unitContractService);
        lenient().when(unitContractService.supportsUsage(eq("F006"), anyString(), any())).thenReturn(true);
    }

    @Test
    void createsCanonicalPackageSpecAndKeepsOnlyOneDefault() {
        SupplierMaterial relation = relation(true);
        RawMaterialType material = material("kg");
        SupplierMaterialPurchaseSpec previous = new SupplierMaterialPurchaseSpec();
        previous.setId("old"); previous.setDefaultSpec(true); previous.setActive(true);
        when(relationRepository.findByIdAndFactoryId("rel", "F006")).thenReturn(Optional.of(relation));
        when(materialRepository.findById("mat")).thenReturn(Optional.of(material));
        when(unitContractService.normalize(eq("F006"), anyString())).thenAnswer(invocation -> {
            String raw = invocation.getArgument(1);
            String code = "箱".equals(raw) ? "case" : raw;
            return new UnitNormalizationResult(raw, code, mock(com.cretas.aims.service.unit.CanonicalUnit.class));
        });
        when(repository.findByFactoryIdAndSupplierMaterialIdAndActiveTrueOrderByCreatedAtAsc("F006", "rel"))
                .thenReturn(List.of(previous));
        when(repository.saveAndFlush(any())).thenAnswer(invocation -> invocation.getArgument(0));

        SupplierMaterialPurchaseSpecRequest request = request("箱", "kg", true);
        var result = service.create("F006", "sup", "rel", request);

        assertThat(result.getPurchasePackageUnit()).isEqualTo("case");
        assertThat(result.getInventoryBaseUnit()).isEqualTo("kg");
        assertThat(result.getFactor()).isEqualByComparingTo("10");
        assertThat(result.getQuotedPriceUnit()).isEqualTo("case");
        assertThat(previous.getDefaultSpec()).isFalse();
    }

    @Test
    void rejectsBaseUnitThatDiffersFromMaterialMasterData() {
        when(relationRepository.findByIdAndFactoryId("rel", "F006")).thenReturn(Optional.of(relation(true)));
        when(materialRepository.findById("mat")).thenReturn(Optional.of(material("kg")));
        when(unitContractService.normalize(eq("F006"), anyString())).thenAnswer(invocation -> {
            String raw = invocation.getArgument(1);
            return new UnitNormalizationResult(raw, raw, mock(com.cretas.aims.service.unit.CanonicalUnit.class));
        });

        assertThatThrownBy(() -> service.create("F006", "sup", "rel", request("case", "g", false)))
                .isInstanceOf(BusinessException.class);
        verify(repository, never()).saveAndFlush(any());
    }

    @Test
    void inactiveSupplierMaterialRelationCannotAddSpecs() {
        when(relationRepository.findByIdAndFactoryId("rel", "F006")).thenReturn(Optional.of(relation(false)));
        assertThatThrownBy(() -> service.create("F006", "sup", "rel", request("case", "kg", false)))
                .isInstanceOf(BusinessException.class);
        verifyNoInteractions(materialRepository);
    }

    @Test
    void rejectsTimeUnitForPurchasePackage() {
        when(relationRepository.findByIdAndFactoryId("rel", "F006")).thenReturn(Optional.of(relation(true)));
        when(materialRepository.findById("mat")).thenReturn(Optional.of(material("kg")));
        when(unitContractService.normalize(eq("F006"), anyString())).thenAnswer(invocation -> {
            String raw = invocation.getArgument(1);
            return new UnitNormalizationResult(raw, raw, mock(com.cretas.aims.service.unit.CanonicalUnit.class));
        });
        when(unitContractService.supportsUsage("F006", "minute",
                com.cretas.aims.service.unit.UnitUsageScope.PURCHASE_QUANTITY)).thenReturn(false);

        assertThatThrownBy(() -> service.create("F006", "sup", "rel", request("minute", "kg", false)))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("不允许用于当前采购规格字段");
        verify(repository, never()).saveAndFlush(any());
    }

    @Test
    void rejectsZeroQuotedPriceAtServiceBoundary() {
        when(relationRepository.findByIdAndFactoryId("rel", "F006")).thenReturn(Optional.of(relation(true)));
        when(materialRepository.findById("mat")).thenReturn(Optional.of(material("kg")));
        when(unitContractService.normalize(eq("F006"), anyString())).thenAnswer(invocation -> {
            String raw = invocation.getArgument(1);
            return new UnitNormalizationResult(raw, raw, mock(com.cretas.aims.service.unit.CanonicalUnit.class));
        });
        SupplierMaterialPurchaseSpecRequest request = request("case", "kg", false);
        request.setQuotedPrice(BigDecimal.ZERO);

        assertThatThrownBy(() -> service.create("F006", "sup", "rel", request))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("报价必须大于0");
        verify(repository, never()).saveAndFlush(any());
    }

    private SupplierMaterial relation(boolean active) {
        SupplierMaterial relation = new SupplierMaterial();
        relation.setId("rel"); relation.setFactoryId("F006"); relation.setSupplierId("sup");
        relation.setMaterialTypeId("mat"); relation.setActive(active);
        return relation;
    }

    private RawMaterialType material(String unit) {
        RawMaterialType material = new RawMaterialType();
        material.setId("mat"); material.setFactoryId("F006"); material.setUnit(unit);
        return material;
    }

    private SupplierMaterialPurchaseSpecRequest request(String packageUnit, String baseUnit, boolean defaultSpec) {
        SupplierMaterialPurchaseSpecRequest request = new SupplierMaterialPurchaseSpecRequest();
        request.setName("10kg/箱"); request.setPurchasePackageUnit(packageUnit);
        request.setInventoryBaseUnit(baseUnit); request.setFactor(new BigDecimal("10"));
        request.setQuotedPrice(new BigDecimal("120")); request.setDefaultSpec(defaultSpec);
        request.setActive(true);
        return request;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 客户 2026-07-30 表格第 38 行:「在成品SKU中设定完换算规格后, 生成采购订单的时候
    // 将不再自动填入供应商中设定好的采购单价。」
    //
    // 成因: 配了采购规格后, 采购行的计价单位变成规格的包装单位 (箱), 而供应关系上配的价
    // 是按库存基本单位 (kg) 的 —— 前端 applyRelationPrice 单位不等就把价清空。
    //
    // 这里只做**规格自己声明的那一次换算**: 规格行上就写着 1 箱 = conversionFactor kg。
    // 不引入通用单位引擎, 也不跨任何未声明的单位猜。
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    void derivesSpecPriceFromRelationPriceUsingTheSpecOwnFactor() {
        SupplierMaterial relation = relation(true);
        relation.setDefaultPurchasePrice(new BigDecimal("20"));
        relation.setPurchaseUnit("kg");                       // 供应关系价 = 20 元/kg
        when(relationRepository.findByIdAndFactoryId("rel", "F006")).thenReturn(Optional.of(relation));
        when(repository.findByFactoryIdAndSupplierMaterialIdOrderByActiveDescDefaultSpecDescCreatedAtDesc("F006", "rel"))
                .thenReturn(List.of(spec(null, "case", "kg", new BigDecimal("20"))));  // 1 箱 = 20 kg

        SupplierMaterialPurchaseSpecDTO dto = service.list("F006", "sup", "rel").get(0);

        assertThat(dto.getQuotedPrice()).isNull();
        assertThat(dto.getDerivedPrice()).isEqualByComparingTo("400");   // 20 元/kg × 20 kg/箱
        assertThat(dto.getDerivedPriceUnit()).isEqualTo("case");
        assertThat(dto.getDerivedPriceSource()).isEqualTo("SUPPLIER_RELATION_CONVERTED");
    }

    @Test
    void keepsRelationPriceAsIsWhenTheSpecIsPricedInTheSameUnit() {
        SupplierMaterial relation = relation(true);
        relation.setDefaultPurchasePrice(new BigDecimal("20"));
        relation.setPurchaseUnit("case");
        when(relationRepository.findByIdAndFactoryId("rel", "F006")).thenReturn(Optional.of(relation));
        when(repository.findByFactoryIdAndSupplierMaterialIdOrderByActiveDescDefaultSpecDescCreatedAtDesc("F006", "rel"))
                .thenReturn(List.of(spec(null, "case", "kg", new BigDecimal("20"))));

        SupplierMaterialPurchaseSpecDTO dto = service.list("F006", "sup", "rel").get(0);

        assertThat(dto.getDerivedPrice()).isEqualByComparingTo("20");   // 同单位, 不得再乘一次系数
        assertThat(dto.getDerivedPriceSource()).isEqualTo("SUPPLIER_RELATION_SAME_UNIT");
    }

    @Test
    void neverGuessesAcrossAnUndeclaredUnit() {
        SupplierMaterial relation = relation(true);
        relation.setDefaultPurchasePrice(new BigDecimal("20"));
        relation.setPurchaseUnit("g");                        // 既不是包装单位也不是该规格的基本单位
        when(relationRepository.findByIdAndFactoryId("rel", "F006")).thenReturn(Optional.of(relation));
        when(repository.findByFactoryIdAndSupplierMaterialIdOrderByActiveDescDefaultSpecDescCreatedAtDesc("F006", "rel"))
                .thenReturn(List.of(spec(null, "case", "kg", new BigDecimal("20"))));

        SupplierMaterialPurchaseSpecDTO dto = service.list("F006", "sup", "rel").get(0);

        assertThat(dto.getDerivedPrice()).isNull();
        assertThat(dto.getDerivedPriceSource()).isNull();
    }

    @Test
    void specOwnQuoteAlwaysWinsAndSuppressesDerivation() {
        SupplierMaterial relation = relation(true);
        relation.setDefaultPurchasePrice(new BigDecimal("20"));
        relation.setPurchaseUnit("kg");
        when(relationRepository.findByIdAndFactoryId("rel", "F006")).thenReturn(Optional.of(relation));
        when(repository.findByFactoryIdAndSupplierMaterialIdOrderByActiveDescDefaultSpecDescCreatedAtDesc("F006", "rel"))
                .thenReturn(List.of(spec(new BigDecimal("380"), "case", "kg", new BigDecimal("20"))));

        SupplierMaterialPurchaseSpecDTO dto = service.list("F006", "sup", "rel").get(0);

        assertThat(dto.getQuotedPrice()).isEqualByComparingTo("380");
        assertThat(dto.getDerivedPrice()).isNull();   // 有自己的报价就不推导, 免得两个数打架
    }

    @Test
    void derivesNothingWhenTheFactorIsMissingOrZero() {
        SupplierMaterial relation = relation(true);
        relation.setDefaultPurchasePrice(new BigDecimal("20"));
        relation.setPurchaseUnit("kg");
        when(relationRepository.findByIdAndFactoryId("rel", "F006")).thenReturn(Optional.of(relation));
        when(repository.findByFactoryIdAndSupplierMaterialIdOrderByActiveDescDefaultSpecDescCreatedAtDesc("F006", "rel"))
                .thenReturn(List.of(spec(null, "case", "kg", null), spec(null, "case", "kg", BigDecimal.ZERO)));

        List<SupplierMaterialPurchaseSpecDTO> dtos = service.list("F006", "sup", "rel");

        assertThat(dtos).allSatisfy(dto -> assertThat(dto.getDerivedPrice()).isNull());
    }

    private SupplierMaterialPurchaseSpec spec(BigDecimal quotedPrice, String packageUnit,
                                              String baseUnit, BigDecimal factor) {
        SupplierMaterialPurchaseSpec spec = new SupplierMaterialPurchaseSpec();
        spec.setId("spec-1"); spec.setFactoryId("F006"); spec.setSupplierMaterialId("rel");
        spec.setMaterialTypeId("mat"); spec.setName("整箱");
        spec.setPurchasePackageUnit(packageUnit); spec.setInventoryBaseUnit(baseUnit);
        spec.setConversionFactor(factor); spec.setQuotedPrice(quotedPrice);
        spec.setActive(true);
        return spec;
    }
}
