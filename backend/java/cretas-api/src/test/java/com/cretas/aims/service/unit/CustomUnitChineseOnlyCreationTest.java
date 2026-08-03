package com.cretas.aims.service.unit;

import com.cretas.aims.entity.config.UnitOfMeasurement;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.repository.config.UnitOfMeasurementRepository;
import com.cretas.aims.service.impl.SystemEnumServiceImpl;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 自定义单位<b>只填中文</b>就能建 —— Steve 2026-08-03 拍板:「单位创建允许纯中文, 英文码自动生成,
 * 不好翻译用拼音」。
 *
 * <p>此前的挡点不在校验(createUnit 的校验本来就是「代码、名称或符号至少填一项」),
 * 而在列约束 {@code unit_code NOT NULL}。<b>因此不需要改成可空</b> —— 服务端按中文名生成拼音码
 * 就同时满足了「用户只填中文」和「码非空」, 省掉一条 DDL 迁移, 也保住了这个真约束。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("自定义单位纯中文创建 —— 英文码按拼音自动生成")
class CustomUnitChineseOnlyCreationTest {

    private static final String FACTORY = "F006";

    @Mock private UnitOfMeasurementRepository unitRepository;
    @Mock private com.cretas.aims.repository.config.SystemEnumRepository systemEnumRepository;

    private SystemEnumServiceImpl service() {
        when(unitRepository.save(any(UnitOfMeasurement.class))).thenAnswer(i -> i.getArgument(0));
        return new SystemEnumServiceImpl(systemEnumRepository, unitRepository);
    }

    private UnitOfMeasurement chineseOnly(String unitName) {
        UnitOfMeasurement unit = new UnitOfMeasurement();
        unit.setFactoryId(FACTORY);
        unit.setUnitName(unitName);
        unit.setCategory("COUNT");
        return unit;
    }

    private UnitOfMeasurement created(UnitOfMeasurement request) {
        service().createUnit(request);
        ArgumentCaptor<UnitOfMeasurement> saved = ArgumentCaptor.forClass(UnitOfMeasurement.class);
        verify(unitRepository).save(saved.capture());
        return saved.getValue();
    }

    @Test
    @DisplayName("🔴 只填「半只」→ 自动生成拼音码 banzhi, 中文名原样保留")
    void chineseOnlyGeneratesPinyinCode() {
        when(unitRepository.findAllByFactoryId(anyString())).thenReturn(List.of());

        UnitOfMeasurement saved = created(chineseOnly("半只"));

        assertThat(saved.getUnitCode()).isEqualTo("banzhi");
        assertThat(saved.getUnitName()).isEqualTo("半只");
    }

    @Test
    @DisplayName("baseUnit 也是 NOT NULL —— 没给就自成基准, 不能让插入炸在列约束上")
    void baseUnitDefaultsToItself() {
        when(unitRepository.findAllByFactoryId(anyString())).thenReturn(List.of());

        UnitOfMeasurement saved = created(chineseOnly("两头鲍"));

        assertThat(saved.getUnitCode()).isEqualTo("liangtoubao");
        assertThat(saved.getBaseUnit()).isEqualTo(saved.getUnitCode());
    }

    @Test
    @DisplayName("拼音撞车时加序号 —— 同厂已有 banzhi 就生成 banzhi2")
    void pinyinCollisionGetsSuffix() {
        UnitOfMeasurement existing = new UnitOfMeasurement();
        existing.setFactoryId(FACTORY);
        existing.setUnitCode("banzhi");
        existing.setUnitName("伴之");          // 不同中文名, 同拼音
        when(unitRepository.findAllByFactoryId(anyString())).thenReturn(List.of(existing));

        UnitOfMeasurement saved = created(chineseOnly("半只"));

        assertThat(saved.getUnitCode()).isEqualTo("banzhi2");
    }

    @Test
    @DisplayName("用户自己填了码就不覆盖 —— 自动生成只在缺码时兜底")
    void explicitCodeIsKept() {
        when(unitRepository.findAllByFactoryId(anyString())).thenReturn(List.of());
        UnitOfMeasurement request = chineseOnly("半只");
        request.setUnitCode("HALF");

        assertThat(created(request).getUnitCode()).isEqualTo("HALF");
    }

    @Test
    @DisplayName("名字里有非中文时只取字母数字 —— 「1号箱(大)」→ 1haoxiangda, 括号被丢掉")
    void mixedNameKeepsAlphanumericOnly() {
        when(unitRepository.findAllByFactoryId(anyString())).thenReturn(List.of());

        assertThat(created(chineseOnly("1号箱(大)")).getUnitCode()).isEqualTo("1haoxiangda");
    }

    @Test
    @DisplayName("超长中文名截到列宽 20 以内 —— 否则 insert 直接炸在 varchar(20) 上")
    void longNameIsTruncatedToColumnWidth() {
        when(unitRepository.findAllByFactoryId(anyString())).thenReturn(List.of());

        assertThat(created(chineseOnly("超长单位名称测试用例十个字以上")).getUnitCode())
                .hasSizeLessThanOrEqualTo(20);
    }

    @Test
    @DisplayName("与内置单位撞名仍然 409 —— 自动生成不能变成绕过既有防重复的后门")
    void builtInConflictStillRejected() {
        when(unitRepository.findAllByFactoryId(anyString())).thenReturn(List.of());

        assertThatThrownBy(() -> service().createUnit(chineseOnly("盒")))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("单位已存在");
    }

    @Test
    @DisplayName("纯符号名(拼不出拼音)退回可用码, 不写空 —— unit_code 是 NOT NULL")
    void unpronounceableNameStillGetsCode() {
        when(unitRepository.findAllByFactoryId(anyString())).thenReturn(List.of());

        UnitOfMeasurement saved = created(chineseOnly("※※"));

        assertThat(saved.getUnitCode()).isNotBlank();
    }
}
